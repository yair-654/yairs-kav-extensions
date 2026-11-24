package il.yair.routeweather

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** IMS (Israel Meteorological Service) data: parsing, day keys, condition kinds, the sun, warnings. No Android views. */
object IMS {

    val ISRAEL: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

    /** An IMS forecast location. rid is its land warning region; sea regions are never used (riders aren't at sea). */
    class City(val lid: Int, val nameEn: String, val nameHe: String, val lat: Double, val lon: Double, val rid: Int?) {
        fun name(hebrew: Boolean): String = if (hebrew) nameHe.ifEmpty { nameEn } else nameEn.ifEmpty { nameHe }
    }

    // ---------------- Condition kinds ----------------

    /** The ten kinds, in the default worst-to-best order. ids are what the settings file stores. */
    enum class Kind(val id: String) {
        THUNDERSTORM("thunderstorm"), SNOW("snow"), RAIN("rain"), HEAT("heat"), DUST("dust"),
        WIND("wind"), FOG("fog"), CLOUDY("cloudy"), PARTLY("partly"), CLEAR("clear");

        companion object {
            fun of(id: String): Kind? = values().firstOrNull { it.id == id }
        }
    }

    val DEFAULT_ORDER: List<String> = Kind.values().map { it.id }

    /** On by default: only the kinds worth knowing about. Cloudy, partly cloudy and clear start off. */
    val DEFAULT_OFF: Set<String> = setOf("cloudy", "partly", "clear")

    /**
     * An IMS weather code's kind. Known codes first; any other code by the words of its English description
     * (from IMS's weather_codes list), so a code this file doesn't know still lands in the right kind.
     */
    fun kindOf(code: Int, descEn: String): Kind {
        when (code) {
            1020 -> return Kind.THUNDERSTORM
            1060, 1070, 1080, 1520 -> return Kind.SNOW
            1140, 1530, 1540, 1560 -> return Kind.RAIN
            1160 -> return Kind.FOG
            1310, 1580 -> return Kind.HEAT
            1260, 1510 -> return Kind.WIND
            1230 -> return Kind.CLOUDY
            1220 -> return Kind.PARTLY
            1250 -> return Kind.CLEAR
        }
        val d = descEn.lowercase(Locale.US)
        return when {
            "thunder" in d -> Kind.THUNDERSTORM
            "snow" in d || "sleet" in d -> Kind.SNOW
            "rain" in d || "shower" in d || "drizzle" in d -> Kind.RAIN
            "dust" in d || "sand" in d || "haze" in d -> Kind.DUST
            "fog" in d || "mist" in d -> Kind.FOG
            "hot" in d || "heat" in d -> Kind.HEAT
            "wind" in d || "storm" in d -> Kind.WIND
            "partly" in d -> Kind.PARTLY
            "cloud" in d || "overcast" in d -> Kind.CLOUDY
            "clear" in d || "sunny" in d || "fine" in d -> Kind.CLEAR
            else -> Kind.CLOUDY
        }
    }

    /** "Possible rain": a rain code that only says rain may come. Below the rider's threshold it doesn't count as rain. */
    fun isPossibleRain(code: Int, descEn: String): Boolean =
        code == 1530 || code == 1540 || "possib" in descEn.lowercase(Locale.US)

    // ---------------- Forecast ----------------

    /** One location's forecast: day key (yyyy-MM-dd, Israel time) -> {daily, hourly}. */
    class Forecast(val lid: Int, val fetchedAt: Long, val days: Map<String, JSONObject>) {

        /** Every hourly block, by its epoch second, sorted. */
        val hours: List<Pair<Long, JSONObject>> by lazy {
            val out = ArrayList<Pair<Long, JSONObject>>()
            for ((_, day) in days) {
                val hourly = day.optJSONObject("hourly") ?: continue
                val it = hourly.keys()
                while (it.hasNext()) {
                    val h = hourly.optJSONObject(it.next()) ?: continue
                    val t = parseStamp(h.optString("forecast_time")) ?: continue
                    out.add(Pair(t / 1000, h))
                }
            }
            out.sortedBy { it.first }
        }

