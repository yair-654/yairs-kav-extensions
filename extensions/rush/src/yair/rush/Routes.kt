package yair.rush

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading routes as Kav gives them (a route's legs, each a walk, a wait, a ride...), and the walk before a ride.
 */
internal object Routes {
    fun legs(trip: JSONObject?): List<JSONObject> {
        val a = trip?.optJSONArray("legs") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    fun kind(leg: JSONObject) = leg.optString("kind")

    /** A leg's own length in seconds. */
    fun seconds(leg: JSONObject) = (leg.optLong("arr") - leg.optLong("dep")).coerceAtLeast(0)

    /** When a ride leaves its stop: its live time when Kav has one. */
    fun boards(ride: JSONObject): Long = if (ride.isNull("liveDep")) ride.optLong("dep") else ride.optLong("liveDep")

    /** When a ride gets to the stop it's left at: its live time when Kav has one. */
    fun alights(ride: JSONObject): Long = if (ride.isNull("liveArr")) ride.optLong("arr") else ride.optLong("liveArr")

    fun tripOf(ride: JSONObject): String = if (ride.isNull("trip")) "" else ride.optString("trip")

    fun line(ride: JSONObject): String = if (ride.isNull("lineNumber")) "" else ride.optString("lineNumber")

    fun place(end: JSONObject?): DoubleArray? {
        if (end == null || end.isNull("lat") || end.isNull("lon")) return null
        val lat = end.optDouble("lat"); val lon = end.optDouble("lon")
        return if (lat == 0.0 && lon == 0.0) null else doubleArrayOf(lat, lon)
    }

    /**
     * The walk to a ride: the walks (and waits) right before the ride at [ride], back to the ride or the start before
     * them. Null when something else comes between, or there's no walk.
     */
    class Approach(val walks: List<Int>, val ride: Int) {
        fun metres(legs: List<JSONObject>) = walks.sumOf { legs[it].optInt("metres") }
        fun seconds(legs: List<JSONObject>) = walks.sumOf { seconds(legs[it]) }
        fun turns(legs: List<JSONObject>): List<String> = walks.flatMap { w ->
            val a = legs[w].optJSONArray("turns") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("way") }
        }
        fun walkMetres(legs: List<JSONObject>) = walks.map { legs[it].optInt("metres") }
    }

    fun approach(legs: List<JSONObject>, ride: Int): Approach? {
        if (legs.getOrNull(ride)?.let(::kind) != "ride") return null
        val walks = ArrayList<Int>()
        var i = ride - 1
        while (i >= 0 && kind(legs[i]).let { it == "walk" || it == "wait" }) {
            if (kind(legs[i]) == "walk") walks.add(0, i)
            i--
        }
        if (i >= 0 && kind(legs[i]) != "ride") return null
        return if (walks.isEmpty()) null else Approach(walks, ride)
    }

    /** The first ride's index, when only walking and waiting come before it. */
    fun firstRide(legs: List<JSONObject>): Int {
        val i = legs.indexOfFirst { kind(it) == "ride" }
        if (i < 0) return -1
        return if ((0 until i).all { kind(legs[it]).let { k -> k == "walk" || k == "wait" } }) i else -1
    }

    /** The ride after [from], when only walking and waiting come between them: a change. -1 for none. */
    fun changeAfter(legs: List<JSONObject>, from: Int): Int {
        var i = from + 1
        while (i < legs.size && kind(legs[i]).let { it == "walk" || it == "wait" }) i++
        return if (i < legs.size && kind(legs[i]) == "ride") i else -1
    }

    /**
     * Each step of a trip as Kav's step-by-step guide has them (Kav's buildSteps): the start, each walk (walks one after
     * another are one, and one under a minute and 30 m is none), a wait and a ride for each ride, then the arrival.
     * For each step, the index of the ride a walk goes to, or -1.
     */
    fun walkSteps(legs: List<JSONObject>): List<Int> {
        val out = ArrayList<Int>()
        out.add(-1)
        var i = 0
        while (i < legs.size) {
            when (kind(legs[i])) {
                "walk" -> {
                    var j = i; var mins = 0; var metres = 0
                    while (j < legs.size && kind(legs[j]) == "walk") { mins += (seconds(legs[j]) / 60).toInt(); metres += legs[j].optInt("metres"); j++ }
                    if (mins >= 1 || metres > 30) {
                        var k = j
                        while (k < legs.size && kind(legs[k]) == "wait") k++
                        out.add(if (k < legs.size && kind(legs[k]) == "ride") k else -1)
                    }
                    i = j; continue
                }
                "ride" -> { out.add(-1); out.add(-1) }
                "taxi", "bike" -> out.add(-1)
            }
            i++
        }
        out.add(-1)
        return out
    }
}
