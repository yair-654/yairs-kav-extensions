package yair.almostshabbat

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The rider's four settings, kept only on this phone, in Android's folder that is never backed up to the cloud. */
internal class Store(context: Context) {
    val dir = File(context.noBackupFilesDir, "almost-shabbat").apply { mkdirs() }
    private val file = File(dir, "settings.json")

    /** The trip the delay notifications are watching, so they carry on when Android restarts Kav. */
    val watchFile = File(dir, "watch.json")

    @Volatile var ref = Ref.CANDLES; private set
    @Volatile var notify = true; private set
    /** "kav" (follow Kav's language), "he" or "en". */
    @Volatile var parshaLang = "kav"; private set
    @Volatile var placeLang = "kav"; private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        try {
            if (file.isFile) {
                val j = JSONObject(file.readText())
                ref = if (j.optString("ref") == "sunset") Ref.SUNSET else Ref.CANDLES
                notify = j.optBoolean("notify", true)
                parshaLang = lang(j.optString("parshaLang"))
                placeLang = lang(j.optString("placeLang"))
            }
        } catch (e: Exception) {
            // A broken file: start again from the defaults.
        }
    }

    private fun lang(v: String) = if (v == "he" || v == "en") v else "kav"

    fun setRef(v: Ref) = change { ref = v }
    fun setNotify(v: Boolean) = change { notify = v }
    fun setParshaLang(v: String) = change { parshaLang = lang(v) }
    fun setPlaceLang(v: String) = change { placeLang = lang(v) }

    /** Hebrew for parsha and holiday names, or for locality names: the setting, or Kav's own language. */
    fun parshaHebrew(kavHebrew: Boolean) = if (parshaLang == "kav") kavHebrew else parshaLang == "he"
    fun placeHebrew(kavHebrew: Boolean) = if (placeLang == "kav") kavHebrew else placeLang == "he"

    fun onChange(listener: () -> Unit) { listeners += listener }

    private fun change(edit: () -> Unit) {
        synchronized(this) {
            edit()
            val j = JSONObject()
                .put("ref", if (ref == Ref.SUNSET) "sunset" else "candles")
                .put("notify", notify)
                .put("parshaLang", parshaLang)
                .put("placeLang", placeLang)
            val tmp = File(dir, "settings.tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        for (l in listeners) l()
    }
}
