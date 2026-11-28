package il.yair.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import il.yair.weather.IMS.Kind
import il.yair.weather.WeatherExtension.Weather
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// ---------------- Shared bits ----------------

/** Whether Kav is in a dark look now, from its background color. Main thread. */
private fun WeatherExtension.isDark(): Boolean {
    val bg = kav.ui().color("bg")
    val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255
    return lum < 0.5
}

/**
 * A warning's color by severity (a higher IMS severity_id is taken as more severe): red, orange, yellow. On a light
 * look, orange and yellow are darkened so text in them stays readable.
 */
fun WeatherExtension.warningColor(severity: Int): Int {
    val dark = isDark()
    return when {
        severity >= 3 -> 0xFFE53935.toInt()
        severity == 2 -> if (dark) 0xFFFFA726.toInt() else 0xFFE65100.toInt()
        else -> if (dark) 0xFFFDD835.toInt() else 0xFF8D6E00.toInt()
    }
}

/** The icon for a place's weather: its kind, with a moon instead of the sun after dark. */
fun WeatherExtension.iconName(w: Weather): String = when (kindOf(w)) {
    Kind.CLEAR -> if (w.night) "moon" else "sun"
    Kind.PARTLY -> if (w.night) "cloud_moon" else "cloud_sun"
    Kind.CLOUDY -> "cloud"
    Kind.RAIN -> "rain"
    Kind.THUNDERSTORM -> "storm"
    Kind.SNOW -> "snow"
    Kind.FOG -> "fog"
    Kind.HEAT -> "heat"
    Kind.WIND -> "wind"
    Kind.DUST -> "dust"
}

private val iconCache = ConcurrentHashMap<String, Bitmap>()

