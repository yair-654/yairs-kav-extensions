package yair.almostshabbat

/** The extension's own words, in Kav's language. The arrival sentences are in Words (Arrival.kt). */
internal class Texts(val he: Boolean) {
    val name = if (he) "כמעט שבת" else "Almost Shabbat"
    val noHebcal = if (he) "לא הצלחנו להתחבר ל-Hebcal" else "Couldn't connect to Hebcal"
    val candles = if (he) "הדלקת נרות" else "Candle lighting"
    val sunset = if (he) "שקיעה" else "Sunset"
    val thanks = if (he) "הזמנים מ-Hebcal.com. תודה על ה-API החינמי!" else "Times from Hebcal.com. Thank you for the free API!"

    /** Under the times: which locality they are for. */
    fun timesFor(place: String) =
        if (he) "הזמנים לפי $place, היישוב הקרוב ליעד שלכם" else "Times for $place, the nearest locality to your destination"

    // Settings
    val warnBefore = if (he) "התרעה לפני" else "Warn me before"
    val warnCandles = if (he) "הדלקת נרות" else "Candle lighting"
    val warnSunset = if (he) "שקיעה" else "Sunset"
    val warnExplain = if (he) "למה משווים את שעת ההגעה של הנסיעה. בעמוד הנסיעה תמיד מוצגים שני הזמנים."
        else "What a trip's arrival is compared with. The trip's page always shows both times."
    val delays = if (he) "התראות על עיכוב" else "Delay notifications"
    fun delaysExplain(ref: Ref) = when {
        he && ref == Ref.CANDLES -> "אם עיכוב בזמן הנסיעה מקרב את שעת ההגעה להדלקת נרות, תקבלו התראה עם צליל."
        he -> "אם עיכוב בזמן הנסיעה מקרב את שעת ההגעה לשקיעה, תקבלו התראה עם צליל."
        ref == Ref.CANDLES -> "If a delay during your trip brings your arrival closer to candle lighting, you'll get a notification with sound."
        else -> "If a delay during your trip brings your arrival closer to sunset, you'll get a notification with sound."
    }
    val parshaNames = if (he) "שמות פרשה וחג" else "Parsha and holiday names"
    val placeNames = if (he) "שמות יישובים" else "Locality names"
    val likeKav = if (he) "כמו Kav" else "Like Kav"
    val about = if (he)
        "כמעט שבת מופיעה רק בנסיעות שמגיעות בערב שבת או חג, מ-12:00 בצהריים. הזמנים לפי היישוב הקרוב ביותר ליעד, " +
            "ורק היישוב נשלח ל-Hebcal, לעולם לא היעד המדויק. תודה ל-Hebcal.com על ה-API החינמי!"
    else
        "Almost Shabbat only shows for trips arriving on an erev of Shabbat or a holiday, from 12:00 noon. Times are for " +
            "the locality nearest the destination, and only that locality is sent to Hebcal, never the exact destination. " +
            "Thank you to Hebcal.com for the free API!"
    val notifyChannel = if (he) "התראות על עיכוב" else "Delay notifications"
}
