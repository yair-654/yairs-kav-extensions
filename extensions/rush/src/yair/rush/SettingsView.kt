package yair.rush

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kav.kavapp.api.Kav

/**
 * Rush's settings, on its page in Settings › Extensions, drawn with Kav's own components. One page, in the order a
 * rider meets things: the safety card first, then the search results, the speeds, and trips under way.
 */
internal class SettingsView(private val c: Context, private val kav: Kav, private val bits: KavBits, private val store: Store) {
    private val ui = kav.ui()
    private val t = Texts.of(kav)
    private val root = column(c)

    fun view(): View { fill(); return root }

    private fun fill() {
        root.removeAllViews()
        values.clear()
        if (!bits.ok) root.addFill(ui.explain(c, t.notAvailable))
        root.addFill(safetyCard())

        root.addFill(ui.header(c, t.inResults))
        root.addFill(ui.switchRow(c, t.buttonTitle, t.buttonSub, store.button) { store.setButton(it) })
        root.addFill(ui.switchRow(c, t.hintTitle, t.hintSub, store.hint) { store.setHint(it) })
        root.addFill(stepper(t.savesAtLeast, { t.minutes(store.savesMin) }) { by -> store.setSavesMin(store.savesMin + by) })
        root.addFill(ui.explain(c, t.savesExplain))
        root.addFill(ui.switchRow(c, t.tightTitle, t.tightSub, store.tight) { store.setTight(it) })

        root.addFill(ui.header(c, t.howFast))
        root.addFill(paceTitle(RunMath.Pace.JOG, t.jogging))
        root.addFill(stepper(t.speed, { t.kmh(store.jogKmh) }) { by -> store.setJogKmh(store.jogKmh + by * Store.KMH_STEP); refreshSpeeds() })
        root.addFill(stepper(t.upTo, { t.metres(store.jogMaxM) }) { by -> store.setJogMaxM(store.jogMaxM + by * Store.M_STEP); refreshSpeeds() })
        root.addFill(paceTitle(RunMath.Pace.RUN, t.running))
        root.addFill(stepper(t.speed, { t.kmh(store.runKmh) }) { by -> store.setRunKmh(store.runKmh + by * Store.KMH_STEP); refreshSpeeds() })
        root.addFill(stepper(t.upTo, { t.metres(store.runMaxM) }) { by -> store.setRunMaxM(store.runMaxM + by * Store.M_STEP); refreshSpeeds() })
        root.addFill(ui.explain(c, t.speedsExplain))
        resetBox = LinearLayout(c).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val g = ui.px(c, "gap4")
            setPadding(g, 0, g, ui.px(c, "gap3"))
            addView(ui.button(c, t.reset, false) { store.resetSpeeds(); fill() })
        }
        root.addFill(resetBox!!)

        root.addFill(ui.header(c, t.duringTrip))
        root.addFill(ui.switchRow(c, t.transfersTitle, t.transfersSub, store.transfers) { on ->
            if (!on || store.safetyAccepted) { store.setTransfers(on); return@switchRow }
            // The first time Rush is used at all: what it is, and that it's a gamble. On only with "I understand".
            fill()
            ui.dialog(c, t.safetyTitle, t.safetyBody, t.understand) {
                store.acceptSafety()
                store.setTransfers(true)
                fill()
            }
        })
        root.addFill(ui.explain(c, t.transfersRisk))
        root.addFill(ui.explain(c, t.transfersHow))
        root.addFill(ui.explain(c, t.replanExplain))
        refreshSpeeds()
    }

    private var resetBox: LinearLayout? = null
    private val values = ArrayList<Pair<TextView, () -> String>>()

    /** Every stepper's value again (jogging and running keep a step apart), and Reset only when there's something to reset. */
    private fun refreshSpeeds() {
        for ((v, text) in values) v.text = text()
        resetBox?.visibility = if (store.speedsChanged) View.VISIBLE else View.GONE
    }

    /** The safety card: always here, at the top, in Kav's warning colour. */
    private fun safetyCard(): View {
        val content = column(c).apply {
            addFill(row(c).apply {
                addView(dot(c, ui.color("problem")))
                addWeighted(ui.text(c, t.safetyShort, "headline", "problem"))
            })
            addFill(ui.text(c, t.safetyBody, "subhead", "textSecondary").apply { setPadding(0, ui.px(c, "gap2"), 0, 0) })
        }
        return ui.card(c, content).also { card ->
            val g = ui.px(c, "gap4")
            card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(g, ui.px(c, "gap2"), g, ui.px(c, "gap4")) }
        }
    }

    /** "● Jogging" in its colour, over its two steppers. */
    private fun paceTitle(pace: RunMath.Pace, label: String): View = row(c).apply {
        val g = ui.px(c, "gap4")
        setPaddingRelative(g, ui.px(c, "gap3"), g, ui.px(c, "gap1"))
        addView(dot(c, ui.paceColor(pace)))
        addView(ui.text(c, label, "headline"))
    }

    /** "Label   [−]  value  [+]" */
    private fun stepper(label: String, value: () -> String, step: (Int) -> Unit): View = row(c).apply {
        val g = ui.px(c, "gap4")
        setPadding(g, ui.px(c, "gap2"), g, ui.px(c, "gap2"))
        val shown = ui.text(c, value(), "body").apply {
            gravity = Gravity.CENTER
            minWidth = ui.px(c, "gap8") * 2
        }
        values += shown to value
        addWeighted(ui.text(c, label, "body"))
        addView(ui.chip(c, "−", false) { step(-1); shown.text = value(); refreshSpeeds() })
        addView(shown)
        addView(ui.chip(c, "+", false) { step(+1); shown.text = value(); refreshSpeeds() })
    }
}
