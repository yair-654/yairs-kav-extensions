package yair.almostshabbat

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import kav.kavapp.api.Kav
import org.json.JSONObject
import java.lang.reflect.Method
import java.time.LocalDate

/**
 * The delay notification. During a trip, Kav works out when the trip will really arrive, delays and all, each time it
 * updates its own trip notification (about every 15 seconds): `tripArrival` in Kav's ui/Navigate.kt. A hook reads that
 * answer. When a trip arriving on an erev moves into a worse band (from "within 45 minutes" to "within 30", say), the
 * rider gets one notification with sound. Never for a better band, and never twice for the same one.
 *
 * Surviving Kav being closed, as The Heroes does: an extension can't add a receiver of its own to Android. So while a
 * trip is watched, an alarm every few minutes is aimed at one of Kav's own background receivers. If Android closed Kav,
 * the alarm starts it again, Kav starts this extension, and the extension asks Kav to bring back its own trip service
 * (the one that works out the arrival), so the hook carries on. The receiver itself ignores an action it doesn't know.
 */
internal class DelayWatch(
    private val app: Context,
    private val kav: Kav,
    private val store: Store,
    private val hebcal: Hebcal,
    private val clock: Clock,
) {
    private companion object {
        const val ACTION_WAKE = "yair.almostshabbat.WAKE"
        const val CHANNEL = "almost_shabbat_delays"
        const val NOTIFICATION_ID = 0x5348
        const val MINUTE = 60_000L
        const val WAKE_EVERY = 3 * MINUTE
        /** Watching ends this long after the trip's latest known arrival. */
        const val AFTER_ARRIVAL = 60 * MINUTE

        const val NAVIGATE = "uk.noammm.kav.ui.NavigateKt"
        const val METHOD = "tripArrival"
        val PARAMS = arrayOf(
            "uk.noammm.kav.data.Transit\$Itinerary", "uk.noammm.kav.data.Transit\$Resolved", "java.util.Map",
            "uk.noammm.kav.data.Transit\$Leg", "uk.noammm.kav.ui.Fix", "long", "z3.g",
        )
    }

    /** The trip being watched. */
    private class Watch(
        val key: String,
        val placeId: Int,
        val date: LocalDate,
        @Volatile var worst: Int,
        @Volatile var endAt: Long,
    ) {
        @Volatile var times: Times? = null
    }

    @Volatile private var watch: Watch? = null
    /** A trip already looked at that doesn't arrive on an erev, so it isn't looked at again every 15 seconds. */
    @Volatile private var ignored: String? = null

    private val loader = kav.kavClassLoader()
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val wakeTarget: ComponentName? = findWakeTarget()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_WAKE) wake()
        }
    }

    // Kav's own functions, found once by name.
    private var routeJson: Method? = null
    private var pipeline: Any? = null
    private var guid: Method? = null
    private var dep: Method? = null

    fun install() {
        val filter = IntentFilter(ACTION_WAKE)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        wakeTarget?.let { hookWakeTarget(it) }
        findKavFunctions()
        hookArrival()
        // A setting changed during a trip: start counting bands again from where the trip is now, without a notification.
        store.onChange { watch?.worst = -1 }
        restore()
    }

    /** The rider ended the trip with Kav's End trip. */
    fun stop() {
        watch = null
        ignored = null
        alarms.cancel(wakePending())
        store.watchFile.delete()
    }

    // Reading Kav's arrival

    private fun findKavFunctions() {
        try {
            val p = Class.forName("uk.noammm.kav.ext.Pipeline", true, loader)
            pipeline = p.getField("INSTANCE").get(null)
            routeJson = p.methods.firstOrNull { it.name == "routeJson" && it.parameterTypes.size == 2 }
            val itinerary = Class.forName("uk.noammm.kav.data.Transit\$Itinerary", true, loader)
            guid = itinerary.getMethod("getGuid")
            dep = itinerary.getMethod("getDep")
        } catch (e: Throwable) {
            kav.log("Delays: couldn't find Kav's trip functions: ${e.javaClass.simpleName}")
        }
    }

    private fun hookArrival() {
        // Never let a mistake of ours count against the extension: Kav's own trip notification must go on as always.
        val after = Kav.MethodHook { call ->
            try { onArrival(call) } catch (e: Exception) { kav.log("Delays: ${e.javaClass.simpleName}: ${e.message}") }
        }
        try {
            kav.hook(NAVIGATE, METHOD, PARAMS, null, after)
            if (kav.hooked(NAVIGATE, METHOD, *PARAMS)) { kav.log("Delays: reading Kav's arrival times"); return }
            // Kav renamed a parameter type in this release: find the function by its name instead.
            val navigate = Class.forName(NAVIGATE, true, loader)
            val method = navigate.declaredMethods.firstOrNull {
                it.name == METHOD && it.parameterTypes.size == PARAMS.size && it.returnType == java.lang.Long.TYPE
            }
            if (method == null) { kav.log("Delays: Kav's arrival function isn't in this Kav; no delay notifications"); return }
            kav.hook(method, null, after)
            kav.log("Delays: reading Kav's arrival times (found by name)")
        } catch (e: Throwable) {
            kav.log("Delays: couldn't read Kav's arrival times: ${e.javaClass.simpleName}")
        }
    }

    /** Kav just worked out when the trip under way arrives. On Kav's main thread, every few seconds: keep it quick. */
    private fun onArrival(call: Kav.Call) {
        val arrival = (call.result() as? Long) ?: return
        val args = call.args()
        val itinerary = args.getOrNull(0) ?: return
        val key = try { "${guid?.invoke(itinerary)}:${dep?.invoke(itinerary)}" } catch (e: Throwable) { return }

        val known = watch
        val w: Watch = if (known != null && known.key == key) known else {
            if (key == ignored) return
            begin(key, itinerary, args.getOrNull(1), arrival) ?: run { ignored = key; return }
        }
        w.endAt = maxOf(w.endAt, arrival * 1000 + AFTER_ARRIVAL)
        val times = w.times ?: return
        if (System.currentTimeMillis() > w.endAt) { stop(); return }

        val verdict = Verdict.of(arrival, times.candles, times.sunset, store.ref)
        when {
            w.worst < 0 -> { w.worst = verdict.level; remember() }
            verdict.level > w.worst -> {
                w.worst = verdict.level
                remember()
                if (store.notify) post(w, verdict, times)
            }
        }
    }

    /** A trip seen for the first time: watched when it arrives on an erev from noon, by plan or as things stand. */
    private fun begin(key: String, itinerary: Any, resolved: Any?, arrival: Long): Watch? {
        val trip = try { routeJson?.invoke(pipeline, itinerary, resolved) as? JSONObject } catch (e: Throwable) { null } ?: return null
        val planned = trip.optLong("arr", 0L)
        val erev = Calendar.erevFor(planned) ?: Calendar.erevFor(arrival) ?: return null
        val (lat, lon) = destination(trip) ?: return null
        val place = Places.nearest(lat, lon) ?: return null
        val w = Watch(key, place.id, erev.date, -1, maxOf(planned, arrival) * 1000 + AFTER_ARRIVAL)
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
        val at = System.currentTimeMillis() + WAKE_EVERY
        val pending = wakePending()
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun wakePending(): PendingIntent {
        val intent = Intent(ACTION_WAKE)
        if (wakeTarget != null) intent.component = wakeTarget else intent.setPackage(app.packageName)
        return PendingIntent.getBroadcast(app, 0x53, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @Synchronized
    private fun wake() {
        val w = watch ?: return
        if (System.currentTimeMillis() > w.endAt) { stop(); return }
        if (w.times == null) fetchTimes(w)
        bringBackTripService()
        schedule()
    }

    /** If Android closed Kav's own trip service, ask Kav to show it again, the way Kav does when it's opened. */
    private fun bringBackTripService() {
        try {
            val service = Class.forName("uk.noammm.kav.TripService", true, loader)
            val companion = service.getField("Companion").get(null) ?: return
            val showing = companion.javaClass.getMethod("getShowing").invoke(companion) as? Boolean ?: return
            if (!showing) {
                companion.javaClass.getMethod("show", Context::class.java).invoke(companion, app)
                kav.log("Delays: asked Kav to bring back its trip service")
            }
        } catch (e: Throwable) {
            kav.log("Delays: couldn't check Kav's trip service: ${e.javaClass.simpleName}")
        }
    }

    // Remembering the watch across Kav being closed

    private fun remember() {
        val w = watch
        try {
            if (w == null) store.watchFile.delete()
            else store.watchFile.writeText(
                JSONObject().put("key", w.key).put("place", w.placeId).put("date", w.date.toString())
                    .put("worst", w.worst).put("endAt", w.endAt).toString()
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
            val w = Watch(j.getString("key"), j.getInt("place"), LocalDate.parse(j.getString("date")), j.optInt("worst", -1), endAt)
            watch = w
            fetchTimes(w)
            wake()
        } catch (e: Exception) {
            store.watchFile.delete()
        }
    }

    // The wake-up receiver, as in The Heroes

    /**
     * One of Kav's own receivers that Android can start Kav for: enabled, in Kav's main process, needing no permission
     * Kav lacks. A home-screen widget's receiver first (it ignores actions it doesn't know), then Kav's own, then
     * WorkManager's.
     */
    private fun findWakeTarget(): ComponentName? {
        return try {
            val pm = app.packageManager
            val flags = PackageManager.GET_RECEIVERS or PackageManager.GET_META_DATA
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(app.packageName, flags)
            }
            val mainProcess = app.applicationInfo.processName
            fun usable(r: ActivityInfo): Boolean {
                val state = pm.getComponentEnabledSetting(ComponentName(r.packageName, r.name))
                val enabled = when (state) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> r.enabled
                    else -> false
                }
                val allowed = r.permission == null || app.checkSelfPermission(r.permission) == PackageManager.PERMISSION_GRANTED
                val avoid = listOf("ProfileInstall", "Diagnostics", "Boot").any { r.name.contains(it) }
                return enabled && allowed && !avoid && r.processName == mainProcess
            }
            val receivers = (info.receivers ?: emptyArray()).filter(::usable)
            val pick = receivers.firstOrNull { it.metaData?.containsKey("android.appwidget.provider") == true }
                ?: receivers.firstOrNull { !it.name.startsWith("androidx.") }
                ?: receivers.firstOrNull { it.name.endsWith("RescheduleReceiver") }
            if (pick == null) {
                kav.log("Delays: no Kav receiver to wake Kav with; notifications need Kav running")
                null
            } else {
                ComponentName(pick.packageName, pick.name)
            }
        } catch (e: Exception) {
            kav.log("Delays: couldn't look at Kav's receivers: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Take the wake-up in that receiver before its own code sees it. */
    private fun hookWakeTarget(target: ComponentName) {
        try {
            var type: Class<*>? = loader.loadClass(target.className)
            while (type != null && type != Any::class.java) {
                val method = try {
                    type.getDeclaredMethod("onReceive", Context::class.java, Intent::class.java)
                } catch (e: NoSuchMethodException) {
                    null
                }
                if (method != null) {
                    kav.hook(method, Kav.MethodHook { call ->
                        val intent = call.args()[1] as Intent?
                        if (intent?.action == ACTION_WAKE) { call.setResult(null); wake() }
                    }, null)
                    return
                }
                type = type.superclass
            }
        } catch (e: Throwable) {
            // Not hooked: the receiver ignores the alarm, and restore() carries on when Kav starts.
            kav.log("Delays: couldn't hook ${target.className}: ${e.javaClass.simpleName}")
        }
    }
}
