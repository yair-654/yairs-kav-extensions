package il.yair.routeweather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension

/**
 * Route Weather — a line under each search result (slot.results) with the worst weather
 * on that trip in one sentence, and a card on the trip's page (slot.trip) showing the
 * Israel Meteorological Service forecast for the area around each bus stop on the route,
 * at the hour the rider passes it. Tap a tile for a detail sheet with feels-like,
 * humidity, wind and UV.
 */
class RouteWeather : KavExtension {

    private lateinit var kav: Kav
    private var hebrew = false
    private val main = Handler(Looper.getMainLooper())

    private val cities = ConcurrentHashMap<Int, IMS.City>()
    private val forecastCache = LinkedHashMap<Int, Forecast>()   // lid -> forecast, capped and TTL'd
    private val codes = ConcurrentHashMap<Int, String>()         // weather code -> description
    private var codesFetched = false

    // IMS warnings: region id -> active alert (region r-<rid>)
    private val alerts = ConcurrentHashMap<Int, Alert>()
    private var alertsFetchedAt = 0L

    // Settings: which surfaces show weather, which condition kinds are allowed, and
    // the rider's own worst-to-best order (highest first). Kinds may be switched off.
    // Immutable snapshot, swapped atomically: card threads and the settings screen
    // never iterate a collection the other side is changing (that crashed Kav once).
    @Volatile private var kindConfig: KindConfig = KindConfig.default()

