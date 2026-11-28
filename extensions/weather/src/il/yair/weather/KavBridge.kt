package il.yair.weather

import kav.kavapp.api.Kav
import org.json.JSONArray
import org.json.JSONObject

/**
 * The two things this extension asks Kav for, through Kav's own API 5 calls (no reaching into Kav's code). The approach
 * is Noam's, from his API 5 port of Weather:
 *  - `kav.prefs()`: the rider's display settings, whose "clock" is 12 or 24, the clock every time in Kav is shown in.
 *  - `kav.stops({"code": N})`: a ministry stop from Kav's timetable on the phone, with its lat and lon.
 */
class KavBridge(private val kav: Kav) {

    /** Kav's clock pattern now ("HH:mm" or "h:mm a"), read each time so a change in Kav's settings shows at once. */
    fun clockPattern(): String {
        val clock = runCatching { JSONObject(kav.prefs()).optInt("clock", 0) }.getOrDefault(0)
        return when (clock) {
            12 -> "h:mm a"
            24 -> "HH:mm"
            else -> if (android.text.format.DateFormat.is24HourFormat(kav.context())) "HH:mm" else "h:mm a"
        }
    }

    private val points = HashMap<Int, Pair<Double, Double>>()

    /** A ministry stop's place from Kav's timetable, (lat, lon), or null (no timetable yet, or an unknown stop). */
    fun stopPoint(code: Int): Pair<Double, Double>? {
        if (code <= 0) return null
        synchronized(points) { points[code]?.let { return it } }
        val found = try {
            val answer = kav.stops(JSONObject().put("code", code).put("limit", 1).toString()) ?: return null
            val stops = JSONArray(answer)
            if (stops.length() == 0) return null
            val s = stops.getJSONObject(0)
            val la = s.optDouble("lat", 0.0)
            val lo = s.optDouble("lon", 0.0)
            if (la == 0.0 && lo == 0.0) return null
            Pair(la, lo)
        } catch (e: Exception) {
            kav.log("can't read stop $code from Kav's timetable: $e")
            return null
        }
        synchronized(points) { points[code] = found }
        return found
    }
}