/** Draws a condition icon on a 24-unit grid. Plain colors that read on light and dark looks alike. */
fun drawIcon(name: String, sizePx: Int): Bitmap {
    iconCache["$name-$sizePx"]?.let { return it }
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val d = sizePx / 24f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val sun = 0xFFFDB813.toInt()
    val moon = 0xFFFFE082.toInt()
    val cloud = 0xFF90A4AE.toInt()
    val drop = 0xFF4FC3F7.toInt()

    fun cloudAt(top: Float) {
        p.style = Paint.Style.FILL; p.color = cloud
        cv.drawCircle(9f * d, (top + 3f) * d, 4.5f * d, p)
        cv.drawCircle(14.5f * d, (top + 3f) * d, 3.8f * d, p)
        cv.drawRoundRect(RectF(4f * d, (top + 3.5f) * d, 20f * d, (top + 9f) * d), 2.8f * d, 2.8f * d, p)
    }
    fun moonAt(cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.FILL; p.color = moon
        val path = Path()
        path.addCircle(cx * d, cy * d, r * d, Path.Direction.CW)
        val cut = Path()
        cut.addCircle((cx + r * 0.55f) * d, (cy - r * 0.35f) * d, r * 0.85f * d, Path.Direction.CW)
        path.op(cut, Path.Op.DIFFERENCE)
        cv.drawPath(path, p)
    }

    when (name) {
        "sun" -> {
            p.color = sun; cv.drawCircle(12f * d, 12f * d, 5f * d, p)
            p.strokeWidth = 1.6f * d; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            for (i in 0..7) {
                val a = Math.toRadians(i * 45.0)
                cv.drawLine((12 + Math.cos(a) * 7.5).toFloat() * d, (12 + Math.sin(a) * 7.5).toFloat() * d,
                    (12 + Math.cos(a) * 9.8).toFloat() * d, (12 + Math.sin(a) * 9.8).toFloat() * d, p)
            }
        }
        "moon" -> moonAt(12f, 12f, 7f)
        "cloud_sun" -> { p.color = sun; cv.drawCircle(16f * d, 8f * d, 4f * d, p); cloudAt(8f) }
        "cloud_moon" -> { moonAt(16f, 8f, 4.2f); cloudAt(8f) }
        "cloud" -> cloudAt(7f)
        "rain" -> {
            cloudAt(5f)
            p.color = drop; p.strokeWidth = 1.8f * d; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            for (x in listOf(8f, 12f, 16f)) cv.drawLine(x * d, 17.5f * d, (x - 1.5f) * d, 21.5f * d, p)
        }
        "storm" -> {
            cloudAt(4.5f)
            p.color = 0xFFFFC107.toInt(); p.style = Paint.Style.FILL
            val bolt = Path()
            bolt.moveTo(12.5f * d, 14f * d); bolt.lineTo(9f * d, 19f * d); bolt.lineTo(11.5f * d, 19f * d)
            bolt.lineTo(10f * d, 23f * d); bolt.lineTo(15f * d, 17.5f * d); bolt.lineTo(12.5f * d, 17.5f * d); bolt.lineTo(14f * d, 14f * d)
            bolt.close(); cv.drawPath(bolt, p)
        }
        "snow" -> {
            cloudAt(5f)
            p.color = drop; p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * d; p.strokeCap = Paint.Cap.ROUND
            for ((x, y) in listOf(7.5f to 19.5f, 12f to 21f, 16.5f to 19.5f)) {
                cv.drawLine((x - 1.6f) * d, y * d, (x + 1.6f) * d, y * d, p)
                cv.drawLine(x * d, (y - 1.6f) * d, x * d, (y + 1.6f) * d, p)
            }
        }
        "fog" -> {
            cloudAt(3.5f)
            p.color = 0xFFB0BEC5.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d; p.strokeCap = Paint.Cap.ROUND
            for (y in listOf(16.5f, 19f, 21.5f)) cv.drawLine(5f * d, y * d, 19f * d, y * d, p)
        }
        "heat" -> {
            // a thermometer beside a small sun
            p.color = sun; cv.drawCircle(17f * d, 7f * d, 3.5f * d, p)
            p.color = 0xFFE53935.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d
            cv.drawRoundRect(RectF(7f * d, 3f * d, 11f * d, 16f * d), 2f * d, 2f * d, p)
            p.style = Paint.Style.FILL
            cv.drawCircle(9f * d, 18.5f * d, 3.5f * d, p)
            cv.drawRect(RectF(8.2f * d, 8f * d, 9.8f * d, 17f * d), p)
        }
        "wind" -> {
            p.color = 0xFF90A4AE.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.strokeCap = Paint.Cap.ROUND
            cv.drawPath(Path().apply { moveTo(3f * d, 8f * d); lineTo(15f * d, 8f * d); quadTo(19f * d, 8f * d, 19f * d, 5f * d) }, p)
            cv.drawPath(Path().apply { moveTo(3f * d, 13f * d); lineTo(19f * d, 13f * d) }, p)
            cv.drawPath(Path().apply { moveTo(3f * d, 18f * d); lineTo(13f * d, 18f * d); quadTo(17f * d, 18f * d, 17f * d, 21f * d) }, p)
        }
        "dust" -> {
            // a pale sun behind dotted haze
            p.color = 0x99FDB813.toInt(); cv.drawCircle(12f * d, 9f * d, 5f * d, p)
            p.color = 0xFFC8A165.toInt()
            for (row in 0..2) for (col in 0..4) cv.drawCircle((4f + col * 4f + (row % 2) * 2f) * d, (14f + row * 3.2f) * d, 1.1f * d, p)
        }
    }
    iconCache["$name-$sizePx"] = bmp
    return bmp
}

// ---------------- The results line ----------------

/**
 * The line under each search result: one line for the trip's weather ("Rain expected on this trip · 14–18°, 40%
 * chance of rain") and, when IMS warns about a place on it, one line for the warning. Nothing at all when there's
 * nothing to say; why goes to the log, so an empty line can be traced.
 */
class ResultsLine(private val ext: WeatherExtension, private val c: Context, private val trip: JSONObject) {

    fun view(): View {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        ext.cardPool.execute {
            try {
                val items = ext.tripWeather(trip)
                val verdict = ext.verdict(items)
                val warning = ext.warningLine(items)
                if (verdict.isEmpty() && warning == null) {
                    val why = when {
                        items.isEmpty() -> "no places with coordinates on this route"
                        items.none { it.status == Weather.Status.OK } -> "no forecast: " + items.joinToString { it.status.name }
                        else -> "every condition on this trip is switched off"
                    }
                    ext.kav.log("results line empty: $why")
                }
                ext.main.post {
                    try { fill(box, verdict, warning) } catch (e: Exception) { ext.kav.log("results line view: $e") }
                }
            } catch (e: Exception) {
                ext.kav.log("results line: $e")
            }
        }
        return box
    }

