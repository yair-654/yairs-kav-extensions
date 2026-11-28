package yair.heroes

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kav.kavapp.api.Kav
import org.json.JSONArray
import org.json.JSONObject

/** The extension's views on Kav's Home and above search results. */
internal class Views(
    private val kav: Kav,
    private val store: Store,
    private val config: Config,
    private val stayAwake: StayAwake,
) {
    private val ui get() = kav.ui()

    /**
     * Home's card, in Kav's own look: rows like Kav's "Pay for a ride", inside the frame Kav draws with the extension's
     * name. "Pay in Zuzu" while Pay with Zuzu is on; the document while its Home button is on; once after the update to
     * 2.0, a row saying the settings went back to their defaults. No card at all when there's nothing to show.
     */
    fun homeCard(c: Context): View? {
        c.activity()?.let { stayAwake.checkMute(it) }
        val t = Texts.of(kav)
        val rows = column(c)
        fun add(row: View) {
            if (rows.childCount > 0) rows.addView(separator(c))
            rows.addView(row)
        }
        if (store.showUpdateNotice) add(settingsRow(c, "info", t.updatedTitle, t.updatedText))
        if (config.zuzu) add(ui.row(c, "bus", t.payInZuzu, null) { Zuzu.open(c) })
        if (config.docHome) {
            add(
                if (store.hasPhoto()) ui.row(c, "check", t.showDocument(config.tzav8), null) { Viewer.show(c, store.photo, t) }
                else settingsRow(c, "plus", t.addDocument(config.tzav8), null)
            )
        }
        return if (rows.childCount == 0) null else rows
    }

    /**
     * A row that opens this extension's own page in Settings › Extensions. Only Kav's block rows can do that (the
     * action {"settings": true}), so this one row is drawn by Kav from a block. If Kav can't read it, a plain row opens
     * the Extensions list instead, one tap from here.
     */
    private fun settingsRow(c: Context, icon: String, title: String, subtitle: String?): View {
        val row = JSONObject().put("type", "row").put("icon", icon).put("title", title)
            .put("action", JSONObject().put("settings", true))
        if (subtitle != null) row.put("subtitle", subtitle)
        val page = JSONObject().put("sections", JSONArray().put(JSONObject().put("blocks", JSONArray().put(row))))
        return try {
            ui.blocks(c, page.toString())
        } catch (e: Exception) {
            kav.log("Home card: couldn't draw the settings row: ${e.javaClass.simpleName}")
            ui.row(c, icon, title, subtitle) { openExtensions(c) }
        }
    }

    private fun openExtensions(c: Context) {
        val activity = c.activity() ?: return
        try {
            kav.kavAction("settings.page.open", activity, """{"page": "extensions"}""")
        } catch (e: Exception) {
            kav.log("Couldn't open Settings: ${e.javaClass.simpleName}")
        }
    }

    /** The thin line Kav draws between rows of one card. */
    private fun separator(c: Context) = View(c).apply {
        setBackgroundColor(ui.color("separator"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = ui.px(c, "gap4")
        }
    }

    /** The thank-you banner, under the Earlier / Later row: above everything in the results. Hebrew, centered. */
    fun banner(c: Context): View? {
        if (!config.banner) return null
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
