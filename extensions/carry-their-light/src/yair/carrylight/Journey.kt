package yair.carrylight

import org.json.JSONArray
import org.json.JSONObject

/**
 * A long ride, or (when the rider allows it) a long wait, of a trip that gets a hero. Only the time on board counts
 * for a ride; a wait is its time at the stop.
 *
 * key: the same also when Kav plans the rest of the trip again: its kind, line, boarding stop and the ride's planned
 * departure (so the same line from the same stop later in the day is another spot).
 * leg: its ride's place in trip.legs (a wait's too, as Kav's steps count it). end: when it ends, in seconds.
 */
internal class Spot(val key: String, val kind: String, val leg: Int, val minutes: Int, val end: Long) {
    /** Its place in the trip: a ride's wait just before the ride. */
    val order get() = leg * 2 + if (kind == RIDE) 1 else 0

    companion object {
        const val RIDE = "ride"
        const val WAIT = "wait"
    }
}

/**
 * Where the rider is in a trip, as Kav tells it (a trip event, or navigation's banner): the trip, its steps when known,
 * the step the trip is on (its kind and its leg's index), and the step whose card navigation shows (-1: not open).
 */
internal class Journey(
    val trip: JSONObject,
    private val steps: JSONArray?,
    private val step: Int,
    private val kind: String,
    private val legIndex: Int,
    private val shown: Int,
) {
    val key: String = keyOf(trip)

    /** Temporary, for testing: whether its steps are known (from a trip event). */
    val knowsSteps get() = steps != null


    /** The spot key of the wait the trip is on now, if it's on one. */
    fun waitingAt(): String? {
        if (kind != Spot.WAIT) return null
        return trip.optJSONArray("legs")?.optJSONObject(legIndex)?.let { "wait|${idOf(it)}" }
    }

    /** The spot the trip is on now, if it's one. */
    fun current(spots: List<Spot>): Spot? = spots.firstOrNull { it.kind == kind && it.leg == legIndex }

    /** The spot whose card navigation shows: the one the rider swiped to when the steps are known, else the trip's. */
    fun onScreen(spots: List<Spot>): Spot? {
        if (shown < 0 || shown == step) return current(spots)
        val s = steps?.optJSONObject(shown) ?: return null
        return spots.firstOrNull { it.kind == s.optString("kind") && it.leg == s.optInt("legIndex", -1) }
    }

    /** Whether the rider is past a spot: by the steps when known, else by the clock. */
    fun past(spot: Spot, nowSec: Long): Boolean {
        val at = position() ?: return spot.end < nowSec
        return spot.order < at
    }

    /** The trip's place as a spot's order, half a step before the next ride or wait during a walk; null when unknown. */
    private fun position(): Double? {
        if (kind == Spot.RIDE || kind == Spot.WAIT) return (legIndex * 2 + if (kind == Spot.RIDE) 1 else 0).toDouble()
        val all = steps ?: return null
        for (i in (step + 1) until all.length()) {
            val s = all.optJSONObject(i) ?: continue
            val leg = s.optInt("legIndex", -1)
            when (s.optString("kind")) {
                Spot.WAIT -> return leg * 2 - 0.5
                Spot.RIDE -> return leg * 2 + 0.5
            }
        }
        return Double.MAX_VALUE // after the last ride
    }

    companion object {
        /**
         * The same trip, from a trip event or a part: its rides (line and boarding stop). Its times may differ between
         * the two, and a replan that changes a ride is another trip.
         */
        fun keyOf(trip: JSONObject): String {
            val legs = trip.optJSONArray("legs") ?: return ""
            return (0 until legs.length()).mapNotNull { legs.optJSONObject(it) }.filter { it.optString("kind") == Spot.RIDE }
                .joinToString(",") { r -> "${r.str("lineNumber") ?: r.str("line").orEmpty()}@${r.optJSONObject("from")?.let { it.str("stop") ?: it.str("name") }.orEmpty()}" }
        }

        /** From a trip event: {"trip", "step": {"index", "kind", "legIndex", "shown"}, "steps"}. */
        fun fromEvent(e: JSONObject): Journey? {
            val trip = e.optJSONObject("trip") ?: return null
            val step = e.optJSONObject("step") ?: return null
            return Journey(trip, e.optJSONArray("steps"), step.optInt("index", -1), step.optString("kind"),
                step.optInt("legIndex", -1), step.optInt("shown", -1))
        }

        /** From navigation's banner: {"trip", "step", "kind", "index", "shown"}, with the steps of the same trip's last
         *  event, when the rider allows following trips. */
        fun fromBanner(a: JSONObject, event: Journey?): Journey? {
            val trip = a.optJSONObject("trip") ?: return null
            val steps = event?.takeIf { it.key == keyOf(trip) }?.steps
            return Journey(trip, steps, a.optInt("step", -1), a.optString("kind"), a.optInt("index", -1), a.optInt("shown", -1))
        }

        private fun idOf(ride: JSONObject): String {
            val line = ride.str("lineNumber") ?: ride.str("line").orEmpty()
            val stop = ride.optJSONObject("from")?.let { it.str("stop") ?: it.str("name") }.orEmpty()
            return "$line|$stop|${ride.optLong("dep")}"
        }

        /**
         * The trip's spots that get a hero: rides (and waits, when the rider allows them) of at least the rider's
         * minimum; on Yom HaZikaron, of 5 minutes or more. One per trip (its longest) unless the rider chose every long
         * ride, or it's Yom HaZikaron. In the trip's order.
         *
         * A wait counts from when the trip reached it (reached, in seconds) until the bus leaves, by its live time when
         * Kav has one: Kav plans the walk to reach the stop just before the bus, and the rider may be there long before.
         * A wait not reached yet counts as planned.
         */
        fun spots(trip: JSONObject, prefs: Prefs, memorial: Boolean, reached: (String) -> Long?): List<Spot> {
            val legs = trip.optJSONArray("legs") ?: return emptyList()
            val least = if (memorial) Memorial.MIN_MINUTES else prefs.minutes
            val all = ArrayList<Spot>()
            for (i in 0 until legs.length()) {
                val ride = legs.optJSONObject(i) ?: continue
                if (ride.optString("kind") != Spot.RIDE) continue
                val id = idOf(ride)
                if (prefs.waits) {
                    // Planned: at the stop from when the leg before ends (a wait's own start, when the plan has one),
                    // or the trip's start, until the ride leaves.
                    val before = legs.optJSONObject(i - 1)
                    val planned = when {
                        before == null -> trip.optLong("dep")
                        before.optString("kind") == Spot.WAIT -> before.optLong("dep")
                        else -> before.optLong("arr")
                    }
                    val leaves = ride.optLong("liveDep").takeIf { it > 0 } ?: ride.optLong("dep")
                    val from = reached("wait|$id") ?: planned
                    all += Spot("wait|$id", Spot.WAIT, i, ((leaves - from) / 60).toInt(), leaves)
                }
                all += Spot("ride|$id", Spot.RIDE, i, minutes(ride), ride.optLong("arr"))
            }
            val long = all.filter { it.minutes >= least }
            if (memorial || prefs.every) return long
            return listOfNotNull(long.maxByOrNull { it.minutes })
        }

        private fun minutes(leg: JSONObject) = ((leg.optLong("arr") - leg.optLong("dep")) / 60).toInt()
    }
}

/**
 * Which hero each spot gets: the spots still to come (or under way) that aren't done take the waiting heroes in order.
 * So each spot has a different hero; and one closed with ✕, or not opened before its spot was over, comes back on
 * the next one.
 */
internal fun heroes(spots: List<Spot>, journey: Journey, store: Store): Map<String, Sticker> {
    val now = System.currentTimeMillis() / 1000
    val waiting = store.queue()
    val open = spots.filter { !store.done(it.key) && !journey.past(it, now) }
    return open.zip(waiting).associate { (spot, hero) -> spot.key to hero }
}
