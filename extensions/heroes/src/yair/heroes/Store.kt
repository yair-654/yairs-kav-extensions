package yair.heroes

import android.content.Context
import java.io.File

/**
 * What the extension keeps itself, all in Android's "no backup" folder, which Android never puts in the phone's cloud
 * backup: the rider's photo and the stay-awake reminders under way. The rider's settings are Kav's (declared in the
 * manifest, see [Config]). Nothing here is ever sent anywhere: the extension has no network code.
 */
internal class Store(context: Context) {
    private val dir = File(context.noBackupFilesDir, "heroes-extension").apply { mkdirs() }

    /** The rider's choger or Tzav 8, saved again as a JPEG with no metadata (no location, no camera, no time). */
    val photo = File(dir, "document.jpg")
    val photoTemp = File(dir, "document.tmp")

    /** The stay-awake reminders under way, so they carry on when Android starts Kav again. */
    val stayAwakeFile = File(dir, "stay-awake.json")

    /** Versions 1.x kept their own settings here. Its being there means this rider used 1.x. */
    private val oldSettings = File(dir, "settings.json")

    /** Set once, when 2.0 first starts on a phone that had 1.x: the Home card then says the settings went back to defaults. */
    private val updateNotice = File(dir, "update-notice")

    init {
        if (oldSettings.isFile) {
            try { updateNotice.createNewFile() } catch (e: Exception) { /* no notice: nothing else depends on it */ }
            oldSettings.delete()
            File(dir, "settings.tmp").delete()
        }
    }

    fun hasPhoto() = photo.isFile && photo.length() > 0

    val showUpdateNotice get() = updateNotice.isFile

    /** The rider has seen their settings since the update: the Home card's notice goes. */
    fun updateNoticeSeen() { if (updateNotice.isFile) updateNotice.delete() }
}
