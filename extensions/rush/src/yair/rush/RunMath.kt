package yair.rush

/**
 * Whether a jog or a run still makes a bus, and which. Plain Kotlin with no Android or Kav in it, so it can be tested
 * on its own.
 *
 * A run's time is its distance at the rider's speed, plus time to get going (standing up, getting off a bus) and time
 * for the crossings on the way, counted from the walk's turns. It never goes past the speeds and distances the rider set.
 */
object RunMath {
    enum class Kind { JOG, RUN }

    class Speeds(val jogKmh: Double, val jogMetres: Int, val runKmh: Double, val runMetres: Int)

    /** How to make the bus: jog or run, how long it takes in seconds, and how many seconds are left at the stop. */
    class Choice(val kind: Kind, val seconds: Int, val spare: Int) {
        val tight get() = spare < TIGHT_S
    }

    /** Getting going from where a search starts. */
    const val START_S = 20

    /** Getting off a bus and going. */
    const val OFF_BUS_S = 25

    /** Each crossing on the way, at most [CROSSINGS_MAX] of them. */
    const val CROSSING_S = 10
    const val CROSSINGS_MAX = 4

    /** Less than this to spare is tight; less than [MIN_SPARE_S] is never offered. */
    const val TIGHT_S = 60
    const val MIN_SPARE_S = 20

    /** Walking pace for the comparison: no run is offered when walking makes the bus anyway. */
    const val WALK_MS = 1.33

    /** No run this short. */
    const val MIN_METRES = 30

    /** Running is always at least this much faster than jogging, whatever the settings say. */
    const val RUN_OVER_JOG_KMH = 0.5

    /** Seconds to cover [metres] at [kmh], with [crossings] crossings and [startS] to get going. */
    fun seconds(metres: Int, kmh: Double, crossings: Int, startS: Int): Int =
        startS + minOf(crossings, CROSSINGS_MAX) * CROSSING_S + Math.ceil(metres / (kmh / 3.6)).toInt()

    /**
     * How to make a bus leaving at [board] (seconds since 1970) after [metres] on foot with [crossings] crossings, setting
     * out at [from]. A jog whenever a jog is enough, a run only when it isn't, and a tight one only when [tightOk]. Null
     * when none makes it, or walking does anyway.
     */
    fun choose(metres: Int, crossings: Int, from: Long, board: Long, speeds: Speeds, tightOk: Boolean, startS: Int): Choice? {
        if (metres < MIN_METRES) return null
        val window = board - from
        val walk = seconds(metres, WALK_MS * 3.6, crossings, startS)
        if (window - walk >= TIGHT_S) return null
        val runKmh = maxOf(speeds.runKmh, speeds.jogKmh + RUN_OVER_JOG_KMH)
        val options = listOf(Kind.JOG to (speeds.jogKmh to speeds.jogMetres), Kind.RUN to (runKmh to speeds.runMetres))
            .filter { (_, s) -> metres <= s.second }
            .map { (kind, s) -> val t = seconds(metres, s.first, crossings, startS); Choice(kind, t, (window - t).toInt()) }
            .filter { it.spare >= MIN_SPARE_S }
        return options.firstOrNull { !it.tight } ?: if (tightOk) options.firstOrNull() else null
    }

    /** Crossings on a walk from its turns' directions; without turns, one for each 200 m. */
    fun crossings(ways: List<String>, metres: Int): Int =
        if (ways.isEmpty()) minOf(CROSSINGS_MAX, (metres + 199) / 200)
        else ways.count { it != "DEPART" && it != "CONTINUE" }.coerceAtMost(CROSSINGS_MAX)

    /** [total] seconds shared out over walks of these lengths, the getting going on the first. */
    fun share(total: Int, metres: List<Int>, startS: Int): List<Long> {
        if (metres.isEmpty()) return emptyList()
        val moving = (total - startS).coerceAtLeast(0)
        val all = metres.sum().coerceAtLeast(1)
        val parts = metres.map { (moving.toLong() * it / all) }.toMutableList()
        parts[parts.lastIndex] += moving - parts.sum()
        parts[0] += startS.toLong()
        return parts
    }
}
