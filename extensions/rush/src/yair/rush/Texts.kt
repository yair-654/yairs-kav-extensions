package yair.rush

import kav.kavapp.api.Kav
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything Rush says, in English and Hebrew, in Kav's language at the moment it's said. */
internal class Texts(private val kav: Kav) {
    private val he get() = kav.locale() == "he"
    private fun t(en: String, he: String) = if (this.he) he else en

    private fun kind(k: RunMath.Kind) = if (k == RunMath.Kind.JOG) t("Jog", "ריצה קלה") else t("Run", "ריצה")

    fun distance(metres: Int): String =
        if (metres >= 1000) {
            val km = String.format(Locale.US, "%.1f", metres / 1000.0).removeSuffix(".0")
            t("$km km", "$km ק״מ")
        } else t("$metres m", "$metres מ׳")

    fun minutes(seconds: Int): String {
        val m = maxOf(1, (seconds + 59) / 60)
        return t("$m min", "$m דק׳")
    }

    /** "1 min to spare", "30 s to spare", or "tight". */
    fun spare(spare: Int, tight: Boolean, seconds: Boolean = false): String = when {
        tight && !seconds -> t("tight", "צמוד")
        spare < 60 -> t("$spare s to spare", "רזרבה של $spare שנ׳")
        spare < 120 -> t("1 min to spare", "רזרבה של דקה")
        else -> (spare / 60).let { t("$it min to spare", "רזרבה של $it דק׳") }
    }

    fun chip(savesSeconds: Long) = (savesSeconds / 60).let { t("Rush · saves $it min", "ממהר · חוסך $it דק׳") }

    /** A run on a route card and a trip's page: "Run 350 m · 2 min · 1 min to spare". */
    fun runLine(r: Runs.Run) = "${kind(r.kind)} ${distance(r.metres)} · ${minutes(r.seconds)} · ${spare(r.spare, r.tight)}"

    /** Over the walk's card in the step-by-step guide: "Run · 1 min to spare · Watch your way". */
    fun guideLine(r: Runs.Run) = "${kind(r.kind)} · ${spare(r.spare, r.tight)} · ${t("Watch your way", "שימו לב לדרך")}"

    fun offerTitle(line: String, at: Long) =
        if (line.isEmpty()) t("Rush: run and catch an earlier connection", "ממהר: רוצו ותתפסו חיבור מוקדם יותר")
        else t("Rush: run and catch line $line at ${clock(at)}", "ממהר: רוצו ותתפסו את קו $line ב־${clock(at)}")

    fun offerBody(kind: RunMath.Kind, metres: Int, seconds: Int, savesSeconds: Long) =
        "${kind(kind)} ${distance(metres)} · ${minutes(seconds)} · " +
            (savesSeconds / 60).let { t("$it min sooner", "$it דק׳ מוקדם יותר") }

    val switch get() = t("Switch", "החלפה")
    val notNow get() = t("Not now", "לא עכשיו")
    val switched get() = t("Rush: trip switched. Run safely.", "ממהר: הנסיעה הוחלפה. רוצו בזהירות.")
    val gone get() = t("Rush: that bus can't be caught any more.", "ממהר: כבר אי אפשר לתפוס את האוטובוס הזה.")
    val notSwitched get() = t("Rush couldn't switch your trip. Open the trip in Kav to try again.",
        "ממהר לא הצליח להחליף את הנסיעה. פתחו את הנסיעה ב־Kav כדי לנסות שוב.")
    val channel get() = t("Rush at changes", "ממהר בהחלפות")

    /** Under Kav's "This connection won't work": "Rush: you can still make line 18 if you run 200 m · 30 s to spare". */
    fun stillMake(line: String, kind: RunMath.Kind, metres: Int, spare: Int, tight: Boolean): String {
        val how = if (kind == RunMath.Kind.JOG) t("jog", "עושים ריצה קלה") else t("run", "רצים")
        val what = if (line.isEmpty()) t("the connection", "את החיבור") else t("line $line", "את קו $line")
        val left = if (tight) "${t("tight", "צמוד")} (${spare(spare, false)})" else spare(spare, false)
        return t("Rush: you can still make $what if you $how ${distance(metres)} · $left",
            "ממהר: אפשר עוד להספיק $what אם $how ${distance(metres)} · $left")
    }

    // The settings page, under Kav's own settings.
    val beforeYouRush get() = t("Before you rush", "לפני שממהרים")
    val safety get() = t(
        "Rush tries to find buses you can catch if you jog or run to the stop, using the bus's live time when there is one.\n\n" +
            "It's a gamble. The times are estimates: buses can come early, lights can hold you up, and you may run slower than set. " +
            "If it says \"tight\", you might miss it.\n\n" +
            "Stay safe. Run on the pavement, cross only on green, keep your phone in your pocket, and watch out for people, " +
            "kerbs and anything in your way. No bus is worth getting hurt for.",
        "ממהר מנסה למצוא אוטובוסים שאפשר לתפוס אם רצים או עושים ריצה קלה לתחנה, לפי הזמן החי של האוטובוס כשיש כזה.\n\n" +
            "זה הימור. הזמנים הם הערכה: אוטובוסים יכולים להגיע מוקדם, רמזורים יכולים לעכב, ואולי תרוצו לאט יותר ממה שהוגדר. " +
            "אם כתוב \"צמוד\", ייתכן שתפספסו.\n\n" +
            "שמרו על עצמכם. רוצו על המדרכה, חצו רק בירוק, השאירו את הטלפון בכיס, ושימו לב לאנשים, למדרכות ולכל מה שבדרך. " +
            "שום אוטובוס לא שווה פציעה.",
    )
    val worksOnly get() = t(
        "Rush works only on searches for leaving now, without stops on the way, and only when Kav itself found routes. " +
            "It shows only when it finds a run worth it, and adds runs only after you tap it.",
        "ממהר עובד רק בחיפוש של יציאה עכשיו, בלי עצירות בדרך, ורק כש־Kav עצמה מצאה מסלולים. " +
            "הוא מופיע רק כשהוא מוצא ריצה שכדאית, ומוסיף ריצות רק אחרי שמקישים עליו.",
    )
    val defaults get() = t("Defaults: jog 8 km/h up to 1 km, run 10 km/h up to 400 m.",
        "ברירת מחדל: ריצה קלה 8 קמ״ש עד 1 ק״מ, ריצה 10 קמ״ש עד 400 מ׳.")
    val needsFollow get() = t("Run between rides too needs \"Follow your trips\" on this page.",
        "\"לרוץ גם בין נסיעות\" צריך את \"מעקב אחרי הנסיעות שלכם\" בעמוד הזה.")

    /** A time of day as Kav shows it: Israel time, 12 or 24 hours as the rider set Kav. */
    fun clock(at: Long): String {
        val twelve = runCatching { JSONObject(kav.prefs()).optInt("clock", 24) == 12 }.getOrDefault(false)
        val f = SimpleDateFormat(if (twelve) "h:mm a" else "HH:mm", Locale.US)
        f.timeZone = TimeZone.getTimeZone("Asia/Jerusalem")
        return f.format(Date(at * 1000))
    }
}
