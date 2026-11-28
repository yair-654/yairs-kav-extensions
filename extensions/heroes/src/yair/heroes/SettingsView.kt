package yair.heroes

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import kav.kavapp.api.Kav

/**
 * The extension's part of its page in Settings › Extensions, under the settings Kav draws from the manifest: the photo,
 * which only the extension can do (add, show, replace, remove), then what each setting above does, grouped in one
 * place, then the one switch of Kav's the trip buttons need. All in Kav's own components.
 */
internal class SettingsView(
    private val c: Context,
    private val kav: Kav,
    private val store: Store,
    private val config: Config,
    private val photos: Photos,
) {
    private val ui = kav.ui()
    private val t = Texts.of(kav)

    fun view(): View {
        // The rider is on their settings: the Home card's note about the update has done its job.
        store.updateNoticeSeen()
        val page = column(c)
        val photo = column(c)
        page.addView(photo)
        fillPhoto(photo)
        // Filled again when the photo is added or removed, or "Your document" changes, while the page is on screen.
        val refill: () -> Unit = { onMain { fillPhoto(photo) } }
        photo.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { photos.onChange(refill); config.onChange(refill); fillPhoto(photo) }
            override fun onViewDetachedFromWindow(v: View) { photos.removeListener(refill); config.removeListener(refill) }
        })

        page.addView(ui.header(c, t.followHeader))
        page.addView(ui.explain(c, t.followText))

        page.addView(ui.header(c, t.aboutHeader))
        for (line in listOf(t.aboutZuzu, t.aboutDocument, t.aboutDocTrips, t.aboutStayAwake, t.aboutBanner)) {
            page.addView(ui.explain(c, line))
        }
        return page
    }

    private fun fillPhoto(box: LinearLayout) {
        box.removeAllViews()
        box.addView(ui.header(c, t.photoHeader(config.tzav8)))
        val buttons = column(c).apply {
            val g = ui.px(c, "gap4")
            setPadding(g, 0, g, 0)
        }
        val gap = ui.px(c, "gap2")
        if (store.hasPhoto()) {
            buttons.addWithGap(ui.button(c, t.show, true) { Viewer.show(c, store.photo, t) }, gap)
            buttons.addWithGap(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.button(c, t.replacePhoto, false) { photos.pick() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(View(c), LinearLayout.LayoutParams(gap, 1))
                addView(ui.button(c, t.removePhoto, false) {
                    ui.dialog(c.activity() ?: c, t.removeTitle, t.removeText, t.removePhoto) { photos.remove() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, gap)
        } else {
            buttons.addWithGap(ui.button(c, t.addPhoto, true) { photos.pick() }, gap)
        }
        box.addView(buttons)
        box.addView(ui.explain(c, t.privacy))
    }
}
