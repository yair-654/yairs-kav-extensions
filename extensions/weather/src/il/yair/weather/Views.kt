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
import il.yair.weather.IMS.Level
import il.yair.weather.WeatherExtension.Weather
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// ---------------- Shared bits ----------------

/** Whether Kav is in a dark look now, from its background color. Main thread. */
fun WeatherExtension.isDark(): Boolean {
    val bg = kav.ui().color("bg")
    val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255
    return lum < 0.5
}

/** A warning's color by its level: red, orange, yellow. On a light look, orange and yellow are darkened so text stays readable. */
fun WeatherExtension.warningColor(level: Level): Int {
    val dark = isDark()
    return when (level) {
        Level.RED -> 0xFFE53935.toInt()
        Level.ORANGE -> if (dark) 0xFFFFA726.toInt() else 0xFFE65100.toInt()
        Level.YELLOW -> if (dark) 0xFFFDD835.toInt() else 0xFF8D6E00.toInt()
    }
}

/** The icon for a place's weather: its kind, with a moon instead of the sun after dark, and Cold as IMS says it. */
fun WeatherExtension.iconName(w: Weather): String = when (kindOf(w)) {
    Kind.CLEAR -> if (w.night) "moon" else "sun"
    Kind.PARTLY -> if (w.night) "cloud_moon" else "cloud_sun"
    Kind.CLOUDY -> "cloud"
    Kind.RAIN -> "rain"
    Kind.THUNDERSTORM -> "storm"
    Kind.SNOW -> "snow"
    Kind.FOG -> "fog"
    Kind.HEAT -> "heat"
    Kind.MUGGY -> "muggy"
    Kind.COLD -> when (w.code) {
        IMS.EXTREMELY_COLD -> "cold_extreme"
        IMS.FROST -> "frost"
        else -> "cold"
    }
    Kind.WIND -> "wind"
    Kind.DUST -> "dust"
}

/** An icon for a place's weather, in Kav's look now. Main thread. */
fun WeatherExtension.icon(w: Weather, sizePx: Int): Bitmap = drawIcon(iconName(w), sizePx, isDark())

private val iconCache = ConcurrentHashMap<String, Bitmap>()

/**
 * Draws a condition icon on a 24-unit grid. Plain colors that read on light and dark looks alike, except ice, which is
 * a deeper blue on a light look so it doesn't fade into white.
 */
