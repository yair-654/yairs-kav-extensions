package yair.carrylight

import kav.kavapp.api.Kav
import org.json.JSONObject

/** The rider's settings, as Kav keeps them (the manifest declares them and Kav draws them). */
internal class Prefs(json: String) {
    private val o = try { JSONObject(json) } catch (e: Exception) { JSONObject() }

    /** The shortest long ride, in minutes on board. */
    val minutes: Int = o.optInt("minutes", 15).coerceIn(5, 90)
    /** A hero on every long ride; otherwise one per trip, on its longest. */
    val every: Boolean = o.optString("heroes") == "every"
    val waits: Boolean = o.optBoolean("waits", false)
    val notify: Boolean = o.optBoolean("notify", false)
    /** "kav", "en" or "he". */
    val language: String = o.optString("language").takeIf { it == "en" || it == "he" } ?: "kav"
}

/**
 * The two languages. The extension's own words follow Kav's; the stories (what comes from stickers.org.il, and its
 * pages) follow the story language: like Kav, English or Hebrew.
 */
internal class Lang(private val kav: Kav, private val prefs: () -> Prefs) {
    /** Kav's language, for the extension's own words. */
    val ui: Boolean get() = kav.locale() == "he"

    /** The stories' language. */
    val story: Boolean get() = when (prefs().language) {
        "he" -> true
        "en" -> false
        else -> ui
    }

    val texts: Texts get() = Texts(ui)
}
