package il.yair.routeweather

import kav.kavapp.api.Kav
import java.lang.reflect.Method

/**
 * The two things this extension reads from Kav's own code, by name, through kavClassLoader() (API 4). Both are
 * guarded: when a future Kav renames them, the extension carries on (the phone's clock setting, no stop row) and says
 * so once in its log.
 *
 * From Kav's source (github.com/ImNoammm/Kav):
 *  - ui/Common.kt: `val CLOCK get() = if (Shown.twelveHour) "h:mm a" else "HH:mm"`, the pattern every time in Kav
 *    is formatted with. A top-level property, so it's the static uk.noammm.kav.ui.CommonKt.getCLOCK().
 *  - MainActivity.kt: `object Loaded { val net: Net? }`, the timetable on the phone; data/Net.kt gives each stop's
 *    `code: IntArray`, `lat: DoubleArray`, `lon: DoubleArray` by its index.
 */
class KavBridge(private val kav: Kav) {

    private var clockMethod: Method? = null
    @Volatile private var clockBroken = false

    /** Kav's clock pattern now ("HH:mm" or "h:mm a"), read each time so a change in Kav's settings shows at once. */
    fun clockPattern(): String {
        if (!clockBroken) {
            try {
                val m = clockMethod ?: Class.forName("uk.noammm.kav.ui.CommonKt", true, kav.kavClassLoader())
                    .getMethod("getCLOCK").also { clockMethod = it }
                val p = m.invoke(null) as? String
                if (p != null && p.isNotEmpty()) return p
            } catch (e: Throwable) {
                clockBroken = true
                kav.log("can't read Kav's clock setting in this Kav, using the phone's: $e")
            }
        }
        return if (android.text.format.DateFormat.is24HourFormat(kav.context())) "HH:mm" else "h:mm a"
    }

    private val stopLock = Any()
    private var stopsOf: Any? = null                       // the Net the index below was built from
    private var index: HashMap<Int, Int>? = null
    private var lats: DoubleArray? = null
    private var lons: DoubleArray? = null
    @Volatile private var stopsBroken = false

    /** A ministry stop's place from Kav's timetable, (lat, lon), or null (no timetable yet, unknown stop, or a Kav this doesn't fit). */
    fun stopPoint(code: Int): Pair<Double, Double>? {
        if (stopsBroken || code <= 0) return null
        return try {
            val cl = kav.kavClassLoader()
            val loadedClass = Class.forName("uk.noammm.kav.Loaded", true, cl)
            val loaded = loadedClass.getField("INSTANCE").get(null)
            val net = loadedClass.getMethod("getNet").invoke(loaded) ?: return null
            synchronized(stopLock) {
                if (net !== stopsOf) {
                    val nc = net.javaClass
                    val codes = nc.getMethod("getCode").invoke(net) as IntArray
                    lats = nc.getMethod("getLat").invoke(net) as DoubleArray
                    lons = nc.getMethod("getLon").invoke(net) as DoubleArray
                    val m = HashMap<Int, Int>(codes.size * 2)
                    for (i in codes.indices) m.putIfAbsent(codes[i], i)
                    index = m
                    stopsOf = net
                }
                val i = index?.get(code) ?: return null
                val la = lats!![i]
                val lo = lons!![i]
                if (la == 0.0 && lo == 0.0) null else Pair(la, lo)
            }
        } catch (e: Throwable) {
            stopsBroken = true
            kav.log("can't read Kav's stops in this Kav, so stops get no weather row: $e")
            null
        }
    }
}
