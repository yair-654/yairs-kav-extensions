package yair.rush

import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension

/**
 * Rush: buses you can still catch if you jog or run. Kav calls start once each time it starts, on a background thread.
 */
class Rush : KavExtension {
    override fun start(kav: Kav) {
        val app = kav.context()
        val store = Store(app)
        val bits = KavBits(kav).apply { find() }

        kav.settingsScreen(Kav.Screen { c, _ -> SettingsView(c, kav, bits, store).view() })
        if (!bits.ok) return

        val search = Search(kav, bits, store).apply { install() }
        if (!search.installed) return
        val trip = TripWatch(app, kav, bits, store, search).apply { install() }
        val views = Views(kav, bits, store, search, trip)

        kav.part("results.options", "after", Kav.Card { c, about -> views.rushRow(c, about) })
        kav.card("results", Kav.Card { c, about -> views.routeLine(c, about) })
        kav.part("trip.walk", "replace", Kav.Card { c, about -> views.tripWalk(c, about) })
        kav.part("nav.card", "before", Kav.Card { c, about -> views.navCard(c, about) })
        kav.log("Rush: ready")
    }
}
