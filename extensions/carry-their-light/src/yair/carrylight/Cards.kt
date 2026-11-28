package yair.carrylight

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kav.kavapp.api.Kav
import org.json.JSONObject

/**
 * The hero's card. In navigation, under the step dots (the part nav.banner, on Kav's own glass card): for the spot
 * whose card the rider looks at, the one the trip is on or one swiped ahead to. On Home, under the trip (the part
 * home.journey): only while the trip is on a spot with a hero, which needs Follow your trips. The sticker, the hero's
 * name and words, and ✕. A tap opens the hero's story; ✕ hides them for that ride.
 */
internal class Cards(
    private val kav: Kav,
    private val store: Store,
    private val picker: Picker,
    private val story: Story,
    private val live: Live,
    private val lang: Lang,
) {
    private val ui get() = kav.ui()

    fun banner(c: Context, about: String): View? = safely { bannerOf(c, JSONObject(about)) }

    fun home(c: Context, about: String): View? = safely { homeOf(c, JSONObject(about)) }

    /** A card that can't be made is no card, never a crash of Kav's screen. */
    private fun safely(make: () -> View?): View? = try {
        make()
    } catch (e: Exception) {
        kav.log("Card not shown: ${e.javaClass.simpleName}")
        null
    }

    private fun bannerOf(c: Context, a: JSONObject): View? {
        val trip = a.optJSONObject("trip") ?: return null
        val journey = Journey.fromBanner(a, live.following(trip)) ?: return null
        val spots = live.spots(journey)
        ready(spots)
        val spot = journey.onScreen(spots)
            ?: return trace.skip("navigation", "step ${a.optInt("shown")} (on ${a.optInt("step")}, ${a.optString("kind")}, steps ${if (journey.knowsSteps) "known" else "unknown"}) isn't a long ride or wait; long: ${describe(spots)}")
        val hero = heroes(spots, journey, store)[spot.key]
            ?: return trace.skip("navigation", "no hero for ${spot.kind} ${spot.minutes} min: ${whyNot(spot, spots, journey)}")
        // Seen while on it: that spot's notification isn't needed. (Seen ahead, it still is.)
        if (spot === journey.current(spots)) store.markSeen(spot.key)
        store.markShown(hero.id)
        // Without Follow your trips there's no Home card and no notification: said once, here.
        val hint = live.following(trip) == null && !store.hinted()
        if (hint) store.setHinted()
        return card(c, hero, spot.key, hint)
    }

    private fun homeOf(c: Context, a: JSONObject): View? {
        val trip = a.optJSONObject("trip") ?: return null
        val journey = live.following(trip) ?: return trace.skip("home", "no trip event for this trip yet")
        val spots = live.spots(journey)
        ready(spots)
        val spot = journey.current(spots) ?: return trace.skip("home", "the trip isn't on a long ride or wait; long: ${describe(spots)}")
        val hero = heroes(spots, journey, store)[spot.key]
            ?: return trace.skip("home", "no hero for ${spot.kind} ${spot.minutes} min: ${whyNot(spot, spots, journey)}")
        store.markShown(hero.id)
        return ui.card(c, card(c, hero, spot.key, hint = false))
    }

    // Temporary, for testing: why a card didn't show, in the extension's Log, once per reason and place.
    private val trace = Trace(kav)

    private fun describe(spots: List<Spot>) =
        if (spots.isEmpty()) "none (min ${live.prefs.minutes}, every ${live.prefs.every}, waits ${live.prefs.waits})"
        else spots.joinToString { "${it.kind} leg ${it.leg} ${it.minutes} min" }

    private fun whyNot(spot: Spot, spots: List<Spot>, journey: Journey) = when {
        store.done(spot.key) -> "opened or hidden earlier"
        journey.past(spot, System.currentTimeMillis() / 1000) -> "already past"
        else -> "${store.queue().size} heroes ready for ${spots.count { !store.done(it.key) }} spots"
    }

    /** Enough heroes ready for every spot of the trip. */
    private fun ready(spots: List<Spot>) {
        if (spots.isEmpty()) return
        picker.want(spots.size)
        picker.ensure()
    }

    private fun card(c: Context, s: Sticker, spot: String, hint: Boolean): View {
        val he = lang.story
        val g = ui.px(c, "gap4")
        // The end side is kept free for ✕ (in the top corner), so the words never run under it.
        val closeSize = c.dpx(40f)
        val content = row(c).apply { setPaddingRelative(g, g, closeSize, g) }

        // The whole sticker, at its own shape; the candle when its picture isn't there.
        val tall = c.dpx(60f)
        val bmp = Pictures.load(store.picture(s.id), tall * 3)
        val wide = if (bmp != null && bmp.height > 0) (tall * bmp.width.toFloat() / bmp.height).toInt().coerceIn(tall, c.dpx(110f)) else tall
        val radius = ui.px(c, "rTile").toFloat()
        content.addView(ImageView(c).apply {
            scaleType = if (bmp != null) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply { cornerRadius = radius; setColor(ui.color("fill")) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            setImageBitmap(bmp ?: Candle.bitmap(tall / 2, ui.color("textSecondary")))
        }, LinearLayout.LayoutParams(wide, tall).apply { marginEnd = ui.px(c, "gap3") })

        // Their name and words, in the story language, sized to how much they said.
        val words = s.quote.of(he)
        val (style, lines) = when {
            words.length <= 45 -> "body" to 2
            words.length <= 100 -> "subhead" to 3
            else -> "subhead" to 4
        }
        content.addWeighted(column(c).apply {
            direct(he)
            s.fellToday()?.let { year -> addFill(ui.text(c, Texts(he).fellToday(s.gender, year), "caption", "accent")) }
            addFill(ui.text(c, s.title(he), "headline").apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            addFill(ui.text(c, quoted(words), style, "textSecondary").apply {
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, ui.px(c, "gap1"), 0, 0)
            })
        })
        content.contentDescription = "${s.title(he)}. $words"
        content.isClickable = true
        content.setOnClickListener { story.open(c, s, spot) }

        // ✕ in the top corner, a full-size touch target around a small glyph: gone for this ride.
        val close = TextView(c).apply {
            text = "✕"
            contentDescription = lang.texts.hide
            gravity = Gravity.CENTER
            setTextColor(ui.color("textSecondary"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textSize("subhead"))
            isClickable = true
            isFocusable = true
            setOnClickListener { store.dismiss(spot) }
        }

        val card = WideFrame(c).apply {
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(close, FrameLayout.LayoutParams(closeSize, c.dpx(44f), Gravity.TOP or Gravity.END))
        }
        if (!hint) return card
        return column(c).apply {
            addFill(card)
            addFill(ui.text(c, lang.texts.followHint, "caption", "textSecondary").apply { setPaddingRelative(g, 0, g, g) })
        }
    }

    /** As wide as it's allowed, whatever the words' length: Kav's card fits itself to what's inside, and ✕ stays in the
     *  corner. */
    private class WideFrame(c: Context) : FrameLayout(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val exact = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && w > 0)
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY) else widthMeasureSpec
            super.onMeasure(exact, heightMeasureSpec)
        }
    }

    /** Temporary, for testing: a line in the Log when a card doesn't show, only when the reason changes. */
    private class Trace(private val kav: Kav) {
        private val last = HashMap<String, String>()

        fun skip(place: String, why: String): View? {
            if (last.put(place, why) != why) kav.log("No card ($place): $why")
            return null
        }
    }
}
