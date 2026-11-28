package yair.heroes

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import kav.kavapp.api.Kav
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The document button during a trip: "Show choger" (or Tzav 8), floating over navigation's map at the screen's edge,
 * within reach of the thumb. It sits in Kav's own layer over its whole screen (`screen.overlay`, API 5), which takes
 * no touch but the button's, so nothing of Kav's moves or is pushed.
 *
 * Where: just above the tallest the step card can be, so it never covers the card or its Current step button. Kav
 * keeps the map clear of the bottom panel by the same sizes (ui/Navigate.kt: the card at 30% of the screen's height,
 * from 160 to 240 dp, and 88 dp for the End trip row and gaps).
 *
 * When: while navigation shows a step the rider wants it on (at a station's way in, or every step), with a photo saved.
 * Kav says which step navigation shows through "Follow your trips" ([Trips]); without it, there's no button.
 */
internal class DocumentButton(
    private val kav: Kav,
    private val store: Store,
    private val config: Config,
    private val trips: Trips,
    private val stayAwake: StayAwake,
) {
    private companion object {
        const val CARD_SHARE = 0.30f
        const val CARD_MIN_DP = 160f
        const val CARD_MAX_DP = 240f
        const val BELOW_CARD_DP = 88f
        const val COMPACT_DP = 480f // under this, navigation puts its cards in a side panel: no button then
    }

    private val layers = CopyOnWriteArrayList<WeakReference<Layer>>()

    /** The trip's plan is open over navigation: the button waits until the rider is back on a step. */
    @Volatile private var planOpen = false

    init {
        trips.onEvent { event, _, _ ->
            if (event != "eta") planOpen = false
            if (event == "eta") return@onEvent
            update()
        }
    }

    fun planOpened() { planOpen = true; update() }

    /** Everything that decides the button changed: each layer on screen looks again. */
    fun update() = onMain {
        layers.removeAll { it.get() == null }
        for (ref in layers) ref.get()?.refresh()
    }

    /** Kav's layer over its screen, made again each time the tab changes. */
    fun view(c: Context): View {
        val layer = Layer(c)
        layers += WeakReference(layer)
        return layer
    }

    private fun wanted(): Boolean {
        val now = trips.now ?: return false
        if (planOpen || now.shown < 0) return false
        if (!store.hasPhoto()) return false
        return when (config.docTrips) {
            "every" -> true
            "stations" -> Trips.atStation(now, now.shown)
            else -> false
        }
    }

    /** A see-through layer as big as Kav's screen, holding the button only. A touch anywhere else goes on to Kav. */
    private inner class Layer(c: Context) : FrameLayout(c) {
        private val ui get() = kav.ui()
        private var bottomInset = 0

        private val button = TextView(c).apply {
            val h = ui.px(c, "gap4")
            setPaddingRelative(h, 0, h, 0)
            gravity = Gravity.CENTER
            minHeight = c.dpx(44f)
            textSize = ui.textSize("subhead")
            isClickable = true
            isFocusable = true
            elevation = c.dpx(2f).toFloat()
            setOnClickListener { Viewer.show(context, store.photo, Texts.of(kav)) }
            visibility = GONE
        }

        init {
            addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
            refresh()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            context.activity()?.let { stayAwake.checkMute(it) }
            requestApplyInsets()
            refresh()
        }

        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            bottomInset = bottomOf(insets)
            place()
            return super.onApplyWindowInsets(insets)
        }

        /** The height of the bars at the bottom of the screen (Android's navigation), as Kav keeps its cards above. */
        private fun bottomOf(insets: WindowInsets): Int = if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).bottom
        } else {
            @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            place()
        }

        fun refresh() {
            val t = Texts.of(kav)
            button.text = t.showDocument(config.tzav8)
            button.contentDescription = button.text
            // It stands out a little (Kav's accent as its border and text) wherever the rider asked for it: at train
            // stations, or on every step.
            val lit = config.docTrips == "every" ||
                trips.now?.let { it.shown >= 0 && Trips.atStation(it, it.shown) } == true
            button.setTextColor(ui.color(if (lit) "accent" else "text"))
            button.typeface = if (lit) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            button.background = ui.pillBackground(context, false, highlighted = lit)
            place()
        }

        private fun place() {
            // Insets that never reached this layer: read them from the window, so the button can't sit too low.
            if (bottomInset == 0) rootWindowInsets?.let { bottomInset = bottomOf(it) }
            val density = resources.displayMetrics.density
            val contentDp = (height - bottomInset) / density
            val show = height > 0 && contentDp >= COMPACT_DP && wanted()
            button.visibility = if (show) VISIBLE else GONE
            if (!show) return
            val cardDp = (contentDp * CARD_SHARE).coerceIn(CARD_MIN_DP, CARD_MAX_DP)
            val params = button.layoutParams as LayoutParams
            val bottom = bottomInset + ((cardDp + BELOW_CARD_DP) * density).toInt() + ui.px(context, "gap2")
            val side = ui.px(context, "gap4")
            if (params.bottomMargin != bottom || params.marginEnd != side) {
                params.bottomMargin = bottom
                params.marginEnd = side
                button.layoutParams = params
            }
        }
    }
}