    /** The kinds' order and on/off set, replaced wholesale, never edited in place. */
    data class KindConfig(val order: List<String>, val enabled: Set<String>) {
        fun isEnabled(id: String): Boolean = enabled.contains(id)
        fun rankOf(id: String): Int = order.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }
        companion object {
            fun from(order: List<String>, onOff: Map<String, Boolean>): KindConfig {
                val o = LinkedHashSet<String>(order)
                for (k in KIND_ORDER_DEFAULT) o.add(k)          // keep every kind present
                return KindConfig(ArrayList(o), o.filter { onOff[it] ?: true }.toSet())
            }
            fun default(): KindConfig = from(KIND_ORDER_DEFAULT.toList(),
                KIND_ORDER_DEFAULT.associateWith { true })
        }
    }

    companion object {
        private const val IMS_BASE = "https://ims.gov.il/%s/full_forecast_data/%d"
        private const val FORECAST_TTL_MS = 30L * 60 * 1000
        private const val GAP_MIN_SEC = 30L * 60      // min time between kept stops
        private const val HOUR_WINDOW_MS = 2L * 60 * 60 * 1000   // nearest hourly block must be within 2 h
        private const val ALERT_TTL_MS = 10L * 60 * 1000         // warnings feed refresh
        private const val MAX_STATION_KM = 30.0                 // a stop further than this from every station: no forecast
        private const val RAIN_CHANCE_LINE_MIN = 20             // "chance of rain" wording needs at least this
        private const val CACHE_MAX = 40                        // forecasts kept at most (LRU-ish, oldest evicted)

        /** The nine condition kinds, in the extension's own worst-to-best order. */
        val KIND_ORDER_DEFAULT = arrayOf(
            "thunderstorm", "rain", "snow", "fog", "heat", "wind", "cloudy", "partly", "sunny")
    }

    override fun start(kav: Kav) {
        this.kav = kav
        this.hebrew = kav.locale() == "he"
        loadSettings(kav.settings())
        kav.onSettings { all -> loadSettings(all) }
        val ui = kav.ui()

        kav.card("results") { context, about ->
            val trip = JSONObject(about).getJSONObject("trip")
            ResultsLine(ui, context, trip).view()
        }

        kav.card("trip") { context, about ->
            val trip = JSONObject(about).getJSONObject("trip")
            CardPresenter(ui, context, trip).view()
        }

        // The rider's own settings view: reordering the ranking, switching kinds off.
        kav.settingsScreen { context, _ -> SettingsScreen(ui, context).view() }

        kav.log("Route Weather started, API ${kav.apiVersion()}, language ${kav.locale()}")
    }

    // ---------------- Settings ----------------

    /** Reads Kav's declared settings into the volatile view state; also validates. */
    private fun loadSettings(allJson: String?) {
        // The kind list (order + on/off) is this extension's own data file, kept beside the
        // settings Kav keeps, saved by our own settings screen. Missing file: defaults.
        try {
            val f = File(kav.context().filesDir, "kinds_v2.json")
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val orderArr = JSONArray(o.optString("order", "[]"))
                val off = o.optJSONArray("off") ?: JSONArray()
                val order = ArrayList<String>()
                for (i in 0 until orderArr.length()) order.add(orderArr.getString(i))
                val onOff = LinkedHashMap<String, Boolean>()
                for (k in order) onOff[k] = true
                for (i in 0 until off.length()) onOff[off.getString(i)] = false
                kindConfig = KindConfig.from(order, onOff)
            } else kindConfig = KindConfig.default()
        } catch (e: Exception) { kindConfig = KindConfig.default() }
    }

    /** Persists the rider's kind order and switches as one immutable snapshot. */
    fun saveKinds(order: List<String>, onOff: Map<String, Boolean>) {
        kindConfig = KindConfig.from(order, onOff)
        try {
            val o = JSONObject()
            o.put("order", JSONArray(kindConfig.order))
            val off = JSONArray()
            for (k in kindConfig.order) if (!kindConfig.isEnabled(k)) off.put(k)
            o.put("off", off)
            File(kav.context().filesDir, "kinds_v2.json").writeText(o.toString())
        } catch (e: Exception) {
            kav.log("could not save kinds: $e")
        }
    }

    // ---------------- Networking ----------------

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "RouteWeatherKavExt/1.0 (Kav extension)")
            if (conn.responseCode != 200) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { br ->
                val sb = StringBuilder()
                var line: String?
                while (br.readLine().also { line = it } != null) sb.append(line).append('\n')
                sb.toString()
            }
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    private fun fetchForecast(lid: Int): Forecast? {
        synchronized(forecastCache) {
            val cached = forecastCache[lid]
            if (cached != null && System.currentTimeMillis() - cached.fetchedAt < FORECAST_TTL_MS) return cached
        }
        val lang = if (hebrew) "he" else "en"
        val body = httpGet(String.format(Locale.US, IMS_BASE, lang, lid)) ?: return null
        val f = IMS.parseForecast(body, lid) ?: return null
        synchronized(forecastCache) {
            if (forecastCache.size >= CACHE_MAX) {           // evict the oldest entries first
                val excess = forecastCache.size - CACHE_MAX + 1
                val it = forecastCache.entries.iterator()
                var removed = 0
                while (removed < excess && it.hasNext()) { it.next(); it.remove(); removed++ }
            }
            forecastCache[lid] = f
        }
        return f
    }

    private fun weatherDesc(code: Int): String {
        if (code == 0) return ""
        codes[code]?.let { return it }
        if (!codesFetched) {
            val lang = if (hebrew) "he" else "en"
            val body = httpGet("https://ims.gov.il/$lang/weather_codes")
            if (body != null) {
                try {
                    val data = JSONObject(body).getJSONObject("data")
                    val it = data.keys()
                    while (it.hasNext()) {
                        val o = data.getJSONObject(it.next())
                        val desc = (if (hebrew) o.optString("desc") else o.optString("desc_en")).trim()
                        val id = o.optInt("weather_code")
                        if (id != 0 && desc.isNotEmpty()) codes[id] = desc
                    }
                    codesFetched = true
                } catch (e: Exception) {}
            }
        }
        return codes[code] ?: ""
    }

    // ---------------- Cities ----------------

    private fun ensureCities() {
        if (cities.isNotEmpty()) return
        val body = httpGet("https://ims.gov.il/" + (if (hebrew) "he" else "en") + "/locations_info") ?: return
        try {
            val data = JSONObject(body).getJSONObject("data")
            val it = data.keys()
            while (it.hasNext()) {
                val o = data.getJSONObject(it.next())
                val lid = o.optInt("lid")
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                val name = o.optString("name")
                if (lid != 0 && !lat.isNaN() && !lon.isNaN() && name.isNotEmpty()) {
                    val seaRidStr = o.optString("sea_rid")
                    cities[lid] = IMS.City(lid, name, lat, lon,
                        o.optString("rid").toIntOrNull(),
                        if (seaRidStr.isEmpty() || seaRidStr == "null") null else seaRidStr.toIntOrNull())
                }
            }
        } catch (e: Exception) {}
        if (cities.isEmpty()) cities.putAll(IMS.builtinLocations(hebrew))
    }

    /**
     * The nearest IMS station to a point by great-circle (Haversine) distance, or null
     * when every station is further than MAX_STATION_KM. There is no fallback city: a
     * point with no nearby station simply has no forecast, rather than being assigned
     * the weather of an unrelated place.
     */
    private fun nearestCity(lat: Double, lon: Double): IMS.City? {
        ensureCities()
        var best: IMS.City? = null
        var bestKm = Double.MAX_VALUE
        val la = Math.toRadians(lat)
        for ((_, c) in cities) {
            val ca = Math.toRadians(c.lat)
            val dLa = ca - la
            val dLo = Math.toRadians(c.lon - lon)
            val h = Math.sin(dLa / 2) * Math.sin(dLa / 2) +
                    Math.cos(la) * Math.cos(ca) * Math.sin(dLo / 2) * Math.sin(dLo / 2)
            val km = 2 * 6371.0 * Math.asin(Math.sqrt(h))
            if (km < bestKm) { bestKm = km; best = c }
        }
        return if (bestKm <= MAX_STATION_KM) best else null
    }

    // ---------------- Route stops ----------------

    fun pickRouteStops(trip: JSONObject): List<RouteStop> {
        val legs = trip.optJSONArray("legs") ?: return emptyList()
        val stops = ArrayList<Triple<IMS.City?, String, Long>>()   // station (null: none nearby), stop name, time
        for (i in 0 until legs.length()) {
            val leg = legs.optJSONObject(i) ?: continue
            if (leg.optString("kind") != "ride" && leg.optString("kind") != "walk") continue
            val dep = leg.optLong("dep", 0)
            val arr = leg.optLong("arr", 0)
            for (end in listOf("from", "to")) {
                val p = leg.optJSONObject(end) ?: continue
                val lat = p.optDouble("lat", 0.0)
                val lon = p.optDouble("lon", 0.0)
                val t = if (end == "from") dep else arr
                val nm = p.optString("name")
                if (t <= 0) continue
                // A missing name is not a refusal: name it by what it is, and fall
                // back to the route's first named stop when nothing else is known.
                val name = nm.ifEmpty { (if (end == "from")
                    (if (hebrew) "נקודת המוצא" else "origin point")
                else (if (hebrew) "נקודת היעד" else "destination point")) }
                if (lat == 0.0 && lon == 0.0) continue
                stops.add(Triple(nearestCity(lat, lon), name, t))
            }
        }
        // The origin/destination fallback text needs a real place when coordinates exist:
        // use the route's first named stop's station as the label for unnamed ends.
        val fallbackCity = stops.firstOrNull { it.first != null }?.first
        val out0 = ArrayList<Triple<IMS.City?, String, Long>>()
        for ((city, name, t) in stops) {
            var nm2 = name
            if (name.contains("point") || name.contains("מוצא") || name.contains("יעד")) {
                val real = fallbackCity?.name
                if (real != null) nm2 = real
            }
            out0.add(Triple(city, nm2, t))
        }
        val sorted = out0.sortedBy { it.third }
        // Collapse: keep the first stop, then a next one only when at least 30 minutes passed.
        val out = ArrayList<RouteStop>()
        var lastT = Long.MIN_VALUE
        for ((city, name, t) in sorted) {
            if (out.isEmpty() || t - lastT >= GAP_MIN_SEC) {
                out.add(RouteStop(city, name, t))
                lastT = t
            }
        }
        return out
    }

    // ---------------- Weather for a stop ----------------

    private fun weatherFor(stop: RouteStop): Weather {
        val alert = stop.city?.let { alertFor(it, stop.time) }
        if (stop.city == null)
            return Weather(-1, stop.name, Weather.Status.NO_STATION, stopName = stop.name, atSec = stop.time, alert = alert)
        val f = fetchForecast(stop.city.lid)
            ?: return Weather(stop.city.lid, stop.city.name, Weather.Status.NETWORK, stopName = stop.name, atSec = stop.time, alert = alert)
        val inIsrael = IMS.israelEpochMatches(stop.time)
        val key = IMS.dayKey(stop.time * 1000)
        val dayData = f.days[key]
            ?: return Weather(stop.city.lid, stop.city.name, Weather.Status.EMPTY, stopName = stop.name, atSec = stop.time, alert = alert)
        val hourly = dayData.optJSONObject("hourly")

        // The hour block closest to the time the rider is actually there.
        var bestHour: JSONObject? = null
        var bestGap = Long.MAX_VALUE
        if (hourly != null) {
            val it = hourly.keys()
            while (it.hasNext()) {
                val h = hourly.optJSONObject(it.next()) ?: continue
                val ft = IMS.parseStamp(h.optString("forecast_time")) ?: continue
                val gap = Math.abs(ft - stop.time * 1000)
                if (gap < bestGap) { bestGap = gap; bestHour = h }
            }
        }
        if (bestHour == null)
            return Weather(stop.city.lid, stop.city.name, Weather.Status.EMPTY, stopName = stop.name, atSec = stop.time, alert = alert)
        val h = bestHour!!
        return if (bestGap <= HOUR_WINDOW_MS) hourlyWeather(stop, h, inIsrael, alert)
        else dailyFallback(stop, dayData.optJSONObject("daily"), inIsrael, alert)
    }

    private fun hourlyWeather(stop: RouteStop, h: JSONObject, inIsrael: Boolean, alert: Alert?): Weather {
        val code = h.optInt("weather_code")
        val city = stop.city!!
        return Weather(
            lid = city.lid, cityName = city.name, status = Weather.Status.OK,
            stopName = stop.name,
            temp = h.optString("temperature"), desc = weatherDesc(code),
            alert = alert,
            rainChance = h.optInt("rain_chance", -1).let { if (it < 0) null else it },
            atSec = stop.time, atText = hourLabel(h.optString("hour"), inIsrael),
            detail = h, israelTime = inIsrael,
            feelsLike = h.optString("heat_stress", "").ifEmpty { h.optString("wind_chill", "") },
            humidity = h.optString("relative_humidity"),
            windSpeed = h.optString("wind_speed"), windDirId = h.optInt("wind_direction_id", -1),
            gustSpeed = h.optString("gust_speed"), uvIndex = h.optString("u_v_index"),
            code = code
        )
    }

    private fun dailyFallback(stop: RouteStop, daily: JSONObject?, inIsrael: Boolean, alert: Alert?): Weather {
        val city = stop.city!!
        if (daily == null) return Weather(city.lid, city.name, Weather.Status.EMPTY, stopName = stop.name, atSec = stop.time, alert = alert)
        val code = daily.optInt("weather_code")
        return Weather(
            lid = city.lid, cityName = city.name, status = Weather.Status.OK,
            stopName = stop.name,
            temp = daily.optString("minimum_temperature", "") + "–" + daily.optString("maximum_temperature", ""),
            desc = weatherDesc(code), rainChance = null,
            atSec = stop.time, atText = null, summary = true, israelTime = inIsrael,
            code = code, uvMax = daily.optString("maximum_uvi"), alert = alert
        )
    }

    /** The hour the rider passes the place, formatted by the phone's clock setting (12/24 h). */
    private fun hourLabel(hourField: String, israel: Boolean): String {
        val c = kav.context()
        return try {
            val v = hourField.toInt()
            if (DateFormat.is24HourFormat(c)) String.format(Locale.US, "%02d:00", v)
            else {
                val ampm = if (v < 12) "AM" else "PM"
                var h = v % 12; if (h == 0) h = 12
                "$h:00 $ampm"
            }
        } catch (e: Exception) { "" }
    }

    /** The day of a stop, in the rider's language ("Today", "Tomorrow") over Israel-time day keys. */
    private fun dayLabel(stopSec: Long): String? {
        val todayKey = IMS.dayKey(System.currentTimeMillis())
        val stopDay = IMS.dayKey(stopSec * 1000)
        val tomorrowKey = IMS.dayKey(System.currentTimeMillis() + 86_400_000L)
        return when {
            stopDay == todayKey -> if (hebrew) "היום" else "Today"
            stopDay == tomorrowKey -> if (hebrew) "מחר" else "Tomorrow"
            else -> null
        }
    }

    // ---------------- IMS weather alerts ----------------

    /** Fetches IMS's public warnings feed (ims.gov.il/<lang>/warnings) every 10 minutes. */
    private fun ensureAlerts() {
        if (System.currentTimeMillis() - alertsFetchedAt < ALERT_TTL_MS) return
        val lang = if (hebrew) "he" else "en"
        val body = httpGet("https://ims.gov.il/$lang/warnings") ?: return
        try {
            val full = JSONObject(body).getJSONObject("data").getJSONObject("full_warnings_data")
            val nowSec = System.currentTimeMillis() / 1000
            val fresh = ConcurrentHashMap<Int, Alert>()
            val it = full.keys()
            while (it.hasNext()) {
                val regionKey = it.next()                       // "r-54", "r-55", ...
                if (!regionKey.startsWith("r-")) continue
                val regionId = regionKey.substring(2).toIntOrNull() ?: continue
                val days = full.optJSONObject(regionKey) ?: continue
                val dit = days.keys()
                while (dit.hasNext()) {
                    val warns = days.optJSONObject(dit.next()) ?: continue
                    val wit = warns.keys()
                    while (wit.hasNext()) {
                        val w = warns.optJSONObject(wit.next()) ?: continue
                        val to = (w.optLong("valid_from_unix", 0) + 0L)   // keep epoch start
                        val from = w.optLong("valid_from_unix", 0)
                        val toSecAlt = IMS.parseStamp(w.optString("valid_to"))?.div(1000) ?: Long.MAX_VALUE
                        if (from > 0 && nowSec > toSecAlt) continue      // expired
                        fresh[regionId] = Alert(
                            regionId = regionId,
                            severity = w.optInt("severity_id", 3),
                            fromSec = from,
                            toSec = toSecAlt,
                            titleHe = trimAlertText(w.optString("full_he")),
                            titleEn = trimAlertText(w.optString("full_en")),
                            bodyHe = w.optString("full_he"),
                            bodyEn = w.optString("full_en")
                        )
                    }
                }
            }
            alerts.clear()
            alerts.putAll(fresh)
            alertsFetchedAt = System.currentTimeMillis()
        } catch (e: Exception) {}
    }

    /** IMS alert texts come with padding/newlines; trims to a clean single-line snippet. */
    private fun trimAlertText(s: String): String =
        s.trim().replace("\\s+".toRegex(), " ")

    /** The strongest active alert for a city, by its land and sea warning regions. Null when none. */
    private fun alertFor(city: IMS.City, atSec: Long): Alert? {
        ensureAlerts()
        var best: Alert? = null
        for (rid in listOfNotNull(city.rid, city.seaRid)) {
            val a = alerts[rid] ?: continue
            if (atSec in a.fromSec until a.toSec || a.toSec == Long.MAX_VALUE) {
                if (best == null || a.severity < best!!.severity) best = a
            }
        }
        return best
    }

    /** IMS severity: 1 red, 2 orange, 3 yellow — else gray. */
    private fun alertColor(sev: Int): Int = when (sev) {
        1 -> 0xFFE53935.toInt()
        2 -> 0xFFFB8C00.toInt()
        3 -> 0xFFFDD835.toInt()
        else -> 0xFF9E9E9E.toInt()
    }

    // ---------------- Trip verdict (worst leg, one sentence) ----------------

    /** One leg's condition in one phrase ("Rain"), used in lists and spoken labels. */
    private fun conditionSentence(w: Weather): String {
        return when (conditionKind(w)) {
            ConditionKind.THUNDERSTORM -> if (hebrew) "סופת רעמים" else "A thunderstorm"
            ConditionKind.RAIN -> if (hebrew) "גשם" else "Rain"
            ConditionKind.SNOW -> if (hebrew) "שלג" else "Snow"
            ConditionKind.FOG -> if (hebrew) "ערפל" else "Fog"
            ConditionKind.HEAT -> if (hebrew) "חם" else "Heat"
            ConditionKind.WIND -> if (hebrew) "רוח חזקה" else "Strong wind"
            ConditionKind.CLOUDY -> if (hebrew) "מעונן" else "Cloudy"
            ConditionKind.PARTLY -> if (hebrew) "מעונן חלקית" else "Partly cloudy"
            ConditionKind.SUNNY -> if (hebrew) "שמש" else "Sunny"
        }
    }

    /**
     * The trip's one-line verdict: the weather of the worst leg, ranked by the rider's
     * own order (his settings screen), counting only the kinds he left switched on.
     * A sentence pattern shared by all: "There will be a thunderstorm during this trip."
     * When every kind is switched off, or no leg has a forecast, there is no line at
     * all — never an empty value.
     */
    private fun tripVerdict(items: List<Weather>): String {
        val oks = items.filter { it.status == Weather.Status.OK }
        if (oks.isEmpty()) return ""
        val cfg = kindConfig        // one consistent snapshot for the whole computation
        val order = if (cfg.order.isNotEmpty()) cfg.order else KIND_ORDER_DEFAULT.toList()
        // Worst = the lowest position in the rider's order among legs whose kind is on.
        val ranked = oks.filter { cfg.isEnabled(kindId(conditionKind(it))) }
            .map { Pair(it, order.indexOf(kindId(conditionKind(it)))) }
            .filter { it.second >= 0 }   // a kind missing from the order: not a candidate
        if (ranked.isEmpty()) return ""  // every kind switched off: no line, never a crash
        val worst = ranked.minBy { it.second }.first
        val line = when (conditionKind(worst)) {
            ConditionKind.THUNDERSTORM -> if (hebrew) "צפויה סופת רעמים בנסיעה זו" else "There will be a thunderstorm during this trip"
            ConditionKind.RAIN -> if (hebrew) "יהיו גשמים בנסיעה זו" else "It will be rainy during this trip"
            ConditionKind.SNOW -> if (hebrew) "יהיה שלג בנסיעה זו" else "It will be snowy during this trip"
            ConditionKind.FOG -> if (hebrew) "יהיה ערפל בנסיעה זו" else "It will be foggy during this trip"
            ConditionKind.HEAT -> if (hebrew) "יהיה חם בנסיעה זו" else "It will be hot during this trip"
            ConditionKind.WIND -> if (hebrew) "יהיו רוחות חזקות בנסיעה זו" else "It will be windy during this trip"
            ConditionKind.CLOUDY -> if (hebrew) "יהיה מעונן בנסיעה זו" else "It will be cloudy during this trip"
            ConditionKind.PARTLY -> if (hebrew) "יהיה בהיר חלקית בנסיעה זו" else "It will be partly cloudy during this trip"
            ConditionKind.SUNNY -> if (hebrew) "יהיה שמש בנסיעה זו" else "It will be sunny during this trip"
        }
        // When rain is the worst thing on the trip and IMS gives a probability for it,
        // say the percentage in the same sentence pattern.
        val rc = worst.rainChance?.takeIf { it >= RAIN_CHANCE_LINE_MIN }
        return if (conditionKind(worst) == ConditionKind.RAIN && rc != null) {
            if (hebrew) "יש $rc% סיכוי לגשם בנסיעה זו" else "There is a $rc% chance of rain during this trip"
        } else line
    }

    private fun kindId(kind: ConditionKind): String = when (kind) {
        ConditionKind.THUNDERSTORM -> "thunderstorm"
        ConditionKind.RAIN -> "rain"
        ConditionKind.SNOW -> "snow"
        ConditionKind.FOG -> "fog"
        ConditionKind.HEAT -> "heat"
        ConditionKind.WIND -> "wind"
        ConditionKind.CLOUDY -> "cloudy"
        ConditionKind.PARTLY -> "partly"
        ConditionKind.SUNNY -> "sunny"
    }

    private enum class ConditionKind { THUNDERSTORM, RAIN, SNOW, FOG, HEAT, WIND, CLOUDY, PARTLY, SUNNY }

    private fun conditionKind(w: Weather): ConditionKind = when (w.code) {
        1020 -> ConditionKind.THUNDERSTORM
        1530, 1540, 1560, 1140 -> ConditionKind.RAIN     // possible/light/rainy — same codes the rain icon uses
        1080, 1070, 1060, 1520 -> ConditionKind.SNOW
        1160 -> ConditionKind.FOG
        1310, 1580 -> ConditionKind.HEAT
        1260, 1510 -> ConditionKind.WIND
        1230 -> ConditionKind.CLOUDY
        1220 -> ConditionKind.PARTLY
        1250 -> ConditionKind.SUNNY
        else -> ConditionKind.CLOUDY                            // dust etc: treat as cloudy
    }

    private fun rankOf(kind: ConditionKind): Int = when (kind) {
        ConditionKind.THUNDERSTORM -> 0
        ConditionKind.RAIN -> 1
        ConditionKind.SNOW -> 1
        ConditionKind.FOG -> 2
        ConditionKind.HEAT -> 3
        ConditionKind.WIND -> 4
        ConditionKind.CLOUDY -> 5
        ConditionKind.PARTLY -> 6
        ConditionKind.SUNNY -> 7
    }

    private fun conditionRank(w: Weather): Int = rankOf(conditionKind(w))

    /** Maps an IMS weather code to one of a small drawn set: sun, cloud-sun, cloud, rain, storm, snow, fog, heat, wind. */
    fun iconFor(code: Int): String {
        return when (code) {
            1250 -> "sun"                                   // clear
            1220 -> "cloud_sun"                             // partly cloudy
            1230 -> "cloud"                                 // cloudy
            1530, 1540, 1560, 1140 -> "rain"                // possible/light/rainy
            1020 -> "storm"                                 // thunderstorms
            1080, 1070, 1060, 1520 -> "snow"                // sleet/light/heavy snow
            1160 -> "fog"                                   // fog
            1310, 1580 -> "heat"                            // hot / extremely hot
            1260, 1510 -> "wind"                            // windy / stormy
            else -> "cloud"                                 // dust etc: a neutral cloud
        }
    }

    private val iconCache = ConcurrentHashMap<String, Bitmap>()

    /** Draws one of the condition icons with Kav's theme colors: blue sky accent, gray clouds. */
    private fun icon(name: String, sizePx: Int): Bitmap {
        iconCache["$name-$sizePx"]?.let { return it }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val d = sizePx / 24f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val sunCol = 0xFFFDB813.toInt()      // warm amber sun
        val cloudCol = 0xFF78909C.toInt()     // slate cloud
        val rainCol = 0xFF4FC3F7.toInt()      // light-blue drops
        val accent = kav.ui().let { 0xFF6200EA.toInt() }  // unused placeholder, kept for tuning

        when (name) {
            "sun" -> {
                p.color = sunCol; cv.drawCircle(12f*d, 12f*d, 5f*d, p)
                p.strokeWidth = 1.6f*d; p.style = Paint.Style.STROKE
                for (i in 0..7) {
                    val a = Math.toRadians(i * 45.0)
                    cv.drawLine(
                        (12 + Math.cos(a)*7.5).toFloat()*d, (12 + Math.sin(a)*7.5).toFloat()*d,
                        (12 + Math.cos(a)*9.8).toFloat()*d, (12 + Math.sin(a)*9.8).toFloat()*d, p)
                }
            }
            "cloud_sun" -> {
                p.color = sunCol; cv.drawCircle(16f*d, 8.5f*d, 3.4f*d, p)
                p.color = cloudCol; cv.drawOval(android.graphics.RectF(4f*d, 11f*d, 16f*d, 19f*d), p)
                p.style = Paint.Style.FILL; cv.drawCircle(9f*d, 10f*d, 4f*d, p); cv.drawCircle(13.5f*d, 10f*d, 3.5f*d, p)
                cv.drawOval(android.graphics.RectF(4f*d, 12f*d, 18.5f*d, 19f*d), p)
            }
            "cloud" -> {
                p.color = cloudCol; cv.drawCircle(9f*d, 12f*d, 4.5f*d, p); cv.drawCircle(14.5f*d, 12f*d, 3.8f*d, p)
                cv.drawOval(android.graphics.RectF(4f*d, 13f*d, 20f*d, 19.5f*d), p)
            }
            "rain" -> {
                p.color = cloudCol; cv.drawCircle(9f*d, 10f*d, 4.5f*d, p); cv.drawCircle(14.5f*d, 10f*d, 3.8f*d, p)
                cv.drawOval(android.graphics.RectF(4f*d, 11f*d, 20f*d, 17f*d), p)
                p.color = rainCol; p.strokeWidth = 1.8f*d; p.style = Paint.Style.STROKE
                for (x in listOf(7f, 11f, 15f)) {
                    cv.drawLine(x*d, 18f*d, x*d - 1.5f*d, 22.5f*d, p)
                }
            }
            "storm" -> {
                p.color = cloudCol; cv.drawCircle(9f*d, 9.5f*d, 4.5f*d, p); cv.drawCircle(14.5f*d, 9.5f*d, 3.8f*d, p)
                cv.drawOval(android.graphics.RectF(4f*d, 10.5f*d, 20f*d, 16f*d), p)
                p.color = 0xFFFFC107.toInt(); p.style = Paint.Style.FILL
                val bolt = android.graphics.Path()
                bolt.moveTo(11f*d, 15f*d); bolt.lineTo(8.5f*d, 20f*d); bolt.lineTo(11.5f*d, 19f*d); bolt.lineTo(10f*d, 23.5f*d)
                bolt.lineTo(15f*d, 17.5f*d); bolt.lineTo(12f*d, 18.5f*d); bolt.lineTo(14f*d, 15f*d)
                bolt.close(); cv.drawPath(bolt, p)
            }
            "snow" -> {
                p.color = cloudCol; cv.drawCircle(9f*d, 10f*d, 4.5f*d, p); cv.drawCircle(14.5f*d, 10f*d, 3.8f*d, p)
                cv.drawOval(android.graphics.RectF(4f*d, 11f*d, 20f*d, 17f*d), p)
                p.color = 0xFF4FC3F7.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f*d
                for ((x, y) in listOf(7f to 19.5f, 12f to 21f, 17f to 19.5f)) {
                    cv.drawLine((x-1.6f)*d, y*d, (x+1.6f)*d, y*d, p)
                    cv.drawLine(x*d, (y-1.6f)*d, x*d, (y+1.6f)*d, p)
                }
            }
            "fog" -> {
                // a low cloud with three horizontal mist lines under it
                p.color = cloudCol; p.style = Paint.Style.FILL
                cv.drawCircle(9f*d, 8.5f*d, 4.2f*d, p); cv.drawCircle(14.5f*d, 8.5f*d, 3.6f*d, p)
                cv.drawOval(android.graphics.RectF(4f*d, 9.5f*d, 20f*d, 15f*d), p)
                p.color = 0xFFB0BEC5.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f*d
                for (y in listOf(17.5f, 19.5f, 21.5f)) {
                    cv.drawLine(6f*d, y*d, 18f*d, y*d, p)
                }
            }
            "heat" -> {
                p.color = sunCol; cv.drawCircle(9f*d, 12f*d, 4f*d, p)
                p.color = 0xFFD84315.toInt(); cv.drawCircle(15.5f*d, 12f*d, 4f*d, p)
                p.color = 0xFFFF5722.toInt(); p.strokeWidth = 2f*d; p.style = Paint.Style.STROKE
                cv.drawRect(android.graphics.RectF(3f*d, 16f*d, 21f*d, 21f*d), p)
            }
            "wind" -> {
                p.color = 0xFF90A4AE.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 2f*d
                cv.drawPath(android.graphics.Path().apply {
                    moveTo(3f*d, 9f*d); quadTo(10f*d, 3f*d, 16f*d, 9f*d)
                }, p)
                cv.drawPath(android.graphics.Path().apply {
                    moveTo(5f*d, 15f*d); quadTo(12f*d, 9f*d, 18f*d, 15f*d)
                }, p)
                cv.drawPath(android.graphics.Path().apply {
                    moveTo(7f*d, 21f*d); quadTo(14f*d, 15f*d, 20f*d, 21f*d)
                }, p)
            }
        }
        iconCache["$name-$sizePx"] = bmp
        return bmp
    }

    // ---------------- Shared view bits ----------------

    private fun dp(v: Float): Int = (v * kav.context().resources.displayMetrics.density).toInt()

    private fun tileBackground(c: Context): GradientDrawable = GradientDrawable().apply {
        cornerRadius = kav.ui().dp("rTile") * c.resources.displayMetrics.density
        setColor(kav.ui().color("fill"))
    }

    // ---------------- The trip card ----------------

    inner class CardPresenter(private val ui: kav.kavapp.api.KavUi, private val c: Context, private val trip: JSONObject) {

        fun view(): View {
            val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(c).apply {
                text = if (hebrew) "מזג האוויר לאורך המסלול" else "Weather along the route"
                setTextColor(ui.color("text")); textSize = ui.textSize("headline"); typeface = Typeface.DEFAULT_BOLD
            })

            val loadingBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            box.addView(loadingBox)
            loadingBox.addView(TextView(c).apply {
                text = if (hebrew) "טוען תחזית מהמטאורולוגיה…" else "Loading forecast from the IMS…"
                setTextColor(ui.color("textSecondary")); textSize = ui.textSize("body")
                setPadding(0, dp(ui.dp("gap2")), 0, 0)
            })
            Thread {
                try {
                    val items = pickRouteStops(trip).mapNotNull { weatherFor(it) }
                    main.post {
                        try { loadingBox.removeAllViews(); fill(loadingBox, items) }
                        catch (e: Exception) { kav.log("trip card view: $e") }
                    }
                } catch (e: Exception) {
                    kav.log("trip card: $e")
                }
            }.start()
            return ui.card(c, box)
        }

        private fun fill(box: LinearLayout, items: List<Weather>) {
            if (items.isEmpty()) {
                box.addView(TextView(c).apply {
                    text = if (hebrew) "לא נמצאו תחנות מזג אוויר במסלול זה" else "No weather stations found along this route"
                    setTextColor(ui.color("textSecondary")); textSize = ui.textSize("body")
                    setPadding(0, dp(ui.dp("gap2")), 0, 0)
                })
                return
            }
            box.addView(TextView(c).apply {
                text = if (hebrew) "לפי השירות המטאורולוגי הישראלי, בשעה שבה תעבור בכל מקום"
                       else "From the Israel Meteorological Service, at the hour you pass each place"
                setTextColor(ui.color("textSecondary")); textSize = ui.textSize("caption")
                setPadding(0, dp(ui.dp("gap2")), 0, dp(ui.dp("gap2")))
            })
            val scroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            for (w in items) {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dp(ui.dp("gap3"))
                row.addView(cityCard(w), lp)
            }
            scroll.addView(row)
            box.addView(scroll, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        /** One city tile on the trip card. */
        private fun cityCard(w: Weather): View {
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13f), dp(11f), dp(13f), dp(11f))
                background = tileBackground(c)
            }

            card.addView(TextView(c).apply {
                text = w.cityName
                setTextColor(ui.color("text")); textSize = ui.textSize("subhead"); typeface = Typeface.DEFAULT_BOLD
            })

            if (w.status != Weather.Status.OK) {
                card.addView(TextView(c).apply {
                    text = when (w.status) {
                        Weather.Status.NO_STATION -> if (hebrew) "אין תחנת חיזוי בקרבת תחנת האוטובוס" else "No nearby IMS forecast station available"
                        Weather.Status.NETWORK -> if (hebrew) "לא ניתן לאחזר את התחזית (תקשורת)" else "Could not fetch the forecast (no connection)"
                        else -> if (hebrew) "לא ניתן לאחזר תחזית למועד זה" else "Could not fetch a forecast for this time"
                    }
                    setTextColor(ui.color("problem")); textSize = ui.textSize("caption")
                })
                if (w.alert != null) {
                    // Even a tile with no forecast carries the alert sign, colored by severity.
                    card.addView(TextView(c).apply {
                        text = (if (hebrew) "התראה: " else "Alert: ") + (if (hebrew) w.alert.titleHe else w.alert.titleEn).take(60)
                        setTextColor(alertColor(w.alert.severity)); textSize = ui.textSize("caption")
                    })
                }
                return card
            }

            val iconTemp = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            iconTemp.addView(ImageView(c).apply {
                setImageBitmap(icon(iconFor(w.code), dp(22f)))
                // Spoken label for the icon: never leave a picture unlabeled for screen readers.
                contentDescription = conditionSentence(w)
                setPadding(0, 0, dp(5f), 0)
            })
            iconTemp.addView(TextView(c).apply {
                text = w.temp + "°"
                setTextColor(ui.color("text")); textSize = ui.textSize("title"); typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(iconTemp)

            card.addView(TextView(c).apply {
                // Empty description replaced by the spoken condition, never a blank value.
                text = w.desc.ifEmpty { conditionSentence(w) }
                setTextColor(ui.color("textSecondary")); textSize = ui.textSize("caption")
            })

            if (w.rainChance != null && w.rainChance!! > 0) {
                card.addView(TextView(c).apply {
                    text = (if (hebrew) "סיכוי לגשם " else "Rain ") + w.rainChance + "%"
                    setTextColor(ui.color("realtime")); textSize = ui.textSize("caption")
                })
            }

            // Tap a tile for the full detail sheet (feels-like, humidity, wind, UV).
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener { showDetail(w, c) }
            dayLabel(w.atSec)?.let { day ->
                card.addView(TextView(c).apply {
                    text = day
                    setTextColor(ui.color("textTertiary")); textSize = ui.textSize("caption")
                })
            }
            w.atText?.let { lbl ->
                card.addView(TextView(c).apply {
                    text = if (hebrew) "בשעה $lbl" else "at $lbl"
                    setTextColor(ui.color("textTertiary")); textSize = ui.textSize("caption")
                })
            }
            if (w.summary) {
                card.addView(TextView(c).apply {
                    text = if (hebrew) "תחזית יומית ליום זה" else "Day forecast for that day"
                    setTextColor(ui.color("textTertiary")); textSize = ui.textSize("caption")
                })
            }
            // An active IMS alert for this stop: wrap in a frame with a severity line on the
            // leading edge and a red "!" badge on the far corner (top-left in Hebrew, top-right in English).
            if (w.alert != null) return wrapWithAlertFrame(card, w.alert)
            return card
        }

        /** The red "!" badge and severity line used on trip-card tiles with an active
         * alert. The badge says its meaning aloud too, so a screen reader never hears
         * just "!".
         */
        private fun wrapWithAlertFrame(card: View, a: Alert): View {
            val frame = FrameLayout(c)
            frame.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(3f)
            })
            val sevLine = View(c).apply { setBackgroundColor(alertColor(a.severity)) }
            frame.addView(sevLine, FrameLayout.LayoutParams(dp(3f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = if (hebrew) Gravity.END else Gravity.START
            })
            val badge = TextView(c).apply {
                text = "!"
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                textSize = ui.textSize("caption")
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFE53935.toInt())
                }
                contentDescription = (if (hebrew) "התראת מזג אוויר פעילה" else "Active weather alert")
            }
            val bs = dp(18f)
            frame.addView(badge, FrameLayout.LayoutParams(bs, bs).apply {
                gravity = if (hebrew) (Gravity.TOP or Gravity.START) else (Gravity.TOP or Gravity.END)
                marginStart = dp(2f); marginEnd = dp(2f); topMargin = dp(2f)
            })
            return frame
        }
    }

    // ---------------- Detail sheet on tap ----------------

    /** Kav's own sheet over the trip's screen: the hour's full detail for one city. */
    private fun showDetail(w: Weather, c: Context) {
        val ui = kav.ui()
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(c).apply {
            text = w.cityName + (w.atText?.let { " · " + it } ?: "")
            setTextColor(ui.color("text")); textSize = ui.textSize("headline"); typeface = Typeface.DEFAULT_BOLD
        })
        if (w.stopName.isNotEmpty() && w.stopName != w.cityName) {
            box.addView(TextView(c).apply {
                text = (if (hebrew) "בתחנה: " else "At stop: ") + w.stopName
                setTextColor(ui.color("textSecondary")); textSize = ui.textSize("caption")
            })
        }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(c).apply {
            setImageBitmap(icon(iconFor(w.code), dp(34f)))
            contentDescription = if (hebrew) "סמל מזג האוויר" else "Weather icon"
            setPadding(0,0,dp(8f),0)
        })
        head.addView(TextView(c).apply {
            text = if (w.summary) w.temp + "° (day)" else w.temp + "°"
            setTextColor(ui.color("text")); textSize = ui.textSize("largeTitle"); typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(head)
        box.addView(TextView(c).apply {
            // Empty description replaced with a reason, never a blank line.
            text = w.desc.ifEmpty { (if (hebrew) "אין תיאור לשעה זו" else "No description for this hour") }
            setTextColor(ui.color("textSecondary")); textSize = ui.textSize("subhead")
            setPadding(0, 0, 0, dp(ui.dp("gap3")))
        })
        // The city's active IMS alert: first two lines above the details; tapping shows all of it.
        if (w.alert != null) {
            val alertText = (if (hebrew) w.alert.bodyHe else w.alert.bodyEn).trim().replace("\\s+".toRegex(), " ")
            val preview = TextView(c).apply {
                text = alertText
                setTextColor(alertColor(w.alert.severity))
                textSize = ui.textSize("body")
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 0, 0, dp(ui.dp("gap1")))
            }
            if (hebrew) {
                preview.textAlignment = TextView.TEXT_ALIGNMENT_VIEW_START
                preview.layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            preview.setOnClickListener { preview.maxLines = if (preview.maxLines == 2) Integer.MAX_VALUE else 2 }
            box.addView(preview)
            val hint = TextView(c).apply {
                text = if (hebrew) "הקישו להצגת כל ההתראה" else "Tap to see the full alert"
                setTextColor(ui.color("textTertiary")); textSize = ui.textSize("caption")
                setPadding(0, 0, 0, dp(ui.dp("gap2")))
            }
            if (hebrew) {
                hint.textAlignment = TextView.TEXT_ALIGNMENT_VIEW_START
                hint.layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            box.addView(hint)
        }

        if (w.detail == null) {
            box.addView(TextView(c).apply {
                text = if (hebrew) "אין פירוט שעתי לשעה זו; מוצגת התחזית היומית." else "No hourly detail for this hour; showing the day's forecast."
                setTextColor(ui.color("textTertiary")); textSize = ui.textSize("caption")
            })
        } else {
            for (r in detailRows(w)) box.addView(r)
        }
        box.setPadding(dp(4f), dp(4f), dp(4f), dp(ui.dp("gap4")))
        ui.sheet(c, if (hebrew) "מזג האוויר במקום" else "Weather there", box)
    }

    private fun detailRows(w: Weather): List<View> {
        val ui = kav.ui()
        val c = kav.context()
        val rows = ArrayList<View>()
        fun rowRTL(label: String, value: String) {
            if (value.isEmpty()) return
            if (hebrew) {
                // In Hebrew the list reads right to left: label on the right, value on its left.
                val l = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
                l.addView(TextView(c).apply {
                    text = value
                    setTextColor(ui.color("textSecondary")); textSize = ui.textSize("body")
                    setPadding(0, 0, dp(ui.dp("gap3")), 0)
                })
                l.addView(TextView(c).apply {
                    text = label
                    setTextColor(ui.color("text")); textSize = ui.textSize("body"); typeface = Typeface.DEFAULT_BOLD
                })
                rows.add(l)
            } else {
                rows.add(ui.row(c, null, label, value, null))
            }
        }
        // Every row that IMS did not supply states the reason instead of silently hiding.
        val missHe = "אין נתון"
        val missEn = "Not available"
        fun valueOrMiss(v: String?): String =
            if (v.isNullOrEmpty()) (if (hebrew) missHe else missEn) else v
        val fl = w.feelsLike?.ifEmpty { null }
        rowRTL(if (hebrew) "תחושה" else "Feels like", if (fl != null) "$fl°" else valueOrMiss(fl))
        rowRTL(if (hebrew) "סיכוי לגשם" else "Rain chance",
            w.rainChance?.takeIf { it >= 0 }?.let { "$it%" } ?: (if (hebrew) "לא נמסר סיכוי גשם לשעה זו" else "No rain chance given for this hour"))
        rowRTL(if (hebrew) "לחות" else "Humidity", valueOrMiss(w.humidity).let { if (it == (if (hebrew) missHe else missEn) || w.humidity.isEmpty()) it else "$it%" })
        rowRTL(if (hebrew) "רוח" else "Wind", w.windSpeed.ifEmpty { if (hebrew) missHe else missEn }
            .let { if (w.windSpeed.isEmpty()) it else windText(w) })
        rowRTL(if (hebrew) "משבי רוח" else "Gusts", w.gustSpeed.ifEmpty { if (hebrew) missHe else missEn }
            .let { if (w.gustSpeed.isEmpty()) it else "$it km/h" })
        w.uvIndex?.let { rowRTL(if (hebrew) "מדד קרינה" else "UV index", it) }
        w.uvMax?.let { rowRTL(if (hebrew) "קרינה מקסימלית" else "UV max", it) }
        rowRTL(if (hebrew) "מקור" else "Source", "ims.gov.il")
        return rows
    }

    private fun windText(w: Weather): String {
        val speed = w.windSpeed
        if (speed.isEmpty()) return ""
        val dir = w.windDirId?.let { IMS.windDirectionName(it, hebrew) } ?: ""
        return if (dir.isEmpty()) "$speed km/h" else "$speed km/h $dir"
    }



    // ---------------- The rider's own settings view ----------------

    /**
     * The extension's own settings screen (kav.settingsScreen): a list of the nine
     * condition kinds, each with a switch to show or hide it and up/down arrows to
     * move it in the worst-to-best order. The topmost enabled kind is the one the
     * trip lines speak about; a kind switched off is never mentioned anywhere.
     */
    inner class SettingsScreen(private val ui: kav.kavapp.api.KavUi, private val c: Context) {

        fun view(): View {
            val outer = ScrollView(c)
            val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            outer.addView(box)

            // Full-width cards with the app's own spacing, like every other settings page.
            val gap = dp(ui.dp("gap3"))

            // The heading card: title plus the explanation of what the order does.
            val headInner = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(c).apply {
                    text = if (hebrew) "סדר תנאי מזג האוויר" else "Weather condition order"
                    setTextColor(ui.color("text")); textSize = ui.textSize("headline"); typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, dp(ui.dp("gap2")))
                })
                addView(TextView(c).apply {
                    text = if (hebrew)
                        "הסוג העליון שמופעל הוא הנאמר בשורת התוצאות של הנסיעה. העלו או הורידו כל תנאי לפי מה שחשוב לכם; תנאי שכיביתם לא יוזכר בשום מקום."
                    else
                        "The topmost switched-on kind is the one a trip's result line speaks about. Move each condition up or down by what matters to you; a switched-off kind is never mentioned anywhere."
                    setTextColor(ui.color("textSecondary")); textSize = ui.textSize("subhead")
                })
            }
            box.addView(ui.card(c, headInner), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, gap, 0, gap)
            })

            // Each kind card, full-width like the heading card.
            val listBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            box.addView(listBox)
            redraw(listBox)
            return outer
        }

        private fun kindLabel(id: String): String = when (id) {
            "thunderstorm" -> if (hebrew) "סופת רעמים" else "Thunderstorm"
            "rain" -> if (hebrew) "גשם" else "Rain"
            "snow" -> if (hebrew) "שלג" else "Snow"
            "fog" -> if (hebrew) "ערפל" else "Fog"
            "heat" -> if (hebrew) "חם" else "Heat"
            "wind" -> if (hebrew) "רוח" else "Wind"
            "cloudy" -> if (hebrew) "מעונן" else "Cloudy"
            "partly" -> if (hebrew) "מעונן חלקית" else "Partly cloudy"
            "sunny" -> if (hebrew) "שמש" else "Sunny"
            else -> id
        }

        private fun redraw(listBox: LinearLayout) {
            listBox.removeAllViews()
            var order = kindConfig.order.toMutableList()
            for ((idx, kind) in order.withIndex()) {
                fun move(delta: Int) {
                    val to = idx + delta
                    if (to < 0 || to >= order.size) return
                    Collections.swap(order, idx, to)
                    persistNow(order)
                    redraw(listBox)
                }
                // Each kind in Kav's card box: switch row inside, arrows beside it.
                // No subtitle: an empty one would shift the label off centre.
                val inner = LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48f)
                }
                inner.addView(ui.switchRow(c, kindLabel(kind), null, kindConfig.isEnabled(kind)) { now ->
                    val onOff = LinkedHashMap<String, Boolean>()
                    for (k in kindConfig.order) onOff[k] = if (k == kind) now else kindConfig.isEnabled(k)
                    persistNow(kindConfig.order, onOff)
                    redraw(listBox)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                inner.addView(upDownBtn(true) { move(-1) }, LinearLayout.LayoutParams(dp(36f), dp(36f)))
                inner.addView(upDownBtn(false) { move(+1) }, LinearLayout.LayoutParams(dp(36f), dp(36f)))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(ui.dp("gap2"))
                listBox.addView(ui.card(c, inner), lp)
            }
        }

        private fun upDownBtn(up: Boolean, onClick: Runnable): View =
            TextView(c).apply {
                text = if (up) "▲" else "▼"
                gravity = Gravity.CENTER
                setTextColor(ui.color("accent"))
                textSize = ui.textSize("body")
                isClickable = true; isFocusable = true
                contentDescription = if (up) (if (hebrew) "העלה" else "Move up") else (if (hebrew) "הורד" else "Move down")
                setOnClickListener { onClick.run() }
            }

        private fun persistNow(order: List<String>, onOffOverride: Map<String, Boolean>? = null) {
            val onOff = onOffOverride ?: order.associateWith { k -> kindConfig.isEnabled(k) }
            saveKinds(order, onOff)
        }
    }

    // ---------------- The results-line card ----------------

    /**
     * The line under each search result: the trip's weather in one sentence — the worst
     * leg's condition — plus a second line when an IMS alert covers any part of the
     * route. Loads on a thread, and if there is nothing to say (nothing fetched, kinds
     * switched off, empty route) the line clears itself — no text, no "by Weather"
     * residue, nothing taking space.
     */
    inner class ResultsLine(private val ui: kav.kavapp.api.KavUi, private val c: Context, private val trip: JSONObject) {

        fun view(): View {
            val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            Thread {
                try {
                    val items = pickRouteStops(trip).mapNotNull { weatherFor(it) }
                    main.post {
                        try { fill(box, items) }
                        catch (e: Exception) { kav.log("results line view: $e") }
                    }
                } catch (e: Exception) {
                    kav.log("results line: $e")
                }
            }.start()
            return box
        }

        private fun fill(box: LinearLayout, items: List<Weather>) {
            box.removeAllViews()
            if (items.isEmpty()) return
            val oks = items.filter { it.status == Weather.Status.OK }
            val alerts = items.mapNotNull { it.alert }.distinctBy { it.regionId }
            val verdict = tripVerdict(items)
            if (oks.isNotEmpty() && verdict.isNotEmpty()) {
                box.addView(TextView(c).apply {
                    text = verdict
                    setTextColor(ui.color("text")); textSize = ui.textSize("subhead")
                })
            }
            if (alerts.isNotEmpty()) {
                box.addView(TextView(c).apply {
                    text = if (hebrew) "למסלול זה התראות מזג אוויר" else "This route has weather alerts"
                    setTextColor(alertColor(alerts.minOf { it.severity })); textSize = ui.textSize("subhead")
                    typeface = Typeface.DEFAULT_BOLD
                })
            }
            // Nothing survived: the box stays empty and the line says nothing.
        }
    }
}

