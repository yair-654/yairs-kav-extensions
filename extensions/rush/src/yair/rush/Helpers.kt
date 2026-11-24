package yair.rush

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kav.kavapp.api.KavUi

internal val main = Handler(Looper.getMainLooper())

/** The Activity behind a view's Context, for dialogs and Kav's actions. */
internal fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** One of Kav's sizes (gap1 to gap8, rCard...) in pixels. */
internal fun KavUi.px(context: Context, name: String) = (dp(name) * context.resources.displayMetrics.density).toInt()

internal fun Context.dpx(dp: Float) = (dp * resources.displayMetrics.density).toInt()

/** A text in Kav's colours and type sizes: style is one of Kav's text styles, color one of its colour roles. */
internal fun KavUi.text(context: Context, value: String, style: String = "body", color: String = "text") =
    TextView(context).apply {
        text = value
        setTextColor(this@text.color(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, this@text.textSize(style))
    }

/** The small grey explanation Kav puts under a setting. */
internal fun KavUi.explain(context: Context, value: String) =
    text(context, value, "subhead", "textSecondary").apply {
        val g = this@explain.px(context, "gap4")
        setPaddingRelative(g, this@explain.px(context, "gap1"), g, this@explain.px(context, "gap3"))
    }

/** Jogging's colour (Kav's amber) or running's (Kav's red), in Kav's look now: light, dark or OLED. */
internal fun KavUi.paceColor(pace: RunMath.Pace) = color(if (pace == RunMath.Pace.RUN) "critical" else "problem")

/** A small round dot of a colour, the mark of a jog or a run. */
internal fun dot(context: Context, color: Int, sizeDp: Float = 10f): View = View(context).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    val s = context.dpx(sizeDp)
    layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = context.dpx(8f) }
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

/** A gap of empty space. */
internal fun space(context: Context, widthPx: Int, heightPx: Int = 1) =
    View(context).apply { layoutParams = LinearLayout.LayoutParams(widthPx, heightPx) }
