package yair.heroes

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import kav.kavapp.api.Kav
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

/**
 * The floating pills in navigation: "⏰ Stay awake" and, on the way into a train station, "Show choger".
 *
 * They float over the map like Kav's own "Current step", so Kav's step card is never pushed. Kav gives no spot of its
 * own there, so each step card gets an invisible, zero-height marker right above it (the nav.card part). The pills are
 * placed from where that marker is on this phone: their bottom just above the step card, at the opposite side from
 * "Current step", stacked upward. Whichever are showing fill from the bottom; the chip stays in the bottom place.
 */
internal class NavOverlay(
    private val kav: Kav,
    private val store: Store,
    private val stayAwake: StayAwake,
) {
    private val ui get() = kav.ui()
    private val pills = WeakHashMap<Activity, Pills>()
    private val loggedTrips = Collections.synchronizedSet(HashSet<String>())
    private val loggedSteps = Collections.synchronizedSet(HashSet<String>())
    @Volatile private var loggedStart = false

    /** The marker above one step card. Kav asks for it each time the card shows. */
    fun marker(c: Context, about: String): View? {
        val activity = c.activity() ?: return null
        val json = JSONObject(about)
        val trip = json.optJSONObject("trip") ?: return null
        val step = json.optInt("step", -1)
        val key = TripSteps.key(trip)

        if (loggedTrips.add(key)) kav.log("Navigation: legs are ${TripSteps.outline(trip)}")
        val station = TripSteps.intoTrainStation(trip, step)
        if (loggedSteps.add("$key/$step")) {
            kav.log("Navigation step $step: leg ${TripSteps.legOf(trip, step)}, into a train station ${if (station) "yes" else "no"}")
        }

        val marker = Marker(c, key, trip.optLong("arr"), station)
        marker.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { pillsFor(activity).add(marker) }
            override fun onViewDetachedFromWindow(v: View) { pills[activity]?.remove(marker) }
        })
        return marker
    }

    private fun pillsFor(activity: Activity) = pills.getOrPut(activity) { Pills(activity) }

    /**
     * One pixel high, so it takes no room you can see; it only tells where the step card starts. (Not zero: Android
     * counts a view with no height as not on screen.)
     */
    private class Marker(c: Context, val trip: String, val arrival: Long, val station: Boolean) : View(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 1)
        }
    }

    /** The pills of one screen, over Kav's content. */
    private inner class Pills(private val activity: Activity) {
        private val markers = LinkedHashSet<Marker>()
        private val root = activity.findViewById<ViewGroup>(android.R.id.content)
        private val chip = pill(activity)
        private val document = pill(activity)
        private var current: Marker? = null
        private var loggedNone = false
        private val density = activity.resources.displayMetrics.density

        private val follow = ViewTreeObserver.OnPreDrawListener { place(); true }
        private val redraw: () -> Unit = { chip.post { draw(); place() } }

        init {
            chip.setOnClickListener {
                val m = current ?: return@setOnClickListener
                if (stayAwake.isOn(m.trip)) stayAwake.stop() else stayAwake.turnOn(m.trip, m.arrival)
            }
            document.setOnClickListener { Viewer.show(activity, store.photo, Texts.of(kav)) }
        }

        fun add(m: Marker) {
            if (markers.isEmpty()) {
                // Top left, absolute: the pills are placed by translation, in the same coordinates in either direction.
                for (v in listOf(document, chip)) {
                    root.addView(v, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT
                    ))
                }
                root.viewTreeObserver.addOnPreDrawListener(follow)
                stayAwake.onChange(redraw)
                if (!loggedStart) { loggedStart = true; kav.log("Navigation pills: added over Kav's screen") }
            }
            markers += m
            draw()
        }

        fun remove(m: Marker) {
            markers -= m
            if (markers.isEmpty()) {
                root.viewTreeObserver.removeOnPreDrawListener(follow)
                stayAwake.removeListener(redraw)
                root.removeView(chip)
                root.removeView(document)
                pills.remove(activity)
            }
        }

        /**
         * The marker of the step card on screen: of a row of cards side by side, the one most inside the screen. Found
         * by position rather than by Android's "visible" check, which a view this thin can fail.
         */
        private fun shown(): Marker? {
            val base = IntArray(2)
            root.getLocationInWindow(base)
            val at = IntArray(2)
            var best: Marker? = null
            var bestScore = 0
            for (m in markers) {
                if (!m.isShown) continue
                m.getLocationInWindow(at)
                val left = at[0] - base[0]
                val score = if (m.width > 0) {
                    minOf(left + m.width, root.width) - maxOf(left, 0)
                } else {
                    if (left >= 0 && left < root.width) 1 else 0
                }
                if (score > bestScore) { best = m; bestScore = score }
            }
            if (best == null && !loggedNone && markers.isNotEmpty()) {
                loggedNone = true
                kav.log("Navigation pills: ${markers.size} markers, none on screen")
            }
            return best
        }

        private fun draw() {
            val t = Texts.of(kav)
            val m = current ?: markers.lastOrNull()
            val on = m != null && stayAwake.isOn(m.trip)
            chip.text = if (on) t.chipOn(store.interval) else t.chipOff
            chip.contentDescription = t.stayAwake
            style(chip, on)
            document.text = t.showDoc(store.tzav8)
            style(document, false)
        }

        private fun style(v: TextView, on: Boolean) {
            v.setTextColor(opaque(ui.color(if (on) "bg" else "text")))
            v.background = GradientDrawable().apply {
                cornerRadius = 100 * density
                setColor(opaque(ui.color(if (on) "accent" else "surfaceRaised")))
                if (!on) setStroke(density.toInt().coerceAtLeast(1), opaque(ui.color("separator")))
            }
        }

        /** Stacks the showing pills upward from just above the step card, at the end side. */
        private fun place() {
            val m = shown()
            if (m !== current) { current = m; draw() }
            val showChip = m != null && store.stayAwakeOn
            val showDocument = m != null && store.hasPhoto() &&
                (store.tripMode == "every" || (store.tripMode == "stations" && m.station))

            var bottom = 0
            var left = 0
            var right = root.width
            if (m != null) {
                val at = IntArray(2)
                m.getLocationInWindow(at)
                val base = IntArray(2)
                root.getLocationInWindow(base)
                val margin = ui.px(activity, "gap4")
                left = if (m.width > 0) at[0] - base[0] else margin
                right = if (m.width > 0) left + m.width else root.width - margin
                bottom = at[1] - base[1] - ui.px(activity, "gap2")
            }
            val gap = ui.px(activity, "gap2")
            val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL

            for ((v, wanted) in listOf(chip to showChip, document to showDocument)) {
                if (!wanted) { if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE; continue }
                if (v.width == 0) { v.requestLayout(); continue } // measured on the next frame, shown then
                val y = (bottom - v.height).toFloat()
                // "Current step" is at the start side, so the pills go at the end side, lined up with the card's edge.
                val x = if (rtl) left.toFloat() else (right - v.width).toFloat()
                if (v.translationX != x) v.translationX = x
                if (v.translationY != y) v.translationY = y
                if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
                bottom -= v.height + gap
            }
        }

        private fun pill(c: Context) = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textSize("subhead"))
            val h = ui.px(c, "gap4")
            val v = ui.px(c, "gap2")
            setPadding(h, v, h, v)
            gravity = Gravity.CENTER
            isClickable = true
            visibility = View.INVISIBLE // still measured, so it can be placed before it shows
        }
    }

    private fun opaque(color: Int) = Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
}
