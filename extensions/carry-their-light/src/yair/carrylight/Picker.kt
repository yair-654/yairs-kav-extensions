package yair.carrylight

import kav.kavapp.api.Kav
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Gets heroes ready before they're needed, on a thread of its own: enough for every spot of the trip (at least two),
 * each a hero the rider hasn't met, with their picture kept on the phone, so a card shows at once even with a weak
 * connection. Among new heroes, one who fell on today's date comes first, silently; a hero already waiting is never
 * swapped for another. When every hero was met, it starts over with
 * those met longest ago. If stickers.org.il can't be reached, nothing shows at all, never an error.
 */
internal class Picker(private val kav: Kav, private val store: Store, private val lang: Lang) {
    private companion object {
        const val RETRY_MS = 3 * 60_000L
        const val MAX_READY = 10
    }

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var lastTry = 0L
    @Volatile private var busy = false
    @Volatile private var want = 2
    /** Stickers the site couldn't give, skipped until Kav starts again. */
    private val failed = Collections.synchronizedSet(HashSet<String>())
    /** The day no new hero was found in 10 random handfuls: until it ends, heroes met long ago are met again. */
    @Volatile private var allMet = ""
    /** Today's "fell on this day" ids, in the story language they were asked in. */
    @Volatile private var fellToday: Triple<String, Boolean, List<String>>? = null

    /** The trip has this many spots: keep that many ready. */
    fun want(spots: Int) {
        val n = spots.coerceIn(2, MAX_READY)
        if (n > want) {
            want = n
            ensure(force = true)
        }
    }

    /** Makes sure enough heroes wait, and the widget has today's. force: try now even if a try just failed. */
    fun ensure(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (busy || (!force && now - lastTry < RETRY_MS) || !needsWork()) return
        lastTry = now
        busy = true
        worker.execute {
            try {
                prepare()
            } catch (e: Throwable) {
                kav.log("Couldn't get the next heroes ready: ${e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    /** A hero to read about now, told on the main thread: one by id (the widget's), else the first waiting; null when
     *  the site can't be reached. */
    fun now(id: String?, done: (Sticker?) -> Unit) {
        worker.execute {
            val s = try {
                when {
                    id != null && Site.validId(id) -> store.queued(id) ?: store.widgetHero()?.takeIf { it.id == id } ?: Site.sticker(id, lang.story).takeIf { it.ok }
                    else -> store.queue().firstOrNull() ?: run { prepare(); store.queue().firstOrNull() }
                }
            } catch (e: Throwable) {
                kav.log("Couldn't load a hero now: ${e.javaClass.simpleName}")
                null
            }
            main.post { done(s) }
        }
    }

    private fun needsWork(): Boolean {
        val queue = store.queue()
        return queue.size < want || store.widgetHero() == null || fellToday?.first != Site.today() ||
            queue.any { missingPicture(it) }
    }

    private fun missingPicture(s: Sticker) = s.imageUrl != null && !store.picture(s.id).isFile

    private fun prepare() {
        val he = lang.story
        // Enough heroes first, at random until today's list is in: a card needs a hero sooner than the best one.
        // A hero waiting stays the one waiting: heroes who fell on today's date come first among the new ones.
        fill(he, fellToday?.takeIf { it.first == Site.today() && it.second == he }?.third.orEmpty())
        val today = fellToday(he)
        fill(he, today)

        if (store.widgetHero() == null) next(he, store.queue().mapTo(HashSet()) { it.id }, today)?.let { store.setWidgetHero(it) }

        // Pictures last: a card shows the candle until its picture is here.
        for (s in store.queue()) if (missingPicture(s) && picture(s)) store.pictureArrived()
    }

    /** As many heroes waiting as the trip wants. */
    private fun fill(he: Boolean, today: List<String>) {
        var tries = 0
        while (store.queue().size < want && tries++ < want + 3) {
            next(he, store.queue().mapTo(HashSet()) { it.id }, today)?.let { store.addQueued(it) }
        }
    }

    /** A new hero, none of skip: one who fell today, else at random, else (all were met) the one met longest ago. */
    private fun next(he: Boolean, skip: Set<String>, today: List<String>): Sticker? {
        val avoid = skip + store.metIds() + failed
        val id = today.filter { it !in avoid }.randomOrNull()
            ?: randomNew(he, avoid)
            ?: store.longestAgo()?.takeIf { it !in skip && it !in failed }
            ?: return null
        return fetch(id, he)
    }

    /** A sticker from the site, or null (and skipped until Kav starts again) when it lacks a name or words. */
    private fun fetch(id: String, he: Boolean): Sticker? {
        val s = Site.sticker(id, he)
        if (s.ok) return s
        failed += id
        kav.log("Sticker $id skipped: missing its name or words")
        return null
    }

    /** A hero at random the rider hasn't met, from up to 10 handfuls; none for the rest of the day once they all miss. */
    private fun randomNew(he: Boolean, avoid: Set<String>): String? {
        val today = Site.today()
        if (allMet == today) return null
        val id = (1..10).firstNotNullOfOrNull { Site.randomIds(he).filter { it !in avoid }.randomOrNull() }
        if (id == null) allMet = today
        return id
    }

    /** Downloads a waiting hero's picture; true when it's there. */
    private fun picture(s: Sticker): Boolean {
        val url = s.imageUrl ?: return false
        return try {
            Site.download(url, store.picture(s.id))
            true
        } catch (e: Exception) {
            kav.log("Sticker picture not loaded: ${e.javaClass.simpleName}")
            false
        }
    }

    /** People who fell on today's date, asked once a day (and again when the story language changes). */
    private fun fellToday(he: Boolean): List<String> {
        val day = Site.today()
        fellToday?.let { (d, lang, ids) -> if (d == day && lang == he) return ids }
        val ids = Site.idsFellToday(he) { kav.log(it) }
        fellToday = Triple(day, he, ids)
        return ids
    }
}
