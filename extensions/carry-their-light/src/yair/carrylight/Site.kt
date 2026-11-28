package yair.carrylight

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A text in both languages; either may be missing, and then the other one is used. */
internal class L(val he: String, val en: String) {
    fun of(heLang: Boolean): String = if (heLang) he.ifEmpty { en } else en.ifEmpty { he }
    val isEmpty get() = he.isEmpty() && en.isEmpty()
    fun toJson(): JSONObject = JSONObject().put("he", he).put("en", en)

    companion object {
        fun from(o: JSONObject?) = L(o?.str("he").orEmpty(), o?.str("en").orEmpty())
    }
}

/** One sticker, as stickers.org.il gives it (/api/stickers/<id>). Only what the cards need is read. */
internal class Sticker(val json: JSONObject) {
    private val fallen = json.optJSONObject("fallen")
    private val content = json.optJSONObject("content")

    val id: String = json.str("_id").orEmpty()
    val name = L.from(fallen?.optJSONObject("name") ?: json.optJSONObject("fallenName"))
    val gender: String? = fallen?.str("gender")
    private val rank: String? = fallen?.str("rank")
    /** "2023-03-14", or null. */
    private val fallDate: String? = fallen?.str("fallDate")?.take(10)
    val quote = L.from(content?.optJSONObject("stickerText"))

    /** The sticker's picture: the medium size when there is one. */
    val imageUrl: String? = run {
        val images = content?.optJSONArray("stickerImage") ?: return@run null
        val paths = (0 until images.length()).mapNotNull { images.optJSONObject(it)?.str("content") }
            .filter { it.startsWith("/") && !it.startsWith("//") }
        val path = paths.firstOrNull { ".medium." in it } ?: paths.firstOrNull { ".large." in it } ?: paths.firstOrNull()
        path?.let { Site.BASE + it }
    }

    val ok get() = Site.validId(id) && !quote.isEmpty && !name.isEmpty

    /** The year they fell, when it was on today's date in Israel; otherwise null. */
    fun fellToday(): Int? {
        val date = fallDate?.takeIf { it.length == 10 } ?: return null
        return if (date.substring(5) == Site.today().substring(5)) date.substring(0, 4).toIntOrNull() else null
    }

    /** "Sergeant Amit Lozon z"l" / "סמל עמית לוזון ז״ל". */
    fun title(he: Boolean): String {
        val person = if (he) name.of(true) else Names.english(name.of(false))
        return listOfNotNull(rank?.let { Ranks.title(it, he) }, person, if (he) "ז״ל" else "z\"l").joinToString(" ")
    }
}

internal object Names {
    /** The site keeps some English names in lower case ("amit lozon"): those get capitals. */
    fun english(name: String): String =
        if (name != name.lowercase()) name
        else name.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
}

/** stickers.org.il's ranks, in both languages, as the site itself names them. */
internal object Ranks {
    private val all = mapOf(
        "private" to L("טוראי", "Private"),
        "private-first-class" to L("טוראי ראשון", "Private First Class"),
        "corporal" to L("רב טוראי", "Corporal"),
        "sergeant" to L("סמל", "Sergeant"),
        "staff-sergeant" to L("סמל ראשון", "Staff Sergeant"),
        "sergeant-first-class" to L("רב סמל", "Sergeant First Class"),
        "master-sergeant" to L("רב סמל ראשון", "Master Sergeant"),
        "first-sergeant" to L("רב סמל מתקדם", "First Sergeant"),
        "sergeant-major" to L("רב סמל בכיר", "Sergeant Major"),
        "warrant-officer" to L("רב נגד משנה", "Warrant Officer"),
        "chief-warrant-officer" to L("רב נגד", "Chief Warrant Officer"),
        "academic-officer" to L("קצין מקצועי אקדמאי", "Academic Officer"),
        "senior-academic-officer" to L("קצין אקדמאי בכיר", "Senior Academic Officer"),
        "specialist-academic-officer" to L("קצין אקדמאי מיוחד", "Specialist Academic Officer"),
        "second-lieutenant" to L("סגן משנה", "Second Lieutenant"),
        "lieutenant" to L("סגן", "Lieutenant"),
        "captain" to L("סרן", "Captain"),
        "major" to L("רב סרן", "Major"),
        "lieutenant-colonel" to L("סגן אלוף", "Lieutenant Colonel"),
        "colonel" to L("אלוף משנה", "Colonel"),
        "brigadier-general" to L("תת אלוף", "Brigadier General"),
        "major-general" to L("אלוף", "Major General"),
        "lieutenant-general" to L("רב אלוף", "Lieutenant General"),
        "superintendent" to L("רב פקד", "Superintendent"),
        "constable" to L("שוטר", "Constable"),
        "corporal-police" to L("רב שוטר", "Corporal"),
        "sergeant-second-class" to L("סמל שני", "Sergeant"),
        "sub-inspector" to L("מפקח משנה", "Sub Inspector"),
        "inspector" to L("מפקח", "Inspector"),
        "chief-inspector" to L("פקד", "Chief Inspector"),
        "chief-superintendent" to L("סגן ניצב", "Chief Superintendent"),
        "commander" to L("ניצב משנה", "Commander"),
        "assistant-commissioner" to L("תת ניצב", "Assistant Commissioner"),
        "deputy-commissioner" to L("ניצב", "Deputy Commissioner"),
        "commissioner" to L("רב ניצב", "Commissioner"),
    )

