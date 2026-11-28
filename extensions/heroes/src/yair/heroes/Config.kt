package yair.heroes

import kav.kavapp.api.Kav
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The rider's settings, as Kav keeps them: declared in manifest.json, drawn by Kav on the extension's page, and read
 * here. Kav answers each one's default until the rider changes it. Read once at start and again each time the rider
 * changes one (Kav tells [onChange]'s listeners, on a thread of Kav's).
 */
internal class Config(private val kav: Kav) {
    @Volatile var zuzu = false; private set
    @Volatile var tzav8 = false; private set
    @Volatile var docHome = true; private set
    /** Where the document button shows during a trip: "off", "stations" or "every" step. */
    @Volatile var docTrips = "stations"; private set
    @Volatile var stayAwake = false; private set
    @Volatile var interval = 10; private set
    @Volatile var banner = false; private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        read(kav.settings())
        kav.onSettings(Kav.SettingsChanged { all ->
            read(all)
            for (l in listeners) l()
        })
    }

    fun onChange(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun read(text: String?) {
        val j = try { JSONObject(text ?: "{}") } catch (e: Exception) { JSONObject() }
        zuzu = j.optBoolean("zuzu", false)
        tzav8 = j.optString("doc", "choger") == "tzav8"
        docHome = j.optBoolean("doc-home", true)
        docTrips = when (val v = j.optString("doc-trips", "stations")) { "off", "stations", "every" -> v; else -> "stations" }
        stayAwake = j.optBoolean("stay-awake", false)
        interval = j.optInt("interval", 10).coerceIn(1, 59)
        banner = j.optBoolean("banner", false)
    }
}
