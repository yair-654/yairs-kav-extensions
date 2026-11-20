package il.yair.mytrips

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension
import org.json.JSONArray
import org.json.JSONObject

/**
 * My Trips — a Kav extension (API 2). Records every trip the rider opens in Kav while it is
 * under way or leaving within 15 minutes (no GPS: the route comes from Kav's own trip page,
 * which hands the extension the full route). A My Trips screen (pin-able as a tab) lists the
 * trips of the past day, week, month or all time; tapping one shows where the rider stopped,
 * how long each stop took, the total time of the trip, and a note they can write.
 */
class MyTrips : KavExtension {

    private lateinit var kav: Kav
    private var hebrew = false

    /** Every kept trip, newest first. Guarded by [lock]. */
    private val trips = ArrayList<Trip>()
    private val lock = Any()

    companion object {
        /** A trip leaves within this window (or has left): it is one the rider is taking. */
        private const val RECORD_WINDOW_SEC = 15L * 60
        private const val MAX_TRIPS = 500
        private const val FILE = "mytrips.json"
        private const val FILE_TMP = "mytrips.json.tmp"
    }

    override fun start(kav: Kav) {
        this.kav = kav
        this.hebrew = kav.locale() == "he"
        Thread { loadFromDisk() }.start()

        // The recorder: every trip page the rider opens hands us the full route here.
        kav.card("trip") { context, about -> tripCard(context, about) }

        // The screen: pin-able as a tab (the manifest marks the page "tab": true).
        kav.screen("trips") { context, _ -> TripsScreen(context).view() }

        kav.log(if (hebrew) "My Trips התחיל" else "My Trips started" +
            ", API ${kav.apiVersion()}, language ${kav.locale()}")
    }

    private fun tr(he: String, en: String) = if (hebrew) he else en

    // ------------------------------------------------------------------ recording

    /** Parses the trip a trip page handed us; null when there is nothing to keep from it. */
    private fun parseTrip(about: JSONObject): Trip? {
        val trip = about.optJSONObject("trip") ?: return null
        val legs = trip.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null

        var dep = Long.MAX_VALUE
        var arr = 0L
        val raw = ArrayList<RawPoint>()          // where the rider was, in order
        val lines = LinkedHashSet<String>()

        for (i in 0 until legs.length()) {
            val leg = legs.optJSONObject(i) ?: continue
            val kind = leg.optString("kind")
            if (kind != "ride" && kind != "walk" && kind != "wait") continue
            val lDep = leg.optLong("dep", 0)
            val lArr = leg.optLong("arr", 0)
            if (lDep <= 0 || lArr <= 0) continue
            if (lDep < dep) dep = lDep
            if (lArr > arr) arr = lArr

            val from = leg.optJSONObject("from")
            val to = leg.optJSONObject("to")
            if (raw.isEmpty() && from != null) {
                val name = from.optString("name")
                if (name.isNotEmpty()) {
                    raw.add(RawPoint(name, from.optDouble("lat", 0.0),
                        from.optDouble("lon", 0.0), lDep))
                }
            }
            if (to != null && kind != "wait") {
                val name = to.optString("name")
                if (name.isNotEmpty()) {
                    raw.add(RawPoint(name, to.optDouble("lat", 0.0),
                        to.optDouble("lon", 0.0), lArr))
                }
            }
            if (kind == "ride") {
                val ln = leg.optString("lineNumber")
                if (ln.isNotEmpty()) lines.add(ln)
            }
        }
        if (dep == Long.MAX_VALUE || arr <= dep || raw.isEmpty()) return null

        // Only trips the rider is taking now or is about to take; browsing future plans isn't riding.
        val now = System.currentTimeMillis() / 1000
        if (dep > now + RECORD_WINDOW_SEC) return null

        // Merge consecutive points at the same place: being there from arrival until the
        // next move is how long the rider stayed (a wait leg folds into its stop's dwell).
        val visits = ArrayList<Visit>()
        for (p in raw) {
            val last = if (visits.isEmpty()) null else visits[visits.size - 1]
            if (last != null && last.name == p.name) {
                last.leaveSec = p.t
            } else {
                visits.add(Visit(p.name, p.lat, p.lon, p.t, p.t))
            }
        }
        if (visits.isEmpty()) return null

        val firstName = visits[0].name
        val lastName = visits[visits.size - 1].name
        val id = "$dep|$firstName|$lastName|$arr"
        return Trip(id, dep, arr, System.currentTimeMillis() / 1000, "", visits, ArrayList(lines))
    }

