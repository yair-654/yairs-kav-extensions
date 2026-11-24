package yair.heroes

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a navigation step is in its trip. A trip's legs come as route rules get them: walk, wait, ride (mode 2 is a
 * train), taxi, bike, other. In navigation, each ride takes two steps (boarding, then riding) and every other leg one:
 * seen on real trips (6 legs with 2 rides made 8 step dots, and the screens matched).
 */
internal object TripSteps {
    /** The leg a navigation step shows, or -1. */
    fun legOf(trip: JSONObject, step: Int): Int {
        val legs = trip.optJSONArray("legs") ?: return -1
        var first = 0
        for (i in 0 until legs.length()) {
            val span = if (legs.isRide(i)) 2 else 1
            if (step >= first && step < first + span) return i
            first += span
        }
        return -1
    }

    /**
     * Whether this step is the one where the rider heads into a train station: the walk to the train, or the wait
     * there when there is no walk. Never when already inside a station from an earlier train.
     */
    fun intoTrainStation(trip: JSONObject, step: Int): Boolean {
        val legs = trip.optJSONArray("legs") ?: return false
        val leg = legOf(trip, step)
        if (leg < 0 || legs.isRide(leg)) return false
        val prevRide = (leg - 1 downTo 0).firstOrNull { legs.isRide(it) } ?: -1
        val nextRide = (leg + 1 until legs.length()).firstOrNull { legs.isRide(it) } ?: -1
        if (nextRide < 0 || !legs.isTrain(nextRide)) return false
        if (prevRide >= 0 && legs.isTrain(prevRide)) return false
        val between = (prevRide + 1 until nextRide)
        val into = between.firstOrNull { legs.optJSONObject(it)?.optString("kind") == "walk" } ?: between.first
        return leg == into
    }

    /** A short outline of the trip for the extension's log, with no places in it: "walk, wait, ride/2, walk". */
    fun outline(trip: JSONObject): String {
        val legs = trip.optJSONArray("legs") ?: return "no legs"
        return (0 until legs.length()).joinToString(", ") { i ->
            val leg = legs.optJSONObject(i)
            val kind = leg?.optString("kind") ?: "?"
            if (kind == "ride") "ride/${leg?.optInt("mode", -1)}" else kind
        }
    }

    /** One trip's own key, the same each time navigation shows it. */
    fun key(trip: JSONObject): String {
        val legs = trip.optJSONArray("legs")
        val firstRide = legs?.let { l -> (0 until l.length()).firstOrNull { l.isRide(it) }?.let { l.optJSONObject(it) } }
        return "${trip.optLong("dep")}:${trip.optLong("arr")}:${firstRide?.optString("trip")}:${firstRide?.optString("line")}"
    }

    private fun JSONArray.isRide(i: Int) = optJSONObject(i)?.optString("kind") == "ride"
    private fun JSONArray.isTrain(i: Int) = isRide(i) && optJSONObject(i)?.optInt("mode", -1) == 2
}
