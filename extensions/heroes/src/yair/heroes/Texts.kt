package yair.heroes

import kav.kavapp.api.Kav

/**
 * Every word a rider sees that the extension writes itself, in English and Hebrew; Kav's language decides which.
 * The settings' own labels are in manifest.json, where Kav reads them. Hebrew follows Kav's own (plural, "אתם"),
 * except the stay-awake words, which speak to one soldier ("you" masculine), as Yair asked.
 */
internal class Texts(private val he: Boolean) {
    companion object {
        fun of(kav: Kav) = Texts(kav.locale() == "he")

        /** The thank-you banner: Hebrew in every language, on purpose. */
        const val BANNER = "תודה, ותשמור על עצמך! 🇮🇱❤️"
    }

    private fun t(en: String, he: String) = if (this.he) he else en

    // The document, wherever it shows
    fun document(tzav8: Boolean) = if (tzav8) t("Tzav 8", "צו 8") else t("Choger", "חוגר")
    fun showDocument(tzav8: Boolean) = if (tzav8) t("Show Tzav 8", "הצגת הצו 8") else t("Show choger", "הצגת החוגר")
    fun addDocument(tzav8: Boolean) =
        if (tzav8) t("Add your Tzav 8", "הוספת הצו 8") else t("Add your choger", "הוספת החוגר")
    val payInZuzu get() = t("Pay in Zuzu", "תשלום בזוזו")
    val close get() = t("Close", "סגירה")

    // Home card, once after the update to 2.0
    val updatedTitle get() = t("Soldiers was updated", "חיילים עודכן")
    val updatedText get() = t(
        "Your settings were reset. Tap to check them. Your photo is kept.",
        "ההגדרות אופסו. הקישו כדי לבדוק אותן. התמונה נשמרה."
    )

    // Settings: the part under Kav's list
    fun photoHeader(tzav8: Boolean) = if (tzav8) t("Your Tzav 8", "הצו 8 שלכם") else t("Your choger", "החוגר שלכם")
    val addPhoto get() = t("Add a photo", "הוספת תמונה")
    val show get() = t("Show", "הצגה")
    val replacePhoto get() = t("Replace", "החלפה")
    val removePhoto get() = t("Remove", "הסרה")
    val removeTitle get() = t("Remove the photo?", "להסיר את התמונה?")
    val removeText get() = t(
        "You can add it again at any time.",
        "אפשר להוסיף אותה שוב מתי שרוצים."
    )
    val privacy get() = t(
        "Kept only on this phone. Never sent, never backed up.",
        "נשמרת רק בטלפון הזה. לא נשלחת ולא מגובה."
    )
    val photoFailed get() = t("Couldn't add that photo. Try another one.", "לא הצלחנו להוסיף את התמונה הזו. נסו תמונה אחרת.")
    val pickFailed get() = t("Couldn't open your photos. Try again.", "לא הצלחנו לפתוח את התמונות. נסו שוב.")

    val aboutHeader get() = t("About these settings", "על ההגדרות")
    val aboutZuzu get() = t(
        "Pay with Zuzu: Kav's Pay buttons open Zuzu.",
        "תשלום בזוזו: כפתורי התשלום ב-Kav פותחים את זוזו."
    )
    val aboutDocument get() = t(
        "Your document opens full screen at full brightness, for train security.",
        "המסמך נפתח במסך מלא ובבהירות מלאה, בשביל הבידוק ברכבת."
    )
    val aboutDocTrips get() = t(
        "During trips, the button floats over the map: at train stations, or on every step.",
        "בנסיעות, הכפתור מופיע מעל המפה: בתחנות רכבת, או בכל שלב."
    )
    val aboutStayAwake get() = t(
        "Stay awake: a reminder so you don't fall asleep with your weapon. Tap the button in a ride's card. It stops when the trip ends.",
        "להישאר ער: תזכורת כדי שלא תירדם עם הנשק. הקש על הכפתור בכרטיס הנסיעה. היא נעצרת כשהנסיעה נגמרת."
    )
    val aboutBanner get() = t(
        "Thank-you banner: \"$BANNER\" above your search results.",
        "באנר תודה: \"$BANNER\" מעל תוצאות החיפוש."
    )
    val followHeader get() = t("Turn on \"Follow your trips\"", "הפעילו את \"מעקב אחרי הנסיעות שלכם\"")
    val followText get() = t(
        "Switch it on above. The button during trips needs it, and reminders then stop by themselves.",
        "הפעילו אותו למעלה. הכפתור בנסיעות צריך אותו, ואז התזכורות נעצרות לבד."
    )

    // Navigation
    val stayAwake get() = t("Stay awake", "להישאר ער")
    fun stayAwakeOn(minutes: Int) = t("Stay awake · every $minutes min", "להישאר ער · כל $minutes דק׳")

    // The stay-awake notification
    val notifTitle get() = t("Stay awake: your weapon is with you", "להישאר ער: הנשק עליך")
    val notifText get() = t("Tap Mute to stop the reminders for this trip.", "הקש \"השתקה\" כדי לעצור את התזכורות לנסיעה הזו.")
    val mute get() = t("Mute", "השתקה")
    val channel get() = t("Stay-awake reminders", "תזכורות להישאר ער")

    // The widget and its screens
    val noPhoto get() = t(
        "No photo yet. Add it in Settings › Extensions › Soldiers.",
        "עוד אין תמונה. מוסיפים אותה בהגדרות › תוספים › חיילים."
    )
    val openingZuzu get() = t("Opening Zuzu…", "פותחים את זוזו…")
    val openZuzu get() = t("Open Zuzu", "פתיחת זוזו")
}
