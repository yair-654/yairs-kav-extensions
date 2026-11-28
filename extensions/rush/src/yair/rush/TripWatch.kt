package yair.rush

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.widget.Toast
import kav.kavapp.api.Kav
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Rush during a trip.
 *
 * At changes ("Run between rides too"): while the rider is on a ride with a change after it, Kav works out when the
 * trip arrives every few seconds (`tripArrival` in Kav's ui/Navigate.kt), and a hook reads it, with the ride and its
 * live times. About [WINDOW_S] before the ride gets to the change, Rush searches once from that stop, from when the
 * ride gets there by its live time, for a connection that running makes and that gets there at least the rider's
 * "saves at least" sooner. If there is one, the rider gets a notification and a card in the step-by-step guide, with
 * Switch. Switch changes the trip under way with Kav's own code for New route from here (ReplanKt.switched).
 *
 * Kav's own New route from here: when the trip came from a Rush search, its new route is searched with Rush too, and
 * when it includes a run, the step-by-step guide says so.
 */
internal class TripWatch(
    private val app: Context,
    private val kav: Kav,
    private val bits: KavBits,
    private val store: Store,
    private val search: Search,
) {
    private companion object {
        const val WINDOW_S = 240L
        const val CHANNEL = "rush_changes"
        const val NOTIFICATION_ID = 0x5255
        const val ACTION_SWITCH = "yair.rush.SWITCH"
        const val ACTION_DISMISS = "yair.rush.DISMISS"
        const val NAVIGATE = "uk.noammm.kav.ui.NavigateKt"
        const val ARRIVAL = "tripArrival"
        val ARRIVAL_PARAMS = arrayOf(
            "uk.noammm.kav.data.Transit\$Itinerary", "uk.noammm.kav.data.Transit\$Resolved", "java.util.Map",
            "uk.noammm.kav.data.Transit\$Leg", "uk.noammm.kav.ui.Fix", "long", "z3.g",
        )
        const val REPLAN = "uk.noammm.kav.ui.ReplanKt"
        const val REPLANNED = "replanned"
        val REPLANNED_PARAMS = arrayOf("uk.noammm.kav.ActiveJourney", "uk.noammm.kav.ui.ReplanFrom", "java.util.List", "int")
        const val NOTE_MS = 15 * 60_000L
        const val TRIES = 3
    }

    /** A faster connection found at a change, waiting for the rider's Switch. */
    class Offer(
        val tripKey: String,
        val guid: String,
        val rideIndex: Int,
        val at: Any,
        val alight: Long,
        val route: Any,
        val resolved: Any,
        val run: Store.Run,
        val line: String,
        val savesMin: Int,
        val stopsLeft: List<Any>,
    )

    @Volatile var offer: Offer? = null; private set
    /** After Kav's own New route from here put a run in the trip: which trip, the run, and until when it's said. */
    @Volatile var note: Triple<String, Store.Run, Long>? = null; private set

    /** The changes checked, or being checked now: each once. */
    private val checked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** How many times a change's check failed (no network, say), kept apart so a retry never resets it. */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val notifications = app.getSystemService(NotificationManager::class.java)

    fun onChange(l: () -> Unit) { listeners += l }
    private fun changed() { main.post { for (l in listeners) l() } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SWITCH -> switchNow(null)
                ACTION_DISMISS -> dismiss()
            }
        }
    }

    fun install() {
        val filter = IntentFilter().apply { addAction(ACTION_SWITCH); addAction(ACTION_DISMISS) }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        hookArrival()
        hookReplan()
    }

    fun tripKey(dep: Long, arr: Long) = "$dep:$arr"

    // Changes

    private fun hookArrival() {
        val after = Kav.MethodHook { call ->
            try { onArrival(call) } catch (e: Throwable) { kav.log("Rush: change check skipped: ${e.javaClass.simpleName}") }
        }
        try {
            kav.hook(NAVIGATE, ARRIVAL, ARRIVAL_PARAMS, null, after)
            if (kav.hooked(NAVIGATE, ARRIVAL, *ARRIVAL_PARAMS)) return
            // Kav renamed a parameter type in this release (Kotlin's Pair): find it by its name instead.
            val m = Class.forName(NAVIGATE, true, kav.kavClassLoader()).declaredMethods.firstOrNull {
                it.name == ARRIVAL && it.parameterTypes.size == ARRIVAL_PARAMS.size && it.returnType == java.lang.Long.TYPE
            } ?: run { kav.log("Rush: this Kav has no tripArrival; no checks at changes"); return }
            kav.hook(m, null, after)
        } catch (e: Throwable) {
            kav.log("Rush: can't watch trips under way: ${e.javaClass.simpleName}")
        }
    }

    /** Kav worked out when the trip under way arrives. Often, and maybe on the main thread: keep it quick. */
    private fun onArrival(call: Kav.Call) {
        // Never before the rider has read and accepted what Rush is ("I understand").
        if (!store.transfers || !store.safetyAccepted || !bits.canSwitch) return
        val args = call.args()
        val trip = args[0] ?: return
        val r = args[1] ?: return
        val riding = args[3] ?: return
        val now = (args[5] as? Long)?.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
        val arrival = call.result() as? Long ?: return

        val legs = bits.legs(trip)
        var i = legs.indexOfFirst { it === riding || it == riding || bits.options(it).any { o -> o == riding } }
        if (i < 0) i = legs.indexOfFirst { bits.kind(it) == "RIDE" && bits.tripId(it) == bits.tripId(riding) }
        if (i < 0) return
        // A change: another ride after this one, with only walking and waiting between them.
        val j = (i + 1 until legs.size).firstOrNull { bits.kind(legs[it]) != "WALK" && bits.kind(legs[it]) != "WAIT" } ?: return
        if (bits.kind(legs[j]) != "RIDE") return

        val alight = bits.alightAt(r, riding)
        if (alight - now !in 0..WINDOW_S) return
        val guid = bits.guid(trip)
        val changeKey = "$guid:$i:${bits.tripId(riding)}"
        if ((failures[changeKey] ?: 0) >= TRIES || !checked.add(changeKey)) return
        val planner = bits.replanAt(trip) ?: return
        // A trip an extension's planner planned: Rush never searches with it, so there's nothing to check.
        if (bits.isExtensionPlanner(planner)) return
        val at = bits.endPoint(r, riding) ?: return
        val end = legs.lastOrNull { bits.shape(it).isNotEmpty() }?.let { bits.shape(it).lastOrNull() } ?: return
        val key = tripKey(bits.itDep(trip), bits.itArr(trip))
        // Stops on the way already passed: those before the change. The rest the new route must still go through.
        val passed = legs.subList(0, i + 1).count { bits.isVisit(it) }
        val anyStops = legs.any { bits.isVisit(it) }
        Thread({
            if (!check(key, guid, i, at, end, alight, arrival, planner, passed, anyStops)) {
                // It couldn't search: a later arrival time tries again, [TRIES] times in all.
                if (failures.merge(changeKey, 1, Int::plus)!! < TRIES) checked.remove(changeKey)
            }
        }, "rush-change").start()
    }

    private fun check(
        key: String, guid: String, rideIndex: Int, at: Any, end: Any, alight: Long, arrival: Long, planner: String,
        passed: Int, anyStops: Boolean,
    ): Boolean {
        try {
            // The stops on the way still ahead, from the trip as Kav keeps it. A trip with stops on the way whose list
            // can't be read is left alone: a new route must never skip one.
            val stopsLeft: List<Any> = if (!anyStops) emptyList() else {
                val journey = bits.model()?.let { bits.journey(it) }
                if (journey == null || bits.guid(bits.journeyTrip(journey)) != guid) { kav.log("Rush: can't read the trip's stops on the way"); return false }
                bits.stopovers(journey).drop(passed)
            }
            val c = Search.Context(Search.Context.Kind.TRANSFER, from = alight, startS = RunMath.GET_OFF_S, compareArr = arrival)
            search.within(c) {
                bits.callPlan(at, end, alight * 1000, Search.TIME_DEPARTURE, emptyList<Any>(), false, stopsLeft,
                    planner, bits.riderSettings())
            }
            val out = c.outcome ?: return false
            val best = out.best ?: run { kav.log("Rush: no faster run at the change"); return true }
            val savesMin = (best.saves / 60).toInt()
            if (savesMin < store.savesMin) { kav.log("Rush: a run at the change saves only $savesMin min"); return true }
            val json = bits.routeJson(best.route, out.resolved)
            val legs = json?.optJSONArray("legs")
            var line = ""
            if (legs != null) for (k in 0 until legs.length()) {
                val l = legs.optJSONObject(k) ?: continue
                if (l.optString("kind") == "ride") { line = l.optString("lineNumber").takeIf { it != "null" }.orEmpty(); break }
            }
            offer = Offer(key, guid, rideIndex, at, alight, best.route, out.resolved, best.run, line, savesMin, stopsLeft)
            kav.log("Rush: a ${best.run.pace.name.lowercase()} at the change saves $savesMin min")
            post()
            changed()
            return true
        } catch (e: Throwable) {
            kav.log("Rush: couldn't search at the change: ${e.javaClass.simpleName}")
            return false
        }
    }

    /** The offer for the trip the guide shows, while its bus can still be caught. */
    fun offerFor(tripKey: String): Offer? {
        val o = offer ?: return null
        if (o.tripKey != tripKey) return null
        if (System.currentTimeMillis() / 1000 > o.run.board - 15) { dismiss(); return null }
        return o
    }

    /** Switch: the trip goes on with the faster connection. From the card (its context) or the notification (null). */
    fun switchNow(context: Context?) {
        main.post {
            val o = offer ?: return@post
            val t = Texts.of(kav)
            val toastOn = context ?: app
            if (System.currentTimeMillis() / 1000 > o.run.board - 15) {
                Toast.makeText(toastOn, t.offerGone, Toast.LENGTH_LONG).show(); dismiss(); return@post
            }
            val model = bits.model()
            val newGuid = if (model == null) null else try {
                bits.switchTrip(model, o.guid, o.rideIndex, o.at, o.alight, o.route, o.resolved, o.stopsLeft)
            } catch (e: Throwable) {
                kav.log("Rush: switch failed: ${e.javaClass.simpleName}: ${e.message}"); null
            }
            if (newGuid != null) {
                // The switched trip is a Rush trip, so a later New route from here looks for runs too.
                store.putRuns(emptyMap(), listOf(o.guid, newGuid))
                Toast.makeText(toastOn, t.switched, Toast.LENGTH_LONG).show()
                dismiss()
            } else {
                Toast.makeText(toastOn, t.couldntSwitch, Toast.LENGTH_LONG).show()
                openKav()
            }
        }
    }

    fun dismiss() {
        offer = null
        notifications.cancel(NOTIFICATION_ID)
        changed()
    }

    // Kav's own New route from here

    private fun hookReplan() {
        val before = Kav.MethodHook { call -> onReplan(call) }
        try {
            kav.hook(REPLAN, REPLANNED, REPLANNED_PARAMS, before, null)
            if (kav.hooked(REPLAN, REPLANNED, *REPLANNED_PARAMS)) return
            kav.log("Rush: this Kav has no replanned; New route from here won't look for runs")
        } catch (e: Throwable) {
            kav.log("Rush: can't watch New route from here: ${e.javaClass.simpleName}")
        }
    }

    private fun onReplan(call: Kav.Call) {
        val journey = call.args()[0] ?: return
        val isRush = try { store.isRushTrip(bits.guid(bits.journeyTrip(journey))) } catch (e: Throwable) { false }
        if (!isRush || !store.safetyAccepted) return
        val c = Search.Context(Search.Context.Kind.REPLAN)
        // Kav's own work, with Rush's search inside it. Its own failure (no network, say) stays Kav's to show.
        val result = try {
            search.within(c) { call.callOriginal(*call.args()) }
        } catch (e: Throwable) {
            call.setError(e); return
        }
        call.setResult(result)
        val best = c.outcome?.best ?: return
        try {
            val next = result?.let { bits.pairParts(it).first } ?: return
            val trip = bits.journeyTrip(next)
            val hasRun = bits.legs(trip).any { l ->
                bits.kind(l) == "WALK" && store.run(store.walkKey(bits.meters(l), bits.dep(l), bits.arr(l))) != null
            }
            if (!hasRun) return
            note = Triple(tripKey(bits.itDep(trip), bits.itArr(trip)), best.run, System.currentTimeMillis() + NOTE_MS)
            store.putRuns(emptyMap(), listOf(bits.guid(trip)))
            kav.log("Rush: New route from here includes a ${best.run.pace.name.lowercase()}")
            changed()
        } catch (e: Throwable) {
            kav.log("Rush: couldn't read the new route: ${e.javaClass.simpleName}")
        }
    }

    fun noteFor(tripKey: String): Store.Run? {
        val n = note ?: return null
        if (n.first != tripKey || System.currentTimeMillis() > n.third) return null
        return n.second
    }

    // The notification

    private fun post() {
        val o = offer ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            kav.log("Rush: Kav isn't allowed to show notifications; the offer is only in the guide")
            return
        }
        val t = Texts.of(kav)
        ensureChannel(t)
        val icon = Icon.createWithBitmap(smallIcon())
        fun broadcast(action: String, code: Int) = PendingIntent.getBroadcast(
            app, code, Intent(action).setPackage(app.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = "${t.offerBody(o.run, o.savesMin)}. ${t.offerHow}"
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(t.offerTitle(o.line, o.run.board))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText("$body\n${t.watchYourWay}."))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            // Kav's red or amber, as numbers: Kav's theme can only be read on the main thread.
            .setColor(if (o.run.pace == RunMath.Pace.RUN) 0xFFB3424E.toInt() else 0xFF9A6A00.toInt())
            .addAction(Notification.Action.Builder(icon, t.switchTrip, broadcast(ACTION_SWITCH, 11)).build())
            .addAction(Notification.Action.Builder(icon, t.notNow, broadcast(ACTION_DISMISS, 12)).build())
        val left = (o.run.board - System.currentTimeMillis() / 1000) * 1000
        if (left > 0) builder.setTimeoutAfter(left)
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { open ->
            builder.setContentIntent(PendingIntent.getActivity(app, 13, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        notifications.notify(NOTIFICATION_ID, builder.build())
    }

    private fun openKav() {
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { app.startActivity(open) } catch (e: Exception) { }
    }

    private fun ensureChannel(t: Texts) {
        val channel = NotificationChannel(CHANNEL, t.channel, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        // Creating it again only updates its name: the rider's own choices for the channel are kept.
        notifications.createNotificationChannel(channel)
    }

    /** The notification's small icon, drawn here because an extension has no resources: two forward chevrons. */
    private fun smallIcon(): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 12f
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val c = Canvas(bmp)
        for (x in listOf(22f, 50f)) {
            val p = Path().apply { moveTo(x, 22f); lineTo(x + 24f, 48f); lineTo(x, 74f) }
            c.drawPath(p, paint)
        }
        return bmp
    }
}
