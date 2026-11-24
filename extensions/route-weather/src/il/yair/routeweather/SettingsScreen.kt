package il.yair.routeweather

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import il.yair.routeweather.IMS.Kind
import il.yair.routeweather.RouteWeather.KindConfig

/**
 * The extension's own settings view, under the settings Kav draws (units, rain threshold): the ten conditions in one
 * Kav card, each row with a ≡ handle to drag it up or down and a switch. The list is built once; a switch or a drag
 * changes only that row, and saving happens off the main thread, so nothing jumps or stutters.
 * TalkBack users move a condition with the handle's "Move up" / "Move down" actions instead of dragging.
 */
class SettingsScreen(private val rw: RouteWeather, private val c: Context) {

    private fun t(en: String, he: String) = rw.t(en, he)
    private val ui = rw.kav.ui()
    private lateinit var list: LinearLayout

    companion object {
        private val ACTION_UP = View.generateViewId()
        private val ACTION_DOWN = View.generateViewId()
    }

    fun view(): View {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(ui.header(c, t("What to warn about first", "על מה להזהיר קודם")))
        box.addView(TextView(c).apply {
            text = t("The results line names the highest switched-on condition on your trip. Drag to reorder. A condition you switch off is never mentioned.",
                "שורת התוצאות מציינת את התנאי הגבוה ביותר שמופעל בנסיעה שלכם. גררו כדי לשנות את הסדר. תנאי שכיביתם לא יוזכר אף פעם.")
            setTextColor(ui.color("textSecondary")); textSize = ui.textSize("subhead")
            setPadding(0, 0, 0, rw.pxOf(ui.dp("gap3")))
        })

        list = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setColor(ui.color("separator")); setSize(0, 1) }
            dividerPadding = rw.pxOf(12f)
        }
        fillList()
        box.addView(ui.card(c, list), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        box.addView(ui.button(c, t("Reset to default order", "איפוס לסדר ברירת המחדל"), false) {
            rw.saveKinds(KindConfig(IMS.DEFAULT_ORDER, rw.kinds.enabled))
            fillList()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = rw.pxOf(ui.dp("gap3"))
        })
        return box
    }

    private fun fillList() {
        list.removeAllViews()
        for (id in rw.kinds.order) list.addView(row(id), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun label(id: String): String = Kind.of(id)?.let { rw.kindLabel(it) } ?: id

    private fun row(id: String): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = rw.pxOf(52f)
            tag = id
        }
        val handle = TextView(c).apply {
            text = "≡"
            gravity = Gravity.CENTER
            setTextColor(ui.color("textTertiary"))
            textSize = ui.textSize("title")
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            contentDescription = t("Reorder ${label(id)}", "שינוי המיקום של ${label(id)}")
        }
        handle.setOnTouchListener(Drag(row))
        handle.accessibilityDelegate = MoveActions(row, id)
        row.addView(handle, LinearLayout.LayoutParams(rw.pxOf(40f), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(ui.switchRow(c, label(id), null, rw.kinds.isEnabled(id)) { now -> toggle(id, now) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun toggle(id: String, on: Boolean) {
        val cfg = rw.kinds
        val enabled = cfg.enabled.toMutableSet()
        if (on) enabled.add(id) else enabled.remove(id)
        rw.saveKinds(KindConfig(cfg.order, enabled))
    }

    private fun currentOrder(): List<String> = (0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? String }

    private fun saveOrder() {
        val order = currentOrder()
        if (order != rw.kinds.order) rw.saveKinds(KindConfig(order, rw.kinds.enabled))
    }

    /** Moves a row one place, for TalkBack, and says where it landed. */
    private fun move(row: View, id: String, delta: Int): Boolean {
        val i = list.indexOfChild(row)
        val to = i + delta
        if (i < 0 || to < 0 || to >= list.childCount) return false
        list.removeView(row)
        list.addView(row, to)
        saveOrder()
        row.announceForAccessibility(t("${label(id)}, position ${to + 1} of ${list.childCount}", "${label(id)}, מקום ${to + 1} מתוך ${list.childCount}"))
        return true
    }

    private inner class MoveActions(private val row: View, private val id: String) : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            val i = list.indexOfChild(row)
            if (i > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_UP, t("Move ${label(id)} up", "העברת ${label(id)} למעלה")))
            if (i in 0 until list.childCount - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_DOWN, t("Move ${label(id)} down", "העברת ${label(id)} למטה")))
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
            ACTION_UP -> move(row, id, -1)
            ACTION_DOWN -> move(row, id, +1)
            else -> super.performAccessibilityAction(host, action, args)
        }
    }

    /**
     * Dragging by the handle. The dragged row follows the finger (translationY); when it passes half of a neighbour,
     * the neighbour is moved to its other side, so the dragged row itself is never taken out of the layout mid-gesture
     * (that would cancel the touch).
     */
    private inner class Drag(private val row: View) : View.OnTouchListener {
        private var startY = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    list.requestDisallowInterceptTouchEvent(true)
                    row.background = GradientDrawable().apply {
                        cornerRadius = ui.dp("rTile") * c.resources.displayMetrics.density
                        setColor(ui.color("surfaceRaised"))
                    }
                    row.elevation = rw.pxOf(6f).toFloat()
                    row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    var dy = e.rawY - startY
                    val i = list.indexOfChild(row)
                    if (dy > 0 && i < list.childCount - 1) {
                        val next = list.getChildAt(i + 1)
                        if (dy > next.height / 2f) {
                            list.removeView(next)
                            list.addView(next, i)
                            startY += next.height
                            dy -= next.height
                            row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    } else if (dy < 0 && i > 0) {
                        val prev = list.getChildAt(i - 1)
                        if (-dy > prev.height / 2f) {
                            list.removeView(prev)
                            list.addView(prev, i)
                            startY -= prev.height
                            dy += prev.height
                            row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    row.translationY = dy
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    list.requestDisallowInterceptTouchEvent(false)
                    row.animate().translationY(0f).setDuration(150).withEndAction {
                        row.elevation = 0f
                        row.background = null
                    }.start()
                    saveOrder()
                    return true
                }
            }
            return false
        }
    }
}
