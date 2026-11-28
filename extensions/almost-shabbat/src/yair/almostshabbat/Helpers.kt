package yair.almostshabbat

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kav.kavapp.api.Kav
import kav.kavapp.api.KavUi
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One of Kav's sizes (gap1 to gap8, rCard...) in pixels. */
internal fun KavUi.px(context: Context, name: String) =
    (dp(name) * context.resources.displayMetrics.density).toInt()

/** A text in Kav's colours and type sizes: style is one of Kav's text styles, color one of its colour roles. */
internal fun KavUi.text(context: Context, value: String, style: String = "body", color: String = "text") =
    TextView(context).apply {
        text = value
        setTextColor(this@text.color(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, this@text.textSize(style))
    }

/** The small grey explanation Kav puts under a setting. */
internal fun KavUi.explain(context: Context, value: String) =
    text(context, value, "subhead", "textSecondary").apply {
        val g = this@explain.px(context, "gap4")
        setPaddingRelative(g, this@explain.px(context, "gap1"), g, this@explain.px(context, "gap3"))
    }

internal fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

internal fun LinearLayout.addFull(view: View, topMarginPx: Int = 0) {
    addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = topMarginPx
    })
}

/** The trip's destination: where its last leg ends. Null when Kav doesn't know it. */
internal fun destination(trip: JSONObject): Pair<Double, Double>? {
    val legs = trip.optJSONArray("legs") ?: return null
    if (legs.length() == 0) return null
    val to = legs.getJSONObject(legs.length() - 1).optJSONObject("to") ?: return null
    val lat = to.optDouble("lat", 0.0)
    val lon = to.optDouble("lon", 0.0)
    return if (lat == 0.0 && lon == 0.0) null else lat to lon
}

/** Times as Kav shows them: Israel time, in Kav's own 12 or 24-hour setting, read from Kav itself. */
internal class Clock(private val kav: Kav) {
    private val israel = TimeZone.getTimeZone("Asia/Jerusalem")

    private fun twelveHour(): Boolean = try {
        val shown = Class.forName("uk.noammm.kav.ui.Shown", true, kav.kavClassLoader())
        val instance = shown.getField("INSTANCE").get(null)
        shown.getMethod("getTwelveHour").invoke(instance) as Boolean
    } catch (e: Throwable) {
        false // Not found in this Kav: 24-hour, Kav's own default.
    }

    fun format(seconds: Long): String =
        SimpleDateFormat(if (twelveHour()) "h:mm a" else "HH:mm", Locale.US).apply { timeZone = israel }
            .format(Date(seconds * 1000))
}
