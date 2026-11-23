package yair.heroes

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kav.kavapp.api.Kav

/** The views the extension adds to Kav's own screens. */
internal class Views(
    private val kav: Kav,
    private val store: Store,
) {
    private val ui get() = kav.ui()

    /**
     * Home's card, in Kav's own look: rows like Kav's "Pay for a ride", inside the frame Kav already draws with the
     * extension's name. "Pay in Zuzu" when Zuzu is on, the choger or Tzav 8 when its Home button is on.
     */
    fun homeCard(c: Context): View? {
        val zuzu = store.zuzu
        val document = store.homeButton
        if (!zuzu && !document) return null
        val t = Texts.of(kav)
        val rows = column(c)
        if (zuzu) rows.addView(ui.row(c, "bus", t.payInZuzu, null) { Zuzu.open(c) })
        if (document) {
            if (zuzu) rows.addView(separator(c))
            val row = if (store.hasPhoto()) {
                ui.row(c, "check", t.showDoc(store.tzav8), null) { Viewer.show(c, store.photo, t) }
            } else {
                ui.row(c, "plus", t.addDocInSettings, null) { openExtensionSettings(c) }
            }
            rows.addView(row)
        }
        return rows
    }

    /** The thin line Kav draws between rows of one card. */
    private fun separator(c: Context) = View(c).apply {
        setBackgroundColor(ui.color("separator"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = ui.px(c, "gap4")
        }
    }

    /** Kav's Settings › Extensions, where The Heroes' page is one tap away. */
    private fun openExtensionSettings(c: Context) {
        val activity = c.activity() ?: return
        try {
            kav.kavAction("settings.page.open", activity, """{"page": "extensions"}""")
        } catch (e: Exception) {
            kav.log("Couldn't open Settings: ${e.javaClass.simpleName}")
        }
    }

    /** The thank-you banner, under the Earlier / Later row: above everything in the results. Hebrew, centered. */
    fun banner(c: Context): View? {
        if (!store.banner) return null
        return ui.text(c, Texts.BANNER, "caption", "textSecondary").apply {
            textDirection = View.TEXT_DIRECTION_RTL
            gravity = Gravity.CENTER
            val h = ui.px(c, "gap4")
            val v = ui.px(c, "gap2")
            setPadding(h, v, h, v)
            background = GradientDrawable().apply {
                setColor(ui.color("surface"))
                cornerRadius = ui.dp("rTile") * c.resources.displayMetrics.density
            }
        }
    }
}
