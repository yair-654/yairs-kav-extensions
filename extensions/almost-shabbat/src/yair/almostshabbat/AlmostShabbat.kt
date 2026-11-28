package yair.almostshabbat

import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension

/**
 * Almost Shabbat: for trips arriving on the afternoon before Shabbat or a Yom Tov, how close they arrive to candle
 * lighting (or sunset), from the times of the locality nearest the destination. A line under each search result, a card
 * under a trip's summary, and a notification when a delay makes it worse. Kav calls start once each time it starts, on
 * a background thread.
 */
class AlmostShabbat : KavExtension {
    override fun start(kav: Kav) {
        val app = kav.context()
        val store = Store(app)
        val hebcal = Hebcal(app) { kav.log(it) }
        val clock = Clock(kav)
        val views = Views(kav, store, hebcal, clock)
        val delays = DelayWatch(app, kav, store, hebcal, clock)

        // A trip Kav describes in a way we don't expect shows nothing, rather than counting as a crash.
        fun safe(what: String, make: () -> android.view.View?): android.view.View? =
            try { make() } catch (e: Exception) { kav.log("$what: ${e.javaClass.simpleName}: ${e.message}"); null }
        kav.card("results", Kav.Card { c, about -> safe("Results line") { views.resultLine(c, about) } })
        kav.part("trip.summary", "after", Kav.Card { c, about -> safe("Trip card") { views.tripCard(c, about) } })
        kav.settingsScreen(Kav.Screen { c, _ -> SettingsView(c, kav, store).view() })

        // A trip ended with Kav's End trip: stop watching it, and let Kav end it as always.
        kav.action("trip.end", Kav.Action { _, _ -> delays.stop(); false })
        delays.install()

        kav.log("Almost Shabbat started, API ${kav.apiVersion()}, erevs until ${ErevData.LAST}")
    }
}
