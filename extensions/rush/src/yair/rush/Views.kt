package yair.rush

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kav.kavapp.api.Kav
import org.json.JSONObject

/** What Rush adds to Kav's screens, each made of Kav's own components and colours. */
internal class Views(
    private val kav: Kav,
    private val texts: Texts,
    private val runs: Runs,
    private val search: Search,
    private val changes: Changes,
) {
    private val ui get() = kav.ui()

    // The chip under Kav's time and sort: only when Rush found a run worth it, and nothing else, so the results move
    // as little as they can.

    fun chipRow(c: Context, about: String): View? {
        val a = JSONObject(about)
        if (a.optLong("at", -1) != 0L || a.optString("way") != "depart") return null
        val s = search.current() ?: return null
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            val g = px(c, "gap4")
            setPaddingRelative(g, 0, g, 0)
        }
        row.addView(ui.chip(c, texts.chip(s.saves), s.on) {
            if (search.toggle()) searchAgain(c)
        })
        return row
    }

    /** Kav's own search again, for the results on screen: its routes come with Rush's first, or without them. */
    private fun searchAgain(c: Context) {
        val activity = c.activity() ?: return
        try { kav.kavAction("plan", activity, "{}") } catch (e: Exception) {
            kav.log("Rush: couldn't search again: ${e.javaClass.simpleName}")
        }
    }

    /** Kav's results screen shows these places: told so the chip is never for another search. */
    fun endpoints(about: String): View? {
        search.endpoints(about)
        return null
    }

    // A run, in red, or a jog, in amber, wherever its walk shows.

    /** In the footer of a route's card in the results. */
    fun routeLine(c: Context, about: String): View? {
        val legs = Routes.legs(JSONObject(about).optJSONObject("trip"))
        val run = runs.on(legs, Routes.firstRide(legs)) ?: return null
        return line(c, texts.runLine(run), run, "subhead")
    }

    /** In place of the walk's line on a trip's page. Kav's own line for any other walk. */
    fun tripWalk(c: Context, about: String): View? {
        val a = JSONObject(about)
        val legs = Routes.legs(a.optJSONObject("trip"))
        val index = a.optInt("index", -1)
        var ride = index
        while (ride in legs.indices && Routes.kind(legs[ride]).let { it == "walk" || it == "wait" }) ride++
        val approach = Routes.approach(legs, ride) ?: return null
        if (approach.walks.firstOrNull() != index) return null
        val run = runs.on(legs, ride) ?: return null
        return line(c, texts.runLine(run), run, "body")
    }

    /** Above a step's card in the step-by-step guide: a run on its walk, and Rush's offer at a change. */
    fun guide(c: Context, about: String): View? {
        val a = JSONObject(about)
        val legs = Routes.legs(a.optJSONObject("trip"))
        val step = a.optInt("step", -1)
        val views = ArrayList<View>()
        Routes.walkSteps(legs).getOrNull(step)?.takeIf { it >= 0 }?.let { ride -> runs.on(legs, ride) }?.let { run ->
            views.add(ui.card(c, line(c, texts.guideLine(run), run, "subhead", padded = false)))
        }
        if (a.optInt("current", -1) == step) changes.offerCard(c)?.let(views::add)
        return when (views.size) {
            0 -> null
            1 -> views[0]
            else -> LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                views.forEachIndexed { i, v ->
                    addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { if (i > 0) topMargin = px(c, "gap2") })
                }
            }
        }
    }

    /** Under Kav's "This connection won't work": a run that still makes the change, when there is one. */
    fun problem(c: Context, about: String): View? {
        val a = JSONObject(about)
        changes.problemShown(a.optString("key"))
        if (a.optString("reason") != "late_change") return null
        val legs = Routes.legs(a.optJSONObject("trip"))
        val still = changes.stillMake(legs, a.optString("key")) ?: return null
        return ui.card(c, line(c, still.first, still.second, "subhead", padded = false))
    }

    private fun line(c: Context, text: String, run: Runs.Run, style: String, padded: Boolean = true): View =
        line(c, text, if (run.kind == RunMath.Kind.JOG) "problem" else "critical", style, padded)

    fun line(c: Context, text: String, run: RunMath.Kind, style: String, padded: Boolean = true): View =
        line(c, text, if (run == RunMath.Kind.JOG) "problem" else "critical", style, padded)

    private fun line(c: Context, text: String, colour: String, style: String, padded: Boolean): View = TextView(c).apply {
        this.text = text
        setTextColor(ui.color(colour))
        textSize = ui.textSize(style)
        if (padded) setPaddingRelative(0, px(c, "gap1"), 0, px(c, "gap1"))
    }

    private fun px(c: Context, name: String) = (ui.dp(name) * c.resources.displayMetrics.density).toInt()
}

/** The Activity behind a view's Context, for Kav's actions. */
internal fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
