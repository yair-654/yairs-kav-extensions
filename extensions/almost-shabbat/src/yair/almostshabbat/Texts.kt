package yair.almostshabbat

/** The extension's own words, in Kav's language. The arrival sentences are in Words (Arrival.kt). */
internal class Texts(val he: Boolean) {
    val name = if (he) "כמעט שבת" else "Almost Shabbat"
    val noHebcal = if (he) "לא הצלחנו להתחבר ל-Hebcal" else "Couldn't connect to Hebcal"
    val candles = if (he) "הדלקת נרות" else "Candle lighting"
    val sunset = if (he) "שקיעה" else "Sunset"
    val credit = if (he) "הזמנים מ-Hebcal.com" else "Times from Hebcal.com"

    /** Under the times: which locality they are for. */
    fun timesFor(place: String) =
        if (he) "הזמנים לפי $place, היישוב הקרוב ליעד שלכם" else "Times for $place, the nearest locality to your destination"

    /** Once, on the trip card, while Kav hasn't told the extension about a trip: how to get the delay notification. */
    val followHint = if (he)
        "להתראה אם עיכוב מאחר אתכם, הפעילו את \"מעקב אחרי הנסיעות שלכם\" בעמוד התוסף בהגדרות › תוספים."
    else
        "For a notification if a delay makes you later, turn on \"Follow your trips\" on this extension's page in Settings › Extensions."

    val notifyChannel = if (he) "התראות על עיכוב" else "Delay notifications"
}
