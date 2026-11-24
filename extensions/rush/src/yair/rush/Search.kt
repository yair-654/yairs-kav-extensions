package yair.rush

import android.os.Looper
import kav.kavapp.api.Kav
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Rush's searches. Kav's server walks no faster than a brisk walk (1.6 m/s, its "fast" pace), so it can't be asked for
 * a run. Catching a bus by running is the same as having left a few minutes earlier, though: so Rush asks Kav's server
 * for routes "leaving [LOOKBACK_S] ago", at the fast pace and with the tight time at the stop, and then works out for
 * each one whether a jog or a run from where the rider is now still makes its first bus (RunMath), by the bus's live
 * time when Kav has one. Those it does are kept, with their first walk turned into the jog or run; the rest are dropped.
 *
 * It does this inside Kav's own search (KavPlan.plan), so Kav's results, trip page, step-by-step guide and its own
 * New route from here all get the same routes. Only Kav's own planner is changed, never an extension's, and only for
 * a search leaving now. With stops on the way ("Add a stop"), Kav's server plans through them as always: only the walk
 * to the first bus can become a run, and only when that bus comes before the first stop on the way.
 */
internal class Search(private val kav: Kav, private val bits: KavBits, private val store: Store) {
    companion object {
        /** How far back to ask: running a kilometre instead of walking it saves about 5 minutes. */
        const val LOOKBACK_S = 6 * 60L
        /** A Rush search's answer is used again for this long, so turning Rush on after the note costs no new search. */
        const val KEEP_MS = 60_000L
        /** How long a search with Rush on waits for the Rush answer beyond the normal one. */
        const val WAIT_MS = 25_000L
        const val TIME_DEPARTURE = 2
        /** Rush mode stays on for the same search this long: past it, a search to the same place is a new search. */
        const val ON_MS = 10 * 60_000L
        /** At most this many routes with a run go on top: Kav shows only so many routes in its list. */
        const val MOST_RUNS = 3
        /** A run must get there at least this much sooner than the best route without one, or it isn't worth it. */
        const val WORTH_S = 60L
    }

    /** Why Kav is searching, when Rush itself asked or Kav's New route from here did. */
    class Context(val kind: Kind, val from: Long = 0, val startS: Int = RunMath.START_S, val compareArr: Long = Long.MAX_VALUE) {
        enum class Kind { RAW, REPLAN, TRANSFER }
        @Volatile var outcome: Outcome? = null
    }

    private val context = ThreadLocal<Context?>()

    fun <T> within(c: Context, work: () -> T): T {
        val held = context.get()
        context.set(c)
        try { return work() } finally { context.set(held) }
    }

    /** A route with a run, and the run. */
    class RunRoute(val route: Any, val run: Store.Run, val saves: Long)

    /** What a Rush search found: its routes with a run, best first, and Kav's answer with them in it. */
    class Outcome(val runs: List<RunRoute>, val planned: Any, val resolved: Any) {
        val best get() = runs.firstOrNull()
    }

    // The search on Kav's results screen, for the Rush mode row.

    enum class Phase { NONE, CHECKING, READY, FAILED }

    class Status(val key: String, val phase: Phase, val on: Boolean, val saves: Long = 0, val runs: Int = 0)

    @Volatile var status = Status("", Phase.NONE, false); private set
    /** The search Rush is on for. It goes off by itself with the next search to anywhere else, or after [ON_MS]. */
    @Volatile var rushOnKey: String? = null
    @Volatile private var rushOnAt = 0L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun onStatus(l: () -> Unit) { listeners += l }
    private fun setStatus(s: Status) { status = s; for (l in listeners) l() }

    private class Kept(val key: String, val at: Long, val planned: Any)
    @Volatile private var kept: Kept? = null

