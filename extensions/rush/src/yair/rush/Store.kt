package yair.rush

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The rider's settings, and what Rush remembers about the runs it planned. Everything lives in Android's "no backup"
 * folder, which never goes into the phone's cloud backup, and nothing here is sent anywhere.
 */
internal class Store(context: Context) {
    companion object {
        // The defaults: a young, healthy adult in everyday clothes, with a bag.
        const val JOG_KMH = 8.0
        const val JOG_MAX_M = 1000
        const val RUN_KMH = 10.0
        const val RUN_MAX_M = 400
        const val SAVES_MIN = 10

        const val JOG_KMH_MIN = 5.0
        const val JOG_KMH_MAX = 12.0
        const val RUN_KMH_MIN = 6.0
        const val RUN_KMH_MAX = 20.0
        const val KMH_STEP = 0.5
        const val JOG_M_MIN = 100
        const val JOG_M_MAX = 2000
        const val RUN_M_MIN = 50
        const val RUN_M_MAX = 1000
        const val M_STEP = 50
        const val SAVES_MIN_MIN = 1
        const val SAVES_MIN_MAX = 30

        /** Runs and Rush trips are remembered this long: a trip planned today can still be under way tomorrow morning. */
        private const val KEEP_MS = 36 * 3600_000L
    }

    private val dir = File(context.noBackupFilesDir, "rush-extension").apply { mkdirs() }
    private val settingsFile = File(dir, "settings.json")
    private val runsFile = File(dir, "runs.json")

