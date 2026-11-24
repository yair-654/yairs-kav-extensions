package yair.rush

import kav.kavapp.api.Kav
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every word a rider sees, in English and Hebrew. Kav's language decides which. */
internal class Texts(private val he: Boolean) {
    companion object {
        fun of(kav: Kav) = Texts(kav.locale() == "he")
    }

    private fun t(en: String, he: String) = if (this.he) he else en

    // Numbers

    fun minutes(n: Int) = t("$n min", "$n דק׳")
    fun metres(m: Int) = when {
        m >= 1000 -> {
            val km = if (m % 1000 == 0) "${m / 1000}" else String.format(Locale.US, "%.1f", m / 1000.0)
            t("$km km", "$km ק״מ")
        }
        else -> t("$m m", "$m מ׳")
    }
    fun kmh(v: Double): String {
        val s = if (v % 1.0 == 0.0) "${v.toInt()}" else String.format(Locale.US, "%.1f", v)
        return t("$s km/h", "$s קמ״ש")
    }
    fun clock(epochS: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(epochS * 1000))

    // The run

    fun pace(p: RunMath.Pace) = if (p == RunMath.Pace.RUN) t("Run", "ריצה") else t("Jog", "ריצה קלה")
    fun spare(seconds: Int) = if (seconds < RunMath.COMFORT_S) t("tight", "צמוד")
        else t("${seconds / 60} min to spare", "רזרבה של ${seconds / 60} דק׳")

    /** "Run 350 m · 2 min · 1 min to spare" */
    fun runLine(r: Store.Run) = "${pace(r.pace)} ${metres(r.metres)} · ${minutes(RunMath.minutes(r.seconds))} · ${spare(r.spare)}"
    /** "Run 350 m · 2 min" */
    fun runShort(r: Store.Run) = "${pace(r.pace)} ${metres(r.metres)} · ${minutes(RunMath.minutes(r.seconds))}"
    val tightWarning get() = t("Tight: you might miss it", "צמוד: ייתכן שתפספסו")
    fun busAt(epochS: Long) = t("Bus at ${clock(epochS)}", "האוטובוס ב-${clock(epochS)}")
    val watchYourWay get() = t("Watch your way", "שימו לב לדרך")

    // The Rush mode row

    val rushMode get() = t("🏃 Rush mode", "🏃 מצב ממהר")
    fun saves(min: Int) = t("Running saves $min min", "ריצה חוסכת $min דק׳")
    val checking get() = t("Checking for runs…", "בודקים ריצות…")
    fun routesWithRun(n: Int) = if (n == 1) t("1 route with a run", "מסלול אחד עם ריצה") else t("$n routes with a run", "$n מסלולים עם ריצה")
    val noRuns get() = t("No run worth it right now", "אין ריצה שכדאית עכשיו")
    val couldntCheck get() = t("Couldn't check for runs", "לא הצלחנו לבדוק ריצות")

    // The first time

    val safetyTitle get() = t("Before you rush", "לפני שממהרים")
    val safetyBody get() = t(
        "Rush tries to find buses you can catch if you jog or run to the stop, using the bus's live time when there is one.\n\n" +
            "It's a gamble. The times are estimates: buses can come early, lights can hold you up, and you may run slower " +
            "than set. If it says \"tight\", you might miss it.\n\n" +
            "Stay safe. Run on the pavement, cross only on green, keep your phone in your pocket, and watch out for people, " +
            "kerbs and anything in your way. No bus is worth getting hurt for.",
        "ממהר מנסה למצוא אוטובוסים שאפשר לתפוס אם רצים או עושים ריצה קלה לתחנה, לפי הזמן החי של האוטובוס כשיש כזה.\n\n" +
            "זה הימור. הזמנים הם הערכה: אוטובוסים יכולים להגיע מוקדם, רמזורים יכולים לעכב, ואולי תרוצו לאט יותר ממה " +
            "שהוגדר. אם כתוב \"צמוד\", ייתכן שתפספסו.\n\n" +
            "שמרו על עצמכם. רוצו על המדרכה, חצו רק בירוק, השאירו את הטלפון בכיס, ושימו לב לאנשים, למדרכות ולכל מה " +
            "שבדרך. שום אוטובוס לא שווה פציעה."
    )
    val understand get() = t("I understand", "הבנתי")

    // During a trip

