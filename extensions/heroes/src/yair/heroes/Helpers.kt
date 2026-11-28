package yair.heroes

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kav.kavapp.api.KavUi

/** The Activity behind a view's Context, for dialogs and starting other screens. */
internal fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private val mainHandler = Handler(Looper.getMainLooper())

/** Runs on the main thread: at once when already on it, else as soon as it's free. */
internal fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
}

/** One of Kav's sizes (gap1 to gap8, rCard...) in pixels. */
internal fun KavUi.px(context: Context, name: String) =
    (dp(name) * context.resources.displayMetrics.density).toInt()

/** Plain dp in pixels. */
internal fun Context.dpx(dp: Float) = (dp * resources.displayMetrics.density).toInt()

/** A text in Kav's colors and type sizes: style is one of Kav's text styles, color one of its color roles. */
internal fun KavUi.text(context: Context, value: String, style: String = "body", color: String = "text") =
    TextView(context).apply {
        text = value
        setTextColor(this@text.color(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, this@text.textSize(style))
    }

/** The small grey explanation Kav puts under a setting, inset like Kav's rows. */
internal fun KavUi.explain(context: Context, value: String) =
    text(context, value, "subhead", "textSecondary").apply {
        val g = this@explain.px(context, "gap4")
        setPaddingRelative(g, this@explain.px(context, "gap1"), g, this@explain.px(context, "gap2"))
    }

internal fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

internal fun LinearLayout.addWithGap(view: View, gapPx: Int) {
    addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        if (childCount > 0) topMargin = gapPx
    })
}

/**
 * A small pill in Kav's look, for the extension's own buttons in navigation: Kav's raised surface with a thin border
 * when off, Kav's accent when on. Fully rounded, like Kav's chips. highlighted:
 * Kav's accent as a thicker border, a step above a plain pill.
 */
internal fun KavUi.pillBackground(context: Context, on: Boolean, highlighted: Boolean = false) = GradientDrawable().apply {
    cornerRadius = 999f * context.resources.displayMetrics.density
    when {
        on -> setColor(color("accent"))
        // A little more than a plain pill: Kav's accent as its border, for the one place it matters most.
        highlighted -> {
            setColor(color("surfaceRaised"))
            setStroke(context.dpx(2f).coerceAtLeast(2), color("accent"))
        }
        else -> {
            setColor(color("surfaceRaised"))
            setStroke(context.dpx(1f).coerceAtLeast(1), color("separator"))
        }
    }
}
