package il.yair.routeweather

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** IMS (Israel Meteorological Service) data helpers: parsing, day keys, city catalogues. */
object IMS {

    class City(val lid: Int, val name: String, val lat: Double, val lon: Double,
               val rid: Int? = null, val seaRid: Int? = null)

    /**
     * The forecast endpoint answers { "data": { "<yyyy-mm-dd>": { "daily": {...}, "hourly": { "HH": {...} } } } },
     * day keys in Israel time (Asia/Jerusalem). Hourly fields used here: forecast_time
     * ("yyyy-MM-dd HH:mm:ss", Israel time), temperature, weather_code, rain_chance.
     * The daily object adds minimum_temperature and maximum_temperature.
     */
    fun parseForecast(body: String, lid: Int): Forecast? {
        return try {
            val root = org.json.JSONObject(body).getJSONObject("data")
            val days = LinkedHashMap<String, org.json.JSONObject>()
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
    }

    val ISRAEL: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

    /** Whether the phone's timezone currently has the same offset as Israel (so wall times line up). */
    fun israelEpochMatches(epochMs: Long): Boolean =
        TimeZone.getDefault().getOffset(epochMs) == ISRAEL.getOffset(epochMs)

    /** The forecast's day key for an epoch (ms): yyyy-MM-dd in Israel time (IMS day keys are Israel time). */
    fun dayKey(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = ISRAEL
        return sdf.format(java.util.Date(epochMs))
    }

    /** Parses IMS's "yyyy-MM-dd HH:mm:ss" stamps, which are Israel time. Epoch ms, or null. */
    fun parseStamp(stamp: String): Long? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            sdf.timeZone = ISRAEL
            sdf.parse(stamp)?.time
        } catch (e: Exception) {
            null
        }
    }

    /** IMS's wind_direction_id -> compass name, he/en. Ids 1..17; -1 or unknown -> "". */
    fun windDirectionName(id: Int, hebrew: Boolean): String {
        val az = when (id) {
            1 -> 360; 2 -> 23; 3 -> 45; 4 -> 68; 5 -> 90; 6 -> 113; 7 -> 135; 8 -> 158
            9 -> 180; 10 -> 203; 11 -> 225; 12 -> 248; 13 -> 270; 14 -> 293; 15 -> 315; 16 -> 338; 17 -> 0
            else -> return ""
        }
        val en = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW", "N")
        val he = arrayOf("צפ׳", "צ-צ-מז׳", "צ-מז׳", "מז-צ-מז׳", "מז׳", "ד-מ-מז׳", "ד-מז׳", "ד-ד-מז׳",
            "ד׳", "ד-ד-מע׳", "ד-מע׳", "מע-ד-מע׳", "מע׳", "צ-מ-מע׳", "צ-מע׳", "צ-צ-מע׳", "צפ׳")
        val i = when (az) {
            360, 0 -> 0; 23 -> 1; 45 -> 2; 68 -> 3; 90 -> 4; 113 -> 5; 135 -> 6; 158 -> 7
            180 -> 8; 203 -> 9; 225 -> 10; 248 -> 11; 270 -> 12; 293 -> 13; 315 -> 14; 338 -> 15; else -> 0
        }
        return if (hebrew) he[i] else en[i]
    }

    /** Falls back to IMS's locations_info catalogue subset for the biggest places if the fetch fails. */
    fun builtinLocations(hebrew: Boolean): Map<Int, City> {
        val en = arrayOf(
            intArrayOf(1, 317780, 352000), intArrayOf(2, 320821, 347802), intArrayOf(3, 327900, 349900),
            intArrayOf(6, 318011, 346410), intArrayOf(8, 312497, 347997), intArrayOf(17, 321643, 348423),
            intArrayOf(31, 295552, 349511), intArrayOf(50, 329707, 354959), intArrayOf(40, 327920, 355390),
            intArrayOf(28, 316977, 351205), intArrayOf(19, 318960, 350199), intArrayOf(12, 316654, 345638)
        )
        val he = arrayOf(
            "ירושלים", "תל-אביב", "חיפה", "אשדוד", "באר שבע", "הרצליה", "אילת", "צפת", "טבריה",
            "ביתר עילית", "מודיעין", "אשקלון"
        )
        val engNames = arrayOf(
            "Jerusalem", "Tel Aviv coast", "Haifa", "Ashdod", "Be'er Sheva", "Herzliya", "Eilat",
            "Safed", "Tiberias", "Beitar Illit", "Modi'in", "Ashkelon"
        )
        val m = LinkedHashMap<Int, City>()
        for ((i, row) in en.withIndex()) {
            m[row[0]] = City(row[0],
                if (hebrew) he[i] else engNames[i],
                row[1] / 10000.0, row[2] / 10000.0)
        }
        return m
    }
}
