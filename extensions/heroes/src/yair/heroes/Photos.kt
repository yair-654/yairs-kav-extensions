package yair.heroes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import kav.kavapp.api.Kav
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The rider's choger or Tzav 8: picked with Android's photo picker, which Kav opens over its own screen
 * (`kav.pickFile`, API 5), and kept on the phone.
 *
 * The photo is decoded and saved again as a new JPEG: that drops everything the original file carried besides the
 * picture (where it was taken, the camera, the time). It is never logged and never sent anywhere.
 */
internal class Photos(
    private val app: Context,
    private val store: Store,
    private val kav: Kav,
    private val texts: () -> Texts,
) {
    private companion object {
        const val MAX_SIDE = 3000
    }

    /** One save at a time, in the order the rider picked. */
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun onChange(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun pick() {
        try {
            kav.pickFile(arrayOf("image/*"), Kav.Picked { uri -> if (uri != null) save(Uri.parse(uri)) })
        } catch (e: Exception) {
            kav.log("Photo: couldn't open the picker: ${e.javaClass.simpleName}")
            onMain { Toast.makeText(app, texts().pickFailed, Toast.LENGTH_LONG).show() }
        }
    }

    fun remove() {
        worker.execute {
            store.photo.delete()
            onMain { changed() }
        }
    }

    private fun save(uri: Uri) {
        worker.execute {
            var saved = false
            try {
                val bitmap = decode(uri)
                if (bitmap == null) kav.log("Photo: Android couldn't read it as a picture")
                if (bitmap != null) {
                    try {
                        FileOutputStream(store.photoTemp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    } finally {
                        bitmap.recycle()
                    }
                    // Renaming replaces the old photo in one step, so it's never lost if this fails.
                    saved = store.photoTemp.renameTo(store.photo)
                }
            } catch (e: Throwable) {
                // Only the kind of failure goes in the log, never anything about the photo.
                kav.log("Photo: couldn't save it: ${e.javaClass.simpleName}")
            } finally {
                store.photoTemp.delete()
            }
            onMain {
                if (!saved) Toast.makeText(app, texts().photoFailed, Toast.LENGTH_LONG).show()
                changed()
            }
        }
    }

    private fun decode(uri: Uri): Bitmap? {
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Only measures the photo: Android fills in bounds and answers null on purpose.
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
