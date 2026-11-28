package yair.carrylight

import kav.kavapp.api.Kav
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * The home screen widget: one hero, a different one each day and as soon as the rider opened the one shown; a hero
 * who fell on today's date first. Their name, their words, and when they fell on this day. A tap opens their story on
 * the Read about a hero page. Kav draws it; a widget can't show pictures.
 */
internal class HeroWidget(private val store: Store, private val picker: Picker, private val lang: Lang) : Kav.Widget {
    override fun content(args: String): String {
        val s = try { store.widgetHero() } catch (e: Exception) { null }
        if (s == null) {
            picker.ensure(force = true)
            return JSONObject().put("title", "").put("rows", JSONArray()).put("empty", lang.texts.widgetEmpty).toString()
        }
        val he = lang.story
        val open = JSONObject().put("page", "read").put("args", JSONObject().put("hero", s.id))
        return JSONObject()
            .put("title", s.title(he))
            .put("rows", JSONArray().put(JSONObject().put("title", quoted(s.quote.of(he))).put("action", open)))
            .apply { s.fellToday()?.let { put("note", Texts(he).fellToday(s.gender, it)) } }
            .put("action", open)
            .put("next", secondsToMidnight())
            .toString()
    }

    /** A new day in Israel: a new hero. */
    private fun secondsToMidnight(): Long {
        val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem"))
        val midnight = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 30); set(Calendar.MILLISECOND, 0)
        }
        return (midnight.timeInMillis - now.timeInMillis) / 1000
    }
}
