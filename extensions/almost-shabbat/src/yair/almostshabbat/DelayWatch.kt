package yair.almostshabbat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import kav.kavapp.api.Kav
import org.json.JSONObject
import java.time.LocalDate

/**
 * The delay notification. During a trip, Kav works out when the trip will really arrive, delays and all, the "Arrive"
 * of its own trip notification, and tells it in every trip event as "eta", with an "eta" event each time it moves to
 * another minute, from Kav's trip service, so with Kav in the background too (`kav.onTrip`, contribution
 * `trips.follow`). When a trip arriving on an erev moves into a worse band (from "within 45 minutes" to "within 30",
 * say), the rider gets one notification with sound. Never for a better band, and never twice for the same one, also
 * when Kav plans a new route on the way. Nothing for a trip planned to leave more than an hour after sunset.
 *
 * Surviving Kav being closed: while a trip is watched, an alarm every few minutes (`kav.alarm`) wakes Kav, which
 * starts this extension and tells it (`kav.onAlarm`). The watch, kept in a file, carries on from there.
 */
internal class DelayWatch(
    private val app: Context,
    private val kav: Kav,
    private val store: Store,
    private val hebcal: Hebcal,
    private val clock: Clock,
) {
    private companion object {
        const val WAKE = "wake"
        const val CHANNEL = "almost_shabbat_delays"
        const val NOTIFICATION_ID = 0x5348
        const val MINUTE = 60_000L
        const val WAKE_EVERY = 3 * MINUTE
        /** Watching ends this long after the trip's latest known arrival. */
        const val AFTER_ARRIVAL = 60 * MINUTE
    }

    /**
     * The trip being watched. key follows it to a new route on the way; plannedDep stays the first route's, so a late bus
     * or a new route never counts as the rider choosing to leave late.
     */
    private class Watch(
        @Volatile var key: String,
        val placeId: Int,
        val date: LocalDate,
        val plannedDep: Long,
        @Volatile var worst: Int,
        @Volatile var endAt: Long,
    ) {
        @Volatile var times: Times? = null
        /** When Kav last said the trip arrives, in seconds; 0 until it has. */
        @Volatile var eta = 0L
    }

    @Volatile private var watch: Watch? = null
    /** A trip already looked at that doesn't arrive on an erev, so it isn't looked at again at every event. */
    @Volatile private var ignored: String? = null

    private val notifications = app.getSystemService(NotificationManager::class.java)

    /** Told when the watched trip's arrival moved, for the line in navigation. */
    @Volatile var onArrival: (() -> Unit)? = null

    /** Kav's latest arrival for this trip, when it's the one watched. */
    fun etaFor(trip: JSONObject): Long? = watch?.takeIf { it.key == tripKey(trip) && it.eta > 0 }?.eta

    /** The planned departure of the trip as first planned, when it's the one watched (a new route on the way leaves later). */
    fun plannedDepFor(trip: JSONObject): Long? = watch?.takeIf { it.key == tripKey(trip) }?.plannedDep

    fun install() {
        kav.onAlarm(Kav.Alarm { id -> if (id == WAKE) wake() })
        // A setting changed during a trip: start counting bands again from where the trip is now, without a notification.
        store.onChange { watch?.worst = -1 }
        // The watch from before Kav was closed first: a trip already under way is told at once.
        restore()
        followTrips()
    }

    /** The trip ended, or isn't one to watch after all. */
    private fun stop() {
        watch = null
        kav.alarm(WAKE, 0)
        store.watchFile.delete()
    }

    // Reading Kav's arrival

    private fun followTrips() {
        // Kav tells it only while the rider allows "Follow your trips" on the extension's page. Never let a mistake of
        // ours count against the extension: Kav's own trip notification must go on as always.
        kav.onTrip(Kav.TripListener { event ->
            try { onTrip(JSONObject(event)) } catch (e: Exception) { kav.log("Delays: ${e.javaClass.simpleName}: ${e.message}") }
        })
    }

    /** A trip event, on a thread of Kav's: each tells when the trip arrives as Kav works it out now. */
    @Synchronized
    private fun onTrip(e: JSONObject) {
        // Told about a trip at all: "Follow your trips" is on, so the trip card's hint about it isn't needed.
        store.markFollowSeen()
        val event = e.optString("event")
        if (event == "end") { if (watch != null) stop(); return }
        val trip = e.optJSONObject("trip") ?: return
        val arrival = e.optLong("eta", 0L).takeIf { it > 0 } ?: return
        val key = tripKey(trip)

        val known = watch
        // A new route on the way is the same trip: its band carries on, so a worse one is still told.
        if (event == "replan" && known != null && known.key != key) { known.key = key; remember() }
        val w: Watch = if (known != null && known.key == key) known else {
            if (key == ignored) return
            begin(key, trip, arrival) ?: run { ignored = key; return }
        }
        w.endAt = maxOf(w.endAt, arrival * 1000 + AFTER_ARRIVAL)
        if (w.eta != arrival) { w.eta = arrival; onArrival?.invoke() }
        val times = w.times ?: return
        if (System.currentTimeMillis() > w.endAt) { stop(); return }
        if (leavesAfterShabbat(w.plannedDep, times.sunset)) { ignored = w.key; stop(); return }

        val verdict = Verdict.of(arrival, times.candles, times.sunset, store.ref)
        when {
            w.worst < 0 -> { w.worst = verdict.level; remember() }
            verdict.level > w.worst -> {
                w.worst = verdict.level
                remember()
                post(w, verdict, times)
            }
        }
    }

    /** A trip seen for the first time: watched when it arrives on an erev from noon, by plan or as things stand. */
    private fun begin(key: String, trip: JSONObject, arrival: Long): Watch? {
        val planned = trip.optLong("arr", 0L)
        val erev = Calendar.erevFor(planned) ?: Calendar.erevFor(arrival) ?: return null
        val (lat, lon) = destination(trip) ?: return null
        val place = Places.nearest(lat, lon) ?: return null
        val w = Watch(key, place.id, erev.date, trip.optLong("dep", 0L), -1, maxOf(planned, arrival) * 1000 + AFTER_ARRIVAL)
        watch = w
        kav.log("Delays: watching a trip to ${place.en} on ${erev.date}")
        fetchTimes(w)
        remember()
        schedule()
        return w
    }

    private fun fetchTimes(w: Watch) {
        val place = Places.byId(w.placeId) ?: return
        val state = hebcal.get(place, w.date) { answer -> (answer as? Hebcal.State.Ready)?.let { w.times = it.times } }
        (state as? Hebcal.State.Ready)?.let { w.times = it.times }
    }

    // The notification

    private fun post(w: Watch, v: Verdict, times: Times) {
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            kav.log("Delays: Kav isn't allowed to show notifications")
            return
        }
        val he = kav.locale() == "he"
        val t = Texts(he)
        val erev = Calendar.erevOn(w.date) ?: return
        val text = Words(he, store.ref, erev.beginsName(he)).delayNotice(v, clock.format(times.candles), clock.format(times.sunset))
        ensureChannel(t)
        val icon = Icon.createWithBitmap(Art.smallIcon())
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(t.name)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { open ->
            builder.setContentIntent(
                PendingIntent.getActivity(app, 0x53, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        }
        notifications.notify(NOTIFICATION_ID, builder.build())
        kav.log("Delays: notified, level ${v.level}")
    }

    private fun ensureChannel(t: Texts) {
        val channel = NotificationChannel(CHANNEL, t.notifyChannel, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        // Creating it again only updates its name: the rider's own choices for the channel are kept.
        notifications.createNotificationChannel(channel)
    }

    // Keeping Kav awake during a watched trip

    private fun schedule() {
        kav.alarm(WAKE, System.currentTimeMillis() + WAKE_EVERY)
    }

    @Synchronized
    private fun wake() {
        val w = watch ?: return
        if (System.currentTimeMillis() > w.endAt) { stop(); return }
        if (w.times == null) fetchTimes(w)
        schedule()
    }

    // Remembering the watch across Kav being closed

    private fun remember() {
        val w = watch
        try {
            if (w == null) store.watchFile.delete()
            else store.watchFile.writeText(
                JSONObject().put("key", w.key).put("place", w.placeId).put("date", w.date.toString())
                    .put("dep", w.plannedDep).put("worst", w.worst).put("endAt", w.endAt).toString()
            )
        } catch (e: Exception) {
            // Not remembered: the watch still runs while Kav does.
        }
    }

    private fun restore() {
        try {
            if (!store.watchFile.isFile) return
            val j = JSONObject(store.watchFile.readText())
            val endAt = j.optLong("endAt")
            if (endAt <= System.currentTimeMillis()) { store.watchFile.delete(); return }
            val w = Watch(j.getString("key"), j.getInt("place"), LocalDate.parse(j.getString("date")), j.optLong("dep", 0L), j.optInt("worst", -1), endAt)
            watch = w
            fetchTimes(w)
            wake()
        } catch (e: Exception) {
            store.watchFile.delete()
        }
    }
}
