package yair.heroes

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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

/** One of Kav's sizes (gap1 to gap8, rCard...) in pixels. */
internal fun KavUi.px(context: Context, name: String) =
    (dp(name) * context.resources.displayMetrics.density).toInt()

/** A text in Kav's colors and type sizes: style is one of Kav's text styles, color one of its color roles. */
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

internal fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

internal fun LinearLayout.addWithGap(view: View, gapPx: Int) {
    addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        if (childCount > 0) topMargin = gapPx
    })
}
