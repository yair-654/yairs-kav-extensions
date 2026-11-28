package yair.almostshabbat

import android.content.Context
import kav.kavapp.api.Kav
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The rider's three settings, which Kav draws and keeps (the manifest's "settings"), and what the extension keeps of its
 * own, only on this phone, in Android's folder that is never backed up to the cloud.
 */
internal class Store(context: Context, private val kav: Kav) {
    val dir = File(context.noBackupFilesDir, "almost-shabbat").apply { mkdirs() }

    /** The trip the delay notifications are watching, so they carry on when Android restarts Kav. */
    val watchFile = File(dir, "watch.json")

    private val stateFile = File(dir, "state.json")

    @Volatile var ref = Ref.CANDLES; private set
    /** "kav" (follow Kav's language), "he" or "en". */
    @Volatile var parshaLang = "kav"; private set
    @Volatile var placeLang = "kav"; private set

    /** The trip card's hint about "Follow your trips" was shown once; and Kav has told us about a trip, so it's on. */
    @Volatile var hintShown = false; private set
    @Volatile var followSeen = false; private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        // Version 1 kept its own settings here. Kav keeps them now, starting from the defaults.
        File(dir, "settings.json").delete()
        File(dir, "settings.tmp").delete()
        read(kav.settings())
        try {
            if (stateFile.isFile) {
                val j = JSONObject(stateFile.readText())
                hintShown = j.optBoolean("hintShown")
                followSeen = j.optBoolean("followSeen")
            }
        } catch (e: Exception) {
            // A broken file: start again, the hint at most once more.
        }
        kav.onSettings(Kav.SettingsChanged { all ->
            read(all)
            for (l in listeners) l()
        })
    }

    private fun read(settings: String) {
        val j = try { JSONObject(settings) } catch (e: Exception) { JSONObject() }
        ref = if (j.optString("warn") == "sunset") Ref.SUNSET else Ref.CANDLES
        parshaLang = lang(j.optString("parsha-lang"))
        placeLang = lang(j.optString("place-lang"))
    }

    private fun lang(v: String) = if (v == "he" || v == "en") v else "kav"

    /** Hebrew for parsha and holiday names, or for locality names: the setting, or Kav's own language. */
    fun parshaHebrew(kavHebrew: Boolean) = if (parshaLang == "kav") kavHebrew else parshaLang == "he"
    fun placeHebrew(kavHebrew: Boolean) = if (placeLang == "kav") kavHebrew else placeLang == "he"

    /** Told when the rider changes a setting, on a thread of Kav's. */
    fun onChange(listener: () -> Unit) { listeners += listener }

    fun markHintShown() { if (!hintShown) { hintShown = true; saveState() } }
    fun markFollowSeen() { if (!followSeen) { followSeen = true; saveState() } }

    @Synchronized private fun saveState() {
        try {
            val tmp = File(dir, "state.tmp")
            tmp.writeText(JSONObject().put("hintShown", hintShown).put("followSeen", followSeen).toString())
            if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
        } catch (e: Exception) {
            // Not kept: at worst the hint shows once more after Kav restarts.
        }
    }
}
