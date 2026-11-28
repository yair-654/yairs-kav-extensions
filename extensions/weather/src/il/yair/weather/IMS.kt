package il.yair.weather

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

    /** The twelve kinds, in the default worst-to-best order. ids are what the settings file stores. */
    enum class Kind(val id: String) {
        THUNDERSTORM("thunderstorm"), SNOW("snow"), RAIN("rain"), HEAT("heat"), MUGGY("muggy"), COLD("cold"),
        DUST("dust"), WIND("wind"), FOG("fog"), CLOUDY("cloudy"), PARTLY("partly"), CLEAR("clear");

        companion object {
            fun of(id: String): Kind? = values().firstOrNull { it.id == id }
        }
    }

    val DEFAULT_ORDER: List<String> = Kind.values().map { it.id }

    /** On by default: only the kinds worth knowing about. Cloudy, partly cloudy and clear start off. */
    val DEFAULT_OFF: Set<String> = setOf("cloudy", "partly", "clear")

    const val EXTREMELY_COLD = 1590
    const val FROST = 1300

    /**
     * IMS's weather codes with IMS's own words, (Hebrew, English), from ims.gov.il/he/weather_codes. Used until that
     * list is fetched, and when it can't be.
     */
    val BUILTIN_CODES: Map<Int, Pair<String, String>> = mapOf(
        1250 to Pair("בהיר", "Clear"), 1220 to Pair("מעונן חלקית", "Partly cloudy"), 1230 to Pair("מעונן", "Cloudy"),
        1570 to Pair("אבך", "Dust"), 1010 to Pair("סופות חול", "Sandstorms"), 1160 to Pair("ערפל", "Fog"),
        1310 to Pair("חם", "Hot"), 1580 to Pair("חם מאד", "Extremely hot"), 1270 to Pair("הביל", "Muggy"),
        1320 to Pair("קר", "Cold"), 1590 to Pair("קר מאד", "Extremely cold"), 1300 to Pair("קרה", "Frost"),
        1530 to Pair("מעונן חלקית, יתכן גשם", "Partly cloudy, possible rain"),
        1540 to Pair("מעונן, יתכן גשם", "Cloudy, possible rain"), 1560 to Pair("מעונן עם גשם קל", "Cloudy, light rain"),
        1140 to Pair("גשום", "Rainy"), 1020 to Pair("סופות רעמים", "Thunderstorms"), 1510 to Pair("סוער", "Stormy"),
        1260 to Pair("רוחות חזקות", "Windy"), 1080 to Pair("שלג מעורב בגשם", "Sleet"), 1070 to Pair("שלג קל", "Light snow"),
        1060 to Pair("שלג", "Snow"), 1520 to Pair("שלג כבד", "Heavy snow")
    )

    /**
     * An IMS weather code's kind, from IMS's own list (ims.gov.il/en/weather_codes). A code that isn't on it by the
     * words of its English description, so a new code still lands in the right kind.
     */
    fun kindOf(code: Int, descEn: String): Kind {
        when (code) {
            1020 -> return Kind.THUNDERSTORM
            1060, 1070, 1080, 1520 -> return Kind.SNOW
            1140, 1530, 1540, 1560 -> return Kind.RAIN
            1310, 1580 -> return Kind.HEAT
            1270 -> return Kind.MUGGY
            1320, EXTREMELY_COLD, FROST -> return Kind.COLD
            1570, 1010 -> return Kind.DUST
            1260, 1510 -> return Kind.WIND
            1160 -> return Kind.FOG
            1230 -> return Kind.CLOUDY
            1220 -> return Kind.PARTLY
            1250 -> return Kind.CLEAR
        }
        val d = descEn.lowercase(Locale.US)
        return when {
            "thunder" in d -> Kind.THUNDERSTORM
            "snow" in d || "sleet" in d -> Kind.SNOW
            "rain" in d || "shower" in d || "drizzle" in d -> Kind.RAIN
            "muggy" in d || "humid" in d -> Kind.MUGGY
            "cold" in d || "frost" in d || "freez" in d -> Kind.COLD
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

    /** Within Cold, which is worse: extremely cold (0), then frost (1), then cold (2). Only asked for a Cold code. */
    fun coldRank(code: Int): Int = when (code) {
        EXTREMELY_COLD -> 0
        FROST -> 1
        else -> 2
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

    /**
     * A warning's level, most severe first. IMS writes it as a color at the start of the English text ("Yellow Warning
     * of ..."), which is what riders know; severity_id is read too, as lower = more severe (yellow, the only one seen so
     * far, is 3), and only used when the text has no color word.
     */
    enum class Level { RED, ORANGE, YELLOW }

    /**
     * Warning types never shown: aviation (2), sea swimming (3), fire danger (16, wildfire risk in open country) and
     * mountain obscuration (42). They're not about the weather a rider meets at a stop.
     */
    val HIDDEN_TYPES = setOf(2, 3, 16, 42)

    /**
     * One IMS warning: its land regions, when it holds (seconds), its level and its words in both languages. textEn and
     * textHe are the whole warning, cleaned, without IMS's leading "update:". whatEn and whatHe are its short subject
     * ("strong winds", "אינדקס שריפות גבוה"), or null when the text doesn't say it in the usual way.
     */
    class Warning(val key: String, val level: Level, val levelFromText: Level?, val severityId: Int, val type: Int,
                  val fromSec: Long?, val toSec: Long?, val regions: MutableSet<Int>,
                  val textEn: String, val textHe: String, val whatEn: String?, val whatHe: String?) {
        /** Whether it holds at a time. With no end, it holds until the end of its first day (Israel time). */
        fun holdsAt(atSec: Long): Boolean {
            val from = fromSec ?: 0L
            val to = toSec ?: endOfDay(fromSec ?: atSec)
            return atSec in from..to
        }

        /** The whole warning in Kav's language, else in the other one. */
        fun text(hebrew: Boolean): String = if (hebrew) textHe.ifEmpty { textEn } else textEn.ifEmpty { textHe }

        /** The short subject in Kav's language, or null. */
        fun what(hebrew: Boolean): String? = if (hebrew) whatHe else whatEn

        /** The level IMS's number says, for checking it against the color word. */
        val levelFromId: Level? get() = levelOf(severityId)
    }

    private fun endOfDay(sec: Long): Long {
        val c = java.util.Calendar.getInstance(ISRAEL)
        c.timeInMillis = sec * 1000
        c.set(java.util.Calendar.HOUR_OF_DAY, 23); c.set(java.util.Calendar.MINUTE, 59); c.set(java.util.Calendar.SECOND, 59)
        return c.timeInMillis / 1000
    }

    /** severity_id as a level: 1 red, 2 orange, 3 yellow. Null for any other number. */
    fun levelOf(severityId: Int): Level? = when (severityId) {
        1 -> Level.RED
        2 -> Level.ORANGE
        3 -> Level.YELLOW
        else -> null
    }

    private val COLOR_WORD = Regex("^(red|orange|yellow)\\s+warning\\b", RegexOption.IGNORE_CASE)
    private val WHAT = Regex("\\bwarning\\s+of\\s+(.+?)\\s+in\\s", RegexOption.IGNORE_CASE)
    private val UPDATE = Regex("^(update|עדכון)\\s*:\\s*", RegexOption.IGNORE_CASE)
    // "אזהרה צהובה על אינדקס שריפות גבוה בהרי הגליל, ...": the words after "על" (or "מפני") up to the first place,
    // which starts with ב. Seen in IMS's Hebrew on Nov 28, 2026.
    private val WHAT_HE = Regex("^אזהרה\\s+\\S+\\s+(?:על|מפני)\\s+(.+?)\\s+ב\\S")

    /** The level a warning's English text starts with ("Yellow Warning of ..."), or null. */
    fun levelOfText(textEn: String): Level? = when (COLOR_WORD.find(textEn)?.groupValues?.get(1)?.lowercase(Locale.US)) {
        "red" -> Level.RED
        "orange" -> Level.ORANGE
        "yellow" -> Level.YELLOW
        else -> null
    }

    /** "Yellow Warning of STRONG WINDS in Carmel ..." -> "strong winds"; null when the text isn't worded that way. */
    fun whatOf(textEn: String): String? {
        val first = textEn.substringBefore(". ")
        val w = WHAT.find("$first ")?.groupValues?.get(1)?.trim()?.lowercase(Locale.US) ?: return null
        return w.takeIf { it.length in 3..60 }
    }

    /** "אזהרה צהובה על אינדקס שריפות גבוה בהרי הגליל" -> "אינדקס שריפות גבוה"; null when it isn't worded that way. */
    fun whatOfHe(textHe: String): String? =
        WHAT_HE.find(textHe.substringBefore(". "))?.groupValues?.get(1)?.trim()?.takeIf { it.length in 2..60 }

    private val REGION_KEY = Regex("^r-?(\\d+)$")
    private val REGION_FIELDS = setOf("rid", "region", "region_id", "regions", "region_ids", "rids", "area", "areas", "area_ids")

    /**
     * IMS's warnings feed (ims.gov.il/en/warnings; it carries both languages). With no warnings it answers
     * {"data": {"full_warnings_data": [], "distinct_warnings": []}}. With warnings, full_warnings_data is
     * "yyyy-mm-dd" -> "r-<region>" -> "<wid>" -> {wid, severity_id, warning_type_id, valid_from, valid_to,
     * valid_from_unix, full_en, full_he, text, regions, ...}. It's read loosely, in case that shape moves: any object
     * carrying severity_id, warning_type_id or valid_from/valid_to is a warning, its regions come from region fields
     * inside it or from "r-<id>" keys above it, and the same warning under several regions is merged. Null when the body
     * isn't the feed at all. Hidden types are kept here; whoever shows warnings leaves them out.
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

    /** A field as clean text, without IMS's leading "update:"; "" for none. */
    private fun field(o: JSONObject, key: String): String {
        if (o.isNull(key)) return ""
        val s = clean(o.optString(key))
        return if (s == "null") "" else s.replace(UPDATE, "")
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
        // full_en is the whole English warning; text and text_full are often empty or only its second half.
        val textEn = field(o, "full_en").ifEmpty { field(o, "text_full") }.ifEmpty { field(o, "text") }
        val textHe = field(o, "full_he")
        if (textEn.isEmpty() && textHe.isEmpty()) return null
        val severityId = o.optString("severity_id").toIntOrNull() ?: 0
        val type = o.optString("warning_type_id").toIntOrNull() ?: 0
        val fromText = levelOfText(textEn)
        val level = fromText ?: levelOf(severityId) ?: Level.YELLOW
        val key = listOf("wid", "id", "warning_id", "nid").firstNotNullOfOrNull { o.optString(it).takeIf { s -> s.isNotEmpty() && s != "null" } }
            ?: "$type|$from|$to|${textEn.hashCode()}"
        return Warning(key, level, fromText, severityId, type, from, to, regions, textEn, textHe, whatOf(textEn), whatOfHe(textHe))
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
            arrayOf<Any?>(1, "Jerusalem", "ירושלים", 31.7780, 35.2000, 118),
            arrayOf<Any?>(2, "Tel Aviv coast", "תל-אביב", 32.0821, 34.7802, null),
            arrayOf<Any?>(3, "Haifa", "חיפה", 32.7900, 34.9900, 92),
            arrayOf<Any?>(6, "Ashdod", "אשדוד", 31.8011, 34.6410, null),
            arrayOf<Any?>(8, "Be'er Sheva", "באר שבע", 31.2497, 34.7997, null),
            arrayOf<Any?>(17, "Herzliya", "הרצליה", 32.1643, 34.8423, null),
            arrayOf<Any?>(31, "Eilat", "אילת", 29.5552, 34.9511, null),
            arrayOf<Any?>(50, "Safed", "צפת", 32.9707, 35.4959, null),
            arrayOf<Any?>(40, "Tiberias", "טבריה", 32.7920, 35.5390, null),
            arrayOf<Any?>(28, "Beitar Illit", "ביתר עילית", 31.6977, 35.1205, null),
            arrayOf<Any?>(19, "Modi'in", "מודיעין", 31.8960, 35.0199, null),
            arrayOf<Any?>(12, "Ashkelon", "אשקלון", 31.6654, 34.5638, null)
        )
        val m = LinkedHashMap<Int, City>()
        for (r in rows) {
            val lid = r[0] as Int
            m[lid] = City(lid, r[1] as String, r[2] as String, r[3] as Double, r[4] as Double, r[5] as Int?)
        }
        return m
    }
}
