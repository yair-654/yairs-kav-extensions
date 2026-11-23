package yair.heroes

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kav.kavapp.api.Kav
import kav.kavapp.api.KavUi

/**
 * The extension's settings, on its page in Settings › Extensions. Like Kav's own Settings: a short list of rows, each
 * opening its own page with Kav's transition, and a Back button at the top of each page.
 */
internal class Settings(
    private val c: Context,
    private val kav: Kav,
    private val store: Store,
    private val photos: Photos,
    private val stayAwake: StayAwake,
) {
    private val ui = kav.ui()
    private val t = Texts.of(kav)
    private lateinit var pages: KavUi.Pages

    companion object {
        /** Set while the gallery is open, so that if Kav rebuilds its page meanwhile, it comes back on Choger / Tzav 8. */
        @Volatile var pickingPhoto = false
    }

    fun view(): View {
        val first = if (pickingPhoto) document() else main()
        pickingPhoto = false
        pages = ui.pages(c, first)
        return pages.view()
    }

    private fun pick() {
        pickingPhoto = true
        photos.pick(c)
    }

    private fun main(): View = column(c).apply {
        addView(ui.row(c, "bus", t.paying, t.onOff(store.zuzu)) { pages.forward(paying()) })
        addView(ui.row(c, "clock", t.stayAwake, t.onOff(store.stayAwakeOn)) { pages.forward(stayAwakePage()) })
        addView(ui.row(c, "check", t.document, null) { pages.forward(document()) })
        addView(ui.row(c, "heart", t.banner, t.onOff(store.banner)) { pages.forward(banner()) })
    }

    /** A page with Back and its title at the top. */
    private fun page(title: String, body: LinearLayout.() -> Unit): View = column(c).apply {
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val g = ui.px(c, "gap2")
            setPadding(g, g, g, g)
            addView(ui.iconButton(c, "back", t.back) { pages.back(main()) })
            addView(ui.text(c, title, "headline").apply { setPaddingRelative(ui.px(c, "gap3"), 0, 0, 0) })
        }
        addView(top)
        body()
    }

    private fun paying() = page(t.paying) {
        addView(ui.switchRow(c, t.payWithZuzu, null, store.zuzu) { on -> store.setZuzu(on) })
        addView(ui.explain(c, t.payExplain))
    }

    private fun stayAwakePage() = page(t.stayAwake) {
        addView(ui.switchRow(c, t.stayShow, null, store.stayAwakeOn) { on ->
            store.setStayAwakeOn(on)
            if (!on) stayAwake.stop()
        })
        addView(ui.explain(c, t.stayShowExplain))
        addView(ui.explain(c, t.stayPurpose))
        addView(ui.explain(c, t.stayHow))
        addView(stepper())
        addView(ui.explain(c, t.remindExplain))
        addView(ui.explain(c, t.stayClosed))
    }

    /** "Remind me every [−] 10 min [+]", from 1 to 59. */
    private fun stepper(): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val g = ui.px(c, "gap4")
        setPadding(g, ui.px(c, "gap2"), g, ui.px(c, "gap2"))
        val value = ui.text(c, t.minutes(store.interval), "body").apply {
            gravity = Gravity.CENTER
            minWidth = ui.px(c, "gap8") * 2
        }
        fun step(by: Int) {
            store.setInterval(store.interval + by)
            value.text = t.minutes(store.interval)
        }
        addView(ui.text(c, t.remindEvery, "body"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(ui.chip(c, "−", false) { step(-1) })
        addView(value)
        addView(ui.chip(c, "+", false) { step(+1) })
    }

    private fun document(): View {
        val content = column(c)
        fun fill() {
            content.removeAllViews()

            content.addView(ui.text(c, t.uploadQuestion, "subhead", "textSecondary").apply {
                val g = ui.px(c, "gap4")
                setPadding(g, ui.px(c, "gap2"), g, ui.px(c, "gap2"))
            })
            content.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                val g = ui.px(c, "gap4")
                setPadding(g, 0, g, ui.px(c, "gap3"))
                addView(ui.chip(c, t.choger, !store.tzav8) { store.setTzav8(false); fill() })
                addView(View(c), LinearLayout.LayoutParams(ui.px(c, "gap2"), 1))
                addView(ui.chip(c, t.tzav8, store.tzav8) { store.setTzav8(true); fill() })
            })

            val buttons = column(c).apply {
                val g = ui.px(c, "gap4")
                setPadding(g, 0, g, 0)
            }
            val gap = ui.px(c, "gap2")
            if (store.hasPhoto()) {
                buttons.addWithGap(ui.button(c, t.show, true) { Viewer.show(c, store.photo, t) }, gap)
                buttons.addWithGap(ui.button(c, t.replacePhoto, false) { pick() }, gap)
                buttons.addWithGap(ui.button(c, t.removePhoto, false) {
                    ui.dialog(c, t.removeTitle, "", t.removeConfirm) { photos.remove() }
                }, gap)
            } else {
                buttons.addWithGap(ui.button(c, t.addPhoto, true) { pick() }, gap)
            }
            content.addView(buttons)
            content.addView(ui.explain(c, t.docExplain))

            content.addView(ui.switchRow(c, t.homeButton, null, store.homeButton) { on -> store.setHomeButton(on) })
            content.addView(ui.explain(c, t.homeButtonExplain(store.tzav8)))

            content.addView(ui.text(c, t.tripButton, "subhead", "textSecondary").apply {
                val g = ui.px(c, "gap4")
                setPadding(g, ui.px(c, "gap3"), g, ui.px(c, "gap2"))
            })
            content.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                val g = ui.px(c, "gap4")
                setPadding(g, 0, g, 0)
                for ((mode, label) in listOf("off" to t.tripOff, "stations" to t.tripStations, "every" to t.tripEvery)) {
                    if (childCount > 0) addView(View(c), LinearLayout.LayoutParams(ui.px(c, "gap2"), 1))
                    addView(ui.chip(c, label, store.tripMode == mode) { store.setTripMode(mode); fill() })
                }
            })
            content.addView(ui.explain(c, t.tripButtonExplain(store.tzav8)))
            content.addView(ui.explain(c, t.privacy))
        }
        fill()
        // Filled again when a photo is added or removed, while this page is on screen.
        val refill: () -> Unit = { content.post { fill() } }
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { photos.onChange(refill); fill() }
            override fun onViewDetachedFromWindow(v: View) { photos.removeListener(refill) }
        })
        return page(t.document) { addView(content) }
    }

    private fun banner() = page(t.banner) {
        addView(ui.switchRow(c, t.showBanner, null, store.banner) { on -> store.setBanner(on) })
        addView(ui.explain(c, t.bannerExplain))
    }
}
