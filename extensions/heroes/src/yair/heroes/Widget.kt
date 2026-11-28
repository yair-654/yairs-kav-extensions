package yair.heroes

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kav.kavapp.api.Kav
import org.json.JSONArray
import org.json.JSONObject

/**
 * The home screen widget (API 5): the document, and "Pay in Zuzu" while Pay with Zuzu is on. Kav draws it in its own look; each row opens one
 * of the extension's two screens in Kav (the photo, or Zuzu), since only a screen of Kav's can open the photo bright
 * and full size, or start another app.
 */
internal class Widget(
    private val kav: Kav,
    private val store: Store,
    private val config: Config,
) {
    companion object {
        const val ID = "soldiers"
    }

    fun register() {
        kav.widget(ID, object : Kav.Widget {
            override fun content(args: String): String {
                val t = Texts.of(kav)
                val rows = JSONArray()
                rows.put(
                    if (store.hasPhoto()) row(t.showDocument(config.tzav8), "check", JSONObject().put("page", "document"))
                    else row(t.addDocument(config.tzav8), "plus", JSONObject().put("settings", true))
                )
                if (config.zuzu) rows.put(row(t.payInZuzu, "bus", JSONObject().put("page", "zuzu")))
                return JSONObject()
                    .put("title", "")
                    .put("rows", rows)
                    .put("action", JSONObject().put("settings", true))
                    .toString()
            }
        })
    }

    /** Asks Kav to draw the widget again: a setting or the photo changed. */
    fun refresh() {
        try { kav.refreshWidget(ID) } catch (e: Exception) { kav.log("Widget: couldn't refresh: ${e.javaClass.simpleName}") }
    }

    private fun row(title: String, icon: String, action: JSONObject) =
        JSONObject().put("title", title).put("icon", icon).put("action", action)
}

/** The extension's two screens, opened by the widget. Kav shows each under its own header, with Back. */
internal class Screens(
    private val kav: Kav,
    private val store: Store,
    private val config: Config,
) {
    fun register() {
        kav.screen("document", Kav.Screen { c, _ -> document(c) })
        kav.screen("zuzu", Kav.Screen { c, _ -> zuzu(c) })
    }

    /** The photo, as big as the screen allows, at full brightness while it shows. */
    private fun document(c: Context): View {
        val t = Texts.of(kav)
        val ui = kav.ui()
        if (!store.hasPhoto()) return column(c).apply { addView(ui.explain(c, t.noPhoto)) }
        val frame = FrameLayout(c).apply {
            setBackgroundColor(Color.BLACK)
            minimumHeight = c.resources.displayMetrics.heightPixels * 2 / 3
        }
        val image = ZoomImageView(c)
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.context.activity()?.window?.let { Viewer.brighten(it) }
                val metrics = v.resources.displayMetrics
                val maxSide = maxOf(metrics.widthPixels, metrics.heightPixels) * 2
                Thread {
                    val bitmap = Viewer.decodeFitting(store.photo, maxSide)
                    onMain {
                        if (bitmap == null) return@onMain
                        if (frame.isAttachedToWindow) image.setImageBitmap(bitmap) else bitmap.recycle()
                    }
                }.start()
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.context.activity()?.window?.let { Viewer.restore(it) }
                image.setImageDrawable(null)
            }
        })
        frame.contentDescription = t.showDocument(config.tzav8)
        return frame
    }

    /** Opens Zuzu at once, with a button to open it again for a rider who comes back here. */
    private fun zuzu(c: Context): View {
        val t = Texts.of(kav)
        val ui = kav.ui()
        val box = column(c).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val g = ui.px(c, "gap4")
            setPadding(g, g, g, g)
        }
        box.addView(ui.text(c, t.openingZuzu, "body", "textSecondary"))
        box.addWithGap(ui.button(c, t.openZuzu, true) { Zuzu.open(c) }, ui.px(c, "gap3"))
        var opened = false
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (opened) return
                opened = true
                Zuzu.open(v.context)
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        return box
    }
}