    /** A rank the site added after this release has no title here: then none is shown. */
    fun title(id: String, he: Boolean): String? = all[id]?.of(he)
}

/** stickers.org.il, read only: the few public addresses its own pages use. Nothing about the rider is ever sent. */
internal object Site {
    const val BASE = "https://stickers.org.il"
    private const val HOST = "stickers.org.il"
    private val ID = Regex("^[a-f0-9]{24}$")
    /** Drishat Shalom's stickers are mostly of people who fell from October 2023 on. */
    private const val FIRST_YEAR = 2023

    fun validId(id: String) = ID.matches(id)
    private fun lang(he: Boolean) = if (he) "he" else "en"

    fun stickerPage(id: String, he: Boolean) = "$BASE/${lang(he)}/sticker/$id"
    fun addPage(he: Boolean) = "$BASE/${lang(he)}/sticker/add"
    fun home(he: Boolean) = "$BASE/${lang(he)}"

    /** Whether an address is Drishat Shalom's own, so it may open inside Kav. */
    fun isSite(scheme: String?, host: String?) =
        scheme == "https" && (host == HOST || host?.endsWith(".$HOST") == true)

    /** Today in Israel, "2026-11-28". */
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("Asia/Jerusalem") }.format(Date())

    /** A random handful of stickers' ids (the site's home wall). */
    fun randomIds(he: Boolean): List<String> = idsIn(JSONArray(get("$BASE/api/stickers/images/random?lang=${lang(he)}")), onlyMatching = false)

    /** The stickers of people who fell on today's date, in each year the site covers. A year that fails is skipped. */
    fun idsFellToday(he: Boolean, log: (String) -> Unit): List<String> {
        val today = today()
        val ids = LinkedHashSet<String>()
        for (year in FIRST_YEAR..today.take(4).toInt()) {
            try {
                val j = JSONObject(get("$BASE/api/stickers/images/filtered?fallDate=$year${today.substring(4)}&lang=${lang(he)}"))
                if (j.optInt("matchingCount", 0) > 0) ids += idsIn(j.optJSONArray("stickers") ?: continue, onlyMatching = true)
            } catch (e: Exception) {
                log("Fell on this day ($year) not loaded: ${e.javaClass.simpleName}")
            }
        }
        return ids.toList()
    }

    fun sticker(id: String, he: Boolean): Sticker {
        require(validId(id))
        return Sticker(JSONObject(get("$BASE/api/stickers/$id?lang=${lang(he)}")))
    }

    /** A sticker's picture, written whole or not at all. Pictures may be served from a storage server: redirects are
     *  followed, https only. */
    fun download(url: String, to: File) {
        val data = bytes(url, 6 * 1024 * 1024, redirects = true)
        val tmp = File(to.path + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(to)) { to.delete(); tmp.renameTo(to) }
    }

    /** Ids of the sticker objects anywhere in the site's walls (lists of columns, with empty places as null). */
    private fun idsIn(walls: JSONArray, onlyMatching: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is JSONObject -> {
                    val id = v.str("_id")
                    if (id != null && validId(id) && (!onlyMatching || v.optBoolean("matchesFilter", false))) out += id
                }
            }
        }
        walk(walls)
        return out.toList()
    }

    private fun get(url: String): String = String(bytes(url, 4 * 1024 * 1024), Charsets.UTF_8)

    /** Once more when the answer ended at once: Android reused a connection the server had already closed. */
    private fun bytes(url: String, maxBytes: Int, redirects: Boolean = false): ByteArray =
        try {
            fetch(url, maxBytes, redirects)
        } catch (e: java.io.EOFException) {
            fetch(url, maxBytes, redirects)
        }

    private fun fetch(url: String, maxBytes: Int, redirects: Boolean): ByteArray {
        require(url.startsWith("$BASE/"))
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = redirects
            conn.setRequestProperty("User-Agent", "CarryTheirLight/1.0 (Kav extension)")
            conn.setRequestProperty("Accept", "application/json, image/*")
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            if (conn.url.protocol != "https") throw IOException("not https")
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("too large")
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }
}
