package yair.heroes

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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import kav.kavapp.api.Kav
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stay-awake reminders for one trip under way. Off at the start of every trip; the rider turns them on with the chip
 * in navigation. They stop when the rider turns the chip off, taps "Mute for this trip", ends the trip, or 30 minutes
 * after the trip's planned arrival, whichever comes first.
 *
 * Surviving Kav being closed: an extension can't add a receiver of its own to Android, and when Kav is swiped away,
 * Android stops everything the extension runs. So each reminder's alarm is aimed at one of Kav's own background
 * receivers (the kind Android may start Kav for). When the alarm goes off with Kav closed, Android starts Kav, Kav
 * starts the extension, and the extension finds the reminder that is due and shows it. That receiver's own code gets
 * an action it doesn't know, which receivers ignore; while Kav runs, a hook takes the alarm before it even gets there.
 */
internal class StayAwake(
    private val app: Context,
    private val kav: Kav,
    private val store: Store,
    private val texts: () -> Texts,
) {
    private companion object {
        const val ACTION_FIRE = "yair.heroes.STAY_AWAKE_FIRE"
        const val ACTION_MUTE = "yair.heroes.STAY_AWAKE_MUTE"
        const val CHANNEL = "heroes_stay_awake"
        const val NOTIFICATION_ID = 0x4E52
        const val MINUTE = 60_000L
        const val AFTER_ARRIVAL = 30 * MINUTE
        const val NO_ARRIVAL = 4 * 60 * MINUTE
        const val EARLY = 30_000L // a reminder this close to its time counts as due
    }

    /** The trip the reminders are on for, or null. */
    @Volatile var activeTrip: String? = null; private set
    @Volatile private var endAt = 0L
    @Volatile private var nextAt = 0L
    @Volatile private var lastPost = 0L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)

    /** The Kav receiver alarms are aimed at, so Android starts Kav for them; null to use the extension's own. */
    private val wakeTarget: ComponentName? = findWakeTarget()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_FIRE -> fire()
                ACTION_MUTE -> stop()
            }
        }
    }

    init {
        val filter = IntentFilter().apply { addAction(ACTION_FIRE); addAction(ACTION_MUTE) }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        wakeTarget?.let { hookWakeTarget(it) }
        restore()
    }

    fun onChange(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun isOn(trip: String) = activeTrip == trip

    /** Turns reminders on for this trip. arrivalSec: the trip's planned arrival, seconds since 1970, or 0. */
    fun turnOn(trip: String, arrivalSec: Long) {
        val now = System.currentTimeMillis()
        activeTrip = trip
        endAt = if (arrivalSec > 0) arrivalSec * 1000 + AFTER_ARRIVAL else now + NO_ARRIVAL
        if (endAt <= now) endAt = now + AFTER_ARRIVAL
        schedule(minOf(now + store.interval * MINUTE, endAt))
        changed()
    }

    fun stop() {
        if (activeTrip == null) return
        activeTrip = null
        alarms.cancel(firePending())
        notifications.cancel(NOTIFICATION_ID)
        remember()
        changed()
    }

    @Synchronized
    private fun fire() {
        if (activeTrip == null) return
        val now = System.currentTimeMillis()
        if (now >= endAt - 1000) { stop(); return }
        // The same alarm can arrive twice when Kav was just started for it: once here, once through the hook.
        if (now - lastPost < EARLY) return
        lastPost = now
        post()
        schedule(minOf(now + store.interval * MINUTE, endAt))
    }

    private fun schedule(at: Long) {
        nextAt = at
        remember()
        val pending = firePending()
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

    private fun firePending(): PendingIntent {
        val intent = Intent(ACTION_FIRE)
        if (wakeTarget != null) intent.component = wakeTarget else intent.setPackage(app.packageName)
        return PendingIntent.getBroadcast(app, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // Remembering the reminders across Kav being closed

    /** Kav started again during a trip with reminders on: carry on, and show the reminder now if it's due. */
    private fun restore() {
        try {
            if (!store.stayAwakeFile.isFile) return
            val j = JSONObject(store.stayAwakeFile.readText())
            val trip = j.optString("trip")
            val end = j.optLong("endAt")
            val next = j.optLong("nextAt")
            val now = System.currentTimeMillis()
            if (trip.isEmpty() || end <= now) { store.stayAwakeFile.delete(); return }
            activeTrip = trip
            endAt = end
            if (next <= now + EARLY) fire() else schedule(next)
        } catch (e: Exception) {
            store.stayAwakeFile.delete()
        }
    }

    private fun remember() {
        val trip = activeTrip
        try {
            if (trip == null) store.stayAwakeFile.delete()
            else store.stayAwakeFile.writeText(
                JSONObject().put("trip", trip).put("endAt", endAt).put("nextAt", nextAt).toString()
            )
        } catch (e: Exception) {
            // Not remembered: the reminders still run while Kav does.
        }
    }

    // The wake-up receiver

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
                kav.log("Stay awake: no Kav receiver to wake Kav with; reminders need Kav running")
                null
            } else {
                kav.log("Stay awake: reminders wake Kav through ${pick.name}")
                ComponentName(pick.packageName, pick.name)
            }
        } catch (e: Exception) {
            kav.log("Stay awake: couldn't look at Kav's receivers: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Take the alarm and "Mute for this trip" in that receiver before its own code sees them. When Kav was closed,
     * Android starts it for them, and Kav starts the extension (and this hook) first.
     */
    private fun hookWakeTarget(target: ComponentName) {
        try {
            var type: Class<*>? = kav.kavClassLoader().loadClass(target.className)
            while (type != null && type != Any::class.java) {
                val method = try {
                    type.getDeclaredMethod("onReceive", Context::class.java, Intent::class.java)
                } catch (e: NoSuchMethodException) {
                    null
                }
                if (method != null) {
                    kav.hook(method, Kav.MethodHook { call ->
                        val intent = call.args()[1] as Intent?
                        when (intent?.action) {
                            ACTION_FIRE -> { call.setResult(null); fire() }
                            ACTION_MUTE -> { call.setResult(null); stop() }
                        }
                    }, null)
                    return
                }
                type = type.superclass
            }
        } catch (e: Throwable) {
            // Not hooked: the receiver ignores the alarm, and restore() shows the reminder when Kav starts.
            kav.log("Stay awake: couldn't hook ${target.className}: ${e.javaClass.simpleName}")
        }
    }

    // The notification

    private fun post() {
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            kav.log("Stay awake: Kav isn't allowed to show notifications, so the reminder can't show")
            return
        }
        val t = texts()
        ensureChannel(t)
        val icon = Icon.createWithBitmap(smallIcon())
        // Aimed like the alarm, so "Mute for this trip" works even when Kav was swiped away.
        val muteIntent = Intent(ACTION_MUTE)
        if (wakeTarget != null) muteIntent.component = wakeTarget else muteIntent.setPackage(app.packageName)
        val mute = PendingIntent.getBroadcast(
            app, 2, muteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(t.notifTitle)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .addAction(Notification.Action.Builder(icon, t.mute, mute).build())
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { open ->
            builder.setContentIntent(
                PendingIntent.getActivity(app, 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        }
        notifications.notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel(t: Texts) {
        val channel = NotificationChannel(CHANNEL, t.stayAwake, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
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

    /** The notification's small icon: a white Star of David, drawn here because an extension has no resources. */
    private fun smallIcon(): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 9f; strokeJoin = Paint.Join.ROUND
        }
        val c = Canvas(bmp)
        val r = size * 0.42f
        val cx = size / 2f
        val cy = size / 2f
        for (start in listOf(-90.0, 90.0)) {
            val path = Path()
            for (k in 0..2) {
                val a = Math.toRadians(start + k * 120.0)
                val x = cx + r * Math.cos(a).toFloat()
                val y = cy + r * Math.sin(a).toFloat()
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, paint)
        }
        return bmp
    }

    private fun changed() { for (l in listeners) l() }
}
