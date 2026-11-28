package yair.carrylight

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import kav.kavapp.api.Kav
import java.lang.ref.WeakReference

/**
 * A hero's story: their own page on Drishat Shalom (stickers.org.il), inside Kav, in the story language. Their page
 * has everything: the sticker, their words, who they were, a letter to the family and sharing.
 *
 * From a card or the heroes you met it opens over the whole of Kav's screen (the part screen.overlay), with a bar of
 * Back, Save and Open in browser; Back goes back inside the page first, then closes it. On the Read
 * about a hero page it sits under Kav's own header. Links to anywhere else (Izkor, say) open in the browser, and the
 * site's cookies and stored data are removed each time a page closes.
 */
internal class Story(private val kav: Kav, private val store: Store, private val picker: Picker, private val lang: Lang) {
    companion object {
        const val EXTRA_HERO = "yair.carrylight.HERO"
        const val EXTRA_SPOT = "yair.carrylight.SPOT"
    }

    private val ui get() = kav.ui()
    private var host: WeakReference<Host>? = null

    /** The view over the whole screen: empty, and letting every touch through, until a story opens in it. */
    fun overlay(c: Context): View = Host(c).also { host = WeakReference(it) }

    /** Opens a hero's story over the screen. spot: where they were offered, done once opened. */
    fun open(c: Context, s: Sticker, spot: String?) {
        store.open(s, spot)
        picker.ensure(force = true)
        show(c, s.id)
    }

    /**
     * Read about a hero: the story over the whole screen, as from a card, so the site's page has all of it; closing it
     * leaves Kav's page it was opened on too (Kav's own Back). False when there's no view over the screen.
     */
    fun read(c: Context, s: Sticker): Boolean {
        val h = host?.get()?.takeIf { it.isAttachedToWindow } ?: return false
        store.open(s, spot = null)
        picker.ensure(force = true)
        h.show(page(h.context, s.id, onBack = { h.close() })) { @Suppress("DEPRECATION") c.activity()?.onBackPressed() }
        return true
    }

    /** Opens the story of a hero the rider already met. */
    fun show(c: Context, id: String) {
        val h = host?.get()?.takeIf { it.isAttachedToWindow }
        if (h == null) {
            openOutside(c, Site.stickerPage(id, lang.story))
            return
        }
        h.show(page(h.context, id, onBack = { h.close() }))
    }

