package yair.carrylight

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import kav.kavapp.api.Kav

/**
 * The hero as a notification, 2 minutes after the rider starts a long ride (or wait) that has one, if they turned it
 * on. Kav tells the extension when the trip moves to that step (Follow your trips); Kav's own alarm wakes it 2 minutes
 * later, also with Kav closed, and every later trip event checks again, so it comes on time even when Android holds
 * the alarm back. It goes out as long as the rider is still on that spot, never once they've left it; it's skipped
 * when they already saw the hero's card there, and it goes when they leave the spot or the trip ends. A tap opens the
 * hero's story in Kav (Story's view over the screen takes it).
 */
internal class Notifier(
    private val app: Context,
    private val kav: Kav,
    private val store: Store,
    private val live: Live,
    private val lang: Lang,
) {
    companion object {
        const val ALARM = "notify"
        private const val CHANNEL = "hero"
        private const val ID = 1
        private const val DELAY_MS = 2 * 60_000L
    }

    private val manager = app.getSystemService(NotificationManager::class.java)

    /** Kav's language may have changed since the channel was made: its name follows. */
    fun channel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, lang.texts.channel, NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** The trip moved on, was planned again, or Kav told it afresh: the notification follows the spot it's on. */
    @Synchronized fun follow() {
        val journey = live.trip
        val spots = journey?.let(live::spots).orEmpty()
        val spot = journey?.current(spots)
        val hero = spot?.let { s -> heroes(spots, journey, store)[s.key] }
        val due = store.due()
        if (spot == null || hero == null || !live.prefs.notify) {
            if (due != null) drop()
            return
        }
        if (due?.spot != spot.key) {
            if (due?.posted == true) manager.cancel(ID)
            val at = System.currentTimeMillis() + DELAY_MS
            store.setDue(Store.Due(spot.key, at, posted = false))
            kav.alarm(ALARM, at)
        }
        check()
    }

    /** The trip ended, or the notification was turned off. */
    @Synchronized fun drop() {
        if (store.due()?.posted == true) manager.cancel(ID)
        store.setDue(null)
        kav.alarm(ALARM, 0)
    }

    /** Its alarm rang, or the trip told something: out now, if it's due and still wanted. */
    @Synchronized fun check() {
        val due = store.due()?.takeIf { !it.posted } ?: return
        if (System.currentTimeMillis() < due.at) return
        val journey = live.trip ?: return // Kav tells the trip again as it starts; then it's checked again.
        val spots = live.spots(journey)
        val spot = journey.current(spots)?.takeIf { it.key == due.spot } ?: return
        val hero = heroes(spots, journey, store)[spot.key]
        if (hero != null && !store.wasSeen(spot.key) && live.prefs.notify) post(hero, spot)
        store.setDue(Store.Due(due.spot, due.at, posted = true))
    }

    private fun post(s: Sticker, spot: Spot) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            kav.log("Notification not sent: Kav isn't allowed to show notifications")
            return
        }
        try {
            channel()
            val he = lang.story
            val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                it.putExtra(Story.EXTRA_HERO, s.id).putExtra(Story.EXTRA_SPOT, spot.key)
                PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val left = spot.end * 1000 - System.currentTimeMillis()
            val words = quoted(s.quote.of(he))
            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(Icon.createWithBitmap(Candle.bitmap(96, Color.WHITE)))
                .setContentTitle(s.title(he))
                .setContentText(words)
                .setStyle(Notification.BigTextStyle().bigText(words))
                .setAutoCancel(true)
                .apply {
                    open?.let { setContentIntent(it) }
                    Pictures.load(store.picture(s.id), app.dpx(64f))?.let { setLargeIcon(it) }
                    if (left > 60_000) setTimeoutAfter(left)
                }
                .build()
            manager.notify(ID, n)
        } catch (e: Exception) {
            kav.log("Notification not sent: ${e.javaClass.simpleName}")
        }
    }
}
