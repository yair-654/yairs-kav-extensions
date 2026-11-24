package yair.heroes

import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension

/**
 * The Heroes, for soldiers: paying with Zuzu, the choger or Tzav 8 at train security, stay-awake reminders during a
 * trip, and a thank-you above search results. Kav calls start once each time it starts, on a background thread.
 */
class Heroes : KavExtension {
    override fun start(kav: Kav) {
        val app = kav.context()
        val store = Store(app)
        val texts = { Texts.of(kav) }
        val photos = Photos(app, store, kav, texts).apply { install() }
        val stayAwake = StayAwake(app, kav, store, texts)
        val views = Views(kav, store)
        val navOverlay = NavOverlay(kav, store, stayAwake)

        // Paying: Kav's Pay buttons open Zuzu only while Zuzu is on. Off, the action isn't registered at all, so Kav's
        // own Pay, or another payment extension, works exactly as without this one.
        val zuzuPay = Kav.Action { activity, _ -> Zuzu.open(activity); true }
        fun updatePay() = kav.action("pay", if (store.zuzu) zuzuPay else null)
        updatePay()
        store.onChange { updatePay() }

        // A trip ended with Kav's End trip: stop the reminders, and let Kav end it as always.
        kav.action("trip.end", Kav.Action { _, _ -> stayAwake.stop(); false })

        kav.card("home", Kav.Card { c, _ -> views.homeCard(c) })
        kav.part("results.time.shift", "after", Kav.Card { c, _ -> views.banner(c) })
        // An invisible marker above each step card in navigation, which the floating pills are placed from.
        kav.part("nav.card", "before", Kav.Card { c, about -> navOverlay.marker(c, about) })
        kav.settingsScreen(Kav.Screen { c, _ -> Settings(c, kav, store, photos, stayAwake).view() })
    }
}
