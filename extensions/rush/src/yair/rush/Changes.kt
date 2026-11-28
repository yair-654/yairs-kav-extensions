package yair.rush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kav.kavapp.api.Kav
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Run between rides too": during a trip, a little before the bus gets to a change, Rush asks Kav's planner once whether
 * a jog or a run from that stop catches an earlier connection that gets there at least the rider's "Saves at least"
 * sooner. If so: a notification and a card in the step-by-step guide, with Switch (Kav's own switch of the trip under
 * way) and Not now. It never switches by itself.
 *
 * It goes with Kav, never against it: while Kav says the plan stopped working, Rush offers nothing and takes back what
 * it offered; under Kav's own warning it only says when a run still makes the change Kav thinks is lost.
 *
 * Kav tells Rush about the trip only while the rider allows "Follow your trips" on Rush's page.
 */
internal class Changes(
    private val app: Context,
    private val kav: Kav,
    private val settings: Settings,
    private val texts: Texts,
    private val runs: Runs,
    private val search: Search,
    private val refresh: () -> Unit,
) {
    private class Offer(
        val change: String, val ref: String, val after: Int, val dep: Long, val arr: Long, val start: Long,
        val walks: List<Long>, val run: Runs.Run, val runKey: String, val line: String, val saves: Long,
    )

    private val thread = HandlerThread("rush-changes").apply { start() }
    private val handler = Handler(thread.looper)

    /** The last trip event Kav told, the changes already looked at, the offer standing, and Kav's warning on screen. */
    private var last: JSONObject? = null
    private val looked = HashSet<String>()
    @Volatile private var offer: Offer? = null
    @Volatile private var problem: String? = null
    private var tries = 0

    private val check = Runnable { check() }

    fun install() {
        kav.onTrip(Kav.TripListener { event -> handler.post { onTrip(event) } })
        kav.onAlarm(Kav.Alarm { id -> if (id == ALARM) handler.post { plan() } })
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    SWITCH -> handler.post { switchNow() }
                    LATER -> handler.post { withdraw() }
                }
            }
        }
        val filter = IntentFilter().apply { addAction(SWITCH); addAction(LATER) }
        // Switched off and on again while Kav runs, Rush starts again: the earlier start's receiver and thread go.
        synchronized(Changes) {
            earlier?.let { (r, t) -> runCatching { app.unregisterReceiver(r) }; t.quitSafely() }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            earlier = receiver to thread
        }
    }

    // The trip as Kav tells it.

    private fun onTrip(text: String) {
        val e = runCatching { JSONObject(text) }.getOrNull() ?: return
        val event = e.optString("event")
        if (event == "end") { last = null; looked.clear(); problem = null; withdraw(); kav.alarm(ALARM, 0); return }
        val was = last
        last = e
        val stepMoved = was == null || was.optJSONObject("step")?.optInt("index") != e.optJSONObject("step")?.optInt("index")
        if (event == "replan" || stepMoved) {
            problem = null
            offer?.let { o -> if (event == "replan" || rideNow(e) != o.after) withdraw() }
        }
        plan()
    }

    /** The ride the rider is on or waiting for: its index in the trip's legs, or -1. */
    private fun rideNow(e: JSONObject): Int {
        val step = e.optJSONObject("step") ?: return -1
        return if (step.optString("kind") in listOf("ride", "wait")) step.optInt("legIndex", -1) else -1
    }

    /** When to look at the change after the ride the rider is on: [CHECK_S] before the bus gets there. */
    private fun plan() {
        handler.removeCallbacks(check)
        val e = last ?: return
        if (!settings.betweenRides) return
        val legs = Routes.legs(e.optJSONObject("trip"))
        val ride = rideNow(e)
        if (ride < 0 || Routes.changeAfter(legs, ride) < 0 || changeKey(e, legs, ride) in looked) return
        val at = Routes.alights(rideLeg(e, legs, ride)) - CHECK_S
        val wait = (at - Runs.now()) * 1000
        if (wait <= 0) handler.post(check)
        else { handler.postDelayed(check, wait); kav.alarm(ALARM, at * 1000) }
    }

    private fun rideLeg(e: JSONObject, legs: List<JSONObject>, ride: Int): JSONObject {
        val step = e.optJSONObject("step")
        return step?.optJSONObject("leg")?.takeIf { step.optInt("legIndex", -1) == ride } ?: legs[ride]
    }

    private fun changeKey(e: JSONObject, legs: List<JSONObject>, ride: Int): String =
        "${e.optJSONObject("trip")?.optLong("dep")}|${Routes.tripOf(legs[ride])}|${Routes.changeAfter(legs, ride)}"

    // Looking at a change.

    private fun check() {
        val e = last ?: return
        if (!settings.betweenRides || problem != null) return
        val trip = e.optJSONObject("trip") ?: return
        val legs = Routes.legs(trip)
        val ride = rideNow(e)
        val change = if (ride >= 0) Routes.changeAfter(legs, ride) else -1
        if (change < 0) return
        val key = changeKey(e, legs, ride)
        if (key in looked) return
        val bus = rideLeg(e, legs, ride)
        val alight = Routes.alights(bus)
        val from = Routes.place(bus.optJSONObject("to")) ?: return
        val to = legs.asReversed().firstNotNullOfOrNull { Routes.place(it.optJSONObject("to")) } ?: return
        val letters = search.lastSettings
        val ask = JSONObject().put("from", JSONArray().put(from[0]).put(from[1])).put("to", JSONArray().put(to[0]).put(to[1]))
            .put("at", alight - Search.LOOKBACK_S).put("way", "d").put("settings", "f${letters[1]}${letters[2]}t")
        val answer = try { kav.plan(ask.toString()) } catch (ex: Exception) { null }
        if (answer == null) {
            if (++tries < TRIES) handler.postDelayed(check, RETRY_MS) else { looked.add(key); tries = 0 }
            return
        }
        looked.add(key); tries = 0
        val eta = e.optLong("eta").takeIf { it > 0 } ?: trip.optLong("arr")
        val found = best(JSONObject(answer), legs[change], alight, eta) ?: return
        val o = Offer(key, found.ref, ride, trip.optLong("dep"), trip.optLong("arr"), alight, found.walks, found.run,
            runs.key(Routes.tripOf(found.ride), found.walks.sum()), Routes.line(found.ride), eta - found.arr)
        offer = o
        notifyOffer()
        refresh()
        // Gone with the bus it's for.
        handler.postDelayed({ if (offer === o) withdraw() }, ((o.run.board - Runs.now()) * 1000).coerceAtLeast(0))
    }

    private class Found(val ref: String, val arr: Long, val ride: JSONObject, val run: Runs.Run, val walks: List<Long>)

    /** The soonest route from the change a run makes, sooner by at least "Saves at least" than the trip as it is. */
    private fun best(answer: JSONObject, planned: JSONObject, alight: Long, eta: Long): Found? {
        val routes = answer.optJSONArray("routes") ?: return null
        val speeds = settings.speeds
        val tightOk = settings.tightOk
        val saves = maxOf(settings.savesSeconds, Search.MIN_SAVES_S)
        var best: Found? = null
        for (i in 0 until routes.length()) {
            val route = routes.optJSONObject(i) ?: continue
            val ref = route.optString("ref")
            val arr = route.optLong("arr")
            val legs = Routes.legs(route)
            val first = Routes.firstRide(legs)
            if (ref.isEmpty() || first < 0 || Routes.tripOf(legs[first]) == Routes.tripOf(planned)) continue
            if (eta - arr < saves || (best != null && best.arr <= arr)) continue
            val approach = Routes.approach(legs, first) ?: continue
            val metres = approach.metres(legs)
            val board = Routes.boards(legs[first])
            val choice = RunMath.choose(metres, RunMath.crossings(approach.turns(legs), metres), alight, board, speeds, tightOk,
                RunMath.OFF_BUS_S) ?: continue
            val walks = RunMath.share(choice.seconds, approach.walkMetres(legs), RunMath.OFF_BUS_S)
            best = Found(ref, arr, legs[first], Runs.Run(choice.kind, metres, choice.seconds, choice.spare, board, Runs.now()), walks)
        }
        return best
    }

    // The offer.

    /** The card over the step-by-step guide's current step, while an offer stands. */
    fun offerCard(c: Context): View? {
        val o = offer ?: return null
        val ui = kav.ui()
        fun px(name: String) = (ui.dp(name) * c.resources.displayMetrics.density).toInt()
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(c).apply {
            text = texts.offerTitle(o.line, o.run.board)
            setTextColor(ui.color("text")); textSize = ui.textSize("headline")
        })
        box.addView(TextView(c).apply {
            text = texts.offerBody(o.run.kind, o.run.metres, o.run.seconds, o.saves)
            setTextColor(ui.color(if (o.run.kind == RunMath.Kind.JOG) "problem" else "critical")); textSize = ui.textSize("subhead")
            setPaddingRelative(0, px("gap1"), 0, px("gap3"))
        })
        val buttons = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val weight = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        buttons.addView(ui.button(c, texts.notNow, false) { handler.post { withdraw() } }, weight())
        buttons.addView(ui.button(c, texts.switch, true) { handler.post { switchNow() } },
            weight().apply { marginStart = px("gap2") })
        box.addView(buttons)
        return ui.card(c, box)
    }

    private fun switchNow() {
        val o = offer ?: return
        val change = JSONObject().put("ref", o.ref).put("after", o.after).put("dep", o.dep).put("arr", o.arr)
            .put("start", o.start).put("walks", JSONArray(o.walks))
        val ok = try { kav.switchTrip(change.toString()) } catch (e: Exception) {
            kav.log("Rush: couldn't switch: ${e.javaClass.simpleName}"); false
        }
        if (ok) {
            runs.put(o.runKey, o.run)
            withdraw()
            note(texts.switched)
        } else if (Runs.now() > o.run.board - RunMath.MIN_SPARE_S) {
            withdraw()
            note(texts.gone)
        } else note(texts.notSwitched)
    }

    /** Takes the offer back: the notification and the card go. The change isn't looked at again. */
    private fun withdraw() {
        if (offer == null) return
        offer = null
        manager()?.cancel(OFFER_ID)
        refresh()
    }

    // Kav's own warning.

    /** Kav shows its warning that the plan stopped working: Rush steps back for this step of the trip. */
    fun problemShown(key: String) {
        if (key.isEmpty() || key == problem) return
        problem = key
        handler.post { withdraw() }
    }

    /**
     * For Kav's "This connection won't work" (its key "late-<the next ride's leg>-<its trip>"): whether a run from the stop
     * gotten off at still makes that next ride, and what Rush says about it. Null when it doesn't, or walking does.
     */
    fun stillMake(legs: List<JSONObject>, key: String): Pair<String, RunMath.Kind>? {
        val next = key.split('-').getOrNull(1)?.toIntOrNull() ?: return null
        val approach = Routes.approach(legs, next) ?: return null
        var ride = approach.walks.first() - 1
        while (ride >= 0 && Routes.kind(legs[ride]) == "wait") ride--
        if (ride < 0 || Routes.kind(legs[ride]) != "ride") return null
        val metres = approach.metres(legs)
        val choice = RunMath.choose(metres, RunMath.crossings(approach.turns(legs), metres), Routes.alights(legs[ride]),
            Routes.boards(legs[next]), settings.speeds, settings.tightOk, RunMath.OFF_BUS_S) ?: return null
        return texts.stillMake(Routes.line(legs[next]), choice.kind, metres, choice.spare, choice.tight) to choice.kind
    }

    // Notifications.

    private fun manager(): NotificationManager? = app.getSystemService(NotificationManager::class.java)

    private fun notifyOffer() {
        val o = offer ?: return
        val m = manager() ?: return
        if (!m.areNotificationsEnabled()) return
        channel(m)
        val left = (o.run.board - Runs.now()) * 1000
        if (left <= 0) return
        val n = builder(texts.offerTitle(o.line, o.run.board), texts.offerBody(o.run.kind, o.run.metres, o.run.seconds, o.saves))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setTimeoutAfter(left)
            .addAction(Notification.Action.Builder(Look.small(), texts.switch, broadcast(SWITCH, 1)).build())
            .addAction(Notification.Action.Builder(Look.small(), texts.notNow, broadcast(LATER, 2)).build())
            .build()
        m.notify(OFFER_ID, n)
    }

    /** A short word after Switch, gone by itself. */
    private fun note(text: String) {
        val m = manager() ?: return
        if (!m.areNotificationsEnabled()) return
        channel(m)
        m.notify(NOTE_ID, builder(text, null).setTimeoutAfter(NOTE_MS).build())
    }

    private fun builder(title: String, text: String?): Notification.Builder =
        Notification.Builder(app, CHANNEL)
            .setSmallIcon(Look.small())
            .setLargeIcon(Look.large())
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply { openKav()?.let(::setContentIntent) }

    private fun channel(m: NotificationManager) {
        if (m.getNotificationChannel(CHANNEL) != null) return
        m.createNotificationChannel(NotificationChannel(CHANNEL, texts.channel, NotificationManager.IMPORTANCE_HIGH))
    }

    private fun broadcast(action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(app, code,
        Intent(action).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openKav(): PendingIntent? = app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
        PendingIntent.getActivity(app, 3, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private var earlier: Pair<BroadcastReceiver, HandlerThread>? = null

        /** How long before the bus gets to a change Rush looks at it. */
        const val CHECK_S = 4 * 60L
        const val TRIES = 3
        const val RETRY_MS = 30_000L
        const val NOTE_MS = 15_000L
        const val ALARM = "change"
        const val CHANNEL = "rush.changes"
        const val OFFER_ID = 7801
        const val NOTE_ID = 7802
        const val SWITCH = "yair.rush.SWITCH"
        const val LATER = "yair.rush.LATER"
    }
}