    private fun fill(box: LinearLayout, verdict: String, warning: Pair<String, IMS.Warning>?) {
        val ui = ext.kav.ui()
        box.removeAllViews()
        if (verdict.isNotEmpty()) {
            box.addView(TextView(c).apply {
                text = verdict
                setTextColor(ui.color("text")); textSize = ui.textSize("subhead")
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
        }
        if (warning != null) {
            box.addView(TextView(c).apply {
                text = warning.first
                setTextColor(ext.warningColor(warning.second.severity)); textSize = ui.textSize("subhead")
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
    }
}

// ---------------- The trip card ----------------

/**
 * The card on a trip's page: the leave-later hint when there is one, then a tile for each place the rider is outside,
 * and the same places as icons on the trip's map.
 */
class TripCard(private val ext: WeatherExtension, private val c: Context, private val trip: JSONObject) {

    private fun t(en: String, he: String) = ext.t(en, he)

    fun view(): View {
        val ui = ext.kav.ui()
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(c).apply {
            text = t("Weather along the route", "מזג האוויר לאורך המסלול")
            setTextColor(ui.color("text")); textSize = ui.textSize("headline"); typeface = Typeface.DEFAULT_BOLD
        })
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(body)
        body.addView(note(t("Loading the IMS forecast…", "טוען את תחזית השירות המטאורולוגי…")))
        ext.cardPool.execute {
            try {
                val items = ext.tripWeather(trip)
                val hint = ext.easeHint(items)
                ext.main.post {
                    try { body.removeAllViews(); fill(body, items, hint); showOnMap(items) }
                    catch (e: Exception) { ext.kav.log("trip card view: $e") }
                }
            } catch (e: Exception) {
                ext.kav.log("trip card: $e")
                ext.main.post { body.removeAllViews(); body.addView(note(t("Couldn't load the forecast", "לא ניתן לטעון את התחזית"))) }
            }
        }
        return ui.card(c, box)
    }

    private fun note(s: String): TextView = TextView(c).apply {
        val ui = ext.kav.ui()
        text = s
        setTextColor(ui.color("textSecondary")); textSize = ui.textSize("body")
        setPadding(0, ext.pxOf(ui.dp("gap2")), 0, 0)
    }

    private fun fill(body: LinearLayout, items: List<Weather>, hint: String?) {
        val ui = ext.kav.ui()
        if (items.isEmpty()) {
            body.addView(note(t("No IMS forecast for the places on this route", "אין תחזית של השירות המטאורולוגי למקומות במסלול הזה")))
            return
        }
        if (hint != null) {
            body.addView(TextView(c).apply {
                text = hint
                setTextColor(ui.color("accent")); textSize = ui.textSize("subhead")
                setPadding(0, ext.pxOf(ui.dp("gap2")), 0, 0)
            })
        }
        body.addView(TextView(c).apply {
            text = t("IMS forecast for each place, at the time you're there", "תחזית השירות המטאורולוגי לכל מקום, בשעה שתהיו בו")
            setTextColor(ui.color("textSecondary")); textSize = ui.textSize("caption")
            setPadding(0, ext.pxOf(ui.dp("gap2")), 0, ext.pxOf(ui.dp("gap2")))
        })
        val scroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for (w in items) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            lp.marginEnd = ext.pxOf(ui.dp("gap3"))
            row.addView(tile(w), lp)
        }
        scroll.addView(row)
        body.addView(scroll, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun caption(s: String, role: String = "textTertiary") = TextView(c).apply {
        val ui = ext.kav.ui()
        text = s; setTextColor(ui.color(role)); textSize = ui.textSize("caption")
    }

    /** One place's tile. */
    private fun tile(w: Weather): View {
        val ui = ext.kav.ui()
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ext.pxOf(13f), ext.pxOf(11f), ext.pxOf(13f), ext.pxOf(11f))
            background = GradientDrawable().apply {
                cornerRadius = ui.dp("rTile") * c.resources.displayMetrics.density
                setColor(ui.color("fill"))
            }
        }
        card.addView(TextView(c).apply {
            text = w.city?.name(ext.hebrew) ?: w.point.stopName
            setTextColor(ui.color("text")); textSize = ui.textSize("subhead"); typeface = Typeface.DEFAULT_BOLD
        })

        if (w.status != Weather.Status.OK) {
            card.addView(caption(when (w.status) {
                Weather.Status.NO_STATION -> t("No IMS forecast nearby", "אין תחזית של השירות המטאורולוגי בקרבת מקום")
                Weather.Status.NETWORK -> t("No connection to IMS", "אין חיבור לשירות המטאורולוגי")
                else -> t("No forecast yet for this time", "אין עדיין תחזית לשעה הזו")
            }, "problem"))
            w.warning?.let { card.addView(caption(t("Warning: ", "אזהרה: ") + it.title.ifEmpty { it.text }.take(60)).apply { setTextColor(ext.warningColor(it.severity)) }) }
            return card
        }

        val iconTemp = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        iconTemp.addView(ImageView(c).apply {
            setImageBitmap(drawIcon(ext.iconName(w), ext.pxOf(22f)))
            contentDescription = ext.desc(w)
            setPaddingRelative(0, 0, ext.pxOf(5f), 0)
        })
        iconTemp.addView(TextView(c).apply {
            text = ext.tempText(w)
            setTextColor(ui.color("text")); textSize = ui.textSize("title"); typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(iconTemp)
        card.addView(caption(ext.desc(w), "textSecondary"))
        w.rainChance?.takeIf { it > 0 }?.let { card.addView(caption(ext.rainText(it), "accent")) }
        card.addView(caption(ext.dayLabel(w.point.time)))
        card.addView(caption(t("You're there at ${ext.clock(w.point.time)}", "תהיו שם ב־${ext.clock(w.point.time)}")))
        if (w.daily) card.addView(caption(t("Daily forecast", "תחזית יומית")))
        ext.staleText(w)?.let { card.addView(caption(it)) }

        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { Detail(ext).show(w, c) }
        return w.warning?.let { warningFrame(card, it) } ?: card
    }

    /** A tile with an IMS warning: a severity line on its leading edge and a "!" badge on its far top corner. */
    private fun warningFrame(card: View, w: IMS.Warning): View {
        val color = ext.warningColor(w.severity)
        val frame = FrameLayout(c)
        frame.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = ext.pxOf(3f)
        })
        frame.addView(View(c).apply { setBackgroundColor(color) },
            FrameLayout.LayoutParams(ext.pxOf(3f), ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.START })
        val badge = TextView(c).apply {
            text = "!"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = ext.kav.ui().textSize("caption")
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            contentDescription = t("IMS weather warning", "אזהרת מזג אוויר של השירות המטאורולוגי")
        }
        val bs = ext.pxOf(18f)
        frame.addView(badge, FrameLayout.LayoutParams(bs, bs).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = ext.pxOf(2f); topMargin = ext.pxOf(2f)
        })
        return frame
    }

    /** The places as icons on the trip's map, replacing the last trip's. A tap opens the same detail as a tile. */
    private fun showOnMap(items: List<Weather>) {
        val map = ext.kav.map()
        map.clear()
        ext.mapItems.clear()
        val ui = ext.kav.ui()
        val size = ext.pxOf(34f)
        val bg = ui.color("surfaceRaised")
        val ring = ui.color("separator")
        for ((i, w) in items.withIndex()) {
            if (w.status != Weather.Status.OK) continue
            val id = "w$i"
            ext.mapItems[id] = w
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = bg; cv.drawCircle(size / 2f, size / 2f, size / 2f - 1, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            p.color = w.warning?.let { ext.warningColor(it.severity) } ?: ring
            cv.drawCircle(size / 2f, size / 2f, size / 2f - 2, p)
            val inner = (size * 0.7f).toInt()
            cv.drawBitmap(drawIcon(ext.iconName(w), inner), (size - inner) / 2f, (size - inner) / 2f, null)
            map.marker(id, "trip", w.point.lat, w.point.lon, 0, bmp)
        }
    }
}

/** dp to px, for Kav's sizes. */
fun WeatherExtension.pxOf(dp: Float): Int = (dp * kav.context().resources.displayMetrics.density).toInt()

// ---------------- The stop board row ----------------

/**
 * One row at the top of a stop's departures (under its buttons): the weather there now, the next rain worth
 * mentioning in the coming 6 hours, and an IMS warning if one holds. The stop's place comes from Kav's own
 * timetable; with none (an extension's stop, no timetable yet, a Kav this doesn't fit) there's no row at all.
 */
class StopRow(private val ext: WeatherExtension, private val c: Context, private val about: JSONObject) {

    private fun t(en: String, he: String) = ext.t(en, he)

    fun view(): View {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val ref = about.optString("stop")
        if (!ref.startsWith("il.mot/gtfs/stop/")) return box
        val code = ref.substringAfterLast('/').toIntOrNull() ?: return box
        val stopName = about.optString("name").takeIf { it.isNotEmpty() && it != "null" } ?: ""
        ext.cardPool.execute {
            try {
                val pt = ext.bridge.stopPoint(code) ?: return@execute
                val city = ext.nearestCity(pt.first, pt.second) ?: return@execute
                val now = System.currentTimeMillis() / 1000
                val point = WeatherExtension.RoutePoint(city, stopName, now, pt.first, pt.second)
                val w = ext.weatherFor(point)
                if (w.status != Weather.Status.OK && w.warning == null) return@execute
                val rain = nextRain(city, now)
                ext.main.post {
                    try { fill(box, w, rain) } catch (e: Exception) { ext.kav.log("stop row view: $e") }
                }
            } catch (e: Exception) {
                ext.kav.log("stop row: $e")
            }
        }
        return box
    }

    /** The first hour within 6 hours whose rain chance reaches the rider's threshold: (time, chance), or null. */
    private fun nextRain(city: IMS.City, now: Long): Pair<Long, Int>? {
        val f = ext.forecast(city.lid) ?: return null
        for ((at, h) in f.hours) {
            if (at < now - 1800) continue
            if (at > now + 6 * 3600) break
            val chance = h.optString("rain_chance").toIntOrNull() ?: continue
            if (chance >= ext.units.rainMin) return Pair(at, chance)
        }
        return null
    }

    private fun fill(box: LinearLayout, w: Weather, rain: Pair<Long, Int>?) {
        val ui = ext.kav.ui()
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (w.status == Weather.Status.OK) {
            row.addView(ImageView(c).apply {
                setImageBitmap(drawIcon(ext.iconName(w), ext.pxOf(26f)))
                contentDescription = ext.desc(w)
                setPaddingRelative(0, 0, ext.pxOf(10f), 0)
            })
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (w.status == Weather.Status.OK) {
            val d = ext.desc(w).let { if (ext.hebrew) it else it.replaceFirstChar { ch -> ch.lowercaseChar() } }
            texts.addView(TextView(c).apply {
                text = t("Now ${ext.tempText(w)}, $d", "עכשיו ${ext.tempText(w)}, $d")
                setTextColor(ui.color("text")); textSize = ui.textSize("subhead"); typeface = Typeface.DEFAULT_BOLD
            })
            if (rain != null) {
                val at = if (rain.first <= System.currentTimeMillis() / 1000 + 1800) t("now", "עכשיו")
                         else t("at ${ext.clock(rain.first)}", "ב־${ext.clock(rain.first)}")
                texts.addView(TextView(c).apply {
                    text = ext.rainText(rain.second) + " " + at
                    setTextColor(ui.color("textSecondary")); textSize = ui.textSize("caption")
                })
            }
        }
        w.warning?.let { wa ->
            texts.addView(TextView(c).apply {
                text = t("IMS warning: ", "אזהרת השירות המטאורולוגי: ") + wa.title.ifEmpty { wa.text }.take(80)
                setTextColor(ext.warningColor(wa.severity)); textSize = ui.textSize("caption"); typeface = Typeface.DEFAULT_BOLD
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (w.status == Weather.Status.OK) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { Detail(ext).show(w, c) }
        }
        box.addView(ui.card(c, row))
    }
}

// ---------------- The detail sheet ----------------

/** Kav's own sheet over the screen: one place's full forecast. Main thread. */
class Detail(private val ext: WeatherExtension) {

    private fun t(en: String, he: String) = ext.t(en, he)

    fun show(w: Weather, c: Context) {
        val ui = ext.kav.ui()
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val place = w.city?.name(ext.hebrew) ?: w.point.stopName

        if (w.point.stopName.isNotEmpty() && w.point.stopName != place) {
            box.addView(TextView(c).apply {
                text = t("Stop: ", "תחנה: ") + w.point.stopName
                setTextColor(ui.color("textSecondary")); textSize = ui.textSize("caption")
            })
        }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(c).apply {
            setImageBitmap(drawIcon(ext.iconName(w), ext.pxOf(34f)))
            contentDescription = ext.desc(w)
            setPaddingRelative(0, 0, ext.pxOf(8f), 0)
        })
        head.addView(TextView(c).apply {
            text = ext.tempText(w)
            setTextColor(ui.color("text")); textSize = ui.textSize("largeTitle"); typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(head)
        box.addView(TextView(c).apply {
            text = ext.desc(w)
            setTextColor(ui.color("textSecondary")); textSize = ui.textSize("subhead")
            setPadding(0, 0, 0, ext.pxOf(ui.dp("gap3")))
        })

        // The warning: its first two lines, the rest on a tap.
        w.warning?.let { wa ->
            val all = listOf(wa.title, wa.text).filter { it.isNotEmpty() }.distinct().joinToString(". ")
            val preview = TextView(c).apply {
                text = all.ifEmpty { t("IMS weather warning", "אזהרת מזג אוויר של השירות המטאורולוגי") }
                setTextColor(ext.warningColor(wa.severity)); textSize = ui.textSize("body"); typeface = Typeface.DEFAULT_BOLD
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, 0, ext.pxOf(ui.dp("gap1")))
            }
            preview.setOnClickListener { preview.maxLines = if (preview.maxLines == 2) Integer.MAX_VALUE else 2 }
            box.addView(preview)
            box.addView(TextView(c).apply {
                text = t("Tap to read the whole warning", "הקישו לקריאת האזהרה המלאה")
                setTextColor(ui.color("textTertiary")); textSize = ui.textSize("caption")
                setPadding(0, 0, 0, ext.pxOf(ui.dp("gap2")))
            })
        }

        if (w.daily) {
            box.addView(TextView(c).apply {
                text = t("The hourly forecast isn't out yet, so this is the day's forecast.",
                    "התחזית לפי שעות עוד לא פורסמה, אז זו התחזית ליום כולו.")
                setTextColor(ui.color("textTertiary")); textSize = ui.textSize("caption")
                setPadding(0, 0, 0, ext.pxOf(ui.dp("gap2")))
            })
        }
        for (r in rows(w, c)) box.addView(r)
        box.setPadding(ext.pxOf(4f), ext.pxOf(4f), ext.pxOf(4f), ext.pxOf(ui.dp("gap4")))
        ui.sheet(c, place, box)
    }

    private fun rows(w: Weather, c: Context): List<View> {
        val ui = ext.kav.ui()
        val miss = t("Not given by IMS", "לא נמסר")
        val out = ArrayList<View>()
        fun row(label: String, value: String?) { out.add(ui.row(c, null, label, value?.takeIf { it.isNotEmpty() } ?: miss, null)) }

        row(t("You're there", "תהיו שם"), ext.dayLabel(w.point.time) + ", " + ext.clock(w.point.time))
        w.forecastAt?.let { row(t("Forecast for", "תחזית לשעה"), ext.clock(it)) }
        if (!w.daily) {
            row(t("Feels like", "מורגש כמו"), w.feelsC?.let { ext.temp(it) })
            row(t("Rain chance", "סיכוי לגשם"), w.rainChance?.let { "$it%" })
            row(t("Humidity", "לחות"), w.humidity?.let { "$it%" })
            row(t("Wind", "רוח"), w.windKmh?.let { k ->
                val dir = IMS.windDirectionName(w.windDir, ext.hebrew)
                if (dir.isEmpty()) ext.speed(k) else "${ext.speed(k)}, $dir"
            })
            row(t("Gusts", "משבי רוח"), w.gustKmh?.let { ext.speed(it) })
            row(t("UV index", "מדד קרינת UV"), w.uv)
        } else {
            row(t("Max UV (day)", "קרינת UV מרבית (יומית)"), w.uvMax)
        }
        ext.staleText(w)?.let { row(t("Forecast", "תחזית"), it) }
        row(t("Source", "מקור"), t("IMS (ims.gov.il)", "השירות המטאורולוגי"))
        return out
    }
}
