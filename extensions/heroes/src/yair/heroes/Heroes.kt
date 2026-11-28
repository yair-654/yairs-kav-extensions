package yair.heroes

import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension

/**
 * Soldiers (internal name "heroes"): paying with Zuzu, the choger or Tzav 8 at train security, stay-awake reminders
 * on rides, and a thank-you above search results. API 5, with no hooks into Kav. Kav calls start once each time it
 * starts, on a background thread.
 */
class Heroes : KavExtension {
    override fun start(kav: Kav) {
        val app = kav.context()
        val texts = { Texts.of(kav) }
        val store = Store(app)
        val config = Config(kav)
        val trips = Trips(kav)
        val photos = Photos(app, store, kav, texts)
        val stayAwake = StayAwake(app, kav, store, config, texts)
        val views = Views(kav, store, config, stayAwake)
        val rideButton = RideButton(kav, config, stayAwake)
        val documentButton = DocumentButton(kav, store, config, trips, stayAwake)
        val widget = Widget(kav, store, config)

        // Paying: Kav's Pay buttons open Zuzu only while Pay with Zuzu is on. Off, the action isn't registered at all, so
        // Kav's own Pay, or another payment extension, works exactly as without this one.
        val zuzuPay = Kav.Action { activity, _ -> Zuzu.open(activity); true }
        fun updatePay() = onMain { kav.action("pay", if (config.zuzu) zuzuPay else null) }
        updatePay()

        // The trip's end: Kav's End trip in navigation, and with "Follow your trips", any end (the notification's End,
        // arriving). A new route on the way keeps the reminders on for the new trip.
        kav.action("trip.end", Kav.Action { _, _ -> stayAwake.stop(); false })
        trips.onEvent { event, was, now ->
            when (event) {
                "end" -> stayAwake.stop()
                "replan" -> if (was != null && now != null) stayAwake.moved(was.key, now.key, now.trip.optLong("arr"))
            }
        }
        // The trip's plan opened over navigation: the document button steps aside. Kav still opens the plan.
        kav.action("nav.plan", Kav.Action { _, _ -> documentButton.planOpened(); false })

        kav.card("home", Kav.Card { c, _ -> views.homeCard(c) })
        kav.part("results.time.shift", "after", Kav.Card { c, _ -> views.banner(c) })
        fun rideParts() = kav.part("nav.ride.details", "after", Kav.Card { c, about -> rideButton.view(c, about) })
        rideParts()
        kav.part("screen.overlay", "after", Kav.Card { c, _ -> documentButton.view(c) })
        kav.settingsScreen(Kav.Screen { c, _ -> SettingsView(c, kav, store, config, photos).view() })
        Screens(kav, store, config).register()
        widget.register()

        // A setting or the photo changed: everything that shows them is asked again.
        config.onChange {
            updatePay()
            onMain { rideParts() }
            documentButton.update()
            widget.refresh()
        }
        photos.onChange {
            documentButton.update()
            widget.refresh()
        }

        kav.log("Started: API ${kav.apiVersion()}")
    }
}
