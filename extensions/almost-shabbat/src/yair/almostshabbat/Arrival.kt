package yair.almostshabbat

/** What a trip's arrival is compared with: the rider's choice in settings. */
internal enum class Ref { CANDLES, SUNSET }

/**
 * How a trip's arrival stands against candle lighting or sunset. Plain Kotlin with no Android in it, so it can be
 * tested on a computer (tests/ArrivalTest.kt).
 */
internal class Verdict(val kind: Kind, val minutes: Int, val band: Int = 0) {
    enum class Kind {
        /** 2 hours or more before. */
        MORE_THAN_2H,
        /** Within [band] minutes (120, 60, 45 or 30): 20 minutes or more before. */
        WITHIN,
        /** Under 20 minutes before: [minutes] exactly. */
        EXACT,
        /** After candle lighting but before sunset, with candle lighting chosen: [minutes] after candle lighting. */
        AFTER_CANDLES,
        /** After sunset: [minutes] after sunset, whichever time the rider chose. */
        AFTER_START,
    }

    /** In Kav's soft warning colour: under 30 minutes before, and every kind of after. */
    val warn get() = kind == Kind.EXACT || kind == Kind.AFTER_CANDLES || kind == Kind.AFTER_START ||
        (kind == Kind.WITHIN && band <= 30)

    /** For the delay notification: a higher level is worse. 2h, 1h, 45, 30 and 15 minutes, then the two kinds of after. */
    val level: Int get() = when (kind) {
        Kind.MORE_THAN_2H -> 0
        Kind.WITHIN -> when (band) { 120 -> 1; 60 -> 2; 45 -> 3; else -> 4 }
        Kind.EXACT -> if (minutes >= 15) 4 else 5
        Kind.AFTER_CANDLES -> 6
        Kind.AFTER_START -> 7
    }

    companion object {
        /** All times in seconds since 1970. */
        fun of(arrivalSec: Long, candlesSec: Long, sunsetSec: Long, ref: Ref): Verdict {
            // To the whole minute, as Kav and Hebcal show the times, so "4:42" and "4:34" make 8 minutes, not 9.
            val arrival = arrivalSec / 60 * 60
            val candles = candlesSec / 60 * 60
            val sunset = sunsetSec / 60 * 60
            val refTime = if (ref == Ref.CANDLES) candles else sunset
            if (arrival > sunset) return Verdict(Kind.AFTER_START, ceilMinutes(arrival - sunset))
            if (ref == Ref.CANDLES && arrival > candles) return Verdict(Kind.AFTER_CANDLES, ceilMinutes(arrival - candles))
            val m = ((refTime - arrival) / 60).toInt()
            return when {
                m >= 120 -> Verdict(Kind.MORE_THAN_2H, m)
                m >= 60 -> Verdict(Kind.WITHIN, m, 120)
                m >= 45 -> Verdict(Kind.WITHIN, m, 60)
                m >= 30 -> Verdict(Kind.WITHIN, m, 45)
                m >= 20 -> Verdict(Kind.WITHIN, m, 30)
                else -> Verdict(Kind.EXACT, m)
            }
        }

        private fun ceilMinutes(seconds: Long) = ((seconds + 59) / 60).toInt()

        /** To the nearest 5 minutes. */
        fun round5(minutes: Int) = ((minutes + 2) / 5) * 5
    }
}

/**
 * The sentences, in English and Hebrew (a trip, נסיעה, is feminine). [ref] is the rider's setting; [begins] what begins
 * that evening, as Erev.beginsName gives it.
 */
internal class Words(private val he: Boolean, private val ref: Ref, private val begins: String) {
    private val refName = when (ref) {
        Ref.CANDLES -> if (he) "הדלקת נרות" else "candle lighting"
        Ref.SUNSET -> if (he) "השקיעה" else "sunset"
    }

    /** The line under a search result: "Arrives within 45 min of candle lighting". */
    fun resultLine(v: Verdict): String = (if (he) "מגיעה " else "Arrives ") + phrase(v, short = true, approx = false)

