package yair.carrylight

import kav.kavapp.api.Kav
import org.json.JSONObject

/** What's true now: the rider's settings, and the trip they follow as Kav last told it (with Follow your trips on). */
internal class Live(kav: Kav, private val store: Store) {
    @Volatile var prefs = Prefs(kav.settings())
    @Volatile var trip: Journey? = null

    /** The trip's spots that get a hero, with today's rules; a wait the trip is at counts from when it got there. */
    fun spots(journey: Journey): List<Spot> {
        journey.waitingAt()?.let(store::reach)
        return Journey.spots(journey.trip, prefs, Memorial.now(), store::reachedAt)
    }

    /** The followed trip, if it's this one. */
    fun following(trip: JSONObject): Journey? = this.trip?.takeIf { it.key == Journey.keyOf(trip) }
}
