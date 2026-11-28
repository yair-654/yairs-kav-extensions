package yair.carrylight

/**
 * Every word the extension says itself, in English and Hebrew. Its own words follow Kav's language; the few words
 * inside a hero's card (when they fell) follow the story language.
 */
internal class Texts(private val he: Boolean) {
    private fun t(en: String, he: String) = if (this.he) he else en

    // The hero's card
    val hide get() = t("Hide for this ride", "הסתרה לנסיעה הזו")
    fun fellToday(gender: String?, year: Int) = when (gender) {
        "FEMALE" -> t("Fell on this day in $year", "נפלה היום ב-$year")
        "MALE" -> t("Fell on this day in $year", "נפל היום ב-$year")
        else -> t("Fell on this day in $year", "נפלו היום ב-$year")
    }
    val followHint get() = t(
        "To see heroes on Home too and get the notification, turn on Follow your trips on this extension's page.",
        "כדי לראות גיבורים גם במסך הבית ולקבל התראה, הפעילו את \"מעקב אחרי הנסיעות שלכם\" בעמוד של התוסף."
    )

    // The story screen
    val back get() = t("Back", "חזרה")
    val drishatShalom get() = t("Drishat Shalom", "דרישת שלום")
    val save get() = t("☆ Save", "☆ שמירה")
    val saved get() = t("★ Saved", "★ נשמר")
    val openInBrowser get() = t("Open in browser", "פתיחה בדפדפן")
    val noConnection get() = t("No connection. Try again in a moment.", "אין חיבור. נסו שוב בעוד רגע.")
    val tryAgain get() = t("Try again", "ניסיון חוזר")

    // About Drishat Shalom
    val credit get() = t(
        "Every hero here comes from Drishat Shalom (stickers.org.il), a project of HILMA and Yad Labanim. Their stories open on Drishat Shalom's own site, right inside Kav.",
        "כל גיבור כאן מגיע מדרישת שלום (stickers.org.il), מיזם של הילמ״ה ויד לבנים. הסיפורים נפתחים באתר של דרישת שלום עצמו, ישר בתוך Kav."
    )
    val sawSticker get() = t(
        "Saw a sticker on your way? Add it to Drishat Shalom, so others can meet this hero too. It opens in your browser.",
        "ראיתם מדבקה בדרך? הוסיפו אותה לדרישת שלום, כדי שגם אחרים יכירו את הגיבור. האתר נפתח בדפדפן."
    )
    val addSticker get() = t("Add a sticker", "הוספת מדבקה")
    val visitSite get() = t("Visit Drishat Shalom", "לאתר של דרישת שלום")
    val whereTitle get() = t("Where you'll see them", "איפה פוגשים אותם")
    val whereText get() = t(
        "On a bus, train or light rail ride at least as long as you chose. Only the time on board counts. The hero's card shows in navigation and on Home while you're on that ride, until you open it or tap ✕.",
        "בנסיעה באוטובוס, ברכבת או ברכבת הקלה שאורכה לפחות מה שבחרתם. נספר רק הזמן על הרכב. הכרטיס של הגיבור מופיע בניווט ובמסך הבית בזמן הנסיעה הזו, עד שפותחים אותו או לוחצים על ✕."
    )
    val memorialTitle get() = t("Yom HaZikaron", "יום הזיכרון")
    val memorialText get() = t(
        "From the evening siren until 8 pm the next day, every ride of 5 minutes or more counts as long.",
        "מהצפירה בערב ועד 20:00 למחרת, כל נסיעה של 5 דקות ומעלה נחשבת ארוכה."
    )

    // Heroes you met
    val search get() = t("Search by name", "חיפוש לפי שם")
    val savedHeroes get() = t("Saved", "שמורים")
    val everyone get() = t("All the heroes you met", "כל הגיבורים שהכרתם")
    val empty get() = t("The heroes you meet on your rides will be here.", "הגיבורים שתפגשו בנסיעות יופיעו כאן.")
    val noMatch get() = t("No hero by that name", "אין גיבור בשם הזה")
    val clear get() = t("Clear the list", "ניקוי הרשימה")
    val clearText get() = t("This removes every hero you met, saved ones too.", "כל הגיבורים שהכרתם יימחקו, גם השמורים.")
    val clearConfirm get() = t("Clear", "ניקוי")

    // The notification and the widget
    val channel get() = t("Heroes on long rides", "גיבורים בנסיעות ארוכות")
    val widgetEmpty get() = t("Open Kav to meet a hero", "פתחו את Kav כדי להכיר גיבור")
}
