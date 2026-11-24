package yair.rush

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import kav.kavapp.api.Kav
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

/**
 * What Rush shows on Kav's screens: the Rush mode row on the results, a line on each route card with a run, the run
 * on a trip's page, and in the step-by-step guide the run, an offer at a change, and a word after New route from here.
 * Each is made by Kav on its main thread whenever its screen shows it, and filled again here when Rush learns more.
 */
internal class Views(
    private val kav: Kav,
    private val bits: KavBits,
    private val store: Store,
    private val search: Search,
    private val trip: TripWatch,
) {
    private val ui get() = kav.ui()
    private val t get() = Texts.of(kav)

    private val rows = Collections.newSetFromMap(WeakHashMap<LinearLayout, Boolean>())
    private val navs = Collections.newSetFromMap(WeakHashMap<NavBox, Boolean>())

    init {
        search.onStatus { main.post { for (r in rows.toList()) fillRow(r) } }
        trip.onChange { for (n in navs.toList()) fillNav(n) }
        store.onChange { main.post { for (r in rows.toList()) fillRow(r) } }
    }

    // The Rush mode row, under the time and sort

    fun rushRow(c: Context, about: String): View? {
        if (!bits.ok) return null
        val a = JSONObject(about)
        if (a.optLong("at", -1) != 0L || a.optString("way") != "depart") return null
        val box = row(c).apply {
            val g = ui.px(c, "gap4")
            setPaddingRelative(g, ui.px(c, "gap1"), g, ui.px(c, "gap1"))
        }
        rows += box
        fillRow(box)
        return box
    }

    private fun fillRow(box: LinearLayout) {
        val c = box.context
        box.removeAllViews()
        val s = search.status
        val note: Pair<String, String>? = when {
            s.on && s.phase == Search.Phase.CHECKING -> t.checking to "textSecondary"
            s.on && s.phase == Search.Phase.READY -> (if (s.runs > 0) t.routesWithRun(s.runs) else t.noRuns) to "textSecondary"
            s.on && s.phase == Search.Phase.FAILED -> t.couldntCheck to "textSecondary"
            !s.on && store.hint && s.phase == Search.Phase.READY && s.runs > 0 && s.saves >= store.savesMin * 60L ->
                t.saves((s.saves / 60).toInt()) to "text"
            else -> null
        }
        if (!store.button && note == null) { box.visibility = View.GONE; return }
        box.visibility = View.VISIBLE
        if (store.button) box.addView(ui.chip(c, t.rushMode, s.on) { toggle(c) })
        box.addWeighted(space(c, 0))
        if (note != null) {
            val v = ui.text(c, note.first, "subhead", note.second)
            if (!s.on) v.setOnClickListener { toggle(c) }
            box.addView(v)
        }
    }

    private fun toggle(c: Context) {
        if (search.status.on) { search.setRush(false); searchAgain(c); return }
        if (store.safetyAccepted) { search.setRush(true); searchAgain(c); return }
        // The first time: what Rush is, and that it's a gamble. Rush only turns on with "I understand".
        ui.dialog(c, t.safetyTitle, t.safetyBody, t.understand) {
            store.acceptSafety()
            search.setRush(true)
            searchAgain(c)
        }
    }

    /** Kav's own handling of "plan": this search, planned again. */
    private fun searchAgain(c: Context) {
        try { kav.kavAction("plan", c.activity() ?: c, "{}") } catch (e: Throwable) {
            kav.log("Rush: couldn't search again: ${e.javaClass.simpleName}")
        }
    }

    // A route card's line

    fun routeLine(c: Context, about: String): View? {
        val legs = JSONObject(about).optJSONObject("trip")?.optJSONArray("legs") ?: return null
        val run = runOf(legs) ?: return null
        return runRow(c, run, t.runLine(run), "subhead")
    }

    // A trip's page: its walk line, as a jog or a run

    fun tripWalk(c: Context, about: String): View? {
        val leg = JSONObject(about).optJSONObject("leg") ?: return null
        val run = runOfLeg(leg) ?: return null
        return column(c).apply {
            val g = ui.px(c, "gap4")
            setPaddingRelative(g, ui.px(c, "gap2"), g, ui.px(c, "gap2"))
            addFill(runRow(c, run, t.runShort(run), "body"))
            val detail = (if (run.tight) t.tightWarning else t.spare(run.spare)) + " · " + t.busAt(run.board)
            addFill(ui.text(c, detail, "caption", if (run.tight) "critical" else "textSecondary").apply {
                setPaddingRelative(c.dpx(18f), c.dpx(2f), 0, 0)
            })
        }
    }

    // The step-by-step guide

    private class NavBox(c: Context, val trip: JSONObject, val step: Int, val current: Int) : LinearLayout(c)

    fun navCard(c: Context, about: String): View? {
        val a = JSONObject(about)
        val tripJson = a.optJSONObject("trip") ?: return null
        val box = NavBox(c, tripJson, a.optInt("step", -1), a.optInt("current", -1)).apply { orientation = LinearLayout.VERTICAL }
        navs += box
        fillNav(box)
        return box
    }

    private fun fillNav(box: NavBox) {
        val c = box.context
        box.removeAllViews()
        val key = trip.tripKey(box.trip.optLong("dep"), box.trip.optLong("arr"))
        val g = ui.px(c, "gap4")
        val onCurrent = box.step == box.current

        if (onCurrent) trip.noteFor(key)?.let { run ->
            box.addFill(runRow(c, run, t.replanNote(run), "subhead").apply { setPaddingRelative(g, ui.px(c, "gap2"), g, 0) })
        }
        // This step is a walk Rush made a run: say so, in its colour, every time the step shows.
        val visits = try { bits.visitsOfTripUnderWay(box.trip.optLong("dep"), box.trip.optLong("arr")) } catch (e: Throwable) { emptySet() }
        walkLegsOf(box.trip, box.step, visits)?.let { legs -> runOf(legs) }?.let { run ->
            box.addFill(column(c).apply {
                setPaddingRelative(g, ui.px(c, "gap2"), g, ui.px(c, "gap1"))
                addFill(runRow(c, run, t.runShort(run) + " · " + t.busAt(run.board), "body"))
                addFill(ui.text(c, if (run.tight) t.tightWarning else t.watchYourWay, "caption",
                    if (run.tight) "critical" else "textSecondary").apply { setPaddingRelative(c.dpx(18f), c.dpx(2f), 0, 0) })
            })
        }
        if (onCurrent) trip.offerFor(key)?.let { o -> box.addFill(offerCard(c, o)) }
        box.visibility = if (box.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun offerCard(c: Context, o: TripWatch.Offer): View {
        val content = column(c).apply {
            addFill(runRow(c, o.run, t.offerTitle(o.line, o.run.board), "headline"))
            addFill(ui.text(c, t.offerBody(o.run, o.savesMin), "subhead", "textSecondary").apply {
                setPaddingRelative(c.dpx(18f), c.dpx(2f), 0, ui.px(c, "gap3"))
            })
            addFill(row(c).apply {
                addView(ui.button(c, t.notNow, false) { trip.dismiss() })
                addWeighted(space(c, 0))
                addView(ui.button(c, t.switchTrip, true) { trip.switchNow(c) })
            })
        }
        return ui.card(c, content).also { card ->
            val g = ui.px(c, "gap4")
            card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(g, ui.px(c, "gap2"), g, ui.px(c, "gap2")) }
        }
    }

    // Shared

    /** A dot in the run's colour and a text in the same colour. */
    private fun runRow(c: Context, run: Store.Run, text: String, style: String): LinearLayout = row(c).apply {
        val color = ui.paceColor(run.pace)
        addView(dot(c, color))
        addWeighted(ui.text(c, text, style).apply { setTextColor(color) })
    }

    private fun runOfLeg(leg: JSONObject): Store.Run? {
        if (leg.optString("kind") != "walk") return null
        return store.run(store.walkKey(leg.optInt("metres"), leg.optLong("dep"), leg.optLong("arr")))
    }

    /** The first run among these legs. */
    private fun runOf(legs: JSONArray): Store.Run? {
        for (i in 0 until legs.length()) runOfLeg(legs.optJSONObject(i) ?: continue)?.let { return it }
        return null
    }

    /**
     * The legs of a step of Kav's step-by-step guide when it's a walk, else null. Kav makes the steps from the legs
     * (buildSteps in its ui/Navigate.kt): a start; walks one after another as one step (each walk's whole minutes
     * added up), except that the walk on from a stop on the way ([visits], by leg) starts a step of its own; none for
     * a walk under a minute and 30 m; a ride as two steps, its wait and the ride; a taxi or a bike one step each;
     * then the arrival.
     */
    private fun walkLegsOf(trip: JSONObject, step: Int, visits: Set<Int>): JSONArray? {
        val legs = trip.optJSONArray("legs") ?: return null
        var at = 1 // step 0 is the start
        var i = 0
        while (i < legs.length()) {
            val leg = legs.optJSONObject(i) ?: return null
            when (leg.optString("kind")) {
                "walk" -> {
                    var j = i
                    var mins = 0L; var metres = 0
                    val group = JSONArray()
                    while (j < legs.length() && legs.optJSONObject(j)?.optString("kind") == "walk" && (j == i || j !in visits)) {
                        val w = legs.getJSONObject(j)
                        mins += maxOf(0L, (w.optLong("arr") - w.optLong("dep")) / 60); metres += w.optInt("metres")
                        group.put(w); j++
                    }
                    if (!(mins < 1 && metres <= 30)) { if (at == step) return group; at++ }
                    i = j; continue
                }
                "ride" -> at += 2
                "taxi", "bike" -> at += 1
            }
            i++
        }
        return null
    }
}
