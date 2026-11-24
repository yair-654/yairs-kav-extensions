package yair.almostshabbat

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kav.kavapp.api.Kav
import org.json.JSONObject

/**
 * The line under each search result and the card under a trip's summary. A trip that doesn't arrive on an erev from
 * noon gets nothing at all: no view, as if the extension weren't there.
 */
internal class Views(private val kav: Kav, private val store: Store, private val hebcal: Hebcal, private val clock: Clock) {
    private val ui get() = kav.ui()
    private val kavHebrew get() = kav.locale() == "he"

    /** What a trip is about, once its erev and locality are known. */
    private class Subject(val arrival: Long, val erev: Erev, val place: Place)

    private fun subject(about: String): Subject? {
        val trip = JSONObject(about).optJSONObject("trip") ?: return null
        val arrival = trip.optLong("arr", 0L)
        val erev = Calendar.erevFor(arrival) ?: return null
        val (lat, lon) = destination(trip) ?: return null
        val place = Places.nearest(lat, lon) ?: return null
        return Subject(arrival, erev, place)
    }

    fun resultLine(c: Context, about: String): View? {
        val s = subject(about) ?: return null
        return whenReady(c, s) { state -> line(c, s, state) }
    }

    fun tripCard(c: Context, about: String): View? {
        val s = subject(about) ?: return null
        return whenReady(c, s) { state -> card(c, s, state) }
    }

    /** The view now if the times are known; otherwise an empty frame, filled once Hebcal answers. */
    private fun whenReady(c: Context, s: Subject, make: (Hebcal.State) -> View): View {
        val frame = FrameLayout(c)
        val state = hebcal.get(s.place, s.erev.date) { answer ->
            if (frame.isAttachedToWindow && frame.childCount == 0) frame.addView(make(answer))
        }
        if (state !is Hebcal.State.Loading) frame.addView(make(state))
        return frame
    }

    private fun words(s: Subject) = Words(kavHebrew, store.ref, s.erev.beginsName(kavHebrew))

    private fun line(c: Context, s: Subject, state: Hebcal.State): View {
        val times = (state as? Hebcal.State.Ready)?.times
            ?: return ui.text(c, Texts(kavHebrew).noHebcal, "subhead", "textSecondary")
        val v = Verdict.of(s.arrival, times.candles, times.sunset, store.ref)
        return ui.text(c, words(s).resultLine(v), "subhead", if (v.warn) "problem" else "textSecondary")
    }

    private fun card(c: Context, s: Subject, state: Hebcal.State): View {
        val t = Texts(kavHebrew)
        val box = column(c)
        val gap = ui.px(c, "gap2")
        val times = (state as? Hebcal.State.Ready)?.times
        if (times == null) {
            box.addFull(ui.text(c, t.noHebcal, "body", "textSecondary"))
            return inset(c, ui.card(c, box))
        }

        // The title: the parsha, or "Erev Shavuot" when there is none.
        val parshaHebrew = store.parshaHebrew(kavHebrew)
        val parsha = if (parshaHebrew) times.parshaHe else times.parshaEn
        val title = if (s.erev.holiday == null && parsha.isNotEmpty()) parsha else s.erev.erevName(parshaHebrew)
        box.addFull(ui.text(c, title, "headline").apply { setTypeface(typeface, Typeface.BOLD) })

        val v = Verdict.of(s.arrival, times.candles, times.sunset, store.ref)
        box.addFull(ui.text(c, words(s).tripSentence(v), "body", if (v.warn) "problem" else "text"), gap)

        box.addFull(timeRow(c, t.candles, clock.format(times.candles)), ui.px(c, "gap3"))
        box.addFull(timeRow(c, t.sunset, clock.format(times.sunset)), ui.px(c, "gap1"))

        box.addFull(ui.text(c, t.timesFor(s.place.name(store.placeHebrew(kavHebrew))), "caption", "textSecondary"), ui.px(c, "gap3"))
        box.addFull(ui.text(c, t.thanks, "caption", "textTertiary").apply {
            setOnClickListener {
                try {
                    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.hebcal.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    kav.log("No browser to open hebcal.com")
                }
            }
        }, ui.px(c, "gap1"))
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
