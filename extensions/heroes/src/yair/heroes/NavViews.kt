package yair.heroes

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kav.kavapp.api.Kav
import org.json.JSONObject

/**
 * The Stay awake button, in Kav's own place for an extension inside a ride's card in navigation (`nav.ride.details`,
 * API 5): one small button under the line's row, so nothing in the card moves or gets crowded. Shown only while
 * "Stay awake button on rides" is on.
 */
internal class RideButton(
    private val kav: Kav,
    private val config: Config,
    private val stayAwake: StayAwake,
) {
    private val ui get() = kav.ui()

    fun view(c: Context, about: String): View? {
        if (!config.stayAwake) return null
        val trip = try { JSONObject(about).optJSONObject("trip") } catch (e: Exception) { null } ?: return null
        val key = Trips.key(trip)
        val arrival = trip.optLong("arr")

        val holder = FrameLayout(c)
        val pill = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = c.dpx(40f)
            val h = ui.px(c, "gap3")
            setPaddingRelative(h, 0, h + ui.px(c, "gap1"), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (stayAwake.isOn(key)) stayAwake.stop() else stayAwake.turnOn(key, arrival)
            }
        }
        val icon = ImageView(c)
        val label = ui.text(c, "", "subhead")
        val iconSize = c.dpx(18f)
        pill.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = ui.px(c, "gap2") })
        pill.addView(label)
        holder.addView(pill, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL,
        ))

        fun draw() {
            val t = Texts.of(kav)
            val on = stayAwake.isOn(key)
            val ink = ui.color(if (on) "bg" else "textSecondary")
            pill.background = ui.pillBackground(c, on)
            icon.setImageBitmap(Icons.clock(iconSize, ink))
            label.text = if (on) t.stayAwakeOn(config.interval) else t.stayAwake
            label.setTextColor(ui.color(if (on) "bg" else "text"))
            pill.contentDescription = label.text
        }
        draw()
        val redraw: () -> Unit = { onMain { draw() } }
        holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                stayAwake.onChange(redraw)
                v.context.activity()?.let { stayAwake.checkMute(it) }
                draw()
            }
            override fun onViewDetachedFromWindow(v: View) { stayAwake.removeListener(redraw) }
        })
        return holder
    }
}