/** One active IMS weather alert for a region, as fetched from ims.gov.il/<lang>/warnings. */
class Alert(val regionId: Int, val severity: Int, val fromSec: Long, val toSec: Long,
            val bodyHe: String, val bodyEn: String,
            val titleHe: String = "", val titleEn: String = "")

/** One weather look along the route: the nearest station (null: none nearby), the bus stop's name, and when the rider passes it. */
class RouteStop(val city: IMS.City?, val name: String, val time: Long)  // time: seconds since epoch

/** Parsed IMS forecast. */
class Forecast(val lid: Int, val fetchedAt: Long, val days: Map<String, JSONObject>)  // day key -> {daily, hourly}

class Weather(
    val lid: Int,
    val cityName: String,
    val status: Status,
    val stopName: String = "",
    val temp: String = "",
    val desc: String = "",
    val rainChance: Int? = null,
    val atSec: Long = 0,
    val atText: String? = null,      // the hour label of the matched hourly block, e.g. "14:00"
    val summary: Boolean = false,     // true when showing a day summary instead of an hour
    val israelTime: Boolean = true,
    val detail: JSONObject? = null,  // the matched hourly block, for the detail sheet
    val feelsLike: String? = null,
    val humidity: String = "",
    val windSpeed: String = "",
    val windDirId: Int? = null,
    val gustSpeed: String = "",
    val uvIndex: String? = null,
    val uvMax: String? = null,
    val code: Int = 0,
    val alert: Alert? = null
) {
    enum class Status { OK, NETWORK, EMPTY, NO_STATION }
}
