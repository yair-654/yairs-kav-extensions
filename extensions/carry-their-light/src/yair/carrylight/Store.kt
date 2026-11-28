package yair.carrylight

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything the extension keeps, on this phone only, in Android's "no backup" folder (never in a cloud backup):
 * the heroes waiting to be met (with their pictures, so a card shows at once), the heroes the rider met, what happened
 * on today's rides, the widget's hero and the notification that is due.
 *
 * A "spot" is a long ride or wait of a trip that gets a hero (Journey). Its key stays the same for the day, also when
 * Kav plans the rest of the trip again.
 */
internal class Store(context: Context) {
    private val dir = File(context.noBackupFilesDir, "carry-their-light").apply { mkdirs() }
    private val pictures = File(dir, "pictures").apply { mkdirs() }
    private val queueFile = File(dir, "queue.json")
    private val metFile = File(dir, "met.json")
    private val dayFile = File(dir, "day.json")
    private val stateFile = File(dir, "state.json")
    private val notifyFile = File(dir, "notify.json")

    /** A hero the rider met. */
    class Met(val id: String, val title: L, val quote: L, val at: Long, val saved: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title.toJson()).put("quote", quote.toJson())
            .put("at", at).put("saved", saved)
    }

    /** The notification for a spot: when it's due, and whether it went out. */
    class Due(val spot: String, val at: Long, val posted: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("spot", spot).put("at", at).put("posted", posted)
    }

    // The heroes waiting to be met, in order, and those of them the rider already saw on a card.
    private val queue = ArrayList<Sticker>()
    private val shown = HashSet<String>()
    // The heroes the rider met, newest first.
    private val met = ArrayList<Met>()
    // Today's spots: closed with ✕, opened, and seen on a card while on them.
    private var day = ""
    private val dismissed = HashSet<String>()
    private val opened = HashSet<String>()
    private val seen = HashSet<String>()
    private val reached = HashMap<String, Long>()
    // The one-time hint about Follow your trips, and the widget's hero of the day.
    private var hinted = false
    private var widgetDay = ""
    private var widgetHero: Sticker? = null
    private var due: Due? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        read(queueFile) { j ->
            j.optJSONArray("queue")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::Sticker)?.takeIf { it.ok }?.let { queue += it } }
            j.optJSONArray("shown")?.strings()?.let { shown += it }
        }
        read(metFile) { j ->
            val a = j.optJSONArray("met") ?: JSONArray()
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val id = e.str("id")?.takeIf(Site::validId) ?: continue
                met += Met(id, L.from(e.optJSONObject("title")), L.from(e.optJSONObject("quote")), e.optLong("at"), e.optBoolean("saved"))
            }
        }
        read(dayFile) { j ->
            day = j.optString("day")
            j.optJSONArray("dismissed")?.strings()?.let { dismissed += it }
            j.optJSONArray("opened")?.strings()?.let { opened += it }
            j.optJSONArray("seen")?.strings()?.let { seen += it }
            j.optJSONObject("reached")?.let { r -> for (k in r.keys()) reached[k] = r.optLong(k) }
        }
        read(stateFile) { j ->
            hinted = j.optBoolean("hinted")
            widgetDay = j.optString("widgetDay")
            widgetHero = j.optJSONObject("widgetHero")?.let(::Sticker)?.takeIf { it.ok }
        }
        read(notifyFile) { j -> j.str("spot")?.let { due = Due(it, j.optLong("at"), j.optBoolean("posted")) } }
    }

    fun onChange(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun changed() { for (l in listeners) l() }

    // -- The heroes waiting to be met ---------------------------------------------------------------------------------

    @Synchronized fun queue(): List<Sticker> = queue.toList()
    @Synchronized fun queued(id: String): Sticker? = queue.firstOrNull { it.id == id }
    @Synchronized fun wasShown(id: String) = id in shown

    /** A waiting hero's picture (it may not be there yet). */
    fun picture(id: String) = File(pictures, "$id.img")

    fun addQueued(s: Sticker) {
        synchronized(this) {
            if (queue.any { it.id == s.id }) return
            queue += s
            saveQueue()
        }
        changed()
    }

    @Synchronized fun markShown(id: String) {
        if (queue.none { it.id == id } || !shown.add(id)) return
        saveQueue()
    }

    fun pictureArrived() = changed()

    // -- The heroes the rider met -------------------------------------------------------------------------------------

    @Synchronized fun met(): List<Met> = met.toList()
    @Synchronized fun isMet(id: String) = met.any { it.id == id }
    @Synchronized fun isSaved(id: String) = met.any { it.id == id && it.saved }
    @Synchronized fun metIds(): Set<String> = met.mapTo(HashSet()) { it.id }

    /** The hero met longest ago that isn't waiting already: met again once every other hero was met. */
    @Synchronized fun longestAgo(): String? = met.lastOrNull { m -> queue.none { it.id == m.id } }?.id

    /**
     * The rider opened a hero's story: they're met (newest first, staying saved), no longer waiting, and the spot
     * they were offered on, if any, is done.
     */
    fun open(s: Sticker, spot: String?) {
        synchronized(this) {
            roll()
            val saved = met.firstOrNull { it.id == s.id }?.saved ?: false
            met.removeAll { it.id == s.id }
            met.add(0, Met(s.id, L(s.title(true), s.title(false)), s.quote, System.currentTimeMillis(), saved))
            if (queue.removeAll { it.id == s.id }) {
                shown -= s.id
                picture(s.id).delete()
                saveQueue()
            }
            spot?.let { opened += it; saveDay() }
            saveMet()
        }
        changed()
    }

    fun setSaved(id: String, saved: Boolean) {
        synchronized(this) {
            val i = met.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
            val m = met[i]
            met[i] = Met(m.id, m.title, m.quote, m.at, saved)
            saveMet()
        }
        changed()
    }

    fun clearMet() {
        synchronized(this) {
            met.clear()
            saveMet()
        }
        changed()
    }

    // -- Today's spots ------------------------------------------------------------------------------------------------

    fun dismiss(spot: String) {
        synchronized(this) {
            roll()
            if (!dismissed.add(spot)) return
            saveDay()
        }
        changed()
    }

    /** Opened or closed with ✕: no hero there any more today. */
    @Synchronized fun done(spot: String): Boolean { roll(); return spot in opened || spot in dismissed }

    /** The trip is at a wait: when it got there (seconds), kept from the first time. */
    @Synchronized fun reach(spot: String) { roll(); if (spot !in reached) { reached[spot] = System.currentTimeMillis() / 1000; saveDay() } }
    @Synchronized fun reachedAt(spot: String): Long? { roll(); return reached[spot] }

    @Synchronized fun markSeen(spot: String) { roll(); if (seen.add(spot)) saveDay() }
    @Synchronized fun wasSeen(spot: String): Boolean { roll(); return spot in seen }

    /** A new day: yesterday's spots are forgotten. */
    private fun roll() {
        val today = Site.today()
        if (day == today) return
        day = today
        dismissed.clear()
        opened.clear()
        seen.clear()
        reached.clear()
        saveDay()
    }

    // -- The hint, the widget's hero and the notification -------------------------------------------------------------

    @Synchronized fun hinted() = hinted
    @Synchronized fun setHinted() { if (!hinted) { hinted = true; saveState() } }

    /** Today's widget hero, unless the rider met them since. */
    @Synchronized fun widgetHero(): Sticker? =
        widgetHero?.takeIf { widgetDay == Site.today() && met.none { m -> m.id == it.id } }

    fun setWidgetHero(s: Sticker) {
        synchronized(this) {
            widgetHero = s
            widgetDay = Site.today()
            saveState()
        }
        changed()
    }

    @Synchronized fun due(): Due? = due
    @Synchronized fun setDue(d: Due?) {
        due = d
        if (d == null) notifyFile.delete() else write(notifyFile, d.toJson())
    }

    // -- Files --------------------------------------------------------------------------------------------------------

    private fun saveQueue() = write(queueFile, JSONObject().put("queue", JSONArray(queue.map { it.json })).put("shown", JSONArray(shown.toList())))
    private fun saveMet() = write(metFile, JSONObject().put("met", JSONArray(met.map { it.toJson() })))
    private fun saveDay() = write(dayFile, JSONObject().put("day", day).put("dismissed", JSONArray(dismissed.toList()))
        .put("opened", JSONArray(opened.toList())).put("seen", JSONArray(seen.toList()))
        .put("reached", JSONObject(reached.mapValues { it.value })))
    private fun saveState() = write(stateFile, JSONObject().put("hinted", hinted).put("widgetDay", widgetDay)
        .put("widgetHero", widgetHero?.json ?: JSONObject.NULL))

    /** Written whole or not at all. */
    private fun write(file: File, json: JSONObject) {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            // Not kept: it still works until Kav closes.
        }
    }

    private fun read(file: File, use: (JSONObject) -> Unit) {
        try {
            if (file.isFile) use(JSONObject(file.readText()))
        } catch (e: Exception) {
            // Unreadable: started afresh.
        }
    }
}

internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

/** A string, or null when it's missing, JSON null or blank. (Android's optString turns JSON null into "null".) */
internal fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
