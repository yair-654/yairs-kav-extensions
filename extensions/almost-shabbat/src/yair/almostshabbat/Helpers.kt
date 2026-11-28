package yair.almostshabbat

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kav.kavapp.api.Kav
import kav.kavapp.api.KavUi
import org.json.JSONArray
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

/**
 * When the trip arrives as things stand: its planned arrival, moved by as much as its last ride runs late or early by
 * Kav's live time at the stop it's left at, as Kav's own trip page shows it. The plan's arrival without a live time.
 */
internal fun liveArrival(trip: JSONObject): Long {
    val planned = trip.optLong("arr", 0L)
    val legs = trip.optJSONArray("legs") ?: return planned
    for (i in legs.length() - 1 downTo 0) {
        val leg = legs.optJSONObject(i) ?: continue
        if (leg.optString("kind") != "ride") continue
        if (leg.isNull("liveArr")) return planned
        val live = leg.optLong("liveArr", 0L)
        val arr = leg.optLong("arr", 0L)
        return if (live > 0 && arr > 0) planned + (live - arr) else planned
    }
    return planned
}

/** A trip's own key, the same for each event and screen it's told in: when it leaves and its first ride's trip. */
internal fun tripKey(trip: JSONObject): String {
    val legs = trip.optJSONArray("legs") ?: JSONArray()
    var ride = ""
    for (i in 0 until legs.length()) {
        val l = legs.optJSONObject(i) ?: continue
        if (l.optString("kind") == "ride") { ride = l.optString("trip"); break }
    }
    return "${trip.optLong("dep")}:$ride"
}

/** Times as Kav shows them: Israel time, in Kav's own 12 or 24-hour setting, asked each time so a change shows at once. */
internal class Clock(private val kav: Kav) {
    private val israel = TimeZone.getTimeZone("Asia/Jerusalem")

    private fun twelveHour(): Boolean = try {
        JSONObject(kav.prefs()).optInt("clock", 24) == 12
    } catch (e: Exception) {
        false // 24-hour, Kav's own default.
    }

    fun format(seconds: Long): String =
        SimpleDateFormat(if (twelveHour()) "h:mm a" else "HH:mm", Locale.US).apply { timeZone = israel }
            .format(Date(seconds * 1000))
}
