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
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension

/**
 * Route Weather — a card on a trip's page (slot.trip) showing the Israel Meteorological
 * Service forecast for every city the route passes through, at the hour the rider passes it.
 * Tap a city tile for a detail sheet with feels-like, humidity, wind and UV.
 */
class RouteWeather : KavExtension {

    private lateinit var kav: Kav
    private var hebrew = false
    private val main = Handler(Looper.getMainLooper())

    private val cities = ConcurrentHashMap<Int, IMS.City>()
    private val forecastCache = ConcurrentHashMap<Int, Forecast>()   // lid -> forecast, refreshed 30 min
    private val codes = ConcurrentHashMap<Int, String>()             // weather code -> description
    private var codesFetched = false

    // IMS warnings: region id -> active alert (region r-<rid>)
    private val alerts = ConcurrentHashMap<Int, Alert>()
    private var alertsFetchedAt = 0L

    companion object {
        private const val IMS_BASE = "https://ims.gov.il/%s/full_forecast_data/%d"
        private const val FORECAST_TTL_MS = 30L * 60 * 1000
        private const val GAP_MIN_SEC = 30L * 60      // min time between kept stops
        private const val HOUR_WINDOW_MS = 2L * 60 * 60 * 1000   // nearest hourly block must be within 2 h
        private const val ALERT_TTL_MS = 10L * 60 * 1000         // warnings feed refresh
    }

    override fun start(kav: Kav) {
        this.kav = kav
        this.hebrew = kav.locale() == "he"
        val ui = kav.ui()

        kav.card("trip") { context, about ->
            val trip = JSONObject(about).getJSONObject("trip")
            CardPresenter(ui, context, trip).view()
        }

        kav.screen("weather") { context, _ -> ForecastScreen(ui, context).view() }

        kav.log("Route Weather started, API ${kav.apiVersion()}, language ${kav.locale()}")
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
        val cached = forecastCache[lid]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < FORECAST_TTL_MS) return cached
        val lang = if (hebrew) "he" else "en"
        val body = httpGet(String.format(Locale.US, IMS_BASE, lang, lid)) ?: return null
        val f = IMS.parseForecast(body, lid) ?: return null
        if (forecastCache.size > 60) forecastCache.clear()
        forecastCache[lid] = f
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

    private fun nearestCity(lat: Double, lon: Double, fallbackName: String?): IMS.City {
        ensureCities()
        var best: IMS.City? = null
        var bestD = Double.MAX_VALUE
        for ((_, c) in cities) {
            val dLat = (c.lat - lat) * 111.0
            val dLon = (c.lon - lon) * 93.0
            val d = dLat * dLat + dLon * dLon
            if (d < bestD) { bestD = d; best = c }
        }
        return best ?: IMS.City(17, fallbackName ?: if (hebrew) "הרצליה" else "Herzliya", 32.1643, 34.8423)
    }

    // ---------------- Route stops ----------------

    fun pickRouteStops(trip: JSONObject): List<RouteStop> {
        val legs = trip.optJSONArray("legs") ?: return emptyList()
        val seen = HashSet<Int>()
        val stops = ArrayList<Pair<IMS.City, Long>>()
        for (i in 0 until legs.length()) {
            val leg = legs.optJSONObject(i) ?: continue
            if (leg.optString("kind") != "ride" && leg.optString("kind") != "walk") continue
            val dep = leg.optLong("dep", 0)
            val arr = leg.optLong("arr", 0)
            for (end in listOf("from", "to")) {
                val p = leg.optJSONObject(end) ?: continue
                val lat = p.optDouble("lat", 0.0)
                val lon = p.optDouble("lon", 0.0)
                if (lat == 0.0 && lon == 0.0) continue
                val nm = p.optString("name")
                val t = if (end == "from") dep else arr
                if (t <= 0 || nm.isEmpty()) continue
                val c = nearestCity(lat, lon, nm)
                if (seen.add(c.lid)) stops.add(c to t)
            }
        }
        stops.sortBy { it.second }
        // Collapse: keep the first stop, then a next one only when at least 30 minutes passed.
        val out = ArrayList<RouteStop>()
        var lastT = Long.MIN_VALUE
        for ((city, t) in stops) {
            if (out.isEmpty() || t - lastT >= GAP_MIN_SEC) {
                out.add(RouteStop(city, t))
                lastT = t
            }
        }
        return out
    }