    fun offerTitle(line: String, at: Long) = if (line.isBlank()) t("Run and catch the ${clock(at)}", "רוצו ותתפסו את ה-${clock(at)}")
        else t("Run and catch line $line at ${clock(at)}", "רוצו ותתפסו את קו $line ב-${clock(at)}")
    fun offerBody(r: Store.Run, savesMin: Int) = t(
        "${runShort(r)} · $savesMin min sooner",
        "${runShort(r)} · $savesMin דק׳ מוקדם יותר",
    )
    val offerHow get() = t("Tap Switch to change your trip.", "הקישו \"החלפה\" כדי לשנות את הנסיעה.")
    val switchTrip get() = t("Switch", "החלפה")
    val notNow get() = t("Not now", "לא עכשיו")
    val switched get() = t("Trip switched. Run safely.", "הנסיעה הוחלפה. רוצו בזהירות.")
    val couldntSwitch get() = t("Couldn't switch here. Open your trip in Kav to switch.", "לא הצלחנו להחליף מכאן. פתחו את הנסיעה ב-Kav כדי להחליף.")
    val offerGone get() = t("That bus can't be caught any more.", "את האוטובוס הזה כבר אי אפשר לתפוס.")
    fun replanNote(r: Store.Run) = t(
        "Rush: your new route includes a ${RunMath.minutes(r.seconds)}-min ${if (r.pace == RunMath.Pace.RUN) "run" else "jog"}",
        "ממהר: המסלול החדש כולל ${pace(r.pace)} של ${RunMath.minutes(r.seconds)} דק׳",
    )
    val channel get() = t("Rush at changes", "ממהר בהחלפות")

    // Settings

    val safetyShort get() = t("It's a gamble. Stay safe.", "זה הימור. שמרו על עצמכם.")
    val inResults get() = t("In search results", "בתוצאות החיפוש")
    val buttonTitle get() = t("Rush mode button", "כפתור מצב ממהר")
    val buttonSub get() = t("Under the time and sort, when you leave now", "מתחת לשעה ולמיון, כשיוצאים עכשיו")
    val hintTitle get() = t("Show me when running saves time", "להראות לי מתי ריצה חוסכת זמן")
    val hintSub get() = t("In the results, even when Rush mode is off", "בתוצאות, גם כשמצב ממהר כבוי")
    val savesAtLeast get() = t("Saves at least", "חוסכת לפחות")
    val savesExplain get() = t(
        "Also used during a trip: a faster connection is offered only when it saves at least this much.",
        "משמש גם במהלך נסיעה: חיבור מהיר יותר מוצע רק כשהוא חוסך לפחות את זה.",
    )
    val tightTitle get() = t("Show tight runs", "להראות ריצות צמודות")
    val tightSub get() = t("Less than a minute to spare", "פחות מדקה רזרבה")
    val howFast get() = t("How fast you go", "כמה מהר אתם")
    val jogging get() = t("Jogging", "ריצה קלה")
    val running get() = t("Running", "ריצה")
    val speed get() = t("Speed", "מהירות")
    val upTo get() = t("Up to", "עד")
    val speedsExplain get() = t(
        "Set for a young, healthy adult. Lower them if you'd rather not push it. Rush picks a jog whenever a jog is enough.",
        "מכוון למבוגר צעיר ובריא. אפשר להוריד אם לא רוצים להתאמץ. ממהר בוחר ריצה קלה בכל פעם שהיא מספיקה.",
    )
    val reset get() = t("Reset speeds to default", "איפוס המהירויות לברירת המחדל")
    val duringTrip get() = t("During a trip", "במהלך נסיעה")
    val transfersTitle get() = t("Run between rides too", "לרוץ גם בין נסיעות")
    val transfersSub get() = t("Before each change, checks whether running gets you there sooner", "לפני כל החלפה, בודק אם ריצה תביא אתכם מוקדם יותר")
    val transfersRisk get() = t(
        "Less reliable: if your first bus or train is late, you can miss the next one even if you hurry.",
        "פחות בטוח שיצליח: אם האוטובוס או הרכבת הראשונים מתעכבים, אפשר לפספס את הבא גם אם ממהרים.",
    )
    val transfersHow get() = t(
        "You'll get a notification and a card in the step-by-step guide, with a Switch button.",
        "תקבלו התראה וכרטיס בהנחיות צעד אחר צעד, עם כפתור החלפה.",
    )
    val replanExplain get() = t(
        "When your trip came from Rush mode, Kav's New route from here looks for runs too, and says so.",
        "כשהנסיעה הגיעה ממצב ממהר, \"מסלול חדש מכאן\" של Kav מחפש גם ריצות, ואומר את זה.",
    )
    val notAvailable get() = t(
        "Rush can't work with this version of Kav. Kav works as usual.",
        "ממהר לא יכול לעבוד עם הגרסה הזו של Kav. Kav עובדת כרגיל.",
    )
}
