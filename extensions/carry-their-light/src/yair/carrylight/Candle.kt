package yair.carrylight

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** A small memorial candle, drawn here because an extension has no pictures of its own in Kav. */
internal object Candle {
    fun bitmap(sizePx: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(sizePx / 96f, sizePx / 96f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val flame = Path().apply {
            moveTo(48f, 8f)
            cubicTo(60f, 22f, 64f, 32f, 64f, 40f)
            cubicTo(64f, 49f, 57f, 55f, 48f, 55f)
            cubicTo(39f, 55f, 32f, 49f, 32f, 40f)
            cubicTo(32f, 32f, 36f, 22f, 48f, 8f)
            close()
        }
        c.drawPath(flame, p)
        c.drawRoundRect(RectF(26f, 62f, 70f, 92f), 8f, 8f, p)
        return bmp
    }
}
