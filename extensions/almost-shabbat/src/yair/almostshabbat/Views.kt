package yair.almostshabbat

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kav.kavapp.api.Kav
import org.json.JSONObject

/**
 * The line under each search result, the card under a trip's summary and the line under navigation's step dots. A trip
 * that doesn't arrive on an erev from noon, or was planned to leave more than an hour after sunset, gets nothing at all:
 * no view, as if the extension weren't there.
 */
internal class Views(
    private val kav: Kav,
    private val store: Store,
    private val hebcal: Hebcal,
    private val clock: Clock,
    private val delays: DelayWatch,
) {
    private companion object {
        const val BANNER = "nav.banner"
        /** The navigation line shows from this long before candle lighting or sunset, as the rider chose... */
        const val BANNER_FROM_SEC = 30 * 60L
        /** ...until this long after sunset. */
        const val BANNER_UNTIL_SEC = 60 * 60L
    }

    private val ui get() = kav.ui()
    private val kavHebrew get() = kav.locale() == "he"
    private val main = Handler(Looper.getMainLooper())
    /** The one trip the "Follow your trips" hint shows on, while Kav runs: its card can be made again. */
    @Volatile private var hintTrip: String? = null

    /** What a trip is about, once its erev and locality are known. arrival: as things stand, live where Kav has it. */
    private class Subject(val key: String, val plannedDep: Long, val arrival: Long, val erev: Erev, val place: Place)

    private fun subject(trip: JSONObject, arrival: Long): Subject? {
        val erev = Calendar.erevFor(arrival) ?: return null
        val (lat, lon) = destination(trip) ?: return null
        val place = Places.nearest(lat, lon) ?: return null
        return Subject(tripKey(trip), trip.optLong("dep", 0L), arrival, erev, place)
    }

    private fun subject(about: String): Subject? {
        val trip = JSONObject(about).optJSONObject("trip") ?: return null
        return subject(trip, liveArrival(trip))
    }

    fun resultLine(c: Context, about: String): View? {
        val s = subject(about) ?: return null
        return whenReady(c, s) { state -> line(c, s, state) }
    }

    fun tripCard(c: Context, about: String): View? {
        val s = subject(about) ?: return null
        return whenReady(c, s) { state -> card(c, s, state) }
    }

    /**
     * Registered with Kav, and again to have Kav ask for the line anew (a new arrival, or its time to show came). A trip
     * Kav describes in a way we don't expect shows nothing, rather than counting as a crash.
     */
    val bannerCard = Kav.Card { c, about ->
        try { banner(c, about) } catch (e: Exception) { kav.log("Navigation line: ${e.javaClass.simpleName}: ${e.message}"); null }
    }

    fun refreshBanner() {
        main.post { try { kav.part(BANNER, "after", bannerCard) } catch (e: Exception) { kav.log("Banner: ${e.message}") } }
    }

    private fun banner(c: Context, about: String): View? {
        val trip = JSONObject(about).optJSONObject("trip") ?: return null
        val s = subject(trip, delays.etaFor(trip) ?: liveArrival(trip)) ?: return null
        val state = hebcal.get(s.place, s.erev.date) { refreshBanner() }
        val times = (state as? Hebcal.State.Ready)?.times ?: return null
        // The trip as first planned: a new route on the way leaves later, but the rider didn't choose that.
        if (leavesAfterShabbat(delays.plannedDepFor(trip) ?: s.plannedDep, times.sunset)) return null
        // The window: from 30 minutes before candle lighting or sunset, as the rider chose, to an hour after sunset. The
        // line shows while the clock is in it, or while the trip arrives in it (a new arrival asks for the line again).
        // Kav is asked to make it again when the clock enters or leaves the window.
        val from = (if (store.ref == Ref.SUNSET) times.sunset else times.candles) - BANNER_FROM_SEC
        val to = times.sunset + BANNER_UNTIL_SEC
        val now = System.currentTimeMillis() / 1000
        val clockIn = now in from until to
        val arrivesIn = s.arrival in from until to
        main.removeCallbacksAndMessages(BANNER)
        val next = if (now < from) from else if (now < to) to else 0L
        if (next > 0) main.postAtTime({ refreshBanner() }, BANNER, SystemClock.uptimeMillis() + (next - now) * 1000 + 1000)
        if (!clockIn && !arrivesIn) return null
        val v = Verdict.of(s.arrival, times.candles, times.sunset, store.ref)
        val text = Words(kavHebrew, store.ref, s.erev.beginsName(kavHebrew)).navLine(v, clock.format(times.candles), clock.format(times.sunset))
        return ui.text(c, text, "subhead", if (v.warn) "problem" else "text").apply {
            val g = ui.px(c, "gap3")
            setPaddingRelative(g, ui.px(c, "gap2"), g, ui.px(c, "gap2"))
        }
    }

    /**
     * The view now if the times are known; otherwise an empty frame, filled once Hebcal answers, whether or not it's on
     * screen then: Kav keeps the view it was given and shows it again when the rider scrolls back to it.
     */
    private fun whenReady(c: Context, s: Subject, make: (Hebcal.State) -> View?): View? {
        val frame = FrameLayout(c)
        // Told only when it had to be fetched: an answer already known comes back at once.
        val state = hebcal.get(s.place, s.erev.date) { answer ->
            if (frame.childCount == 0) make(answer)?.let { frame.addView(it) }
        }
        return if (state is Hebcal.State.Loading) frame else make(state)
    }

    private fun words(s: Subject) = Words(kavHebrew, store.ref, s.erev.beginsName(kavHebrew))

    private fun line(c: Context, s: Subject, state: Hebcal.State): View? {
        val times = (state as? Hebcal.State.Ready)?.times
            ?: return ui.text(c, Texts(kavHebrew).noHebcal, "subhead", "textSecondary")
        if (leavesAfterShabbat(s.plannedDep, times.sunset)) return null
        val v = Verdict.of(s.arrival, times.candles, times.sunset, store.ref)
        return ui.text(c, words(s).resultLine(v), "subhead", if (v.warn) "problem" else "textSecondary")
    }

    private fun card(c: Context, s: Subject, state: Hebcal.State): View? {
        val t = Texts(kavHebrew)
        val box = column(c)
        val times = (state as? Hebcal.State.Ready)?.times
        if (times == null) {
            box.addFull(ui.text(c, t.noHebcal, "body", "textSecondary"))
            return inset(c, ui.card(c, box))
        }
        if (leavesAfterShabbat(s.plannedDep, times.sunset)) return null

        // The title: the parsha, or "Erev Shavuot", "Erev Shabbat Chol ha-Moed Pesach" when there is none.
        val parshaHebrew = store.parshaHebrew(kavHebrew)
        val parsha = if (parshaHebrew) times.parshaHe else times.parshaEn
        val plain = s.erev.holiday == null && s.erev.cholHaMoed == null
        val title = if (plain && parsha.isNotEmpty()) parsha else s.erev.erevName(parshaHebrew)
        box.addFull(ui.text(c, title, "headline").apply { setTypeface(typeface, Typeface.BOLD) })

        val v = Verdict.of(s.arrival, times.candles, times.sunset, store.ref)
        box.addFull(ui.text(c, words(s).tripSentence(v), "body", if (v.warn) "problem" else "text"), ui.px(c, "gap2"))

        box.addFull(timeRow(c, t.candles, clock.format(times.candles)), ui.px(c, "gap3"))
        box.addFull(timeRow(c, t.sunset, clock.format(times.sunset)), ui.px(c, "gap1"))

        box.addFull(ui.text(c, t.timesFor(s.place.name(store.placeHebrew(kavHebrew))), "caption", "textSecondary"), ui.px(c, "gap3"))
        box.addFull(ui.text(c, t.credit, "caption", "textTertiary").apply {
            setOnClickListener {
                try {
                    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.hebcal.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    kav.log("No browser to open hebcal.com")
                }
            }
        }, ui.px(c, "gap1"))

        // Once: how to get the delay notification, until Kav has told the extension about a trip (then it's on).
        if (!store.followSeen && (!store.hintShown || hintTrip == s.key)) {
            hintTrip = s.key
            store.markHintShown()
            box.addFull(ui.text(c, t.followHint, "caption", "textSecondary"), ui.px(c, "gap3"))
        }
        return inset(c, ui.card(c, box))
    }

    /** As wide as Kav's summary card above it: Kav leaves its gap4 at each side, and a part gets the full width. */
    private fun inset(c: Context, card: View): View = FrameLayout(c).apply {
        val g = ui.px(c, "gap4")
        setPaddingRelative(g, 0, g, 0)
        addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    /** "Candle lighting ........ 16:05", the time at the far end. */
    private fun timeRow(c: Context, label: String, time: String): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.text(c, label, "body", "textSecondary"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(ui.text(c, time, "body").apply { setTypeface(typeface, Typeface.BOLD) })
    }
}