    /** The sentence on the trip page: "This trip arrives approximately 1 hour and 25 minutes before candle lighting". */
    fun tripSentence(v: Verdict): String = (if (he) "הנסיעה מגיעה " else "This trip arrives ") + phrase(v, short = false, approx = true)

    /** The delay notification: "Because of a delay, this trip now arrives within 30 minutes of candle lighting (16:05)". */
    fun delayNotice(v: Verdict, candles: String, sunset: String): String {
        // The time in brackets: the one the sentence is about. After Shabbat begins, that is sunset.
        val time = when {
            v.kind == Verdict.Kind.AFTER_START && ref == Ref.CANDLES -> (if (he) "שקיעה " else "sunset ") + sunset
            v.kind == Verdict.Kind.AFTER_START || ref == Ref.SUNSET -> sunset
            else -> candles
        }
        return (if (he) "בגלל עיכוב, הנסיעה מגיעה עכשיו " else "Because of a delay, this trip now arrives ") +
            phrase(v, short = false, approx = false) + " ($time)"
    }

    private fun phrase(v: Verdict, short: Boolean, approx: Boolean): String = when (v.kind) {
        Verdict.Kind.MORE_THAN_2H -> if (he) "יותר משעתיים לפני $refName" else "more than 2 hours before $refName"
        Verdict.Kind.WITHIN ->
            if (approx) about(Verdict.round5(v.minutes)) + before()
            else if (he) "פחות " + when (v.band) {
                120 -> "משעתיים"; 60 -> "משעה"; 45 -> "מ-45 דקות"; else -> "מחצי שעה"
            } + before()
            else "within " + when (v.band) {
                120 -> "2 hours"; 60 -> "1 hour"; 45 -> if (short) "45 min" else "45 minutes"; else -> if (short) "30 min" else "30 minutes"
            } + " of $refName"
        Verdict.Kind.EXACT ->
            if (v.minutes == 0) (if (he) "בזמן $refName" else "at $refName")
            else span(v.minutes, short) + before()
        Verdict.Kind.AFTER_CANDLES ->
            if (he) span(v.minutes, short) + " אחרי הדלקת נרות, אבל לפני השקיעה"
            else span(v.minutes, short) + " after candle lighting, but before sunset"
        Verdict.Kind.AFTER_START ->
            if (short) (if (he) "אחרי כניסת $begins" else "after $begins begins") + sunsetNote()
            else if (!approx) (if (he) "אחרי כניסת $begins" else "after $begins begins")
            else (if (v.minutes < 20) span(v.minutes, false) else about(Verdict.round5(v.minutes))) +
                (if (he) " אחרי כניסת $begins" else " after $begins begins") + sunsetNote()
    }

    /** Shabbat begins at sunset: say so to a rider who chose candle lighting. The notification gives "(sunset 16:25)". */
    private fun sunsetNote() = if (ref == Ref.CANDLES) (if (he) " (שקיעה)" else " (sunset)") else ""

    private fun before() = if (he) " לפני $refName" else " before $refName"

    private fun about(minutes: Int) = (if (he) "בערך " else "approximately ") + span(minutes, false)

    /** "12 min", "12 minutes", "1 hour and 25 minutes"; "12 דקות", "שעה ו-25 דקות". */
    fun span(minutes: Int, short: Boolean): String {
        val h = minutes / 60
        val m = minutes % 60
        if (he) {
            val mins = if (m == 1) "דקה אחת" else "$m דקות"
            if (h == 0) return mins
            val hours = when (h) { 1 -> "שעה"; 2 -> "שעתיים"; else -> "$h שעות" }
            return if (m == 0) hours else "$hours ו-$mins"
        }
        val mins = if (short) "$m min" else if (m == 1) "1 minute" else "$m minutes"
        if (h == 0) return mins
        val hours = if (h == 1) "1 hour" else "$h hours"
        return if (m == 0) hours else "$hours and $mins"
    }
}