        /** The hourly block nearest to a time, within maxGapSec, across day boundaries. */
        fun nearestHour(atSec: Long, maxGapSec: Long): Pair<Long, JSONObject>? =
            hours.minByOrNull { Math.abs(it.first - atSec) }?.takeIf { Math.abs(it.first - atSec) <= maxGapSec }
    }

    /**
     * The forecast endpoint answers { "data": { "<yyyy-mm-dd>": { "daily": {...}, "hourly": { "HH": {...} } } } },
     * day keys in Israel time. Hourly fields used: forecast_time ("yyyy-MM-dd HH:mm:ss", Israel time), temperature,
     * weather_code, rain_chance, relative_humidity, wind_speed, wind_direction_id, gust_speed, u_v_index,
     * heat_stress, wind_chill. Daily: minimum_temperature, maximum_temperature, weather_code, maximum_uvi.
     */
    fun parseForecast(body: String, lid: Int): Forecast? = try {
        val root = JSONObject(body).getJSONObject("data")
        val days = LinkedHashMap<String, JSONObject>()
        val it = root.keys()
        while (it.hasNext()) {
            val k = it.next()
            val o = root.optJSONObject(k) ?: continue
            if (o.has("daily") || o.has("hourly")) days[k] = o
        }
        if (days.isEmpty()) null else Forecast(lid, System.currentTimeMillis(), days)
    } catch (e: Exception) {
        null
    }

    /** locations_info: lid -> City, in one language. lat/lon come as text ("31.7780"); sea_rid is ignored. */
    fun parseLocations(body: String): Map<Int, Triple<String, Double, Double>>? = try {
        val data = JSONObject(body).getJSONObject("data")
        val out = HashMap<Int, Triple<String, Double, Double>>()
        val it = data.keys()
        while (it.hasNext()) {
            val o = data.optJSONObject(it.next()) ?: continue
            val lid = o.optString("lid").toIntOrNull() ?: continue
            val lat = o.optString("lat").toDoubleOrNull() ?: continue
            val lon = o.optString("lon").toDoubleOrNull() ?: continue
            val name = o.optString("name").trim()
            if (lid > 0 && name.isNotEmpty()) out[lid] = Triple(name, lat, lon)
        }
        out
    } catch (e: Exception) {
        null
    }

    /** locations_info's land warning region per lid. */
    fun parseRegions(body: String): Map<Int, Int> = try {
        val data = JSONObject(body).getJSONObject("data")
        val out = HashMap<Int, Int>()
        val it = data.keys()
        while (it.hasNext()) {
            val o = data.optJSONObject(it.next()) ?: continue
            val lid = o.optString("lid").toIntOrNull() ?: continue
            val rid = if (o.isNull("rid")) null else o.optString("rid").toIntOrNull()
            if (rid != null) out[lid] = rid
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }

    // ---------------- Time ----------------

    /** The forecast's day key for an epoch (ms): yyyy-MM-dd in Israel time. */
    fun dayKey(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = ISRAEL
        return sdf.format(java.util.Date(epochMs))
    }

    private val STAMPS = arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "dd/MM/yyyy HH:mm", "dd/MM/yyyy HH:mm:ss")

