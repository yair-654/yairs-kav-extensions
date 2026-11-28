package yair.almostshabbat

import kotlin.math.cos

/**
 * Every locality in Israel, from the Central Bureau of Statistics' list, built in (LocalityData.kt). The phone finds the
 * one nearest a trip's destination, offline; only that locality's own point is ever sent to Hebcal.
 *
 * Jerusalem and Haifa are big, so a destination at their edge can be nearer a neighbouring yishuv's centre than their
 * own. Each of them has extra points spread over its neighbourhoods, which count as the city.
 */
internal class Place(val id: Int, val he: String, val en: String, val lat: Double, val lon: Double, val minutes: Int) {
    fun name(he: Boolean) = if (he) this.he else this.en
}

internal object Places {
    /** Hebcal's own custom: 40 minutes in Jerusalem, 30 in Haifa and Zikhron Ya'akov, 20 everywhere else in Israel. */
    const val JERUSALEM = 3000
    const val HAIFA = 4000
    const val ZIKHRON = 9300

    fun minutesFor(id: Int) = when (id) { JERUSALEM -> 40; HAIFA, ZIKHRON -> 30; else -> 20 }

    private class Point(val lat: Double, val lon: Double, val place: Place)

    private val index = HashMap<Int, Place>()

    private val points: List<Point> by lazy {
        val out = ArrayList<Point>(1400)
        for (chunk in LocalityData.CHUNKS) {
            for (line in chunk.split('\n')) {
                val f = line.split('|')
                if (f.size < 5) continue
                val id = f[0].toInt()
                val place = Place(id, f[1], f[2], f[3].toDouble(), f[4].toDouble(), minutesFor(id))
                index[id] = place
                out += Point(place.lat, place.lon, place)
            }
        }
        // The neighbourhoods of the two big cities with their own custom, so their edges count as the city.
        for ((id, list) in listOf(JERUSALEM to LocalityData.JERUSALEM_POINTS, HAIFA to LocalityData.HAIFA_POINTS)) {
            val city = index[id] ?: continue
            for (p in list) out += Point(p[0], p[1], city)
        }
        out
    }

    /** The locality nearest a place, or null when the list is somehow empty. */
    fun nearest(lat: Double, lon: Double): Place? {
        if (lat == 0.0 && lon == 0.0) return null
        val k = cos(Math.toRadians(lat))
        var best: Point? = null
        var bestD = Double.MAX_VALUE
        for (p in points) {
            val dy = p.lat - lat
            val dx = (p.lon - lon) * k
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = p }
        }
        return best?.place
    }

    fun byId(id: Int): Place? {
        points // the list is read the first time it's needed
        return index[id]
    }
}