    // ---------------- Weather for a stop ----------------

    private fun weatherFor(stop: RouteStop): Weather {
        val alert = alertFor(stop.city, stop.time)
        val f = fetchForecast(stop.city.lid)
            ?: return Weather(stop.city.lid, stop.city.name, Weather.Status.NETWORK, atSec = stop.time, alert = alert)
        val inIsrael = IMS.israelEpochMatches(stop.time)
        val key = IMS.dayKey(stop.time * 1000)
        val dayData = f.days[key] ?: return Weather(stop.city.lid, stop.city.name, Weather.Status.EMPTY, atSec = stop.time)
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
        if (bestHour == null) return Weather(stop.city.lid, stop.city.name, Weather.Status.EMPTY, atSec = stop.time)
        val h = bestHour!!
        return if (bestGap <= HOUR_WINDOW_MS) hourlyWeather(stop, h, inIsrael, alert)
        else dailyFallback(stop, dayData.optJSONObject("daily"), inIsrael, alert)
    }

    private fun hourlyWeather(stop: RouteStop, h: JSONObject, inIsrael: Boolean, alert: Alert?): Weather {
        val code = h.optInt("weather_code")
        return Weather(
            lid = stop.city.lid, cityName = stop.city.name, status = Weather.Status.OK,
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
        if (daily == null) return Weather(stop.city.lid, stop.city.name, Weather.Status.EMPTY, atSec = stop.time, alert = alert)
        val code = daily.optInt("weather_code")
        return Weather(
            lid = stop.city.lid, cityName = stop.city.name, status = Weather.Status.OK,
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
                val stops = pickRouteStops(trip)
                val items = stops.map { weatherFor(it) }
                main.post { loadingBox.removeAllViews(); fill(loadingBox, items) }
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
                    text = if (w.status == Weather.Status.NETWORK) (if (hebrew) "אין תקשורת" else "No connection")
                           else (if (hebrew) "אין תחזית" else "No forecast")
                    setTextColor(ui.color("problem")); textSize = ui.textSize("caption")
                })
                return card
            }