    fun install() {
        val before = Kav.MethodHook { call ->
            try { onPlan(call) } catch (e: KavFailed) {
                // Kav's own search failed (no network, say): its error is Kav's to show, as without Rush.
            } catch (e: Throwable) {
                // Never let a mistake of ours stop a search: Kav plans as if Rush weren't there.
                kav.log("Rush: search skipped: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // A hook that can't be made must not stop the extension from starting (that would count as a crash).
        installed = try {
            kav.hook(bits.planMethod, before, null)
            kav.log("Rush: watching Kav's searches")
            true
        } catch (e: Throwable) {
            kav.log("Rush: can't watch Kav's searches in this Kav (${e.javaClass.simpleName}). Rush is off.")
            false
        }
    }

    /** Whether Rush's hook on Kav's search is in place. Without it, Rush shows nothing. */
    @Volatile var installed = false; private set

    /** Goes up with each search and each tap on Rush mode: an answer for an older one is dropped. */
    @Volatile private var generation = 0

    // Kav's search

    private fun onPlan(call: Kav.Call) {
        val c = context.get()
        if (c?.kind == Context.Kind.RAW) return
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val args = call.args()
        val at = args[7] as? String ?: return
        if (bits.isExtensionPlanner(at)) return
        if (args[3] != TIME_DEPARTURE) return
        val whenMs = args[2] as Long

        when (c?.kind) {
            Context.Kind.REPLAN -> {
                val from = if (whenMs > 0) whenMs / 1000 else now()
                val normal = callOriginal(call, args)
                // From here on Kav's answer is in hand: whatever goes wrong, Kav gets it, never a second search.
                val out = try {
                    process(fetch(args, from, null), normal, from, c.startS, Long.MAX_VALUE, keepAll = true)
                } catch (e: Throwable) {
                    kav.log("Rush: New route from here without runs: ${e.javaClass.simpleName}"); null
                }
                call.setResult(out?.planned ?: normal)
                c.outcome = out
            }
            Context.Kind.TRANSFER -> {
                val rush = try { fetch(args, c.from, null) } catch (e: Throwable) { call.setError(e); return }
                // The Rush answer is in hand: whatever goes wrong now, it's the answer, never a second search.
                val out = try {
                    process(rush, null, c.from, c.startS, c.compareArr, keepAll = false)
                } catch (e: Throwable) {
                    kav.log("Rush: change check without runs: ${e.javaClass.simpleName}"); null
                }
                c.outcome = out
                call.setResult(out?.planned ?: rush)
            }
            else -> results(call, args, whenMs)
        }
    }

    /** A search on Kav's results screen. */
    private fun results(call: Kav.Call, args: Array<Any?>, whenMs: Long) {
        if (whenMs != 0L) { endRush(); return }
        val key = keyOf(args[0], args[1])
        // The Rush answer is kept only for this very search: the same ends, modes, stops on the way, planner and settings.
        val cacheKey = key + "|" + listOf(args[4], args[5], args[6], args[7], args[8]).joinToString("|")
        if (rushOnKey != key || System.currentTimeMillis() - rushOnAt > ON_MS) rushOnKey = null
        val on = rushOnKey == key && store.safetyAccepted
        val gen = ++generation
        if (!on && !store.hint) { setStatus(Status(key, Phase.NONE, false)); return }

        if (on) {
            // Only this search's row is set: a slower, older search finishing later leaves a newer one's alone.
            fun set(s: Status) { if (generation == gen) setStatus(s) }
            set(Status(key, Phase.CHECKING, true))
            val from = now()
            val task = FutureTask { fetch(args, from, cacheKey) }
            Thread(task, "rush-search").start()
            val normal = try { callOriginal(call, args) } catch (e: KavFailed) { set(Status(key, Phase.FAILED, true)); throw e }
            try {
                val rush = try { task.get(WAIT_MS, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
                    ?: throw IllegalStateException("no Rush answer")
                val out = process(rush, normal, now(), RunMath.START_S, Long.MAX_VALUE, keepAll = true)
                call.setResult(out.planned)
                store.putRuns(emptyMap(), bits.itineraries(out.planned).map { bits.guid(it) })
                set(Status(key, Phase.READY, true, out.best?.saves ?: 0, out.runs.size))
            } catch (e: Throwable) {
                kav.log("Rush: results without runs: ${e.javaClass.simpleName}: ${e.message}")
                call.setResult(normal)
                set(Status(key, Phase.FAILED, true))
            }
            return
        }

        // Rush is off: Kav's answer as it is, and a Rush search beside it for the note.
        val normal = callOriginal(call, args)
        call.setResult(normal)
        setStatus(Status(key, Phase.CHECKING, false))
        val from = now()
        Thread({
            val s = try {
                val out = process(fetch(args, from, cacheKey), normal, now(), RunMath.START_S, Long.MAX_VALUE,
                    keepAll = false, remember = false)
                Status(key, Phase.READY, false, out.best?.saves ?: 0, out.runs.size)
            } catch (e: Throwable) {
                Status(key, Phase.FAILED, false)
            }
            // Only if nothing happened since: no newer search, and the rider didn't tap Rush mode meanwhile.
            if (generation == gen) setStatus(s)
        }, "rush-note").start()
    }

    /** Turns Rush on or off for the search shown now. */
    fun setRush(on: Boolean) {
        val key = status.key
        rushOnKey = if (on && key.isNotEmpty()) key else null
        rushOnAt = System.currentTimeMillis()
        generation++
        setStatus(Status(key, Phase.CHECKING, on))
    }

    private fun endRush() {
        rushOnKey = null
        setStatus(Status("", Phase.NONE, false))
    }

    private class KavFailed : RuntimeException()

    /** Kav's own search. When it fails, its error is the search's, and Rush steps out. */
    private fun callOriginal(call: Kav.Call, args: Array<Any?>): Any = try {
        call.callOriginal(*args)!!
    } catch (e: Throwable) {
        call.setError(e)
        throw KavFailed()
    }

    /** Kav's server's answer for a Rush search: leaving [LOOKBACK_S] before [from], at the fast pace. */
    private fun fetch(args: Array<Any?>, from: Long, cacheKey: String?): Any {
        if (cacheKey != null) kept?.let { k -> if (k.key == cacheKey && System.currentTimeMillis() - k.at < KEEP_MS) return k.planned }
        val ask = args.copyOf()
        ask[2] = (from - LOOKBACK_S) * 1000
        ask[3] = TIME_DEPARTURE
        ask[8] = bits.rushSettings(args[8] ?: bits.riderSettings())
        val planned = within(Context(Context.Kind.RAW)) { bits.callPlan(*ask) }
        if (cacheKey != null) kept = Kept(cacheKey, System.currentTimeMillis(), planned)
        return planned
    }

    /**
     * The routes of a Rush search that need a jog or a run, worked out from [from], and Kav's answer with them first.
     * [compareArr]: what they must beat, else the best of [normal]. keepAll: the answer has every route of [normal]
     * after them (Kav's results); otherwise the Rush search's own.
     */
    private fun process(
        rush: Any, normal: Any?, from: Long, startS: Int, compareArr: Long, keepAll: Boolean, remember: Boolean = true,
    ): Outcome {
        val res = bits.resolved(rush)
        val nowS = now()
        val normalRoutes = normal?.let { n -> bits.itineraries(n).filter { transit(n, it) } }.orEmpty()
        val beat = minOf(compareArr, normalRoutes.minOfOrNull { bits.itArr(it) } ?: Long.MAX_VALUE)
        val normalBuses = normalRoutes.mapNotNull { r -> bits.legs(r).firstOrNull { bits.kind(it) == "RIDE" }?.let(bits::tripId) }.toSet()
        val speeds = store.speeds
        val found = HashMap<String, Store.Run>()
        val runs = ArrayList<RunRoute>()
        val seen = HashSet<Long>()

        for (route in bits.itineraries(rush)) {
            if (!transit(rush, route)) continue
            val legs = bits.legs(route)
            val k = legs.indexOfFirst { bits.kind(it) == "RIDE" }
            if (k < 0) continue
            val access = legs.subList(0, k)
            if (access.any { bits.kind(it) != "WALK" && bits.kind(it) != "WAIT" }) continue
            // A stop on the way before the first bus: the walk goes there, not to the bus. Not a run.
            if (legs.subList(0, k + 1).any { bits.isVisit(it) }) continue
            val ride = legs[k]
            // The same bus as a route Kav already has, or as one found just before. A ride without a trip id (0 or
            // below) can't be told apart, so it's never taken for another.
            val bus = bits.tripId(ride)
            if (bus > 0 && (bus in normalBuses || !seen.add(bus))) continue
            val walks = access.filter { bits.kind(it) == "WALK" }
            val metres = walks.sumOf { bits.meters(it) }
            if (metres < 30) continue
            val turns = walks.sumOf { bits.turns(it) }
            val wait = access.lastOrNull()?.takeIf { bits.kind(it) == "WAIT" }
            val board = bits.boardAt(res, ride, wait, nowS) ?: continue
            val choice = RunMath.choose(metres, turns, from, board, speeds, store.tight, startS) ?: continue
            if (bits.itArr(route) > beat - WORTH_S) continue

            // The walks to the stop become the run, shared out by length; the wait is what's left before the bus.
            val run = Store.Run(if (choice.pace == RunMath.Pace.RUN) RunMath.Pace.RUN else RunMath.Pace.JOG,
                metres, choice.seconds, choice.spare, board, System.currentTimeMillis())
            val newLegs = ArrayList<Any>(legs.size)
            var t = from
            var left = choice.seconds
            var metresLeft = metres
            for ((i, leg) in legs.withIndex()) {
                if (i >= k) { newLegs += leg; continue }
                when (bits.kind(leg)) {
                    "WALK" -> {
                        val m = bits.meters(leg)
                        val s = if (metresLeft <= m) left else (left.toLong() * m / metresLeft).toInt()
                        left -= s; metresLeft -= m
                        val moved = bits.legAt(leg, t, t + s)
                        t += s
                        found[store.walkKey(m, t - s, t)] = run
                        newLegs += moved
                    }
                    else -> {
                        val end = maxOf(t, bits.arr(leg))
                        newLegs += bits.legAt(leg, t, end)
                        t = end
                    }
                }
            }
            // Kav's server judged the bus "likely missed" at its own walking pace: not for a run.
            val tags = bits.tags(route).filter { it != "likely_missed" }
            // With no route to beat (no bus at all without running), there's no saving to show, only the run.
            val saves = if (beat == Long.MAX_VALUE) 0L else beat - bits.itArr(route)
            runs += RunRoute(bits.itineraryWith(route, newLegs, from, tags), run, saves)
        }
        runs.sortBy { bits.itArr(it.route) }
        while (runs.size > MOST_RUNS) runs.removeAt(runs.lastIndex)
        if (remember) store.putRuns(found, emptyList())

        val rest = if (keepAll && normal != null) bits.itineraries(normal) else bits.itineraries(rush)
        val base = normal ?: rush
        val resolved = if (normal != null) bits.merge(bits.resolved(normal), res) else res
        val planned = if (runs.isEmpty()) base else bits.planned(base, runs.map { it.route } + rest, resolved)
        return Outcome(runs, planned, resolved)
    }

    /** A route with a ride in it, and no taxi: what can be run for. */
    private fun transit(planned: Any, route: Any): Boolean {
        if (bits.isTaxiCard(planned, route)) return false
        val kinds = bits.legs(route).map { bits.kind(it) }
        return "RIDE" in kinds && "TAXI" !in kinds
    }

    /** A search's ends, to tell one search from the next. */
    private fun keyOf(from: Any?, to: Any?): String {
        fun s(p: Any?) = p?.let { bits.latLon(it) }?.let { (a, b) -> "%.4f,%.4f".format(java.util.Locale.US, a, b) } ?: "?"
        return s(from) + ">" + s(to)
    }

    private fun now() = System.currentTimeMillis() / 1000
}
