package yair.rush

/**
 * The run itself: can the rider still make a bus by jogging or running from where they are, and with how much to spare.
 * Plain arithmetic with no Android or Kav in it, so it can be tested on its own.
 *
 * What it knows well: the walk's length along the streets (Kav's planner gives it in metres), when the bus leaves (its
 * live time when Kav has one), and the time now. What it has to guess, and allows for: getting going (closing the app,
 * standing up, getting off a bus), and the crossings on the way, counted from the walk's turns, since a planner never
 * counts time waiting at lights.
 */
internal object RunMath {
    /** How fast Kav shows a walk (its server's own walking pace, m/s). */
    const val WALK_MS = 1.33
    /** Getting going, from a search's start: closing the app and setting off. */
    const val START_S = 20
    /** Getting off a bus or train at a change, and finding the way out. */
    const val GET_OFF_S = 25
    /** Each turn on the way may be a crossing; this long for each, at most CROSSINGS_MAX of them. */
    const val CROSSING_S = 10
    const val CROSSINGS_MAX = 4
    /** At least this much to spare, or the run isn't offered at all. */
    const val MIN_TIGHT_S = 20
    /** Under this much to spare, a run is "tight". */
    const val COMFORT_S = 60

    enum class Pace { JOG, RUN }

    class Speeds(val jogKmh: Double, val jogMaxM: Int, val runKmh: Double, val runMaxM: Int)

    /** The run chosen: jog or run, how long it takes in all (getting going and crossings too), and the seconds to spare. */
    class Choice(val pace: Pace, val seconds: Int, val spare: Int) {
        val tight get() = spare < COMFORT_S
    }

    /** Seconds on foot at a speed, with getting going and the crossings. */
    fun seconds(metres: Int, kmh: Double, turns: Int, startS: Int): Int =
        startS + minOf(turns, CROSSINGS_MAX) * CROSSING_S + Math.ceil(metres / (kmh / 3.6)).toInt()

    /**
     * How to make a bus leaving at [board] (seconds since 1970) after [metres] on foot with [turns] turns, setting out
     * at [from]. Null when walking makes it anyway (no need to run), or when not even a run within the rider's limits
     * leaves enough to spare. A jog is chosen whenever a jog is enough; a tight one only when [allowTight].
     */
    fun choose(metres: Int, turns: Int, from: Long, board: Long, speeds: Speeds, allowTight: Boolean, startS: Int): Choice? {
        val walk = seconds(metres, WALK_MS * 3.6, turns, startS)
        if (board - (from + walk) >= COMFORT_S) return null

        fun at(pace: Pace): Choice? {
            val (kmh, max) = if (pace == Pace.JOG) speeds.jogKmh to speeds.jogMaxM else speeds.runKmh to speeds.runMaxM
            if (metres > max) return null
            val s = seconds(metres, kmh, turns, startS)
            return Choice(pace, s, (board - (from + s)).toInt())
        }
        val jog = at(Pace.JOG)
        val run = at(Pace.RUN)
        val comfortable = listOfNotNull(jog, run).firstOrNull { it.spare >= COMFORT_S }
        if (comfortable != null) return comfortable
        if (!allowTight) return null
        return listOfNotNull(jog, run).firstOrNull { it.spare >= MIN_TIGHT_S }
    }

    /** Minutes to show for a run: never 0. */
    fun minutes(seconds: Int) = maxOf(1, (seconds + 30) / 60)
}
