package yair.heroes

import kav.kavapp.api.Kav
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The trip the rider follows, as Kav tells it (`kav.onTrip`, API 5), once the rider turns on "Follow your trips" on the
 * extension's page: which step navigation shows, every step of the trip, and when it ends. Without it Kav tells nothing,
 * and [now] stays null.
 */
internal class Trips(kav: Kav) {
    /** What Kav last told about the trip under way, or null when there's none (or the rider hasn't allowed it). */
    class Now(
        val trip: JSONObject,
        val key: String,
        val steps: JSONArray,
        /** The step whose card navigation shows, or -1 while navigation isn't open. */
        val shown: Int,
    )

    @Volatile var now: Now? = null; private set
    private val listeners = CopyOnWriteArrayList<(event: String, was: Now?, now: Now?) -> Unit>()

    /** Told every event, on a thread of Kav's: "start", "step", "replan", "shown", "eta" or "end". */
    fun onEvent(listener: (event: String, was: Now?, now: Now?) -> Unit) { listeners += listener }

    init {
        kav.onTrip(Kav.TripListener { text ->
            val e = try { JSONObject(text) } catch (ex: Exception) { return@TripListener }
            val event = e.optString("event")
            val was = now
            now = if (event == "end") null else e.optJSONObject("trip")?.let { trip ->
                Now(
                    trip = trip,
                    key = key(trip),
                    steps = e.optJSONArray("steps") ?: JSONArray(),
                    shown = e.optJSONObject("step")?.optInt("shown", -1) ?: -1,
                )
            }
            for (l in listeners) l(event, was, now)
        })
    }

    companion object {
        /** A trip's own key, the same each time Kav tells it, and the same in a ride card's part. */
        fun key(trip: JSONObject): String {
            val legs = trip.optJSONArray("legs")
            val firstRide = legs?.let { l -> (0 until l.length()).map { l.optJSONObject(it) }.firstOrNull { it?.optString("kind") == "ride" } }
            return "${trip.optLong("dep")}:${trip.optLong("arr")}:${firstRide?.optString("trip")}:${firstRide?.optString("line")}"
        }

        /** Trains, light rail and the metro: the GTFS route types whose stations have security at their entrance. */
        private val RAIL = setOf(0, 1, 2)

        /**
         * Whether a step is at a station's way in: the walk into a train or light rail station, the wait there, or the
         * start of a trip that begins there. Never at a change between two rides of the same kind (a train to a train),
         * where the rider is already inside.
         */
        fun atStation(now: Now, index: Int): Boolean {
            val steps = now.steps
            val legs = now.trip.optJSONArray("legs") ?: return false
            fun step(i: Int) = steps.optJSONObject(i)
            fun mode(s: JSONObject?): Int {
                val leg = s?.optInt("legIndex", -1) ?: -1
                return if (leg < 0) -1 else legs.optJSONObject(leg)?.optInt("mode", -1) ?: -1
            }
            val here = step(index) ?: return false
            // The ride this step leads to: its own for a wait, the next step's wait for a walk or the start.
            val waitAt = when (here.optString("kind")) {
                "wait" -> index
                "walk", "start" -> (index + 1).takeIf { step(it)?.optString("kind") == "wait" } ?: return false
                else -> return false
            }
            val next = mode(step(waitAt))
            if (next !in RAIL) return false
            // The ride before this one, if any: off a train onto a train, the rider is inside already.
            val before = (waitAt - 1 downTo 0).firstOrNull { step(it)?.optString("kind") == "ride" }
            if (before != null && mode(step(before)) == next) return false
            // With a walk into the station, the button is on that walk and on the wait; with none, on the wait (and
            // the start, for a trip that begins at the station).
            return true
        }
    }
}
