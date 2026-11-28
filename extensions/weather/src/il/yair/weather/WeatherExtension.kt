package il.yair.weather

import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import il.yair.weather.IMS.Kind
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Weather: a line under each search result with the trip's main weather, a card on the trip's page with the
 * IMS forecast at each place the rider is outside (getting on, getting off, walking), weather icons on the trip's
 * map, and a weather row at the top of each stop's departures.
 *
 * Everything slow (the network, Kav's stop data) runs on this class's own threads; views are only touched on the
 * main thread.
 */
class WeatherExtension : KavExtension {

    lateinit var kav: Kav
    lateinit var bridge: KavBridge
    val main = Handler(Looper.getMainLooper())

    /** Cards' work (one task per card), and fetches (which never wait on each other, so the two pools can't deadlock). */
    val cardPool: ExecutorService = Executors.newFixedThreadPool(3) { r -> Thread(r, "Weather-card").apply { isDaemon = true } }
    private val fetchPool: ExecutorService = Executors.newFixedThreadPool(4) { r -> Thread(r, "Weather-fetch").apply { isDaemon = true } }
    private val saver: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "Weather-save").apply { isDaemon = true } }

    val hebrew: Boolean get() = kav.locale() == "he"
    fun t(en: String, he: String): String = if (hebrew) he else en

    companion object {
        private const val VERSION = "1.5.0"
        private const val FORECAST_URL = "https://ims.gov.il/en/full_forecast_data/%d"
        const val FORECAST_TTL_MS = 30L * 60 * 1000           // a forecast is fresh for 30 minutes
        private const val STALE_MAX_MS = 12L * 60 * 60 * 1000   // offline, an older one is still shown up to 12 hours
        private const val RETRY_SHORT_MS = 2L * 60 * 1000       // after a failed fetch, wait this long before trying again
        private const val RETRY_LONG_MS = 10L * 60 * 1000
        private const val WARNINGS_TTL_MS = 10L * 60 * 1000
        private const val TRIP_TTL_MS = 5L * 60 * 1000           // a trip's weather is kept this long (results scroll)
        private const val HOUR_WINDOW_SEC = 2L * 60 * 60         // the nearest hourly block must be within 2 h
        private const val SAME_PLACE_SEC = 45L * 60              // the same IMS place again within 45 min is one tile
        private const val MAX_STATION_KM = 30.0
        private const val MAX_POINTS = 10
        private const val CACHE_MAX = 40
    }

    // ---------------- Settings ----------------

    /** The rider's declared settings: units and the rain threshold. Replaced wholesale. */
    class Units(val celsius: Boolean, val kmh: Boolean, val rainMin: Int)

    @Volatile var units = Units(true, true, 20)

    /** The kinds' order and on/off set, replaced wholesale, never edited in place. */
    class KindConfig(val order: List<String>, val enabled: Set<String>) {
        fun isEnabled(id: String): Boolean = id in enabled
        fun rank(id: String): Int = order.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }

        companion object {
            fun default() = KindConfig(IMS.DEFAULT_ORDER, IMS.DEFAULT_ORDER.filter { it !in IMS.DEFAULT_OFF }.toSet())

            /** Cleans a stored order: old ids renamed, unknown dropped, new kinds put right after their default neighbour. */
            fun from(order: List<String>, off: Set<String>): KindConfig {
                fun rename(id: String) = if (id == "sunny") "clear" else id
                val o = ArrayList<String>()
                for (raw in order) { val id = rename(raw); if (Kind.of(id) != null && id !in o) o.add(id) }
                for ((i, id) in IMS.DEFAULT_ORDER.withIndex()) {
                    if (id in o) continue
                    val before = IMS.DEFAULT_ORDER.subList(0, i).lastOrNull { it in o }
                    o.add(if (before == null) 0 else o.indexOf(before) + 1, id)
                }
                val offIds = off.map { rename(it) }.toSet()
                return KindConfig(o, o.filter { it !in offIds }.toSet())
            }
        }
    }

    @Volatile var kinds: KindConfig = KindConfig.default()

    private fun readUnits(json: String?): Units = try {
        val o = JSONObject(json ?: "{}")
        Units(o.optString("temp", "c") != "f", o.optString("wind", "kmh") != "mph", o.optInt("rain", 20).coerceIn(0, 100))
    } catch (e: Exception) {
        Units(true, true, 20)
    }

    /** This extension's own file, named by its id so it never meets Kav's files or another extension's. */
    private fun kindsFile(): AtomicFile {
        val safe = kav.id().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return AtomicFile(File(kav.context().filesDir, "ext-$safe-kinds.json"))
    }

    private fun loadKinds() {
        try {
            val f = kindsFile()
            val old = File(kav.context().filesDir, "kinds_v2.json")   // 1.4.x kept it un-namespaced
            val text = when {
                f.baseFile.exists() -> String(f.readFully(), Charsets.UTF_8)
                old.exists() -> old.readText()
                else -> null
            }
            if (text == null) { kinds = KindConfig.default(); return }
            val o = JSONObject(text)
            val orderArr = o.optJSONArray("order") ?: JSONArray(o.optString("order", "[]"))
            val offArr = o.optJSONArray("off") ?: JSONArray()
            kinds = KindConfig.from(
                (0 until orderArr.length()).map { orderArr.getString(it) },
                (0 until offArr.length()).map { offArr.getString(it) }.toSet())
            if (old.exists()) { writeKinds(kinds); old.delete() }
        } catch (e: Exception) {
            kav.log("could not read the condition order, using the default: $e")
            kinds = KindConfig.default()
        }
    }

    /** Takes the new order at once and writes it on a thread of its own: the settings screen never waits for the disk. */
    fun saveKinds(cfg: KindConfig) {
        kinds = cfg
        synchronized(tripCache) { tripCache.clear() }
        saver.execute { writeKinds(cfg) }
    }

    private fun writeKinds(cfg: KindConfig) {
        val f = kindsFile()
        val out = try { f.startWrite() } catch (e: Exception) { kav.log("could not save the condition order: $e"); return }
        try {
            val o = JSONObject()
            o.put("order", JSONArray(cfg.order))
            o.put("off", JSONArray(cfg.order.filter { !cfg.isEnabled(it) }))
            out.write(o.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            kav.log("could not save the condition order: $e")
        }
    }

    // ---------------- Start ----------------

    override fun start(kav: Kav) {
        this.kav = kav
        bridge = KavBridge(kav)
        units = readUnits(kav.settings())
        kav.onSettings { all -> units = readUnits(all) }
        loadKinds()

        kav.card("results") { c, about -> ResultsLine(this, c, JSONObject(about).getJSONObject("trip")).view() }
        kav.card("trip") { c, about -> TripCard(this, c, JSONObject(about).getJSONObject("trip")).view() }
        kav.part("stop.board", "before") { c, about -> StopRow(this, c, JSONObject(about)).view() }
        kav.settingsScreen { c, _ -> SettingsScreen(this, c).view() }
        kav.map().onTap { id, c -> mapItems[id]?.let { Detail(this).show(it, c) } }

        // Load the IMS place list now, so the first search's lines don't all race to fetch it.
        fetchPool.execute { ensureCities() }
        kav.log("Weather $VERSION started, API ${kav.apiVersion()}, language ${kav.locale()}")
    }

    // ---------------- Networking ----------------

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "WeatherKavExt/$VERSION (Kav extension)")
            if (conn.responseCode != 200) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    // ---------------- IMS places ----------------

    private val cityLock = Any()
    @Volatile private var cities: Map<Int, IMS.City> = emptyMap()
    @Volatile private var citiesFull = false
    private var citiesTriedAt = 0L

    /**
     * IMS's place list, fetched once in both languages. Only one thread fetches; the others wait for it, so nobody
     * reads a half-filled list (that race is what left some result lines empty). Offline, the built-in list is used and
     * the full one is tried again after 10 minutes.
     */
    fun ensureCities(): Map<Int, IMS.City> {
        if (citiesFull) return cities
        synchronized(cityLock) {
            if (citiesFull) return cities
            val now = System.currentTimeMillis()
            if (cities.isNotEmpty() && now - citiesTriedAt < RETRY_LONG_MS) return cities
            citiesTriedAt = now
            val enBody = httpGet("https://ims.gov.il/en/locations_info")
            val heBody = httpGet("https://ims.gov.il/he/locations_info")
            val en = enBody?.let { IMS.parseLocations(it) }
            val he = heBody?.let { IMS.parseLocations(it) }
            val base = en?.takeIf { it.isNotEmpty() } ?: he?.takeIf { it.isNotEmpty() }
            if (base != null) {
                val regions = IMS.parseRegions(enBody ?: heBody ?: "")
                val m = HashMap<Int, IMS.City>()
                for ((lid, p) in base) {
                    m[lid] = IMS.City(lid, en?.get(lid)?.first ?: "", he?.get(lid)?.first ?: "", p.second, p.third, regions[lid])
                }
                cities = m
                citiesFull = en != null && he != null
            } else {
                if (cities.isEmpty()) cities = IMS.builtinLocations()
                kav.log("IMS place list unreachable: using the ${cities.size} built-in places for now")
            }
            return cities
        }
    }

    /** The nearest IMS place by great-circle distance, or null when every place is further than MAX_STATION_KM. */
    fun nearestCity(lat: Double, lon: Double): IMS.City? {
        var best: IMS.City? = null
        var bestKm = Double.MAX_VALUE
        val la = Math.toRadians(lat)
        for ((_, c) in ensureCities()) {
            val ca = Math.toRadians(c.lat)
            val dLa = ca - la
            val dLo = Math.toRadians(c.lon - lon)
            val h = Math.sin(dLa / 2) * Math.sin(dLa / 2) + Math.cos(la) * Math.cos(ca) * Math.sin(dLo / 2) * Math.sin(dLo / 2)
            val km = 2 * 6371.0 * Math.asin(Math.sqrt(h))
            if (km < bestKm) { bestKm = km; best = c }
        }
        return if (bestKm <= MAX_STATION_KM) best else null
    }

    // ---------------- Forecasts ----------------

    private val forecasts = object : LinkedHashMap<Int, IMS.Forecast>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, IMS.Forecast>?): Boolean = size > CACHE_MAX
    }
    private val lidLocks = ConcurrentHashMap<Int, Any>()
    private val forecastFailedAt = ConcurrentHashMap<Int, Long>()

    /**
     * A place's forecast: fresh (30 min), else fetched; when IMS can't be reached, the last one up to 12 hours old.
     * One fetch per place at a time: a second card asking for the same place waits for the first and gets its answer.
     */
    fun forecast(lid: Int): IMS.Forecast? {
        synchronized(lidLocks.getOrPut(lid) { Any() }) {
            val now = System.currentTimeMillis()
            val cached = synchronized(forecasts) { forecasts[lid] }
            if (cached != null && now - cached.fetchedAt < FORECAST_TTL_MS) return cached
            val failed = forecastFailedAt[lid]
            if (failed == null || now - failed > RETRY_SHORT_MS) {
                val f = httpGet(String.format(Locale.US, FORECAST_URL, lid))?.let { IMS.parseForecast(it, lid) }
                if (f != null) {
                    synchronized(forecasts) { forecasts[lid] = f }
                    forecastFailedAt.remove(lid)
                    return f
                }
                forecastFailedAt[lid] = now
            }
            return cached?.takeIf { now - it.fetchedAt < STALE_MAX_MS }
        }
    }

    // ---------------- Weather codes ----------------

    private val codesLock = Any()
    @Volatile private var codes: Map<Int, Pair<String, String>> = emptyMap()   // code -> (Hebrew, English)
    private var codesTriedAt = 0L

    /** A weather code's description, (Hebrew, English). One fetch gives both; a failed one is retried after 10 minutes. */
    fun codeDesc(code: Int): Pair<String, String> {
        if (code == 0) return Pair("", "")
        codes[code]?.let { return it }
        synchronized(codesLock) {
            codes[code]?.let { return it }
            val now = System.currentTimeMillis()
            if (codes.isEmpty() && now - codesTriedAt > RETRY_LONG_MS) {
                codesTriedAt = now
                try {
                    val data = JSONObject(httpGet("https://ims.gov.il/en/weather_codes") ?: "").getJSONObject("data")
                    val m = HashMap<Int, Pair<String, String>>()
                    val it = data.keys()
                    while (it.hasNext()) {
                        val o = data.optJSONObject(it.next()) ?: continue
                        val id = o.optString("weather_code").toIntOrNull() ?: continue
                        m[id] = Pair(o.optString("desc").trim(), o.optString("desc_en").trim())
                    }
                    if (m.isNotEmpty()) codes = m
                } catch (e: Exception) { }
            }
        }
        return codes[code] ?: Pair("", "")
    }

    // ---------------- IMS warnings ----------------

    private val warnLock = Any()
    @Volatile private var warnings: List<IMS.Warning> = emptyList()
    @Volatile private var warningsAt = 0L
    @Volatile private var warningsLang = ""
    private var warningsTriedAt = 0L
    private var loggedSample = false

    /** IMS's warnings, refreshed every 10 minutes; a failed fetch waits 2 minutes, instead of being retried on every stop. */
    private fun currentWarnings(): List<IMS.Warning> {
        val lang = if (hebrew) "he" else "en"
        val now = System.currentTimeMillis()
        if (lang == warningsLang && now - warningsAt < WARNINGS_TTL_MS) return warnings
        synchronized(warnLock) {
            if (lang == warningsLang && now - warningsAt < WARNINGS_TTL_MS) return warnings
            if (lang == warningsLang && now - warningsTriedAt < RETRY_SHORT_MS) return warnings
            warningsTriedAt = now
            val body = httpGet("https://ims.gov.il/$lang/warnings") ?: return warnings
            val parsed = IMS.parseWarnings(body)
            if (parsed == null) {
                kav.log("IMS warnings feed not readable: ${body.take(300)}")
                return warnings
            }
            if (parsed.isNotEmpty() && !loggedSample) {
                // The feed's shape with warnings in it isn't documented: keep one sample in the log to check against.
                loggedSample = true
                kav.log("IMS warnings sample (${parsed.size} warnings): ${body.take(3000)}")
            }
            for (w in parsed) if (w.regions.isEmpty()) kav.log("IMS warning with no region, not shown: ${w.title.ifEmpty { w.text }.take(80)}")
            warnings = parsed
            warningsAt = now
            warningsLang = lang
            return parsed
        }
    }

    /**
     * The most severe IMS warning for a place's land region that holds at that time. Sea regions are never used:
     * "dangerous sea for swimming" doesn't concern a bus stop. Severity: a higher severity_id is taken as more severe.
     */
    fun warningFor(city: IMS.City, atSec: Long): IMS.Warning? {
        val rid = city.rid ?: return null
        return currentWarnings().filter { rid in it.regions && it.holdsAt(atSec) }.maxByOrNull { it.severity }
    }

    // ---------------- The places on a trip ----------------

    /** One place the rider is outside at: the nearest IMS place (null: none nearby), the stop's name, when (seconds). */
    class RoutePoint(val city: IMS.City?, val stopName: String, val time: Long, val lat: Double, val lon: Double)

    /** A JSON field as text, with JSON null, "null" and "" all meaning none (optString turns null into "null"). */
    private fun str(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    /**
     * The places the rider is outside: where each walk starts and ends, each stop they wait at (from when they get
     * there), and each stop they get off at. Never the middle of a ride. A start or end with no name (a saved place,
     * the rider's location) takes the name of the nearest named stop on the trip. The same IMS place again within
     * 45 minutes is one tile.
     */
    fun routePoints(trip: JSONObject): List<RoutePoint> {
        val legs = trip.optJSONArray("legs") ?: return emptyList()
        class Raw(val lat: Double, val lon: Double, var name: String?, val t: Long)
        val raw = ArrayList<Raw>()
        fun add(p: JSONObject?, t: Long) {
            if (p == null || t <= 0) return
            val lat = p.optDouble("lat", 0.0)
            val lon = p.optDouble("lon", 0.0)
            if (lat == 0.0 && lon == 0.0) return
            raw.add(Raw(lat, lon, str(p, "name"), t))
        }
        for (i in 0 until legs.length()) {
            val leg = legs.optJSONObject(i) ?: continue
            val dep = leg.optLong("dep", 0)
            val arr = leg.optLong("arr", 0)
            when (leg.optString("kind")) {
                "walk", "bike" -> { add(leg.optJSONObject("from"), dep); add(leg.optJSONObject("to"), arr) }
                "wait" -> add(leg.optJSONObject("from"), dep)
                "ride" -> {
                    val waited = i > 0 && legs.optJSONObject(i - 1)?.optString("kind") == "wait"
                    if (!waited) add(leg.optJSONObject("from"), dep)
                    add(leg.optJSONObject("to"), arr)
                }
            }
        }
        val sorted = raw.sortedBy { it.t }
        for ((i, r) in sorted.withIndex()) {
            if (r.name != null) continue
            r.name = sorted.drop(i + 1).firstOrNull { it.name != null }?.name ?: sorted.take(i).lastOrNull { it.name != null }?.name
        }
        val out = ArrayList<RoutePoint>()
        for (r in sorted) {
            val city = nearestCity(r.lat, r.lon)
            val last = out.lastOrNull()
            val samePlace = last != null && (if (city != null) last.city?.lid == city.lid else last.city == null && last.stopName == (r.name ?: ""))
            if (samePlace && r.t - last!!.time < SAME_PLACE_SEC) continue
            out.add(RoutePoint(city, r.name ?: "", r.t, r.lat, r.lon))
        }
        return out.take(MAX_POINTS)
    }

    // ---------------- Weather at a place ----------------

    class Weather(
        val point: RoutePoint,
        val status: Status,
        val city: IMS.City?,
        val code: Int = 0,
        val descHe: String = "",
        val descEn: String = "",
        val tempC: Double? = null,
        val minC: Double? = null,
        val maxC: Double? = null,
        val rainChance: Int? = null,
        val feelsC: Double? = null,
        val humidity: Int? = null,
        val windKmh: Double? = null,
        val windDir: Int = -1,
        val gustKmh: Double? = null,
        val uv: String = "",
        val uvMax: String = "",
        val forecastAt: Long? = null,   // the hourly block's time (seconds); null for a day forecast
        val night: Boolean = false,
        val fetchedAt: Long = 0,
        val warning: IMS.Warning? = null
    ) {
        enum class Status { OK, NETWORK, TOO_FAR, NO_STATION }
        val daily: Boolean get() = status == Status.OK && forecastAt == null
    }

    private fun num(o: JSONObject, key: String): Double? =
        if (o.isNull(key)) null else o.optString(key).trim().toDoubleOrNull()

    /** The weather at a place and time: the nearest hourly block within 2 hours, else that day's forecast. */
    fun weatherFor(p: RoutePoint): Weather {
        val city = p.city ?: return Weather(p, Weather.Status.NO_STATION, null)
        val warning = warningFor(city, p.time)
        val f = forecast(city.lid) ?: return Weather(p, Weather.Status.NETWORK, city, warning = warning)
        val night = IMS.isNight(city.lat, city.lon, p.time)
        f.nearestHour(p.time, HOUR_WINDOW_SEC)?.let { (at, h) -> return fromHour(p, city, at, h, night, f.fetchedAt, warning) }
        val day = f.days[IMS.dayKey(p.time * 1000)]?.optJSONObject("daily")
            ?: return Weather(p, Weather.Status.TOO_FAR, city, warning = warning, fetchedAt = f.fetchedAt)
        val code = day.optString("weather_code").toIntOrNull() ?: 0
        val d = codeDesc(code)
        return Weather(p, Weather.Status.OK, city, code = code, descHe = d.first, descEn = d.second,
            minC = num(day, "minimum_temperature"), maxC = num(day, "maximum_temperature"),
            uvMax = str(day, "maximum_uvi") ?: "", night = night, fetchedAt = f.fetchedAt, warning = warning)
    }

    fun fromHour(p: RoutePoint, city: IMS.City, at: Long, h: JSONObject, night: Boolean, fetchedAt: Long, warning: IMS.Warning?): Weather {
        val code = h.optString("weather_code").toIntOrNull() ?: 0
        val d = codeDesc(code)
        // "Feels like" from heat_stress or wind_chill, only when it reads as a temperature.
        val feels = (num(h, "heat_stress") ?: num(h, "wind_chill"))?.takeIf { it in -30.0..60.0 }
        return Weather(p, Weather.Status.OK, city, code = code, descHe = d.first, descEn = d.second,
            tempC = num(h, "temperature"),
            rainChance = num(h, "rain_chance")?.toInt()?.takeIf { it >= 0 },
            feelsC = feels,
            humidity = num(h, "relative_humidity")?.toInt(),
            windKmh = num(h, "wind_speed"), windDir = h.optString("wind_direction_id").toIntOrNull() ?: -1,
            gustKmh = num(h, "gust_speed"), uv = str(h, "u_v_index") ?: "",
            forecastAt = at, night = night, fetchedAt = fetchedAt, warning = warning)
    }

    // ---------------- A trip's weather, cached ----------------

    private val tripCache = object : LinkedHashMap<String, Pair<Long, List<Weather>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<Weather>>>?): Boolean = size > 60
    }

    private fun tripKey(trip: JSONObject): String {
        val legs = trip.optJSONArray("legs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until legs.length()) {
            val l = legs.optJSONObject(i) ?: continue
            sb.append(l.optString("kind")).append(l.optLong("dep")).append('-').append(l.optLong("arr"))
            l.optJSONObject("to")?.let { sb.append('@').append(it.optDouble("lat")).append(',').append(it.optDouble("lon")) }
            sb.append(';')
        }
        return sb.toString()
    }

    /**
     * Every place's weather for a trip. The places' forecasts are fetched side by side, and the answer is kept for
     * 5 minutes, so scrolling the results doesn't fetch again. Runs on a card thread.
     */
    fun tripWeather(trip: JSONObject): List<Weather> {
        val key = tripKey(trip)
        val now = System.currentTimeMillis()
        synchronized(tripCache) { tripCache[key]?.let { if (now - it.first < TRIP_TTL_MS) return it.second } }
        val points = routePoints(trip)
        val lids = points.mapNotNull { it.city?.lid }.distinct()
        if (lids.size > 1) {
            val jobs = lids.map { lid -> fetchPool.submit<IMS.Forecast?> { forecast(lid) } }
            for (j in jobs) try { j.get(40, TimeUnit.SECONDS) } catch (e: Exception) { }
        }
        val items = points.map { weatherFor(it) }
        if (items.none { it.status == Weather.Status.NETWORK }) synchronized(tripCache) { tripCache[key] = Pair(now, items) }
        return items
    }

    // ---------------- Kinds and wording ----------------

    /** A place's kind for the verdict: "possible rain" under the rider's rain threshold isn't rain. */
    fun kindOf(w: Weather): Kind {
        val k = IMS.kindOf(w.code, w.descEn)
        val chance = w.rainChance
        if (k == Kind.RAIN && chance != null && chance < units.rainMin && IMS.isPossibleRain(w.code, w.descEn))
            return if (w.code == 1530) Kind.PARTLY else Kind.CLOUDY
        return k
    }

    fun kindLabel(k: Kind): String = when (k) {
        Kind.THUNDERSTORM -> t("Thunderstorm", "סופת רעמים")
        Kind.SNOW -> t("Snow", "שלג")
        Kind.RAIN -> t("Rain", "גשם")
        Kind.HEAT -> t("Heat", "חום")
        Kind.DUST -> t("Dust", "אובך")
        Kind.WIND -> t("Strong wind", "רוח חזקה")
        Kind.FOG -> t("Fog", "ערפל")
        Kind.CLOUDY -> t("Cloudy", "מעונן")
        Kind.PARTLY -> t("Partly cloudy", "מעונן חלקית")
        Kind.CLEAR -> t("Clear", "בהיר")
    }

    /** IMS's own words for the weather, or the kind's name when IMS gave none. */
    fun desc(w: Weather): String = (if (hebrew) w.descHe else w.descEn).ifEmpty { if (hebrew) w.descEn else w.descHe }.ifEmpty { kindLabel(kindOf(w)) }

    private fun verdictSentence(k: Kind, night: Boolean): String = when (k) {
        Kind.THUNDERSTORM -> t("Thunderstorms expected on this trip", "צפויות סופות רעמים בנסיעה")
        Kind.SNOW -> t("Snow expected on this trip", "צפוי שלג בנסיעה")
        Kind.RAIN -> t("Rain expected on this trip", "צפוי גשם בנסיעה")
        Kind.HEAT -> t("Heat expected on this trip", "צפוי חום בנסיעה")
        Kind.DUST -> t("Dust expected on this trip", "צפוי אובך בנסיעה")
        Kind.WIND -> t("Strong wind expected on this trip", "צפויות רוחות חזקות בנסיעה")
        Kind.FOG -> t("Fog expected on this trip", "צפוי ערפל בנסיעה")
        Kind.CLOUDY -> t("Cloudy skies expected on this trip", "צפויה מעוננות בנסיעה")
        Kind.PARTLY -> t("Partly cloudy skies expected on this trip", "צפויה מעוננות חלקית בנסיעה")
        Kind.CLEAR -> if (night) t("Clear skies expected on this trip", "צפויים שמיים בהירים בנסיעה")
                      else t("Sunny weather expected on this trip", "צפוי מזג אוויר שמשי בנסיעה")
    }

    fun rainText(chance: Int): String = t("$chance% chance of rain", "$chance% סיכוי לגשם")

    /**
     * The results line: the worst switched-on kind at the places the rider is outside, by the rider's own order, then
     * the trip's temperature range, then the highest rain chance when it's at or above the rider's threshold:
     * "Rain expected on this trip · 14–18°, 40% chance of rain". "" when there's nothing to say.
     */
    fun verdict(items: List<Weather>): String {
        val oks = items.filter { it.status == Weather.Status.OK }
        if (oks.isEmpty()) return ""
        val cfg = kinds
        val worst = oks.mapNotNull { w -> kindOf(w).takeIf { cfg.isEnabled(it.id) }?.let { Pair(w, it) } }
            .minByOrNull { cfg.rank(it.second.id) } ?: return ""
        val sb = StringBuilder(verdictSentence(worst.second, worst.first.night))
        val temps = tempRange(oks)
        if (temps != null) sb.append(" · ").append(temps)
        val rain = oks.mapNotNull { it.rainChance }.maxOrNull()
        if (rain != null && rain >= units.rainMin) sb.append(if (temps != null) ", " else " · ").append(rainText(rain))
        return sb.toString()
    }

    private fun tempRange(oks: List<Weather>): String? {
        val all = oks.flatMap { listOfNotNull(it.tempC, it.minC, it.maxC) }.map { tempNum(it) }
        if (all.isEmpty()) return null
        val lo = all.minOrNull()!!
        val hi = all.maxOrNull()!!
        return if (lo == hi) "$lo°" else "$lo–$hi°"
    }

    /** The results line's warning: the most severe one on the trip, what it is and where. Null when none. */
    fun warningLine(items: List<Weather>): Pair<String, IMS.Warning>? {
        val found = items.mapNotNull { w -> w.warning?.let { Pair(it, w) } }.distinctBy { it.first.key }
        if (found.isEmpty()) return null
        val top = found.maxByOrNull { it.first.severity }!!
        val place = top.second.city?.name(hebrew) ?: top.second.point.stopName
        val what = top.first.title.take(60)
        val sb = StringBuilder(
            if (what.isEmpty()) t("IMS weather warning in $place", "אזהרת מזג אוויר ב$place")
            else t("IMS warning: $what in $place", "אזהרת השירות המטאורולוגי: $what ב$place"))
        if (found.size > 1) sb.append(t(" (+${found.size - 1} more)", " (ועוד ${found.size - 1})"))
        return Pair(sb.toString(), top.first)
    }

    /**
     * The leave-later hint: when it rains at a place the rider is outside within the next 3 hours, the first later
     * hour at that place with no rain (and a chance under the threshold), up to 5 hours on. Null when there's none.
     * Runs on a card thread (it may read a forecast).
     */
    fun easeHint(items: List<Weather>): String? {
        val nowSec = System.currentTimeMillis() / 1000
        val w = items.firstOrNull { it.status == Weather.Status.OK && it.city != null && kindOf(it).let { k -> k == Kind.RAIN || k == Kind.THUNDERSTORM } }
            ?: return null
        if (w.point.time > nowSec + 3 * 3600 || w.point.time < nowSec - 3600) return null
        if (!kinds.isEnabled(kindOf(w).id)) return null
        val city = w.city!!
        val f = forecast(city.lid) ?: return null
        for ((at, h) in f.hours) {
            if (at <= w.point.time) continue
            if (at > w.point.time + 5 * 3600) break
            val later = fromHour(w.point, city, at, h, false, f.fetchedAt, null)
            val k = kindOf(later)
            val chance = later.rainChance
            if (k != Kind.RAIN && k != Kind.THUNDERSTORM && (chance == null || chance < units.rainMin)) {
                val place = city.name(hebrew)
                return t("The rain in $place should ease around ${clock(at)}, so a later trip may keep you dry.",
                    "הגשם ב$place צפוי להיחלש בסביבות ${clock(at)}, כך שנסיעה מאוחרת יותר עשויה להשאיר אתכם יבשים.")
            }
        }
        return null
    }

    // ---------------- Units, time ----------------

    fun tempNum(c: Double): Int = Math.round(if (units.celsius) c else c * 9 / 5 + 32).toInt()
    fun temp(c: Double): String = "${tempNum(c)}°"

    fun tempText(w: Weather): String = when {
        w.tempC != null -> temp(w.tempC)
        w.minC != null && w.maxC != null -> "${tempNum(w.minC)}–${tempNum(w.maxC)}°"
        else -> (w.maxC ?: w.minC)?.let { temp(it) } ?: ""
    }

    fun speed(kmh: Double): String {
        val v = Math.round(if (units.kmh) kmh else kmh * 0.621371)
        return if (units.kmh) "$v ${t("km/h", "קמ״ש")}" else "$v ${t("mph", "מייל לשעה")}"
    }

    /** A time in Kav's own clock format (Settings › Look and language), so it reads like every other time in Kav. */
    fun clock(sec: Long): String = SimpleDateFormat(bridge.clockPattern(), Locale.US).format(Date(sec * 1000))

    /** Today, Tomorrow, then the weekday from two days on, then a date from a week on. */
    fun dayLabel(sec: Long): String {
        val zone = ZoneId.systemDefault()
        val d = Instant.ofEpochSecond(sec).atZone(zone).toLocalDate()
        val diff = ChronoUnit.DAYS.between(LocalDate.now(zone), d)
        val loc = if (hebrew) Locale("he") else Locale.US
        return when (diff) {
            0L -> t("Today", "היום")
            1L -> t("Tomorrow", "מחר")
            in 2L..6L -> d.dayOfWeek.getDisplayName(TextStyle.FULL, loc)
            else -> d.format(DateTimeFormatter.ofPattern(if (hebrew) "d.M" else "d MMM", loc))
        }
    }

    /** "Updated 40 min ago" for a forecast IMS couldn't refresh; null while it's fresh. */
    fun staleText(w: Weather): String? {
        if (w.fetchedAt <= 0) return null
        val age = System.currentTimeMillis() - w.fetchedAt
        if (age < FORECAST_TTL_MS + 5 * 60 * 1000) return null
        val min = age / 60_000
        return if (min < 60) t("Updated $min min ago", "עודכן לפני $min דקות")
        else t("Updated ${min / 60} h ago", "עודכן לפני ${min / 60} שעות")
    }

    // ---------------- The trip's map ----------------

    /** What each marker on the trip's map shows, for a tap. */
    val mapItems = ConcurrentHashMap<String, Weather>()
}