    /** IMS's stamps, which are Israel time. Epoch ms, or null. */
    fun parseStamp(stamp: String?): Long? {
        val s = stamp?.trim() ?: return null
        if (s.isEmpty() || s == "null") return null
        for (p in STAMPS) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US)
                sdf.timeZone = ISRAEL
                sdf.isLenient = false
                val d = sdf.parse(s) ?: continue
                return d.time
            } catch (e: Exception) { }
        }
        return null
    }

    /** Any time IMS might give: unix seconds, unix ms, or a stamp. Seconds, or null. */
    fun toEpochSec(v: Any?): Long? {
        return when (v) {
            null, JSONObject.NULL -> null
            is Number -> v.toLong().let { if (it > 100_000_000_000L) it / 1000 else it }.takeIf { it > 0 }
            is String -> v.trim().toLongOrNull()?.let { toEpochSec(it) } ?: parseStamp(v)?.div(1000)
            else -> null
        }
    }

    /**
     * Whether it's dark at a place and time: the sun's altitude below the horizon (-0.833°, sunrise/sunset as
     * almanacs give them). Accurate to a minute or two for Israel, enough for a moon icon.
     */
    fun isNight(lat: Double, lon: Double, epochSec: Long): Boolean {
        val d = epochSec / 86400.0 + 2440587.5 - 2451545.0
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = Math.toRadians((q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)) % 360)
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = Math.atan2(Math.cos(e) * Math.sin(l), Math.cos(l))
        val dec = Math.asin(Math.sin(e) * Math.sin(l))
        val gmst = ((18.697374558 + 24.06570982441908 * d) % 24 + 24) % 24
        val ha = Math.toRadians(gmst * 15 + lon) - ra
        val la = Math.toRadians(lat)
        val alt = Math.asin(Math.sin(la) * Math.sin(dec) + Math.cos(la) * Math.cos(dec) * Math.cos(ha))
        return Math.toDegrees(alt) < -0.833
    }

    // ---------------- Wind ----------------

    private val WIND_EN = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    private val WIND_HE = arrayOf(
        "צפונית", "צפונית-צפון-מזרחית", "צפון-מזרחית", "מזרחית-צפון-מזרחית",
        "מזרחית", "מזרחית-דרום-מזרחית", "דרום-מזרחית", "דרומית-דרום-מזרחית",
        "דרומית", "דרומית-דרום-מערבית", "דרום-מערבית", "מערבית-דרום-מערבית",
        "מערבית", "מערבית-צפון-מערבית", "צפון-מערבית", "צפונית-צפון-מערבית")

    /** IMS's wind_direction_id (1..16 clockwise from north, 17 north again) -> compass name; "" when unknown. */
    fun windDirectionName(id: Int, hebrew: Boolean): String {
        if (id < 1 || id > 17) return ""
        val i = (id - 1) % 16
        return if (hebrew) WIND_HE[i] else WIND_EN[i]
    }

    // ---------------- Warnings ----------------

    /** One IMS warning: its land regions, when it holds (seconds), its severity and words. */
    class Warning(val key: String, val severity: Int, val type: Int, val fromSec: Long?, val toSec: Long?,
                  val regions: MutableSet<Int>, val title: String, val text: String) {
        /** Whether it holds at a time. With no end, it holds until the end of its first day (Israel time). */
        fun holdsAt(atSec: Long): Boolean {
            val from = fromSec ?: 0L
            val to = toSec ?: endOfDay(fromSec ?: atSec)
            return atSec in from..to
        }
    }

    private fun endOfDay(sec: Long): Long {
        val c = java.util.Calendar.getInstance(ISRAEL)
        c.timeInMillis = sec * 1000
        c.set(java.util.Calendar.HOUR_OF_DAY, 23); c.set(java.util.Calendar.MINUTE, 59); c.set(java.util.Calendar.SECOND, 59)
        return c.timeInMillis / 1000
    }

    private val REGION_KEY = Regex("^r-?(\\d+)$")
    private val REGION_FIELDS = setOf("rid", "region", "region_id", "regions", "region_ids", "rids", "area", "areas", "area_ids")
    private val TITLE_FIELDS = arrayOf("title", "headline", "name", "short_text", "title_he", "title_en")
    private val TEXT_FIELDS = arrayOf("full_text", "text", "description", "desc", "body", "content", "full_he", "full_en", "text_he", "text_en")

    /**
     * IMS's warnings feed (ims.gov.il/<lang>/warnings). With no warnings it answers
     * {"data": {"full_warnings_data": [], "distinct_warnings": []}}. The shape with warnings in it isn't written
     * down anywhere, so this reads it loosely: any object carrying severity_id, warning_type_id or valid_from/valid_to
     * is a warning, its regions come from region fields inside it or from "r-<id>" keys above it, and the same
     * warning under several regions is merged. Null when the body isn't the feed at all.
     */
    fun parseWarnings(body: String): List<Warning>? {
        val data = try { JSONObject(body).optJSONObject("data") } catch (e: Exception) { null } ?: return null
        val out = LinkedHashMap<String, Warning>()
        walk(data.opt("full_warnings_data"), emptySet(), out)
        walk(data.opt("distinct_warnings"), emptySet(), out)
        return out.values.toList()
    }

    private fun walk(node: Any?, regions: Set<Int>, out: MutableMap<String, Warning>) {
        when (node) {
            is JSONObject -> {
                if (node.has("severity_id") || node.has("warning_type_id") || (node.has("valid_from") && node.has("valid_to"))) {
                    val w = toWarning(node, regions) ?: return
                    val had = out[w.key]
                    if (had == null) out[w.key] = w else had.regions.addAll(w.regions)
                    return
                }
                val it = node.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val r = REGION_KEY.find(k)?.groupValues?.get(1)?.toIntOrNull()
                    walk(node.opt(k), if (r != null) regions + r else regions, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), regions, out)
        }
    }

    private fun toWarning(o: JSONObject, outer: Set<Int>): Warning? {
        val regions = HashSet<Int>(outer)
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k.lowercase(Locale.US) in REGION_FIELDS) collectInts(o.opt(k), regions)
        }
        val from = toEpochSec(o.opt("valid_from_unix")) ?: toEpochSec(o.opt("valid_from"))
        val to = toEpochSec(o.opt("valid_to_unix")) ?: toEpochSec(o.opt("valid_to"))
        val title = TITLE_FIELDS.firstNotNullOfOrNull { clean(o.optString(it)).takeIf { s -> s.isNotEmpty() && s != "null" } } ?: ""
        val text = TEXT_FIELDS.firstNotNullOfOrNull { clean(o.optString(it)).takeIf { s -> s.isNotEmpty() && s != "null" } } ?: ""
        val severity = o.optString("severity_id").toIntOrNull() ?: 0
        val type = o.optString("warning_type_id").toIntOrNull() ?: 0
        val key = listOf("id", "warning_id", "nid", "wid").firstNotNullOfOrNull { o.optString(it).takeIf { s -> s.isNotEmpty() && s != "null" } }
            ?: "$type|$from|$to|${text.hashCode()}"
        return Warning(key, severity, type, from, to, regions, title, text)
    }

    private fun collectInts(v: Any?, into: MutableSet<Int>) {
        when (v) {
            is Number -> into.add(v.toInt())
            is String -> v.split(',', ';', ' ').mapNotNull { REGION_KEY.find(it.trim())?.groupValues?.get(1)?.toIntOrNull() ?: it.trim().toIntOrNull() }.forEach { into.add(it) }
            is JSONArray -> for (i in 0 until v.length()) collectInts(v.opt(i), into)
            is JSONObject -> {
                val r = v.opt("rid") ?: v.opt("id") ?: v.opt("region_id")
                if (r != null) collectInts(r, into)
                else { val it = v.keys(); while (it.hasNext()) { val k = it.next(); collectInts(k, into) } }
            }
        }
    }

    /** IMS texts come with HTML and padding: one clean line. */
    fun clean(s: String): String = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").trim().replace(Regex("\\s+"), " ")

    // ---------------- Built-in locations ----------------

    /** IMS's biggest places, for when locations_info can't be fetched. Jerusalem's and Haifa's land regions are known. */
    fun builtinLocations(): Map<Int, City> {
        val rows = listOf(
            arrayOf(1, "Jerusalem", "ירושלים", 31.7780, 35.2000, 118),
            arrayOf(2, "Tel Aviv coast", "תל-אביב", 32.0821, 34.7802, null),
            arrayOf(3, "Haifa", "חיפה", 32.7900, 34.9900, 92),
            arrayOf(6, "Ashdod", "אשדוד", 31.8011, 34.6410, null),
            arrayOf(8, "Be'er Sheva", "באר שבע", 31.2497, 34.7997, null),
            arrayOf(17, "Herzliya", "הרצליה", 32.1643, 34.8423, null),
            arrayOf(31, "Eilat", "אילת", 29.5552, 34.9511, null),
            arrayOf(50, "Safed", "צפת", 32.9707, 35.4959, null),
            arrayOf(40, "Tiberias", "טבריה", 32.7920, 35.5390, null),
            arrayOf(28, "Beitar Illit", "ביתר עילית", 31.6977, 35.1205, null),
            arrayOf(19, "Modi'in", "מודיעין", 31.8960, 35.0199, null),
            arrayOf(12, "Ashkelon", "אשקלון", 31.6654, 34.5638, null)
        )
        val m = LinkedHashMap<Int, City>()
        for (r in rows) {
            val lid = r[0] as Int
            m[lid] = City(lid, r[1] as String, r[2] as String, r[3] as Double, r[4] as Double, r[5] as Int?)
        }
        return m
    }
}
