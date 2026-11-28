package yair.carrylight

import android.icu.util.HebrewCalendar
import java.util.Calendar
import java.util.TimeZone

/**
 * Yom HaZikaron: from the evening siren (20:00 the evening before) until 20:00 on the day, Israel time. Then every
 * ride of 5 minutes or more gets a hero, whatever the rider's settings.
 *
 * The day comes from a list of official dates (Hebcal and timeanddate.com agree on them) and, for years past the
 * list, from the rule in Israeli law: 4 Iyar, moved so that Yom HaAtzmaut (the next day) never falls on Shabbat or
 * its eve or the day after: 5 Iyar on a Friday or Shabbat moves both days earlier to Wednesday and Thursday; 5 Iyar
 * on a Monday moves Yom HaZikaron to Monday, 5 Iyar. When the two ever disagree, the list wins.
 */
internal object Memorial {
    const val MIN_MINUTES = 5

    private val ISRAEL: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

    /** The day itself (the evening before is included by the window), year to "MM-dd". */
    private val official = mapOf(
        2026 to "04-21", 2027 to "05-11", 2028 to "05-01", 2029 to "04-18", 2030 to "05-07",
        2031 to "04-28", 2032 to "04-14", 2033 to "05-03", 2034 to "04-24", 2035 to "05-14", 2036 to "04-30",
    )

    /** Whether it's Yom HaZikaron now (from 20:00 the evening before until 20:00 on the day). */
    fun now(at: Long = System.currentTimeMillis()): Boolean {
        val year = Calendar.getInstance(ISRAEL).apply { timeInMillis = at }.get(Calendar.YEAR)
        for (y in listOf(year, year + 1)) {
            val day = day(y) ?: continue
            val end = day.clone() as Calendar
            end.set(Calendar.HOUR_OF_DAY, 20)
            val start = end.clone() as Calendar
            start.add(Calendar.DAY_OF_MONTH, -1)
            if (at >= start.timeInMillis && at < end.timeInMillis) return true
        }
        return false
    }

    /** Midnight, Israel time, of Yom HaZikaron in this Gregorian year. */
    private fun day(year: Int): Calendar? {
        val cal = Calendar.getInstance(ISRAEL).apply { clear() }
        official[year]?.let { md ->
            cal.set(year, md.substring(0, 2).toInt() - 1, md.substring(3, 5).toInt())
            return cal
        }
        return try {
            // 4 Iyar of the Hebrew year that has its spring in this Gregorian year.
            val hebrew = HebrewCalendar(android.icu.util.TimeZone.getTimeZone("Asia/Jerusalem"))
            hebrew.clear()
            hebrew.set(year + 3760, HebrewCalendar.IYAR, 4, 12, 0)
            cal.timeInMillis = hebrew.timeInMillis
            val fifth = cal.clone() as Calendar
            fifth.add(Calendar.DAY_OF_MONTH, 1)
            val move = when (fifth.get(Calendar.DAY_OF_WEEK)) {
                Calendar.FRIDAY -> -1
                Calendar.SATURDAY -> -2
                Calendar.MONDAY -> 1
                else -> 0
            }
            cal.add(Calendar.DAY_OF_MONTH, move)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal
        } catch (e: Throwable) {
            null
        }
    }
}
