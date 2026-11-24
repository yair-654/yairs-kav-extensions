package yair.heroes

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The extension's settings and the rider's photo. Both live in Android's "no backup" folder, which Android never
 * puts in the phone's cloud backup. Nothing here is ever sent anywhere: this extension has no network code.
 */
internal class Store(context: Context) {
    private val dir = File(context.noBackupFilesDir, "heroes-extension").apply { mkdirs() }
    private val file = File(dir, "settings.json")

    /** The rider's choger or Tzav 8, re-encoded as a JPEG with no metadata (no location, no camera details). */
    val photo = File(dir, "document.jpg")
    val photoTemp = File(dir, "document.tmp")

    /** Which trip the stay-awake reminders are on for, so they carry on when Android restarts Kav. */
    val stayAwakeFile = File(dir, "stay-awake.json")

    @Volatile var zuzu = false; private set
    @Volatile var interval = 10; private set
    @Volatile var tzav8 = false; private set
    @Volatile var homeButton = false; private set
    /** Where the choger or Tzav 8 button shows during a trip: "off", "stations" or "every" step. */
    @Volatile var tripMode = "stations"; private set
    @Volatile var stayAwakeOn = false; private set
    @Volatile var banner = true; private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        try {
            if (file.isFile) {
                val j = JSONObject(file.readText())
                zuzu = j.optBoolean("zuzu", false)
                interval = j.optInt("interval", 10).coerceIn(1, 59)
                tzav8 = j.optString("doc") == "tzav8"
                homeButton = j.optBoolean("homeButton", false)
                tripMode = when (val mode = j.optString("tripMode")) {
                    "off", "stations", "every" -> mode
                    else -> if (j.optBoolean("tripButton", true)) "stations" else "off" // version 1.0.2's switch
                }
                stayAwakeOn = j.optBoolean("stayAwakeOn", false)
                banner = j.optBoolean("banner", true)
            }
        } catch (e: Exception) {
            // A broken file: start again from the defaults.
        }
    }

    fun hasPhoto() = photo.isFile && photo.length() > 0

    fun setZuzu(v: Boolean) = change { zuzu = v }
    fun setInterval(v: Int) = change { interval = v.coerceIn(1, 59) }
    fun setTzav8(v: Boolean) = change { tzav8 = v }
    fun setHomeButton(v: Boolean) = change { homeButton = v }
    fun setTripMode(v: String) = change { tripMode = v }
    fun setStayAwakeOn(v: Boolean) = change { stayAwakeOn = v }
    fun setBanner(v: Boolean) = change { banner = v }

    fun onChange(listener: () -> Unit) { listeners += listener }

    private fun change(edit: () -> Unit) {
        synchronized(this) {
            edit()
            val j = JSONObject()
                .put("zuzu", zuzu)
                .put("interval", interval)
                .put("doc", if (tzav8) "tzav8" else "choger")
                .put("homeButton", homeButton)
                .put("tripMode", tripMode)
                .put("stayAwakeOn", stayAwakeOn)
                .put("banner", banner)
            val tmp = File(dir, "settings.tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        for (l in listeners) l()
    }
}