    /** A story's page: its bar (with Back when onBack is given) and the site's page. */
    @SuppressLint("SetJavaScriptEnabled")
    fun page(c: Context, id: String, onBack: (() -> Unit)?): StoryPage {
        val t = lang.texts
        val url = Site.stickerPage(id, lang.story)
        val web = WebView(c)
        val root = StoryPage(c, web)
        root.setBackgroundColor(ui.color("bg"))

        root.addFill(row(c).apply {
            val g = ui.px(c, "gap2")
            setPadding(g, g, g, g)
            if (onBack != null) addView(ui.iconButton(c, "back", t.back) { onBack() })
            addWeighted(column(c).apply {
                setPaddingRelative(ui.px(c, "gap3"), 0, 0, 0)
                addFill(ui.text(c, t.drishatShalom, "headline"))
                addFill(ui.text(c, "stickers.org.il", "caption", "textSecondary"))
            })
            if (store.isMet(id)) addView(saveChip(c, id))
            addView(View(c), LinearLayout.LayoutParams(ui.px(c, "gap2"), 1))
            addView(browserButton(c) { web.url?.takeIf { root.isSite(it) } ?: url })
        })

        val offline = column(c).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            val g = ui.px(c, "gap6")
            setPadding(g, g, g, g)
            addFill(ui.text(c, t.noConnection, "body", "textSecondary").apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, ui.px(c, "gap4"))
            })
            addFill(ui.button(c, t.tryAgain, false) {
                visibility = View.GONE
                web.visibility = View.VISIBLE
                web.loadUrl(url)
            })
        }

        web.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // Pinch to zoom, with no on-screen zoom buttons.
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            setBackgroundColor(ui.color("bg"))
            webViewClient = object : WebViewClient() {
                /** The site's page asks the browser not to zoom: its viewport is rewritten to allow it. */
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(
                        "(function(){var m=document.querySelector('meta[name=viewport]');" +
                            "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
                            "m.content='width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=5, user-scalable=yes';})()",
                        null
                    )
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (Site.isSite(request.url.scheme, request.url.host)) return false
                    openOutside(c, request.url.toString())
                    return true
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    view.visibility = View.INVISIBLE
                    offline.visibility = View.VISIBLE
                }
            }
        }

        val frame = FrameLayout(c)
        frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(offline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        web.loadUrl(url)
        return root
    }

    /** A story's page: its site's page goes, and the site is forgotten, when it leaves the screen. */
    inner class StoryPage(c: Context, private val web: WebView) : LinearLayout(c) {
        init { orientation = VERTICAL }

        fun isSite(url: String) = android.net.Uri.parse(url).let { Site.isSite(it.scheme, it.host) }

        /** Back inside the site's page; false when there's nowhere to go back to. */
        fun back(): Boolean {
            if (web.visibility != View.VISIBLE || !web.canGoBack()) return false
            web.goBack()
            return true
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            web.destroy()
            forgetSite()
        }
    }

    /**
     * The view over the whole screen. While a story shows it takes every touch and Back (Kav's way: it takes focus and
     * Back's key); otherwise it draws nothing and lets everything through to Kav.
     */
    private inner class Host(c: Context) : FrameLayout(c) {
        private var story: StoryPage? = null
        private var closed: (() -> Unit)? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            post { takeTap() }
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) takeTap()
        }

        /**
         * A tap on the hero's notification opened Kav with the hero's id on its intent (Kav keeps the newest intent
         * as its screen's own): the story opens here, once, as from the card. Asked as this view is made (Kav just
         * started) and as Kav's window comes to the front (Kav was open), so only the extension running now answers.
         */
        private fun takeTap() {
            val intent = context.activity()?.intent ?: return
            val id = intent.getStringExtra(EXTRA_HERO)?.takeIf(Site::validId) ?: return
            val spot = intent.getStringExtra(EXTRA_SPOT)
            intent.removeExtra(EXTRA_HERO)
            intent.removeExtra(EXTRA_SPOT)
            store.queued(id)?.let { store.open(it, spot); picker.ensure(force = true) }
            show(page(context, id, onBack = { close() }))
        }

        /** closed: told when the rider closes it (Back, or the bar's Back). */
        fun show(page: StoryPage, closed: (() -> Unit)? = null) {
            this.closed = null
            close()
            story = page
            this.closed = closed
            page.isClickable = true
            addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            fitInsets(page)
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }

        fun close() {
            val page = story ?: return
            story = null
            removeView(page)
            clearFocus()
            isFocusable = false
            isFocusableInTouchMode = false
            closed?.also { closed = null }?.invoke()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val page = story
            if (page != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !page.back()) close()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        /** Kav made the overlay again (another tab): the story goes with this view. */
        override fun onDetachedFromWindow() {
            story = null
            closed = null
            super.onDetachedFromWindow()
        }

        /** Over the whole screen, so clear of the status bar and the gesture area. */
        private fun fitInsets(page: View) {
            fun pad(insets: WindowInsets?) {
                insets ?: return
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    page.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    page.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
            }
            page.setOnApplyWindowInsetsListener { _, insets -> pad(insets); insets }
            pad(rootWindowInsets)
        }
    }

    /** ☆ Save / ★ Saved, for a hero the rider met. */
    private fun saveChip(c: Context, id: String): View {
        val t = lang.texts
        val holder = FrameLayout(c)
        fun fill() {
            holder.removeAllViews()
            val saved = store.isSaved(id)
            holder.addView(ui.chip(c, if (saved) t.saved else t.save, saved) {
                store.setSaved(id, !saved)
                fill()
            })
        }
        fill()
        return holder
    }

    /** Open in browser: a round button like Kav's own, with an arrow out of a box (Kav's icons have no such one). */
    private fun browserButton(c: Context, current: () -> String): View {
        val size = c.dpx(44f)
        return object : View(c) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ui.color("text")
                style = Paint.Style.STROKE
                strokeWidth = c.dpx(2f).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            override fun onDraw(canvas: Canvas) {
                val s = width / 44f
                canvas.drawPath(Path().apply {
                    moveTo(20 * s, 14 * s); lineTo(14 * s, 14 * s); lineTo(14 * s, 30 * s); lineTo(30 * s, 30 * s); lineTo(30 * s, 24 * s)
                }, paint)
                canvas.drawLine(22 * s, 22 * s, 31 * s, 13 * s, paint)
                canvas.drawLine(24 * s, 13 * s, 31 * s, 13 * s, paint)
                canvas.drawLine(31 * s, 13 * s, 31 * s, 20 * s, paint)
            }
        }.apply {
            contentDescription = lang.texts.openInBrowser
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ui.color("fill"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openOutside(c, current()) }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    /**
     * The site's cookies and stored data, removed: it remembers nothing between visits. Only stickers.org.il's:
     * Android keeps one cookie jar for all of Kav's web pages, and Kav's own cookies there must stay. Android can't
     * delete one site's cookies directly, so each is set to expire now, for the forms the site may have used.
     */
    private fun forgetSite() {
        try {
            val cookies = CookieManager.getInstance()
            val names = (cookies.getCookie(Site.BASE) ?: "").split(";").map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.toSet()
            for (name in names) for (domain in listOf("", "; Domain=stickers.org.il", "; Domain=.stickers.org.il")) {
                cookies.setCookie(Site.BASE, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/$domain")
            }
            cookies.flush()
            WebStorage.getInstance().deleteOrigin(Site.BASE)
        } catch (e: Throwable) {
            kav.log("Couldn't clear Drishat Shalom's cookies: ${e.javaClass.simpleName}")
        }
    }
}
