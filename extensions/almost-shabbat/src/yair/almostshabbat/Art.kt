package yair.almostshabbat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Pictures drawn in code, because an extension has no resources of its own. */
internal object Art {
    /** The notification's small icon: two white candles with their flames, on nothing (Android tints it). */
    fun smallIcon(): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        for (cx in listOf(32f, 64f)) {
            // The candle.
            c.drawRoundRect(RectF(cx - 9f, 46f, cx + 9f, 88f), 3f, 3f, paint)
            // Its flame: a drop pointing up.
            val flame = Path().apply {
                moveTo(cx, 10f)
                cubicTo(cx + 11f, 24f, cx + 9f, 38f, cx, 40f)
                cubicTo(cx - 9f, 38f, cx - 11f, 24f, cx, 10f)
                close()
            }
            c.drawPath(flame, paint)
        }
        return bmp
    }
}
