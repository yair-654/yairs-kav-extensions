package yair.carrylight

import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension
import org.json.JSONObject

/**
 * Carry Their Light (נוסעים לאורם): on a long ride, one of Israel's fallen heroes, through their sticker on Drishat
 * Shalom (stickers.org.il, by HILMA and Yad Labanim). Kav calls start once each time it starts, on a background thread.
 *
 * A hero joins the rider on a long ride: at least their minimum on board (and on a long wait, if they allow it). One
 * per trip, on its longest ride, or one on every long ride; on Yom HaZikaron every ride of 5 minutes or more. The card
 * shows in navigation under the step dots, and on Home while on that ride; if they turn it on, a notification comes 2
 * minutes in, and a tap on it opens the hero's story. A hero the rider opened is met and isn't offered again until every other hero was; one closed with ✕
 * comes back on their next long ride.
 */
class CarryTheirLight : KavExtension {
    override fun start(kav: Kav) {
        val app = kav.context()
        val store = Store(app)
        val live = Live(kav, store)
        val lang = Lang(kav) { live.prefs }
        val picker = Picker(kav, store, lang)
        val story = Story(kav, store, picker, lang)
        val cards = Cards(kav, store, picker, story, live, lang)
        val notifier = Notifier(app, kav, store, live, lang)
        val pages = Pages(kav, store, picker, story, lang)

        val banner = Kav.Card { c, about -> cards.banner(c, about) }
        val home = Kav.Card { c, about -> cards.home(c, about) }
        // What the cards and the widget show changed: Kav asks for them again (coalesced, on the main thread).
        val redraw = Runnable {
            kav.part("nav.banner", "after", banner)
            kav.part("home.journey", "after", home)
            kav.refreshWidget(WIDGET)
        }
        fun changed() { main.removeCallbacks(redraw); main.post(redraw) }

        kav.part("nav.banner", "after", banner)
        kav.part("home.journey", "after", home)
        kav.part("screen.overlay", "after", Kav.Card { c, _ -> story.overlay(c) })
        kav.screen("met") { c, _ -> pages.met(c) }
        kav.screen("read") { c, args -> pages.read(c, args) }
        kav.screen("sticker") { c, _ -> pages.addSticker(c) }
        kav.screen("about") { c, _ -> pages.about(c) }
        kav.widget(WIDGET, HeroWidget(store, picker, lang))

        kav.onAlarm { id -> if (id == Notifier.ALARM) notifier.check() }
        kav.onTrip { event ->
            val e = try { JSONObject(event) } catch (ex: Exception) { return@onTrip }
            when (e.optString("event")) {
                "end" -> { live.trip = null; notifier.drop(); changed() }
                "start", "step", "replan" -> { live.trip = Journey.fromEvent(e); notifier.follow(); changed() }
                "shown", "eta" -> { live.trip = Journey.fromEvent(e) ?: live.trip; notifier.check() }
            }
        }
        kav.onSettings { all ->
            live.prefs = Prefs(all)
            notifier.follow()
            changed()
        }
        store.onChange { changed() }

        notifier.channel()
        picker.ensure(force = true)
        kav.log("Carry Their Light: ready")
    }

    private companion object {
        const val WIDGET = "hero"
    }
}
