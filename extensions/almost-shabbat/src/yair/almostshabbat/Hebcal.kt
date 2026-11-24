package yair.almostshabbat

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** One locality's times for one erev, from Hebcal. Times in seconds since 1970. */
internal class Times(
    val candles: Long,
    val sunset: Long,
    /** "Parashat Vayishlach" and "פרשת וישלח", or empty when that Shabbat has none (a Yom Tov, Chol HaMoed). */
    val parshaEn: String,
    val parshaHe: String,
)

/**
 * Hebcal.com's free APIs: candle lighting (with the locality's own minutes) and the parsha from its calendar, and sunset
 * from its zmanim. Only the locality's point is sent, never the destination. Each locality's times are fetched once
 * per erev and kept on the phone until that day is over.
 */
internal class Hebcal(context: Context, private val log: (String) -> Unit) {
    sealed class State {
        object Loading : State()
        class Ready(val times: Times) : State()
        object Failed : State()
    }

    private val file = File(File(context.noBackupFilesDir, "almost-shabbat").apply { mkdirs() }, "times.json")
    private val states = ConcurrentHashMap<String, State>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val waiting = ConcurrentHashMap<String, CopyOnWriteArrayList<(State) -> Unit>>()
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "almost-shabbat-hebcal").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    init { load() }

    private fun key(place: Place, date: LocalDate) = "${place.id}@$date"

    /** What is known now; starts fetching when nothing is. [done] is told on the main thread once it's fetched. */
    fun get(place: Place, date: LocalDate, done: ((State) -> Unit)? = null): State {
        val key = key(place, date)
        val now = System.currentTimeMillis()
        val known = states[key]
        if (known is State.Ready) return known
        if (known is State.Failed && now - (failedAt[key] ?: 0L) < RETRY_MS) return known
        if (done != null) waiting.getOrPut(key) { CopyOnWriteArrayList() }.add(done)
        if (known !is State.Loading) {
            states[key] = State.Loading
            pool.execute { fetch(key, place, date) }
        }
        return State.Loading
    }

    /** The same, waiting for the answer: only on a thread of our own, never the main one. */
    fun getNow(place: Place, date: LocalDate): State {
        val key = key(place, date)
        (states[key] as? State.Ready)?.let { return it }
        fetch(key, place, date)
        return states[key] ?: State.Failed
    }

    private fun fetch(key: String, place: Place, date: LocalDate) {
        val state = try {
            val times = ask(place, date)
            if (times == null) State.Failed else State.Ready(times)
        } catch (e: Exception) {
            log("Hebcal: ${e.javaClass.simpleName} for ${place.en} on $date")
            State.Failed
        }
        states[key] = state
        if (state is State.Failed) failedAt[key] = System.currentTimeMillis() else { failedAt.remove(key); save() }
        val told = waiting.remove(key) ?: return
        main.post { for (l in told) try { l(state) } catch (e: Exception) { log("Hebcal listener: $e") } }
    }

    private fun ask(place: Place, date: LocalDate): Times? {
        val lat = String.format(Locale.US, "%.4f", place.lat)
        val lon = String.format(Locale.US, "%.4f", place.lon)
        val where = "latitude=$lat&longitude=$lon&tzid=Asia/Jerusalem"
        val next = date.plusDays(1)
        val calendar = http(
            "https://www.hebcal.com/hebcal?v=1&cfg=json&start=$date&end=$next&i=on&c=on&s=on&maj=on" +
                "&min=off&mod=off&nx=off&mf=off&ss=off&b=${place.minutes}&$where"
        ) ?: return null
        val zmanim = http("https://www.hebcal.com/zmanim?cfg=json&date=$date&$where") ?: return null

        var candles = 0L
        var parshaEn = ""
        var parshaHe = ""
        val items = JSONObject(calendar).optJSONArray("items") ?: return null
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val day = item.optString("date")
            when (item.optString("category")) {
                "candles" -> if (day.startsWith(date.toString())) candles = seconds(day)
                "parashat" -> if (day.startsWith(next.toString())) {
                    parshaEn = item.optString("title")
                    parshaHe = item.optString("hebrew")
                }
            }
        }
        val sunsetText = JSONObject(zmanim).optJSONObject("times")?.optString("sunset").orEmpty()
        if (candles <= 0 || sunsetText.isEmpty()) {
            log("Hebcal: no candle lighting or sunset for ${place.en} on $date")
            return null
        }
        return Times(candles, seconds(sunsetText), parshaEn, parshaHe)
    }

    private fun seconds(iso: String) = OffsetDateTime.parse(iso).toEpochSecond()

    private fun http(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "AlmostShabbat/1.0 (Kav extension)")
            if (conn.responseCode != 200) { log("Hebcal answered ${conn.responseCode}"); return null }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            log("Hebcal: couldn't connect (${e.javaClass.simpleName})")
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    // Kept on the phone until the day is over, then thrown away.

    @Synchronized private fun load() {
        try {
            if (!file.isFile) return
            val today = Calendar.today()
            val j = JSONObject(file.readText())
            for (key in j.keys()) {
                val date = LocalDate.parse(key.substringAfter('@'))
                if (date.isBefore(today)) continue
                val t = j.getJSONObject(key)
                states[key] = State.Ready(Times(t.getLong("c"), t.getLong("s"), t.optString("pe"), t.optString("ph")))
            }
        } catch (e: Exception) {
            file.delete()
        }
    }

    @Synchronized private fun save() {
        try {
            val today = Calendar.today()
            val j = JSONObject()
            for ((key, state) in states) {
                if (state !is State.Ready) continue
                if (LocalDate.parse(key.substringAfter('@')).isBefore(today)) { states.remove(key); continue }
                val t = state.times
                j.put(key, JSONObject().put("c", t.candles).put("s", t.sunset).put("pe", t.parshaEn).put("ph", t.parshaHe))
            }
            val tmp = File(file.parentFile, "times.tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            log("Hebcal: couldn't keep the times: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        /** After a failed try, ask again a minute later at the earliest. */
        const val RETRY_MS = 60_000L
    }
}
