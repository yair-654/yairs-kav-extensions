package yair.heroes

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import kav.kavapp.api.Kav
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stay-awake reminders for one trip. Off at the start of every trip; the rider turns them on with the button in a
 * ride's card. They stop when the rider turns the button off, taps Mute, ends the trip, or 30 minutes after the trip's
 * planned arrival, whichever comes first.
 *
 * Each reminder is two alarms for the same moment, and the first to ring shows it:
 * - an exact Android alarm, to the extension's own receiver, which rings on the minute while Kav runs (it does all
 *   through a trip, for its trip notification);
 * - Kav's own alarm (`kav.alarm`, API 5), which also wakes Kav when it was closed, though Android may hold it a few
 *   minutes while the phone is idle. When it wakes Kav, [restore] carries on from the file the reminders are kept in.
 *
 * Mute opens Kav's own screen with a marker on it, which [Application.ActivityLifecycleCallbacks] (Android's own, not a
 * hook) see as soon as Kav's screen opens or comes back, so it works whether Kav was running or not.
 */
internal class StayAwake(
    private val app: Context,
    private val kav: Kav,
    private val store: Store,
    private val config: Config,
    private val texts: () -> Texts,
) {
    private companion object {
        const val ALARM = "stay-awake"
        const val ACTION_FIRE = "yair.heroes.STAY_AWAKE_FIRE"
        const val EXTRA_MUTE = "yair.heroes.STAY_AWAKE_MUTE"
        const val CHANNEL = "heroes_stay_awake"
        const val NOTIFICATION_ID = 0x4E52
        const val MINUTE = 60_000L
        const val AFTER_ARRIVAL = 30 * MINUTE
        const val NO_ARRIVAL = 4 * 60 * MINUTE
        const val EARLY = 30_000L // a reminder this close to its time counts as due, and two this close are one
    }

    /** The trip the reminders are on for, or null. */
    @Volatile var activeTrip: String? = null; private set
    @Volatile private var endAt = 0L
    @Volatile private var nextAt = 0L
    @Volatile private var lastPost = 0L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_FIRE) fire()
        }
    }

    init {
        val filter = IntentFilter(ACTION_FIRE)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        kav.onAlarm(Kav.Alarm { id -> if (id == ALARM) fire() })
        (app.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, saved: Bundle?) = checkMute(a)
            override fun onActivityResumed(a: Activity) = checkMute(a)
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, out: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        restore()
        // The rider turned the button off in settings: no more reminders.
        config.onChange { if (!config.stayAwake) stop() }
    }

    fun onChange(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun isOn(trip: String) = activeTrip == trip

    /** Turns reminders on for this trip. arrivalSec: the trip's planned arrival, seconds since 1970, or 0. */
    @Synchronized
    fun turnOn(trip: String, arrivalSec: Long) {
        val now = System.currentTimeMillis()
        activeTrip = trip
        lastPost = 0L
        endAt = if (arrivalSec > 0) arrivalSec * 1000 + AFTER_ARRIVAL else now + NO_ARRIVAL
        if (endAt <= now) endAt = now + AFTER_ARRIVAL
        schedule(minOf(now + config.interval * MINUTE, endAt))
        changed()
    }

    /** Kav planned a new route on the way: the reminders carry on, now for the new trip and its arrival. */
    @Synchronized
    fun moved(fromTrip: String, toTrip: String, arrivalSec: Long) {
        if (activeTrip != fromTrip || fromTrip == toTrip) return
        activeTrip = toTrip
        if (arrivalSec > 0) endAt = maxOf(endAt, arrivalSec * 1000 + AFTER_ARRIVAL)
        remember()
        changed()
    }

    @Synchronized
    fun stop() {
        if (activeTrip == null) return
        activeTrip = null
        alarms.cancel(firePending())
        try { kav.alarm(ALARM, 0) } catch (e: Exception) { kav.log("Stay awake: couldn't cancel Kav's alarm: ${e.javaClass.simpleName}") }
        notifications.cancel(NOTIFICATION_ID)
        remember()
        changed()
    }

    @Synchronized
    private fun fire() {
        if (activeTrip == null) return
        val now = System.currentTimeMillis()
        if (now >= endAt - 1000) { stop(); return }
        // Both alarms ring for the same reminder: only the first shows it. Early ones (Android may ring a little
        // early) count as due; one rung long before its time is not, and waits.
        if (now < nextAt - EARLY) return
        if (now - lastPost < EARLY) return
        lastPost = now
        post()
        schedule(minOf(now + config.interval * MINUTE, endAt))
    }

    /** Both alarms for the next reminder; the time goes in the file too, for when Kav starts again. */
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
        try { kav.alarm(ALARM, at) } catch (e: Exception) { kav.log("Stay awake: couldn't set Kav's alarm: ${e.javaClass.simpleName}") }
    }

    private fun firePending(): PendingIntent = PendingIntent.getBroadcast(
        app, 1, Intent(ACTION_FIRE).setPackage(app.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // Remembering the reminders across Kav being closed

    /** Kav started again with reminders on: carry on, and show the reminder now if it's due. */
    @Synchronized
    private fun restore() {
        try {
            if (!store.stayAwakeFile.isFile) return
            val j = JSONObject(store.stayAwakeFile.readText())
            val trip = j.optString("trip")
            val end = j.optLong("endAt")
            val next = j.optLong("nextAt")
            val now = System.currentTimeMillis()
            if (trip.isEmpty() || end <= now || !config.stayAwake) { store.stayAwakeFile.delete(); return }
            activeTrip = trip
            endAt = end
            nextAt = next
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

    // Mute

    /** Mute was tapped: Kav's screen opened with the marker on it. */
    fun checkMute(activity: Activity) {
        val intent = activity.intent ?: return
        if (!intent.getBooleanExtra(EXTRA_MUTE, false)) return
        intent.removeExtra(EXTRA_MUTE)
        stop()
    }

    private fun mutePending(): PendingIntent? {
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        open.putExtra(EXTRA_MUTE, true)
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(app, 2, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
        val icon = Icon.createWithBitmap(Icons.star())
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(t.notifTitle)
            .setContentText(t.notifText)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
        mutePending()?.let { builder.addAction(Notification.Action.Builder(icon, t.mute, it).build()) }
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { open ->
            builder.setContentIntent(
                PendingIntent.getActivity(app, 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        }
        notifications.notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel(t: Texts) {
        val channel = NotificationChannel(CHANNEL, t.channel, NotificationManager.IMPORTANCE_HIGH).apply {
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

    private fun changed() { for (l in listeners) l() }
}
