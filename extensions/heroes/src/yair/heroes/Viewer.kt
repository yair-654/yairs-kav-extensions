package yair.heroes

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File

/** The choger or Tzav 8, full screen, at full brightness, with the screen kept on, and pinch to zoom. */
internal object Viewer {
    fun show(context: Context, file: File, texts: Texts) {
        val activity = context.activity() ?: return
        if (!file.isFile) return
        val metrics = activity.resources.displayMetrics
        val bitmap = decodeFitting(file, maxOf(metrics.widthPixels, metrics.heightPixels) * 2) ?: return

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val image = ZoomImageView(activity).apply { setImageBitmap(bitmap) }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // A plain round ✕, drawn here: Kav's own components can't be drawn in a window of their own like this one.
        val d = metrics.density
        val close = TextView(activity).apply {
            text = "✕"
            contentDescription = texts.close
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66FFFFFF) }
            setOnClickListener { dialog.dismiss() }
        }
        val size = (48 * d).toInt()
        val margin = (16 * d).toInt()
        root.addView(close, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
            setMargins(margin, margin * 2, margin, margin)
        })

        dialog.setContentView(root)
        dialog.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            }
        }
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            bitmap.recycle()
        }
        dialog.show()
    }

    private fun decodeFitting(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** An image that fits the screen and can be pinched, dragged and double-tapped to zoom. */
internal class ZoomImageView(context: Context) : ImageView(context) {
    private val matrixNow = Matrix()
    private var fitScale = 1f
    private var scale = 1f // relative to fitting the screen

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (scale * detector.scaleFactor).coerceIn(1f, 6f)
            val factor = next / scale
            scale = next
            matrixNow.postScale(factor, factor, detector.focusX, detector.focusY)
            settle()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            matrixNow.postTranslate(-dx, -dy)
            settle()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.01f) fit() else {
                scale = 2.5f
                matrixNow.postScale(2.5f, 2.5f, e.x, e.y)
                settle()
            }
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        fitScale = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        scale = 1f
        matrixNow.reset()
        matrixNow.postScale(fitScale, fitScale)
        matrixNow.postTranslate((width - d.intrinsicWidth * fitScale) / 2f, (height - d.intrinsicHeight * fitScale) / 2f)
        imageMatrix = matrixNow
    }

    /** Keeps the picture centered when smaller than the screen and its edges on screen when larger. */
    private fun settle() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrixNow.mapRect(r)
        val dx = when {
            r.width() <= width -> (width - r.width()) / 2f - r.left
            r.left > 0 -> -r.left
            r.right < width -> width - r.right
            else -> 0f
        }
        val dy = when {
            r.height() <= height -> (height - r.height()) / 2f - r.top
            r.top > 0 -> -r.top
            r.bottom < height -> height - r.bottom
            else -> 0f
        }
        matrixNow.postTranslate(dx, dy)
        imageMatrix = matrixNow
    }
}
