package yair.rush

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The runs Rush planned, so the route card, the trip's page and the step-by-step guide can show them as runs. A run is
 * known by the ride it catches and how long the walk to it takes, since a route as Kav shows it carries nothing of
 * Rush's own. Kept on this phone only, in a folder Android never backs up, for [KEEP_S].
 */
internal class Runs(context: Context) {
    class Run(val kind: RunMath.Kind, val metres: Int, val seconds: Int, val spare: Int, val board: Long, val at: Long) {
        val tight get() = spare < RunMath.TIGHT_S
    }

    private val dir = File(context.noBackupFilesDir, "rush-extension").apply { mkdirs() }
    private val file = File(dir, "runs.json")
    private val runs = LinkedHashMap<String, Run>()

    init {
        // Rush 1.0 kept its settings here too; Kav keeps them now.
        File(dir, "settings.json").delete()
        synchronized(runs) {
            runCatching {
                if (file.isFile) {
                    val a = JSONArray(file.readText())
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        runs[o.getString("key")] = Run(RunMath.Kind.valueOf(o.getString("kind")), o.getInt("metres"),
                            o.getInt("seconds"), o.getInt("spare"), o.getLong("board"), o.getLong("at"))
                    }
                }
            }
            prune(now())
        }
    }

    fun key(rideTrip: String, walkSeconds: Long) = "$rideTrip|$walkSeconds"

    fun put(key: String, run: Run) = synchronized(runs) {
        runs.remove(key); runs[key] = run
        prune(run.at)
        save()
    }

    fun get(key: String): Run? = synchronized(runs) { runs[key] }

    /** The run planned on the walk to the ride at [ride] of these legs, if any. */
    fun on(legs: List<org.json.JSONObject>, ride: Int): Run? {
        val trip = legs.getOrNull(ride)?.let(Routes::tripOf)?.takeIf { it.isNotEmpty() } ?: return null
        val approach = Routes.approach(legs, ride) ?: return null
        return get(key(trip, approach.seconds(legs)))
    }

    /** Whether any walk of these legs is a run Rush planned. */
    fun any(legs: List<org.json.JSONObject>): Boolean = legs.indices.any { Routes.kind(legs[it]) == "ride" && on(legs, it) != null }

    private fun prune(now: Long) {
        runs.entries.removeAll { now - it.value.at > KEEP_S }
        while (runs.size > MOST) runs.remove(runs.keys.first())
    }

    private fun save() {
        val a = JSONArray()
        for ((k, r) in runs) a.put(JSONObject().put("key", k).put("kind", r.kind.name).put("metres", r.metres)
            .put("seconds", r.seconds).put("spare", r.spare).put("board", r.board).put("at", r.at))
        runCatching {
            val tmp = File(dir, "runs.json.tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(file)
        }
    }

    companion object {
        const val KEEP_S = 6 * 3600L
        const val MOST = 60
        fun now() = System.currentTimeMillis() / 1000
    }
}