fun drawIcon(name: String, sizePx: Int, dark: Boolean): Bitmap {
    val cacheKey = "$name-$sizePx-$dark"
    iconCache[cacheKey]?.let { return it }
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val d = sizePx / 24f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val sun = 0xFFFDB813.toInt()
    val moon = 0xFFFFE082.toInt()
    val cloud = 0xFF90A4AE.toInt()
    val drop = 0xFF4FC3F7.toInt()
    val hot = 0xFFE53935.toInt()
    val cold = 0xFF2979FF.toInt()
    val ice = if (dark) 0xFF81D4FA.toInt() else 0xFF039BE5.toInt()
    val ground = 0xFF8D9EA8.toInt()

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
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = width * d; p.strokeCap = Paint.Cap.ROUND
        cv.drawLine(x1 * d, y1 * d, x2 * d, y2 * d, p)
    }
    /** A thermometer whose tube is centred on x, filled from its bulb up to top. */
    fun thermometer(x: Float, color: Int, top: Float) {
        p.color = color; p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d
        cv.drawRoundRect(RectF((x - 2f) * d, 3f * d, (x + 2f) * d, 16f * d), 2f * d, 2f * d, p)
        p.style = Paint.Style.FILL
        cv.drawCircle(x * d, 18.5f * d, 3.5f * d, p)
        cv.drawRoundRect(RectF((x - 0.8f) * d, top * d, (x + 0.8f) * d, 17f * d), 0.8f * d, 0.8f * d, p)
    }
    /** A six-armed snowflake: three lines through its centre, each end with two small barbs. */
    fun flake(x: Float, y: Float, r: Float, color: Int, width: Float) {
        for (i in 0..2) {
            val a = Math.toRadians(90.0 + i * 60)
            val dx = (Math.cos(a) * r).toFloat()
            val dy = (Math.sin(a) * r).toFloat()
            line(x - dx, y - dy, x + dx, y + dy, color, width)
            for (s in intArrayOf(1, -1)) {
                val ex = x + s * dx * 0.62f
                val ey = y + s * dy * 0.62f
                for (b in intArrayOf(35, -35)) {
                    val ba = a + Math.toRadians(b.toDouble()) + if (s < 0) Math.PI else 0.0
                    line(ex, ey, ex + (Math.cos(ba) * r * 0.35).toFloat(), ey + (Math.sin(ba) * r * 0.35).toFloat(), color, width * 0.8f)
                }
            }
        }
    }
    /** A water drop: a round bottom of radius r under a point, its widest at y. */
    fun dropAt(x: Float, y: Float, r: Float) {
        p.style = Paint.Style.FILL; p.color = drop
        cv.drawCircle(x * d, (y + r * 0.5f) * d, r * d, p)
        val tip = Path()
        tip.moveTo((x - r * 0.85f) * d, y * d); tip.lineTo((x + r * 0.85f) * d, y * d); tip.lineTo(x * d, (y - r * 1.9f) * d)
        tip.close(); cv.drawPath(tip, p)
    }

    when (name) {
        "sun" -> {
            p.color = sun; cv.drawCircle(12f * d, 12f * d, 5f * d, p)
            for (i in 0..7) {
                val a = Math.toRadians(i * 45.0)
                line((12 + Math.cos(a) * 7.5).toFloat(), (12 + Math.sin(a) * 7.5).toFloat(),
                    (12 + Math.cos(a) * 9.8).toFloat(), (12 + Math.sin(a) * 9.8).toFloat(), sun, 1.6f)
            }
        }
        "moon" -> moonAt(12f, 12f, 7f)
        "cloud_sun" -> { p.color = sun; cv.drawCircle(16f * d, 8f * d, 4f * d, p); cloudAt(8f) }
        "cloud_moon" -> { moonAt(16f, 8f, 4.2f); cloudAt(8f) }
        "cloud" -> cloudAt(7f)
        "rain" -> {
            cloudAt(5f)
            for (x in listOf(8f, 12f, 16f)) line(x, 17.5f, x - 1.5f, 21.5f, drop, 1.8f)
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
            for ((x, y) in listOf(7.5f to 19.5f, 12f to 21f, 16.5f to 19.5f)) {
                line(x - 1.6f, y, x + 1.6f, y, drop, 1.4f)
                line(x, y - 1.6f, x, y + 1.6f, drop, 1.4f)
            }
        }
        "fog" -> {
            cloudAt(3.5f)
            for (y in listOf(16.5f, 19f, 21.5f)) line(5f, y, 19f, y, 0xFFB0BEC5.toInt(), 1.6f)
        }
        "heat" -> {
            // a thermometer beside a small sun
            p.style = Paint.Style.FILL; p.color = sun; cv.drawCircle(17f * d, 7f * d, 3.5f * d, p)
            thermometer(9f, hot, 8f)
        }
        "muggy" -> {
            // the heat thermometer with drops beside it instead of the sun
            thermometer(8f, hot, 9f)
            dropAt(16.5f, 6.5f, 2f); dropAt(19f, 13f, 1.6f); dropAt(14.5f, 14.5f, 1.4f)
        }
        "cold" -> {
            thermometer(8f, cold, 13f)
            flake(17f, 8.5f, 4.2f, ice, 1.3f)
        }
        "cold_extreme" -> {
            // colder: the column lower, and two snowflakes
            thermometer(8f, cold, 15.5f)
            flake(16.5f, 7f, 3.6f, ice, 1.2f)
            flake(19f, 15.5f, 2.8f, ice, 1.1f)
        }
        "frost" -> {
            // ice growing up from the ground, with a small snowflake above: on the ground, not falling like snow
            line(3f, 20f, 21f, 20f, ground, 2f)
            for ((x, h) in listOf(6f to 6f, 12f to 9f, 18f to 6.5f)) {
                line(x, 20f, x, 20f - h, ice, 1.8f)
                line(x, 20f - h * 0.55f, x - 2f, 20f - h * 0.55f - 2f, ice, 1.5f)
                line(x, 20f - h * 0.55f, x + 2f, 20f - h * 0.55f - 2f, ice, 1.5f)
            }
            flake(18f, 5.5f, 3.4f, ice, 1.5f)
        }
        "wind" -> {
            p.color = 0xFF90A4AE.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.strokeCap = Paint.Cap.ROUND
            cv.drawPath(Path().apply { moveTo(3f * d, 8f * d); lineTo(15f * d, 8f * d); quadTo(19f * d, 8f * d, 19f * d, 5f * d) }, p)
            cv.drawPath(Path().apply { moveTo(3f * d, 13f * d); lineTo(19f * d, 13f * d) }, p)
            cv.drawPath(Path().apply { moveTo(3f * d, 18f * d); lineTo(13f * d, 18f * d); quadTo(17f * d, 18f * d, 17f * d, 21f * d) }, p)
        }
        "dust" -> {
            // a pale sun behind dotted haze
            p.style = Paint.Style.FILL
            p.color = 0x99FDB813.toInt(); cv.drawCircle(12f * d, 9f * d, 5f * d, p)
            p.color = 0xFFC8A165.toInt()
            for (row in 0..2) for (col in 0..4) cv.drawCircle((4f + col * 4f + (row % 2) * 2f) * d, (14f + row * 3.2f) * d, 1.1f * d, p)
        }
    }
    iconCache[cacheKey] = bmp
    return bmp
}