    @Volatile var button = true; private set
    @Volatile var hint = true; private set
    @Volatile var savesMin = SAVES_MIN; private set
    @Volatile var tight = false; private set
    @Volatile var jogKmh = JOG_KMH; private set
    @Volatile var jogMaxM = JOG_MAX_M; private set
    @Volatile var runKmh = RUN_KMH; private set
    @Volatile var runMaxM = RUN_MAX_M; private set
    @Volatile var transfers = false; private set
    @Volatile var safetyAccepted = false; private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        try {
            if (settingsFile.isFile) {
                val j = JSONObject(settingsFile.readText())
                button = j.optBoolean("button", true)
                hint = j.optBoolean("hint", true)
                savesMin = j.optInt("savesMin", SAVES_MIN).coerceIn(SAVES_MIN_MIN, SAVES_MIN_MAX)
                tight = j.optBoolean("tight", false)
                jogKmh = j.optDouble("jogKmh", JOG_KMH).coerceIn(JOG_KMH_MIN, JOG_KMH_MAX)
                jogMaxM = j.optInt("jogMaxM", JOG_MAX_M).coerceIn(JOG_M_MIN, JOG_M_MAX)
                runKmh = j.optDouble("runKmh", RUN_KMH).coerceIn(RUN_KMH_MIN, RUN_KMH_MAX)
                runMaxM = j.optInt("runMaxM", RUN_MAX_M).coerceIn(RUN_M_MIN, RUN_M_MAX)
                transfers = j.optBoolean("transfers", false)
                safetyAccepted = j.optBoolean("safetyAccepted", false)
            }
        } catch (e: Exception) {
            // A broken file: start again from the defaults.
        }
        loadRuns()
    }

    val speeds get() = RunMath.Speeds(jogKmh, jogMaxM, runKmh, runMaxM)

    /** Whether any speed or distance is not the default, so Reset has something to do. */
    val speedsChanged get() = jogKmh != JOG_KMH || jogMaxM != JOG_MAX_M || runKmh != RUN_KMH || runMaxM != RUN_MAX_M

    fun setButton(v: Boolean) = change { button = v }
    fun setHint(v: Boolean) = change { hint = v }
    fun setSavesMin(v: Int) = change { savesMin = v.coerceIn(SAVES_MIN_MIN, SAVES_MIN_MAX) }
    fun setTight(v: Boolean) = change { tight = v }
    fun setTransfers(v: Boolean) = change { transfers = v }
    fun acceptSafety() = change { safetyAccepted = true }

    /** A jogging speed, with running kept at least a step faster. */
    fun setJogKmh(v: Double) = change {
        jogKmh = v.coerceIn(JOG_KMH_MIN, JOG_KMH_MAX)
        if (runKmh < jogKmh + KMH_STEP) runKmh = (jogKmh + KMH_STEP).coerceAtMost(RUN_KMH_MAX)
    }

    /** A running speed, with jogging kept at least a step slower. */
    fun setRunKmh(v: Double) = change {
        runKmh = v.coerceIn(RUN_KMH_MIN, RUN_KMH_MAX)
        if (jogKmh > runKmh - KMH_STEP) jogKmh = (runKmh - KMH_STEP).coerceAtLeast(JOG_KMH_MIN)
    }

    fun setJogMaxM(v: Int) = change { jogMaxM = v.coerceIn(JOG_M_MIN, JOG_M_MAX) }
    fun setRunMaxM(v: Int) = change { runMaxM = v.coerceIn(RUN_M_MIN, RUN_M_MAX) }

    fun resetSpeeds() = change {
        jogKmh = JOG_KMH; jogMaxM = JOG_MAX_M; runKmh = RUN_KMH; runMaxM = RUN_MAX_M
    }

    fun onChange(listener: () -> Unit) { listeners += listener }

    private fun change(edit: () -> Unit) {
        synchronized(this) {
            edit()
            val j = JSONObject()
                .put("button", button).put("hint", hint).put("savesMin", savesMin).put("tight", tight)
                .put("jogKmh", jogKmh).put("jogMaxM", jogMaxM).put("runKmh", runKmh).put("runMaxM", runMaxM)
                .put("transfers", transfers).put("safetyAccepted", safetyAccepted)
            write(settingsFile, j.toString())
        }
        for (l in listeners) l()
    }

    // The runs Rush planned, by their walk, so Kav's screens can show them as jogs and runs. A walk is known by its
    // length and its times, which stay the same from the results to the trip page to the step-by-step guide.

    class Run(
        val pace: RunMath.Pace,
        /** The whole run to the stop, in metres and seconds, and the seconds to spare when it was planned. */
        val metres: Int,
        val seconds: Int,
        val spare: Int,
        /** When the bus leaves, seconds since 1970. */
        val board: Long,
        val savedAt: Long,
    ) {
        val tight get() = spare < RunMath.COMFORT_S
    }

    private val runs = ConcurrentHashMap<String, Run>()
    /** The trips planned by a Rush search (their guid), so Kav's own New route from here searches with Rush too. */
    private val rushTrips = ConcurrentHashMap<String, Long>()

    fun walkKey(metres: Int, dep: Long, arr: Long) = "$metres/$dep/$arr"

    fun run(key: String): Run? = runs[key]

    fun putRuns(found: Map<String, Run>, trips: Collection<String>) {
        if (found.isEmpty() && trips.isEmpty()) return
        runs.putAll(found)
        val now = System.currentTimeMillis()
        for (t in trips) rushTrips[t] = now
        saveRuns()
    }

    fun isRushTrip(guid: String) = rushTrips.containsKey(guid)

    private fun loadRuns() {
        try {
            if (!runsFile.isFile) return
            val j = JSONObject(runsFile.readText())
            val now = System.currentTimeMillis()
            j.optJSONObject("runs")?.let { o ->
                for (k in o.keys()) {
                    val r = o.getJSONObject(k)
                    val at = r.optLong("savedAt")
                    if (now - at > KEEP_MS) continue
                    runs[k] = Run(
                        if (r.optString("pace") == "run") RunMath.Pace.RUN else RunMath.Pace.JOG,
                        r.optInt("metres"), r.optInt("seconds"), r.optInt("spare"), r.optLong("board"), at,
                    )
                }
            }
            j.optJSONObject("trips")?.let { o ->
                for (k in o.keys()) { val at = o.optLong(k); if (now - at <= KEEP_MS) rushTrips[k] = at }
            }
        } catch (e: Exception) {
            // Lost runs only mean Kav shows them as walks again.
        }
    }

    private fun saveRuns() {
        synchronized(runsFile) {
            val now = System.currentTimeMillis()
            runs.entries.removeIf { now - it.value.savedAt > KEEP_MS }
            rushTrips.entries.removeIf { now - it.value > KEEP_MS }
            val o = JSONObject()
            for ((k, r) in runs) {
                o.put(k, JSONObject().put("pace", if (r.pace == RunMath.Pace.RUN) "run" else "jog").put("metres", r.metres)
                    .put("seconds", r.seconds).put("spare", r.spare).put("board", r.board).put("savedAt", r.savedAt))
            }
            val t = JSONObject()
            for ((k, at) in rushTrips) t.put(k, at)
            write(runsFile, JSONObject().put("runs", o).put("trips", t).toString())
        }
    }

    private fun write(file: File, text: String) {
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
