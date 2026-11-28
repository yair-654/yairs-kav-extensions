package yair.carrylight

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import kav.kavapp.api.Kav
import org.json.JSONArray
import org.json.JSONObject

/** The extension's own pages, opened from its page in Settings › Extensions (and Read about a hero from the widget). */
internal class Pages(
    private val kav: Kav,
    private val store: Store,
    private val picker: Picker,
    private val story: Story,
    private val lang: Lang,
) {
    private val ui get() = kav.ui()

    /** Add a sticker: a sticker the rider saw, added on Drishat Shalom's site, in the browser. Drawn by Kav. */
    fun addSticker(c: Context): View {
        val t = lang.texts
        val page = JSONObject().put("sections", JSONArray().put(JSONObject().put("blocks", JSONArray()
            .put(text(t.sawSticker))
            .put(link(t.addSticker, Site.addPage(lang.story), primary = true)))))
        return ui.blocks(c, page.toString())
    }

    /** About Drishat Shalom: the credit, where heroes show and Yom HaZikaron. Drawn by Kav. */
    fun about(c: Context): View {
        val t = lang.texts
        val page = JSONObject().put("sections", JSONArray()
            .put(JSONObject().put("blocks", JSONArray()
                .put(text(t.credit))
                .put(link(t.visitSite, Site.home(lang.story), primary = false))))
            .put(JSONObject().put("title", t.whereTitle).put("blocks", JSONArray().put(text(t.whereText))))
            .put(JSONObject().put("title", t.memorialTitle).put("blocks", JSONArray().put(text(t.memorialText)))))
        return ui.blocks(c, page.toString())
    }

    /** Read about a hero: the widget's hero (args {"hero"}), else the next one waiting. They're then met. */
    fun read(c: Context, args: String): View {
        val id = try { JSONObject(args).str("hero") } catch (e: Exception) { null }
        val frame = FrameLayout(c)
        fun load() {
            frame.removeAllViews()
            frame.addView(ProgressBar(c), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            picker.now(id) { s ->
                frame.removeAllViews()
                if (s != null) {
                    // Over the whole screen; under Kav's header only when it can't be.
                    if (!story.read(c, s)) {
                        store.open(s, spot = null)
                        picker.ensure(force = true)
                        frame.addView(story.page(c, s.id, onBack = null), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                } else {
                    frame.addView(offline(c) { load() }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                }
            }
        }
        load()
        return frame
    }

    private fun text(s: String) = JSONObject().put("type", "text").put("text", s)

    private fun link(label: String, url: String, primary: Boolean) = JSONObject().put("type", "button").put("label", label)
        .put("style", if (primary) "primary" else "secondary").put("action", JSONObject().put("link", url))

    private fun offline(c: Context, retry: () -> Unit): View = column(c).apply {
        gravity = Gravity.CENTER
        val g = ui.px(c, "gap6")
        setPadding(g, g, g, g)
        addFill(ui.text(c, lang.texts.noConnection, "body", "textSecondary").apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, ui.px(c, "gap4"))
        })
        addFill(ui.button(c, lang.texts.tryAgain, false) { retry() })
    }

    /** Heroes you met: the saved ones first, a small search, and Clear the list. A tap opens the hero's story. */
    fun met(c: Context): View = MetList(c)

    private inner class MetList(c: Context) : ScrollView(c) {
        private val list = column(c)
        private val rows = column(c)
        private var query = ""
        private var searching = false
        private val changed: () -> Unit = { main.post { if (isAttachedToWindow) fill() } }

        init {
            addView(list)
            fill()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            store.onChange(changed)
            fill()
        }

        override fun onDetachedFromWindow() {
            store.removeListener(changed)
            super.onDetachedFromWindow()
        }

        private fun fill() {
            val c = context
            val t = lang.texts
            list.removeAllViews()
            val g = ui.px(c, "gap4")
            // Kav's page already keeps its sides clear.
            list.setPadding(0, ui.px(c, "gap2"), 0, g)
            val all = store.met()
            if (all.isEmpty()) {
                list.addFill(ui.text(c, t.empty, "body", "textSecondary").apply { setPadding(0, g, 0, g) })
                return
            }

            // A small search button; tapped, a field for a name, in Hebrew or English.
            list.addFill(row(c).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                if (searching) addWeighted(ui.textField(c, query, t.search, false, "search") { q -> query = q; showRows(all) })
                else addView(ui.iconButton(c, "search", t.search) { searching = true; fill() })
            })
            list.addFill(rows)
            showRows(all)

            list.addView(View(c), LinearLayout.LayoutParams(1, g))
            list.addFill(ui.button(c, t.clear, false) { ui.dialog(c, t.clear, t.clearText, t.clearConfirm) { store.clearMet() } })
        }

        private fun showRows(all: List<Store.Met>) {
            val box = rows
            val c = context
            val t = lang.texts
            val he = lang.story
            box.removeAllViews()
            val q = query.trim().lowercase()
            val shown = if (q.isEmpty()) all else all.filter { q in it.title.he.lowercase() || q in it.title.en.lowercase() }
            if (shown.isEmpty()) {
                box.addFill(ui.text(c, t.noMatch, "body", "textSecondary").apply { setPadding(0, ui.px(c, "gap4"), 0, 0) })
                return
            }
            fun section(title: String, heroes: List<Store.Met>, icon: String?) {
                if (heroes.isEmpty()) return
                box.addFill(ui.header(c, title))
                for (m in heroes) box.addFill(ui.row(c, icon, m.title.of(he), quoted(m.quote.of(he))) { story.show(c, m.id) })
            }
            section(t.savedHeroes, shown.filter { it.saved }, "star")
            section(t.everyone, shown.filter { !it.saved }, null)
        }
    }
}