// ---------------- The results line ----------------

/**
 * The line under each search result: one line for the trip's weather ("Rain expected on this trip · 14–18°, 40%
 * chance of rain") and, when IMS warns about a place on it, one more line for the warning. Nothing at all when there's
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
                setTextColor(ext.warningColor(warning.second.level)); textSize = ui.textSize("subhead")
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
    }
}

// ---------------- The trip card ----------------

/**
 * The card on a trip's page: the leave-later hint when there is one, then a tile for each place the rider is outside,
 * and the same places as icons on the trip map.
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
                    try { body.removeAllViews(); fill(body, items, hint); ext.showOnMap(items) }
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
            text = ext.placeName(w)
            setTextColor(ui.color("text")); textSize = ui.textSize("subhead"); typeface = Typeface.DEFAULT_BOLD
        })

        if (w.status != Weather.Status.OK) {
            card.addView(caption(when (w.status) {
                Weather.Status.NO_STATION -> t("No IMS forecast nearby", "אין תחזית של השירות המטאורולוגי בקרבת מקום")
                Weather.Status.NETWORK -> t("No connection to IMS", "אין חיבור לשירות המטאורולוגי")
                else -> t("No forecast yet for this time", "אין עדיין תחזית לשעה הזו")
            }, "problem"))
            w.warning?.let { card.addView(caption(ext.warningShort(it)).apply { setTextColor(ext.warningColor(it.level)) }) }
            return card
        }

        val iconTemp = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        iconTemp.addView(ImageView(c).apply {
            setImageBitmap(ext.icon(w, ext.pxOf(22f)))
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

    /** A tile with an IMS warning: a colored line on its leading edge and a "!" badge on its far top corner. */
    private fun warningFrame(card: View, w: IMS.Warning): View {
        val color = ext.warningColor(w.level)
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
            contentDescription = ext.warningShort(w)
        }
        val bs = ext.pxOf(18f)
        frame.addView(badge, FrameLayout.LayoutParams(bs, bs).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = ext.pxOf(2f); topMargin = ext.pxOf(2f)
        })
        return frame
    }
}

// ---------------- The trip map ----------------

/**
 * The places as small round icons on the trip map (on a trip's page, never on Live), in place of whatever was there.
 * Each icon sits on its place (Kav centres an icon on its place and gives extensions no offset), so it covers the
 * stop's own mark there; a rider who'd rather tap stops turns the icons off in Weather's settings. Its ring is the
 * warning's color when one holds there. A tap opens the details. Main thread.
 */
