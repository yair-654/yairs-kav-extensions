package yair.carrylight

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kav.kavapp.api.KavUi
import java.io.File

internal val main = Handler(Looper.getMainLooper())

/** The Activity behind a view's Context. */
internal fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

internal fun Context.dpx(dp: Float) = (dp * resources.displayMetrics.density).toInt()

/** One of Kav's sizes (gap1 to gap8, rCard...) in pixels. */
internal fun KavUi.px(context: Context, name: String) = context.dpx(dp(name))

/** A text in Kav's colors and type sizes: style is one of Kav's text styles, color one of its color roles. */
internal fun KavUi.text(context: Context, value: String, style: String = "body", color: String = "text") =
    TextView(context).apply {
        text = value
        setTextColor(this@text.color(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, this@text.textSize(style))
    }

internal fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

internal fun row(context: Context) = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

internal fun LinearLayout.addFill(view: View) =
    addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

internal fun LinearLayout.addWeighted(view: View) =
    addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

/** Text in a direction of its own (a story in Hebrew inside an English Kav, say). */
internal fun View.direct(he: Boolean) {
    layoutDirection = if (he) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    textDirection = if (he) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
}

/** Opens an address in the phone's browser (or the app it belongs to). */
internal fun openOutside(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context.activity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        // No browser on the phone: nothing to open it with.
    }
}

/** Their words in quotation marks, once: some stickers already have their own. */
internal fun quoted(words: String): String = "\"${words.trim().trim('"', '״', '“', '”', '\'', '׳').trim()}\""

/** Pictures decoded once, at the size they're shown, so cards appear at once. */
internal object Pictures {
    private val cache = LruCache<String, Bitmap>(8)

    fun load(file: File, maxSidePx: Int): Bitmap? {
        if (!file.isFile) return null
        val key = "${file.path}:${file.lastModified()}:$maxSidePx"
        cache.get(key)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSidePx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.also { cache.put(key, it) }
        } catch (e: Throwable) {
            null
        }
    }
}
