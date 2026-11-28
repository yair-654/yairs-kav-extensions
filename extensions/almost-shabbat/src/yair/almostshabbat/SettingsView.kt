package yair.almostshabbat

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import kav.kavapp.api.Kav

/**
 * The settings, on the extension's page in Settings › Extensions: four settings, each with a short line under it,
 * drawn with Kav's own components so they look and feel like Kav.
 */
internal class SettingsView(private val c: Context, private val kav: Kav, private val store: Store) {
    private val ui = kav.ui()
    private val t = Texts(kav.locale() == "he")
    private val root = column(c)

    fun view(): View {
        fill()
        return root
    }

    private fun fill() {
        root.removeAllViews()

        // Warn me before: Candle lighting / Sunset
        root.addView(label(t.warnBefore))
        root.addView(chips(listOf(Ref.CANDLES to t.warnCandles, Ref.SUNSET to t.warnSunset), store.ref) {
            store.setRef(it); fill()
        })
        root.addView(ui.explain(c, t.warnExplain))

        // Delay notifications
        root.addView(ui.switchRow(c, t.delays, null, store.notify) { on -> store.setNotify(on) })
        root.addView(ui.explain(c, t.delaysExplain(store.ref)))

        // Parsha and holiday names, Locality names: Like Kav / עברית / English
        val langs = listOf("kav" to t.likeKav, "he" to "עברית", "en" to "English")
        root.addView(label(t.parshaNames))
        root.addView(chips(langs, store.parshaLang) { store.setParshaLang(it); fill() })
        root.addView(label(t.placeNames))
        root.addView(chips(langs, store.placeLang) { store.setPlaceLang(it); fill() })

        root.addView(ui.explain(c, t.about).apply { setPaddingRelative(paddingStart, ui.px(c, "gap5"), paddingEnd, paddingBottom) })
    }

    private fun label(text: String) = ui.text(c, text, "subhead", "textSecondary").apply {
        val g = ui.px(c, "gap4")
        setPaddingRelative(g, ui.px(c, "gap3"), g, ui.px(c, "gap2"))
    }

    /** A row of Kav's chips, the chosen one lit. */
    private fun <T> chips(options: List<Pair<T, String>>, chosen: T, pick: (T) -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        val g = ui.px(c, "gap4")
        setPaddingRelative(g, 0, g, 0)
        for ((value, name) in options) {
            if (childCount > 0) addView(View(c), LinearLayout.LayoutParams(ui.px(c, "gap2"), 1))
            addView(ui.chip(c, name, value == chosen) { if (value != chosen) pick(value) })
        }
    }
}
