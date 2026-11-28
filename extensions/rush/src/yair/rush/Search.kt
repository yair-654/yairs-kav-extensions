package yair.rush

import kav.kavapp.api.Kav
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs in Kav's results. Kav's planner never plans a run, but catching a bus by running is the same as having left a few
 * minutes earlier. So once Kav's own routes are on screen (Kav asks "results.add"), Rush asks Kav's planner for routes
 * leaving [LOOKBACK_S] ago, at the fast pace with the tight time at the stop, and works out for each one whether a jog
 * or a run from the search's start still makes its first bus, by the bus's live time when Kav has one.
 *
 * Rush only says what it found (the chip); its routes go first in the results only after the rider taps the chip, for
 * that one search. Kav's New route from here on a trip that came from a run looks for runs too.
 */
internal class Search(private val kav: Kav, private val settings: Settings, private val runs: Runs, private val refresh: () -> Unit) {
    /** What the chip says: for which search, how much the best run saves, and whether its routes are in the results. */
    class Status(val search: String, val saves: Long, val on: Boolean, val at: Long)

    /** A run found: the route's ref with Kav, when the rider leaves, each walk's seconds, and the run. */
    private class Found(val ref: String, val start: Long, val walks: List<Long>, val arr: Long, val key: String, val run: Runs.Run)

    @Volatile var status: Status? = null; private set

    /** The search the rider tapped the chip for: its routes go first the next time Kav asks, then it's off again. */
    @Volatile private var wanted: Pair<String, Long>? = null

    /** The search the results screen shows now, by its places as Kav's results.endpoints part tells them. */
    @Volatile private var shown: String? = null

    /** Kav's planner's answer for a search, kept a minute so tapping the chip doesn't ask again. */
    @Volatile private var cached: Pair<String, Pair<Long, JSONObject>>? = null

    @Volatile var lastSettings = "fnnt"; private set

    fun install() {
        kav.provide("results.add", Kav.Hook { input -> answer(JSONObject(input)) })
    }

    /** Kav's results show another search (its places or stops on the way changed): the chip is for the old one. */
    fun endpoints(about: String) {
        if (about == shown) return
        shown = about
        status = null
        refresh()
    }

    /** The chip tapped: this search's runs first, or not. True when Kav should search again. */
    fun toggle(): Boolean {
        val s = status ?: return false
        if (s.on) { wanted = null; status = Status(s.search, s.saves, false, s.at) }
        else { wanted = s.search to Runs.now(); status = Status(s.search, s.saves, true, s.at) }
        refresh()
        return true
    }

    /** Whether the chip has something to say for the results on screen. */
    fun current(): Status? = status?.takeIf { Runs.now() - it.at < STATUS_S }

    private fun answer(input: JSONObject): String {
        val q = input.getJSONObject("query")
        val reason = q.optString("reason")
        lastSettings = q.optString("settings").takeIf { it.length == 4 } ?: lastSettings
        return when (reason) {
            "search" -> search(q, input.optJSONArray("routes") ?: JSONArray())
            "replan" -> replan(q, input.optJSONArray("routes") ?: JSONArray(), input.optJSONObject("trip"))
            else -> "[]"
        }
    }

    private fun search(q: JSONObject, kavRoutes: JSONArray): String {
        val key = keyOf(q)
        val on = wanted?.let { (k, at) -> k == key && Runs.now() - at < WANTED_S } == true
        wanted = null
        if (q.optLong("at") != 0L || q.optString("way") != "d" || !settings.inResults) {
            status = null; refresh(); return "[]"
        }
        val found = try { find(q, kavRoutes, key, Runs.now(), cache = true) } catch (e: Exception) {
            kav.log("Rush: couldn't look for runs: ${e.javaClass.simpleName}")
            emptyList()
        }
        val best = found.firstOrNull()
        status = best?.let { Status(key, bestKav(kavRoutes) - it.arr, on, Runs.now()) }
        refresh()
        if (!on || best == null) return "[]"
        for (f in found) runs.put(f.key, f.run)
        return JSONArray(found.map { f -> JSONObject().put("ref", f.ref).put("start", f.start).put("walks", JSONArray(f.walks)) }).toString()
    }

    /** Kav's New route from here: on a trip that came from a run, the new route may be one too. */
    private fun replan(q: JSONObject, kavRoutes: JSONArray, trip: JSONObject?): String {
        val legs = Routes.legs(trip?.optJSONObject("trip"))
        if (!runs.any(legs)) return "[]"
        // From where the trip goes on, and when: the stop it changes at, or where the rider is now.
        val from = maxOf(Runs.now(), q.optLong("at"))
        val best = try { find(q, kavRoutes, keyOf(q), from, cache = false).firstOrNull() } catch (e: Exception) { null } ?: return "[]"
        val kavFirst = kavRoutes.optJSONObject(0)?.optLong("arr") ?: Long.MAX_VALUE
        if (kavFirst - best.arr < settings.savesSeconds) return "[]"
        runs.put(best.key, best.run)
        return JSONArray().put(JSONObject().put("ref", best.ref).put("start", best.start).put("walks", JSONArray(best.walks))).toString()
    }