fun WeatherExtension.drawMarkers(items: List<Weather>) {
    val map = kav.map()
    map.clear()
    mapItems.clear()
    val ui = kav.ui()
    val size = pxOf(26f)
    val bg = ui.color("surfaceRaised")
    val ring = ui.color("separator")
    val stroke = pxOf(1.5f).toFloat().coerceAtLeast(2f)
    for ((i, w) in items.withIndex()) {
        if (w.status != Weather.Status.OK) continue
        val id = "w$i"
        mapItems[id] = w
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.style = Paint.Style.FILL; p.color = bg
        cv.drawCircle(r, r, r - 1, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.color = w.warning?.let { warningColor(it.level) } ?: ring
        cv.drawCircle(r, r, r - stroke / 2f, p)
        val inner = (size * 0.7f).toInt()
        cv.drawBitmap(icon(w, inner), (size - inner) / 2f, (size - inner) / 2f, null)
        map.marker(id, "trip", w.point.lat, w.point.lon, 0, bmp)
    }
}

/** dp to px, for Kav's sizes. */
fun WeatherExtension.pxOf(dp: Float): Int = (dp * kav.context().resources.displayMetrics.density).toInt()

// ---------------- The stop board row ----------------

/**
 * One card at the top of a stop's departures (under its buttons): the weather there now, the next rain worth
 * mentioning in the coming 6 hours, and an IMS warning if one holds. The stop's place comes from Kav's own
 * timetable; with none (an extension's stop, no timetable yet) there's no card at all. Kav's board puts views only 4 dp
 * (gap1) from the screen's edges, but its own cards above sit 16 dp (gap4) in, so the card gets the difference on each
 * side to line up with them. It stays hidden until there's something to show, and a tap anywhere on it opens the
 * details.
 */
class StopRow(private val ext: WeatherExtension, private val c: Context, private val about: JSONObject) {

    private fun t(en: String, he: String) = ext.t(en, he)

    fun view(): View {
        val ui = ext.kav.ui()
        val content = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val card = ui.card(c, content)
        val side = ext.pxOf(ui.dp("gap4") - ui.dp("gap1"))
        val frame = FrameLayout(c).apply { setPadding(side, 0, side, 0); visibility = View.GONE; addView(card) }
        val ref = about.optString("stop")
        if (!ref.startsWith("il.mot/gtfs/stop/")) return frame
        val code = ref.substringAfterLast('/').toIntOrNull() ?: return frame
        val stopName = about.optString("name").takeIf { it.isNotEmpty() && it != "null" } ?: ""
        ext.cardPool.execute {
            try {
                val pt = ext.bridge.stopPoint(code) ?: return@execute
                val city = ext.nearestCity(pt.first, pt.second) ?: return@execute
                val now = System.currentTimeMillis() / 1000
                val point = WeatherExtension.RoutePoint(city, stopName, now, pt.first, pt.second)
                val w = ext.withWarning(ext.weatherFor(point))
                if (w.status != Weather.Status.OK && w.warning == null) return@execute
                val rain = nextRain(city, now)
                ext.main.post {
                    try { fill(frame, card, content, w, rain) } catch (e: Exception) { ext.kav.log("stop row view: $e") }
                }
            } catch (e: Exception) {
                ext.kav.log("stop row: $e")
            }
        }
        return frame
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

    private fun fill(frame: View, card: View, row: LinearLayout, w: Weather, rain: Pair<Long, Int>?) {
        val ui = ext.kav.ui()
        if (w.status == Weather.Status.OK) {
            row.addView(ImageView(c).apply {
                setImageBitmap(ext.icon(w, ext.pxOf(26f)))
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
                text = ext.warningShort(wa)
                setTextColor(ext.warningColor(wa.level)); textSize = ui.textSize("caption"); typeface = Typeface.DEFAULT_BOLD
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (w.status == Weather.Status.OK || w.warning != null) {
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener { Detail(ext).show(w, c) }
        }
        frame.visibility = View.VISIBLE
    }
}

// ---------------- The detail sheet ----------------

/** Kav's own sheet over the screen: one place's full forecast, and the whole IMS warning when one holds. Main thread. */
class Detail(private val ext: WeatherExtension) {

    private fun t(en: String, he: String) = ext.t(en, he)

    fun show(w: Weather, c: Context) {
        val ui = ext.kav.ui()
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val place = ext.placeName(w)

        if (w.point.stopName.isNotEmpty() && w.point.stopName != place) {
            box.addView(TextView(c).apply {
                text = t("Stop: ", "תחנה: ") + w.point.stopName
                setTextColor(ui.color("textSecondary")); textSize = ui.textSize("caption")
            })
        }
        if (w.status == Weather.Status.OK) {
            val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(ImageView(c).apply {
                setImageBitmap(ext.icon(w, ext.pxOf(34f)))
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
        }

        // The whole warning, at once.
        w.warning?.let { wa ->
            box.addView(TextView(c).apply {
                text = wa.text(ext.hebrew)
                setTextColor(ext.warningColor(wa.level)); textSize = ui.textSize("body"); typeface = Typeface.DEFAULT_BOLD
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
        if (w.status == Weather.Status.OK) for (r in rows(w, c)) box.addView(r)
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
            row(t("Feels like", "טמפרטורה מורגשת"), w.feelsC?.let { ext.temp(it) })
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