            val iconTemp = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            iconTemp.addView(ImageView(c).apply {
                setImageBitmap(icon(iconFor(w.code), dp(22f)))
                setPadding(0, 0, dp(5f), 0)
            })
            iconTemp.addView(TextView(c).apply {
                text = w.temp + "°"
                setTextColor(ui.color("text")); textSize = ui.textSize("title"); typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(iconTemp)

            card.addView(TextView(c).apply {
                text = w.desc
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
            // An active IMS alert for this city: wrap in a frame with a severity line on the
            // leading edge and a red "!" badge on the far corner (top-left in Hebrew, top-right in English).
            if (w.alert != null) {
                val frame = FrameLayout(c)
                frame.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginStart = dp(3f)
                })
                val sevLine = View(c).apply { setBackgroundColor(alertColor(w.alert.severity)) }
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
                }
                val bs = dp(18f)
                frame.addView(badge, FrameLayout.LayoutParams(bs, bs).apply {
                    gravity = if (hebrew) (Gravity.TOP or Gravity.START) else (Gravity.TOP or Gravity.END)
                    marginStart = dp(2f); marginEnd = dp(2f); topMargin = dp(2f)
                })
                return frame
            }
            return card
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
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(c).apply { setImageBitmap(icon(iconFor(w.code), dp(34f))); setPadding(0,0,dp(8f),0) })
        head.addView(TextView(c).apply {
            text = if (w.summary) w.temp + "° (day)" else w.temp + "°"
            setTextColor(ui.color("text")); textSize = ui.textSize("largeTitle"); typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(head)
        box.addView(TextView(c).apply {
            text = w.desc
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
        rowRTL(if (hebrew) "תחושה" else "Feels like", (w.feelsLike ?: "") + "°")
        w.rainChance?.let { if (it >= 0) rowRTL(if (hebrew) "סיכוי לגשם" else "Rain chance", "$it%") }
        rowRTL(if (hebrew) "לחות" else "Humidity", w.humidity + "%")
        rowRTL(if (hebrew) "רוח" else "Wind", windText(w))
        rowRTL(if (hebrew) "משבי רוח" else "Gusts", w.gustSpeed + " km/h")
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

    // ---------------- The standalone screen ----------------

    inner class ForecastScreen(private val ui: kav.kavapp.api.KavUi, private val c: Context) {
        fun view(): View {
            val outer = ScrollView(c)
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            }
            box.addView(TextView(c).apply {
                text = if (hebrew) "מזג האוויר בדרך" else "Route Weather"
                setTextColor(ui.color("text")); textSize = ui.textSize("title"); typeface = Typeface.DEFAULT_BOLD
            })
            box.addView(TextView(c).apply {
                text = if (hebrew) "תחזית מהשירות המטאורולוגי הישראלי לכל מקום שבו תעבור."
                       else "Forecast from the Israel Meteorological Service for every place you will pass through."
                setTextColor(ui.color("textSecondary")); textSize = ui.textSize("subhead")
                setPadding(0, dp(ui.dp("gap2")), 0, dp(ui.dp("gap4")))
            })
            val pick = ui.button(c, if (hebrew) "בחרו מקום" else "Pick a place", true) {
                kav.pickPlace { place ->
                    if (place != null) {
                        val p = JSONObject(place)
                        val lat = p.optDouble("lat", 0.0)
                        val lon = p.optDouble("lon", 0.0)
                        val nm = p.optString("name", "")
                        Thread {
                            val city = nearestCity(lat, lon, nm)
                            val w = weatherFor(RouteStop(city, System.currentTimeMillis() / 1000))
                            main.post {
                                box.removeView(box.findViewWithTag("wbox"))
                                val wbox = LinearLayout(c).apply { tag = "wbox"; orientation = LinearLayout.VERTICAL }
                                wbox.addView(placeResult(w, nm))
                                box.addView(wbox, 2)
                            }
                        }.start()
                    }
                }
            }
            box.addView(pick, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            outer.addView(box)
            return outer
        }

        private fun placeResult(w: Weather, pickedName: String): View {
            val inner = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            inner.addView(TextView(c).apply {
                text = if (hebrew) "תחזית עכשיו: " + w.cityName else "Current forecast: " + w.cityName
                setTextColor(ui.color("text")); textSize = ui.textSize("headline"); typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(ui.dp("gap3")), 0, dp(ui.dp("gap2")))
            })
            val iconTemp = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            iconTemp.addView(ImageView(c).apply { setImageBitmap(icon(iconFor(w.code), dp(30f))); setPadding(0,0,dp(6f),0) })
            iconTemp.addView(TextView(c).apply {
                text = w.temp + "°"
                setTextColor(ui.color("text")); textSize = ui.textSize("largeTitle"); typeface = Typeface.DEFAULT_BOLD
            })
            if (w.status == Weather.Status.OK) {
                inner.addView(iconTemp)
                if (w.desc.isNotEmpty()) inner.addView(TextView(c).apply {
                    text = w.desc; setTextColor(ui.color("textSecondary")); textSize = ui.textSize("subhead")
                })
                // Tap anywhere on the block to open the full detail sheet.
                inner.setOnClickListener { showDetail(w, c) }
                inner.isClickable = true
            } else {
                inner.addView(TextView(c).apply {
                    text = if (hebrew) "אין תחזית זמינה כרגע" else "No forecast available right now"
                    setTextColor(ui.color("problem")); textSize = ui.textSize("subhead")
                })
            }
            return inner
        }
    }
}

/** One active IMS weather alert for a region, as fetched from ims.gov.il/<lang>/warnings. */
class Alert(val regionId: Int, val severity: Int, val fromSec: Long, val toSec: Long,
            val bodyHe: String, val bodyEn: String,
            val titleHe: String = "", val titleEn: String = "")

/** One weather look along the route: which IMS city, and when the rider passes it. */
class RouteStop(val city: IMS.City, val time: Long)  // time: seconds since epoch

/** Parsed IMS forecast. */
class Forecast(val lid: Int, val fetchedAt: Long, val days: Map<String, JSONObject>)  // day key -> {daily, hourly}

class Weather(
    val lid: Int,
    val cityName: String,
    val status: Status,
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
    enum class Status { OK, NETWORK, EMPTY }
}
