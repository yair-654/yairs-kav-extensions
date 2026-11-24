package yair.heroes

import kav.kavapp.api.Kav

/** Every word a rider sees, in English and Hebrew. Kav's language decides which. */
internal class Texts(private val he: Boolean) {
    companion object {
        fun of(kav: Kav) = Texts(kav.locale() == "he")

        /** The thank-you banner: Hebrew in every language, on purpose. */
        const val BANNER = "תודה, ותשמור על עצמך! 🇮🇱❤️"
    }

    private fun t(en: String, he: String) = if (this.he) he else en

    // Settings, main page
    val paying get() = t("Paying with Zuzu", "תשלום בזוזו")
    val on get() = t("On", "פועל")
    val off get() = t("Off", "כבוי")
    fun onOff(value: Boolean) = if (value) on else off
    val stayAwake get() = t("Stay awake", "להישאר ער")
    val document get() = t("Choger / Tzav 8", "חוגר / צו 8")
    val banner get() = t("Thank-you banner", "באנר תודה")
    val back get() = t("Back", "חזרה")

    // Paying with Zuzu page
    val payWithZuzu get() = t("Pay with Zuzu", "תשלום עם זוזו")
    val payExplain get() = t(
        "Every Pay button in Kav opens Zuzu instead, and a \"Pay in Zuzu\" button appears on Home.",
        "כל כפתורי התשלום ב-Kav יפתחו את זוזו, וכפתור \"תשלום בזוזו\" יופיע במסך הבית."
    )

    // Stay awake page
    val stayPurpose get() = t(
        "A reminder so you don't fall asleep on a trip while your weapon is with you.",
        "תזכורת כדי שלא תירדם בנסיעה כשהנשק עליך."
    )
    val stayHow get() = t(
        "During a trip, tap Stay awake to get a reminder every few minutes. It's off at the start of every trip.",
        "במהלך נסיעה, הקש על \"להישאר ער\" כדי לקבל תזכורת כל כמה דקות. התזכורות כבויות בתחילת כל נסיעה."
    )
    val stayShow get() = t("Show the Stay awake button in trips", "הצגת כפתור 'להישאר ער' בנסיעות")
    val stayShowExplain get() = t("Turn off if you don't need reminders.", "כבה אם אין לך צורך בתזכורות.")
    val stayClosed get() = t(
        "If you swipe Kav away, the extension will still try to remind you, but on some phones the reminders may stop until you open Kav again.",
        "אם תסגור את Kav מהאפליקציות האחרונות, התוסף ינסה להמשיך להזכיר לך, אבל בחלק מהטלפונים התזכורות עלולות להיעצר עד שתפתח את Kav שוב."
    )
    val remindEvery get() = t("Remind me every", "תזכורת כל")
    fun minutes(n: Int) = t("$n min", "$n דק׳")
    val remindExplain get() = t(
        "How often the reminder comes once you turn it on, from 1 to 59 minutes.",
        "כל כמה זמן תגיע התזכורת אחרי שמפעילים אותה, מ-1 עד 59 דקות."
    )

    // Choger / Tzav 8 page
    val uploadQuestion get() = t("What do you want to upload?", "מה תרצו להעלות?")
    val choger get() = t("Choger", "חוגר")
    val tzav8 get() = t("Tzav 8", "צו 8")
    val addPhoto get() = t("Add a photo", "הוספת תמונה")
    val show get() = t("Show", "הצגה")
    val replacePhoto get() = t("Replace photo", "החלפת תמונה")
    val removePhoto get() = t("Remove photo", "הסרת תמונה")
    val removeTitle get() = t("Remove the photo?", "להסיר את התמונה?")
    val removeConfirm get() = t("Remove", "הסרה")
    val docExplain get() = t(
        "It opens full screen at full brightness, so you can show it at train security. During a trip, a button to show it appears at train stations.",
        "התמונה נפתחת במסך מלא ובבהירות מלאה, כדי להציג אותה בבידוק ברכבת. במהלך נסיעה, כפתור להצגתה יופיע בתחנות רכבת."
    )
    val homeButton get() = t("Button on Home", "כפתור במסך הבית")
    fun homeButtonExplain(tzav8: Boolean) = t(
        "Adds a \"${showDoc(tzav8)}\" button to the card on Kav's Home, to open it quickly.",
        "מוסיף כפתור \"${showDoc(tzav8)}\" לכרטיס במסך הבית של Kav, כדי לפתוח אותו מהר."
    )
    val tripButton get() = t("Button during trips", "כפתור במהלך נסיעות")
    val tripOff get() = t("Off", "כבוי")
    val tripStations get() = t("At train stations", "בתחנות רכבת")
    val tripEvery get() = t("Every step", "בכל שלב")
    fun tripButtonExplain(tzav8: Boolean) = t(
        "Where to show a \"${showDoc(tzav8)}\" button during a trip: on your way into a train station, or on every step.",
        "איפה להציג כפתור \"${showDoc(tzav8)}\" במהלך נסיעה: בדרך לתחנת רכבת, או בכל שלב."
    )
    val privacy get() = t(
        "Your photo stays on this phone only. It's never sent anywhere and isn't included in backups.",
        "התמונה נשמרת רק בטלפון הזה. היא אף פעם לא נשלחת לשום מקום ולא נכללת בגיבויים."
    )
    val photoFailed get() = t("Couldn't add the photo. Try another one.", "לא הצלחנו להוסיף את התמונה. נסו תמונה אחרת.")
    val pickUnavailable get() = t("Adding a photo doesn't work in this version of Kav.", "הוספת תמונה לא עובדת בגרסה הזו של Kav.")
    val close get() = t("Close", "סגירה")

    // Thank-you banner page
    val showBanner get() = t("Show the banner", "הצגת הבאנר")
    val bannerExplain get() = t(
        "A small \"$BANNER\" above your search results.",
        "שורה קטנה \"$BANNER\" מעל תוצאות החיפוש."
    )

    // Home card and trips
    val payInZuzu get() = t("Pay in Zuzu", "תשלום בזוזו")
    fun showDoc(tzav8: Boolean) = if (tzav8) t("Show Tzav 8", "הצגת הצו 8") else t("Show choger", "הצגת החוגר")
    val addDocInSettings get() = t("Add your choger or Tzav 8 in settings", "הוסיפו את החוגר או הצו 8 בהגדרות")
    val chipOff get() = t("⏰ Stay awake", "⏰ להישאר ער")
    fun chipOn(minutes: Int) = t("⏰ Every $minutes min", "⏰ כל $minutes דק׳")

    // Notification
    val notifTitle get() = t("Stay awake: your weapon is with you", "להישאר ער: הנשק עליך")
    val mute get() = t("Mute for this trip", "השתקה לנסיעה הזו")
}
