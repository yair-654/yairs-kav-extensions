package yair.rush

import kav.kavapp.api.Kav
import org.json.JSONObject

/** The rider's settings, as Kav keeps them (declared in the manifest, drawn by Kav). Read afresh each time. */
internal class Settings(private val kav: Kav) {
    private fun all(): JSONObject = runCatching { JSONObject(kav.settings()) }.getOrDefault(JSONObject())

    val inResults get() = all().optBoolean("results", true)
    val savesSeconds get() = (all().optDouble("saves", 5.0) * 60).toLong()
    val tightOk get() = all().optBoolean("tight", false)
    val betweenRides get() = all().optBoolean("changes", false)

    val speeds: RunMath.Speeds
        get() {
            val a = all()
            return RunMath.Speeds(a.optDouble("jog-kmh", 8.0), a.optDouble("jog-metres", 1000.0).toInt(),
                a.optDouble("run-kmh", 10.0), a.optDouble("run-metres", 400.0).toInt())
        }
}