    /** The runs worth it for this search, soonest first, at most [MOST]. */
    private fun find(q: JSONObject, kavRoutes: JSONArray, key: String, now: Long, cache: Boolean): List<Found> {
        val planned = planned(q, key, now, cache) ?: return emptyList()
        val routes = planned.optJSONArray("routes") ?: return emptyList()
        val kavBuses = kavFirstRides(kavRoutes)
        val bestKav = bestKav(kavRoutes)
        val speeds = settings.speeds
        val tightOk = settings.tightOk
        val saves = settings.savesSeconds
        val out = ArrayList<Found>()
        for (i in 0 until routes.length()) {
            val route = routes.optJSONObject(i) ?: continue
            val ref = route.optString("ref")
            if (ref.isEmpty()) continue
            val legs = Routes.legs(route)
            val ride = Routes.firstRide(legs).takeIf { it >= 0 } ?: continue
            val trip = Routes.tripOf(legs[ride])
            if (trip.isEmpty() || trip in kavBuses) continue
            val approach = Routes.approach(legs, ride) ?: continue
            val metres = approach.metres(legs)
            val choice = RunMath.choose(metres, RunMath.crossings(approach.turns(legs), metres), now,
                Routes.boards(legs[ride]), speeds, tightOk, RunMath.START_S) ?: continue
            val arr = route.optLong("arr")
            if (bestKav - arr < maxOf(saves, MIN_SAVES_S)) continue
            val walks = RunMath.share(choice.seconds, approach.walkMetres(legs), RunMath.START_S)
            val run = Runs.Run(choice.kind, metres, choice.seconds, choice.spare, Routes.boards(legs[ride]), now)
            out.add(Found(ref, now, walks, arr, runs.key(trip, walks.sum()), run))
        }
        return notCancelled(out.sortedBy { it.arr }.distinctBy { it.key }, routes).take(MOST)
    }

    /** Kav's planner, from the search's start, leaving [LOOKBACK_S] ago at the fast pace and tight at the stop. */
    private fun planned(q: JSONObject, key: String, now: Long, cache: Boolean): JSONObject? {
        if (cache) cached?.let { (k, v) -> if (k == key && now - v.first < CACHE_S) return v.second }
        val letters = q.optString("settings").takeIf { it.length == 4 } ?: "nnnn"
        val ask = JSONObject().put("from", q.getJSONArray("from")).put("to", q.getJSONArray("to"))
            .put("at", now - LOOKBACK_S).put("way", "d").put("settings", "f${letters[1]}${letters[2]}t")
        q.optJSONArray("modes")?.let { ask.put("modes", it) }
        val answer = kav.plan(ask.toString())?.let(::JSONObject) ?: return null
        if (cache) cached = key to (now to answer)
        return answer
    }

    /** Kav marks a cancelled bus on its stop's board; a run for one is no run. */
    private fun notCancelled(found: List<Found>, routes: JSONArray): List<Found> {
        if (found.isEmpty()) return found
        val byRef = (0 until routes.length()).mapNotNull { routes.optJSONObject(it) }.associateBy { it.optString("ref") }
        return found.filter { f ->
            val legs = Routes.legs(byRef[f.ref])
            val ride = legs.getOrNull(Routes.firstRide(legs)) ?: return@filter true
            !cancelled(ride)
        }
    }

    private fun cancelled(ride: JSONObject): Boolean = runCatching {
        val stop = ride.optJSONObject("from")?.optString("stop")?.takeIf { it.startsWith("il.mot/") } ?: return false
        val board = kav.departures(JSONObject().put("stop", stop).put("limit", 30).toString()) ?: return false
        val a = JSONArray(board)
        val line = Routes.line(ride)
        val dep = ride.optLong("dep")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.any { d ->
            d.optBoolean("cancelled") && d.optString("line") == line && Math.abs(d.optLong("at") - dep) <= 60
        }
    }.getOrDefault(false)

    private fun kavFirstRides(routes: JSONArray): Set<String> = (0 until routes.length()).mapNotNull { i ->
        val legs = Routes.legs(routes.optJSONObject(i))
        legs.getOrNull(Routes.firstRide(legs))?.let(Routes::tripOf)?.takeIf { it.isNotEmpty() }
    }.toSet()

    private fun bestKav(routes: JSONArray): Long =
        (0 until routes.length()).mapNotNull { routes.optJSONObject(it)?.optLong("arr")?.takeIf { a -> a > 0 } }.minOrNull() ?: Long.MAX_VALUE

    /** A search by its places (to about 10 m), modes and planner settings. */
    private fun keyOf(q: JSONObject): String {
        fun p(name: String) = q.optJSONArray(name)?.let { "%.4f,%.4f".format(java.util.Locale.US, it.optDouble(0), it.optDouble(1)) }
        return listOf(p("from"), p("to"), q.optJSONArray("modes")?.toString(), q.optString("settings")).joinToString("|")
    }

    companion object {
        const val LOOKBACK_S = 6 * 60L
        const val CACHE_S = 60L
        const val WANTED_S = 60L
        const val STATUS_S = 10 * 60L
        const val MOST = 3
        /** A run is worth offering only when it's at least this much sooner than walking, whatever the setting. */
        const val MIN_SAVES_S = 60L
    }
}
