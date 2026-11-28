package yair.heroes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** Small icons drawn in code: an extension has no resources of its own. */
internal object Icons {
    /** The notification's small icon: a white Star of David outline. */
    fun star(): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 9f; strokeJoin = Paint.Join.ROUND
        }
        val c = Canvas(bmp)
        val r = size * 0.42f
        val mid = size / 2f
        for (start in listOf(-90.0, 90.0)) {
            val path = Path()
            for (k in 0..2) {
                val a = Math.toRadians(start + k * 120.0)
                val x = mid + r * Math.cos(a).toFloat()
                val y = mid + r * Math.sin(a).toFloat()
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, paint)
        }
        return bmp
    }

    /** A plain outlined clock, in one color (Kav's grey, or the text color on Kav's accent), sizePx square. */
    fun clock(sizePx: Int, color: Int): Bitmap {
        val size = sizePx.coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val stroke = size * 0.1f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
        }
        val c = Canvas(bmp)
        val mid = size / 2f
        val r = mid - stroke
        c.drawCircle(mid, mid, r, paint)
        c.drawLine(mid, mid, mid, mid - r * 0.55f, paint) // the hour hand, at twelve
        c.drawLine(mid, mid, mid + r * 0.45f, mid, paint) // the minute hand, at three
        return bmp
    }
}
