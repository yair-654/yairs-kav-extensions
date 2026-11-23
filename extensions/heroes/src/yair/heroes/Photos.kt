package yair.heroes

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import kav.kavapp.api.Kav
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Picking the rider's choger or Tzav 8 from the gallery and keeping it on the phone.
 *
 * Android hands a picked photo back to the screen that asked for it, which is one of Kav's. So the extension hooks
 * Android's own Activity.onActivityResult, and takes only the answer with its own request code. Everything else
 * goes on to Kav untouched.
 *
 * The photo is decoded and saved again as a new JPEG: that drops everything the original file carried besides the
 * picture (where it was taken, the camera, the time). It is never logged, never sent anywhere.
 */
internal class Photos(
    private val app: Context,
    private val store: Store,
    private val kav: Kav,
    private val texts: () -> Texts,
) {
    private companion object {
        const val REQUEST = 0x4E31 // within the 16 bits Android's screens allow for a request code
        const val ACTIVITY = "android.app.Activity"
        const val METHOD = "onActivityResult"
        val PARAMS = arrayOf("int", "int", "android.content.Intent")
        const val MAX_SIDE = 3000
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Whether picking a photo works in this Kav. */
    var canPick = false; private set

    /** Takes the gallery's answer when it carries our request code, and only then. */
    private val takeResult = Kav.MethodHook { call ->
        val args = call.args()
        if (args[0] as Int != REQUEST) return@MethodHook
        call.setResult(null) // ours: Kav's own screen never sees it
        val ok = args[1] as Int == Activity.RESULT_OK
        val uri = (args[2] as Intent?)?.data
        if (ok && uri != null) save(uri)
        // Kav may rebuild its settings page as it comes back; after that, the page opens as usual again.
        main.postDelayed({ Settings.pickingPhoto = false }, 2000)
    }
    private val hookedOverrides = HashSet<String>()

    fun install() {
        // Android's own method, which Kav's screens reach when they don't know a result.
        kav.hook(ACTIVITY, METHOD, PARAMS, takeResult, null)
        canPick = kav.hooked(ACTIVITY, METHOD, *PARAMS)
        if (!canPick) kav.log("Photo picking: Android's onActivityResult couldn't be hooked")
    }

    /**
     * Android calls the screen's own onActivityResult first. If Kav's screen has one of its own, hook that one too,
     * so the photo arrives even if it never passes the result on to Android's.
     */
    private fun hookScreen(activity: Activity) {
        var type: Class<*>? = activity.javaClass
        while (type != null && type != Activity::class.java) {
            val method = try {
                type.getDeclaredMethod(METHOD, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
            } catch (e: NoSuchMethodException) {
                null
            }
            if (method != null) {
                val name = "${type.name}.$METHOD"
                if (hookedOverrides.add(name)) {
                    try {
                        kav.hook(method, takeResult, null)
                        canPick = true
                    } catch (e: Throwable) {
                        kav.log("Photo picking: couldn't hook $name")
                    }
                }
                return
            }
            type = type.superclass
        }
    }

    fun onChange(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun pick(context: Context) {
        val activity = context.activity() ?: return
        hookScreen(activity)
        if (!canPick) {
            Toast.makeText(app, texts().pickUnavailable, Toast.LENGTH_LONG).show()
            return
        }
        val picker = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(picker, REQUEST)
        } catch (e: ActivityNotFoundException) {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQUEST)
        }
    }

    fun remove() {
        store.photo.delete()
        changed()
    }

    private fun save(uri: Uri) {
        Thread {
            var saved = false
            try {
                val bitmap = decode(uri)
                if (bitmap != null) {
                    FileOutputStream(store.photoTemp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    bitmap.recycle()
                    // Renaming replaces the old photo in one step, so it's never lost if this fails.
                    saved = store.photoTemp.renameTo(store.photo)
                }
            } catch (e: Throwable) {
                // Only the kind of failure goes in the log, never anything about the photo.
                kav.log("Couldn't save the photo: ${e.javaClass.simpleName}")
            } finally {
                store.photoTemp.delete()
            }
            main.post {
                if (!saved) Toast.makeText(app, texts().photoFailed, Toast.LENGTH_LONG).show()
                changed()
            }
        }.start()
    }

    private fun decode(uri: Uri): Bitmap? {
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val first = resolver.openInputStream(uri) ?: return null
        first.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun changed() { for (l in listeners) l() }
}
