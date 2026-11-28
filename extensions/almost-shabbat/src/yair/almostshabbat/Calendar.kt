package yair.almostshabbat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What begins on the evening of an erev: Shabbat, a Yom Tov, or both (a Yom Tov on Shabbat). */
internal enum class Holiday(val code: String, val en: String, val he: String) {
    ROSH_HASHANA("rh", "Rosh Hashana", "ראש השנה"),
    YOM_KIPPUR("yk", "Yom Kippur", "יום כיפור"),
    SUKKOT("su", "Sukkot", "סוכות"),
    SHEMINI_ATZERET("sa", "Shemini Atzeret", "שמיני עצרת"),
    PESACH("p1", "Pesach", "פסח"),
    PESACH_7("p7", "the seventh day of Pesach", "שביעי של פסח"),
    SHAVUOT("sh", "Shavuot", "שבועות");

    companion object {
        fun of(code: String) = values().firstOrNull { it.code == code }
    }
}

/** A Shabbat in the middle of a Yom Tov's week: no parsha that week, so the card says which it is. */
internal enum class CholHaMoed(val code: String, val en: String, val he: String) {
    PESACH("cp", "Pesach", "פסח"),
    SUKKOT("cs", "Sukkot", "סוכות");

    companion object {
        fun of(code: String) = values().firstOrNull { it.code == code }
    }
}

/**
 * One erev: the day, and what begins that evening. holiday is null for a plain Shabbat; cholHaMoed is set for a Shabbat
 * Chol ha-Moed, which begins as a plain Shabbat does.
 */
internal class Erev(val date: LocalDate, val shabbat: Boolean, val holiday: Holiday?, val cholHaMoed: CholHaMoed? = null) {
    /** "Shabbat", "Shavuot", "Shabbat Sukkot". */
    fun name(he: Boolean): String = when {
        holiday == null -> if (he) "שבת" else "Shabbat"
        shabbat -> if (he) "שבת ${holiday.he}" else if (holiday == Holiday.PESACH_7) "Shabbat, ${holiday.en}" else "Shabbat ${holiday.en}"
        else -> if (he) holiday.he else holiday.en
    }

    /** What follows "after … begins": "Shabbat" / "השבת", "Shavuot" / "שבועות". */
    fun beginsName(he: Boolean): String = if (holiday == null) (if (he) "השבת" else "Shabbat") else name(he)

    /** "Erev Shavuot", "Erev Shabbat Sukkot", "Erev Shabbat Chol ha-Moed Pesach": the card's title when there is no parsha. */
    fun erevName(he: Boolean): String = when {
        cholHaMoed != null -> if (he) "ערב שבת חול המועד ${cholHaMoed.he}" else "Erev Shabbat Chol ha-Moed ${cholHaMoed.en}"
        he -> "ערב ${name(true)}"
        // "Erev the seventh day of Pesach" reads badly in English.
        holiday == Holiday.PESACH_7 -> "Eve of ${name(false)}"
        else -> "Erev ${name(false)}"
    }
}

/** Israel's clock, and the built-in list of erevs. */
internal object Calendar {
    val ISRAEL: ZoneId = ZoneId.of("Asia/Jerusalem")

    /** Kav starts extensions on a background thread; the list is read once, the first time it's needed. */
    private val erevs: Map<LocalDate, Erev> by lazy {
        val out = HashMap<LocalDate, Erev>()
        for (entry in ErevData.LIST.split(';')) {
            if (entry.length < 9) continue
            val date = LocalDate.of(entry.substring(0, 4).toInt(), entry.substring(4, 6).toInt(), entry.substring(6, 8).toInt())
            val code = entry.substring(8)
            val erev = when {
                code == "s" -> Erev(date, true, null)
                code.startsWith("s+") && CholHaMoed.of(code.substring(2)) != null -> Erev(date, true, null, CholHaMoed.of(code.substring(2)))
                code.startsWith("s+") -> Erev(date, true, Holiday.of(code.substring(2)))
                else -> Erev(date, false, Holiday.of(code))
            }
            out[date] = erev
        }
        out
    }

    val last: LocalDate by lazy { LocalDate.parse(ErevData.LAST) }

    /** The erev a trip arriving at this moment belongs to: arriving on an erev from 12:00 to 23:59 Israel time. */
    fun erevFor(arrivalSec: Long): Erev? {
        if (arrivalSec <= 0) return null
        val at = Instant.ofEpochSecond(arrivalSec).atZone(ISRAEL)
        if (at.hour < 12) return null
        return erevs[at.toLocalDate()]
    }

    fun erevOn(date: LocalDate): Erev? = erevs[date]

    fun today(): LocalDate = LocalDate.now(ISRAEL)
}
