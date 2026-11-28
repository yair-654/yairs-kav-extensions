package yair.rush

import android.os.Handler
import android.os.Looper
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rush: buses you can still catch if you jog or run. Kav calls start once each time it starts, on a background thread.
 * Only Kav's API, no hooks into Kav's own code.
 */
class Rush : KavExtension {
    override fun start(kav: Kav) {
        val app = kav.context()
        val main = Handler(Looper.getMainLooper())
        val settings = Settings(kav)
        val texts = Texts(kav)
        val runs = Runs(app)

        lateinit var views: Views
        fun refreshChip() = main.post { kav.part("results.options", "after", Kav.Card { c, about -> views.chipRow(c, about) }) }
        fun refreshGuide() = main.post { kav.part("nav.card", "before", Kav.Card { c, about -> views.guide(c, about) }) }

        val search = Search(kav, settings, runs) { refreshChip() }
        val changes = Changes(app, kav, settings, texts, runs, search) { refreshGuide() }
        views = Views(kav, texts, runs, search, changes)

        search.install()
        changes.install()
        kav.card("results", Kav.Card { c, about -> views.routeLine(c, about) })
        kav.part("results.endpoints", "after", Kav.Card { _, about -> views.endpoints(about) })
        kav.part("trip.walk", "replace", Kav.Card { c, about -> views.tripWalk(c, about) })
        kav.part("nav.problem", "after", Kav.Card { c, about -> views.problem(c, about) })
        refreshChip()
        refreshGuide()
        kav.settingsScreen(Kav.Screen { c, _ -> kav.ui().blocks(c, safetyPage(texts)) })
        kav.log("Rush: ready")
    }

    /** Under Kav's own settings: the safety note in Kav's warning panel, and what Rush can and can't do. */
    private fun safetyPage(texts: Texts): String {
        fun text(t: String) = JSONObject().put("type", "text").put("text", t).put("style", "caption")
        val blocks = JSONArray()
            .put(JSONObject().put("type", "notice").put("level", "warning").put("text", texts.safety))
            .put(text(texts.worksOnly))
            .put(text(texts.needsFollow))
            .put(text(texts.defaults))
        return JSONObject().put("title", "")
            .put("sections", JSONArray().put(JSONObject().put("title", texts.beforeYouRush).put("blocks", blocks))).toString()
    }
}