    /** Keeps a trip (unless already kept) and writes the file. Safe from any thread. */
    private fun record(t: Trip) {
        synchronized(lock) {
            if (trips.none { it.id == t.id }) {
                trips.add(t)
                trips.sortByDescending { it.dep }
                if (trips.size > MAX_TRIPS) trips.subList(MAX_TRIPS, trips.size).clear()
            }
        }
        flush()
    }

    private fun setNote(id: String, note: String) {
        synchronized(lock) {
            for (t in trips) if (t.id == id) t.note = note
        }
        flush()
    }

    private fun snapshot(): List<Trip> {
        synchronized(lock) { return ArrayList(trips) }
    }

    // ------------------------------------------------------------------ storage

    private fun file(): File = File(kav.context().filesDir, FILE)

    private fun loadFromDisk() {
        try {
            val f = file()
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("trips") ?: return
            val loaded = ArrayList<Trip>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val dep = o.optLong("dep", 0)
                val end = o.optLong("arr", 0)
                if (id.isEmpty() || dep <= 0 || end <= 0) continue
                val visits = ArrayList<Visit>()
                val va = o.optJSONArray("stops") ?: JSONArray()
                for (j in 0 until va.length()) {
                    val v = va.optJSONObject(j) ?: continue
                    visits.add(Visit(v.optString("name"), v.optDouble("lat", 0.0),
                        v.optDouble("lon", 0.0), v.optLong("at", 0), v.optLong("leave", 0)))
                }
                if (visits.isEmpty()) continue
                val lines = ArrayList<String>()
                val la = o.optJSONArray("lines") ?: JSONArray()
                for (j in 0 until la.length()) lines.add(la.optString(j))
                loaded.add(Trip(id, dep, end, o.optLong("saved", 0),
                    o.optString("note"), visits, lines))
            }
            synchronized(lock) {
                trips.clear()
                trips.addAll(loaded)
                trips.sortByDescending { it.dep }
            }
        } catch (e: Exception) {
            kav.log("load failed: ${e.message}")
        }
    }

    /** Writes the whole list as JSON, atomically (tmp file, then rename). */
    private fun flush() {
        val snap = snapshot()
        Thread {
            try {
                val root = JSONObject()
                val arr = JSONArray()
                for (t in snap) {
                    val o = JSONObject()
                    o.put("id", t.id)
                    o.put("dep", t.dep)
                    o.put("arr", t.arr)
                    o.put("saved", t.saved)
                    o.put("note", t.note)
                    val va = JSONArray()
                    for (v in t.visits) {
                        val vo = JSONObject()
                        vo.put("name", v.name)
                        vo.put("lat", v.lat)
                        vo.put("lon", v.lon)
                        vo.put("at", v.atSec)
                        vo.put("leave", v.leaveSec)
                        va.put(vo)
                    }
                    o.put("stops", va)
                    val la = JSONArray()
                    for (l in t.lines) la.put(l)
                    o.put("lines", la)
                    arr.put(o)
                }
                root.put("trips", arr)
                val dir = kav.context().filesDir
                val tmp = File(dir, FILE_TMP)
                tmp.writeText(root.toString())
                val f = file()
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (e: Exception) {
                kav.log("save failed: ${e.message}")
            }
        }.start()
    }

    // ------------------------------------------------------------------ formatting

    private fun dp(v: Float): Int =
        (v * kav.context().resources.displayMetrics.density).toInt()

    private fun dpu(name: String): Int = dp(kav.ui().dp(name))

    private fun timeOfDay(epochSec: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(epochSec * 1000))

    private fun dateOf(epochSec: Long): String {
        val loc = if (hebrew) Locale.forLanguageTag("he") else Locale.US
        return SimpleDateFormat("EEE, d MMM", loc).format(Date(epochSec * 1000))
    }

    /** "2 h 05 min" / "45 min"; Hebrew "2 שעות ו-5 דקות" / "45 דקות". */
    private fun duration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) {
            if (hebrew) "$h שעות ו-$m דקות" else "${h} h $m min"
        } else {
            if (hebrew) "$m דקות" else "$m min"
        }
    }

    // ------------------------------------------------------------------ the trip-page card

    private fun tripCard(c: Context, about: String): View {
        val trip = try {
            parseTrip(JSONObject(about))
        } catch (e: Exception) {
            null
        } ?: return cardHint(c)

        val known = synchronized(lock) { trips.any { it.id == trip.id } }
        if (!known) record(trip)

        val ui = kav.ui()
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(ui.row(c, "check",
            tr("Kept in My Trips", "נשמר בהנסיעות שלי"),
            tr("Total ${duration(trip.arr - trip.dep)} · ${trip.visits.size} places",
               "סך הכול ${duration(trip.arr - trip.dep)} · ${trip.visits.size} מקומות"),
            null))
        box.addView(ui.button(c, tr("הוסיפו הערה", "Add a note"), false) {
            NoteEditor().open(trip.id, trip.note)
        })
        return ui.card(c, box)
    }

    /** Shown on trip pages for trips not taken (future plans): we keep nothing of those. */
    private fun cardHint(c: Context): View {
        val ui = kav.ui()
        return ui.card(c, ui.row(c, "info",
            tr("הנסיעות שלי", "My Trips"),
            tr("הנסיעה תישמר כשהיא תתחיל.", "This trip will be kept once it starts."),
            null))
    }

    // ------------------------------------------------------------------ the note editor

    /**
     * A sheet to write a note for one trip. KavUi's textField reports each new value through
     * its Changed callback, so the current text is tracked in a plain holder and read on save.
     */
    private inner class NoteEditor {
        private var current: String = ""

        fun open(id: String, existing: String) {
            val c = kav.context()
            val ui = kav.ui()
            current = existing
            val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            box.addView(ui.textField(c, existing,
                tr("ההערה שלכם לנסיעה", "Your note for this trip")) { v -> current = v })
            box.addView(ui.button(c, tr("שמירת ההערה", "Save note"), true) {
                setNote(id, current.trim())
                kav.log(if (hebrew) "הערה נשמרה לנסיעה" else "note saved for a trip")
            })
            ui.sheet(c, tr("הערה לנסיעה", "Trip note"), box)
        }
    }

    // ------------------------------------------------------------------ the screen

    private enum class Window(val sec: Long) {
        DAY(86_400L), WEEK(604_800L), MONTH(2_592_000L), ALL(Long.MAX_VALUE)
    }

    private inner class TripsScreen(private val c: Context) {

        private val ui = kav.ui()
        private var filter: Window = Window.WEEK
        private var chipsRow: LinearLayout? = null
        private var list: LinearLayout? = null

        fun view(): View {
            val outer = ScrollView(c)
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            }
            box.addView(text(tr("הנסיעות שלי", "My Trips"), "title", bold = true))
            box.addView(text(tr("כל נסיעה שנסעתם, נשמרת בטלפון הזה בלבד.",
                "Every trip you took, kept on this phone only."),
                "subhead", secondary = true, padTop = dpu("gap2"), padBottom = dpu("gap4")))

            val chipScroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
            val chips = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            chipScroll.addView(chips)
            box.addView(chipScroll)
            chipsRow = chips

            val listBox = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dpu("gap4"), 0, 0)
            }
            box.addView(listBox)
            list = listBox

            outer.addView(box)
            renderChips()
            renderList()
            return outer
        }

        private fun renderChips() {
            val row = chipsRow ?: return
            row.removeAllViews()
            for (w in Window.values()) {
                val label = when (w) {
                    Window.DAY -> tr("יום אחרון", "Past day")
                    Window.WEEK -> tr("שבוע", "Week")
                    Window.MONTH -> tr("חודש", "Month")
                    Window.ALL -> tr("הכול", "All time")
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dpu("gap2")
                row.addView(ui.chip(c, label, filter == w) {
                    filter = w
                    renderChips()
                    renderList()
                }, lp)
            }
        }

        private fun renderList() {
            val box = list ?: return
            box.removeAllViews()
            val now = System.currentTimeMillis() / 1000
            val all = snapshot().filter { now - it.dep <= filter.sec }
            if (all.isEmpty()) {
                box.addView(text(tr(
                    "אין עדיין נסיעות. פתחו נסיעה ב-Kav כשהיא מתבצעת והיא תופיע כאן.",
                    "No trips yet. Open a trip in Kav while it is under way and it lands here."),
                    "body", secondary = true))
                return
            }
            for (t in all) box.addView(tripCardRow(t))
        }

        private fun tripCardRow(t: Trip): View {
            val inner = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            inner.addView(text("${dateOf(t.dep)} · ${timeOfDay(t.dep)}", "caption", secondary = true))
            val route = "${t.visits[0].name} → ${t.visits[t.visits.size - 1].name}"
            inner.addView(text(route, "subhead", bold = true))
            val meta = StringBuilder(duration(t.arr - t.dep))
            if (t.lines.isNotEmpty()) {
                meta.append(" · ").append(tr("קווים ", "lines ")).append(t.lines.joinToString(", "))
            }
            inner.addView(text(meta.toString(), "caption", secondary = true))
            if (t.note.isNotEmpty()) {
                inner.addView(text("${tr("הערה:", "Note:")} ${t.note}", "caption", secondary = true))
            }
            inner.setOnClickListener { tripSheet(t) }
            inner.isClickable = true
            return ui.card(c, inner)
        }

        private fun tripSheet(t: Trip) {
            val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

            box.addView(text("${t.visits[0].name} → ${t.visits[t.visits.size - 1].name}",
                "title", bold = true))
            box.addView(text("${dateOf(t.dep)} · ${timeOfDay(t.dep)} – ${timeOfDay(t.arr)}",
                "caption", secondary = true))

            box.addView(text(duration(t.arr - t.dep), "largeTitle", bold = true,
                padTop = dpu("gap3")))
            box.addView(text(tr("זמן הנסיעה הכולל", "total time of the trip"), "caption",
                secondary = true))

            box.addView(ui.header(c, tr("היכן עצרתם", "Where you stopped")))
            for (i in t.visits.indices) {
                val v = t.visits[i]
                val dwell = v.leaveSec - v.atSec
                val sub = StringBuilder(timeOfDay(v.atSec))
                if (dwell >= 60) {
                    sub.append(" · ").append(tr("עצירה של ", "stayed ")).append(duration(dwell))
                }
                val iconName = if (i == 0 || i == t.visits.size - 1) "pin" else "clock"
                box.addView(ui.row(c, iconName, v.name, sub.toString(), null))
            }
            if (t.lines.isNotEmpty()) {
                box.addView(ui.header(c, tr("קווים", "Lines")))
                box.addView(ui.row(c, "bus", t.lines.joinToString(", "),
                    tr("בנסיעה זו", "on this trip"), null))
            }

            box.addView(ui.header(c, tr("ההערה שלכם", "Your note")))
            box.addView(ui.button(c, if (t.note.isEmpty()) tr("הוסיפו הערה", "Add a note")
                else tr("עריכת ההערה", "Edit note"), true) {
                NoteEditor().open(t.id, t.note)
            })

            ui.sheet(c, tr("הנסיעות שלי", "My Trips"), box)
        }

        private fun text(s: String, style: String, bold: Boolean = false,
                         secondary: Boolean = false, padTop: Int = 0, padBottom: Int = 0): TextView {
            return TextView(c).apply {
                text = s
                setTextColor(ui.color(if (secondary) "textSecondary" else "text"))
                textSize = ui.textSize(style)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, padTop, 0, padBottom)
            }
        }
    }
}

/** One raw point along the way, before merging. */
private class RawPoint(val name: String, val lat: Double, val lon: Double, val t: Long)

/** A place the rider was at, from arriving until leaving. */
class Visit(val name: String, val lat: Double, val lon: Double, val atSec: Long, var leaveSec: Long)

/** One kept trip. */
class Trip(
    val id: String,
    val dep: Long,          // seconds since 1970
    val arr: Long,
    val saved: Long,
    var note: String,
    val visits: List<Visit>,
    val lines: List<String>
)
