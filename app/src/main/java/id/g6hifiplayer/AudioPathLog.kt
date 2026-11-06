package id.g6hifiplayer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hifi 1.40: the audio path results of the last tracks (MD5 check verdicts, files that were refused, playback errors), newest first, kept
 * across app starts. It is display only: nothing here touches the audio data or the output.
 */
internal object AudioPathLog {
    const val KIND_OK = 0        // MD5 MATCH, no MD5 in the file
    const val KIND_UNMATCH = 1   // MD5 DIFFERENT, not comparable
    const val KIND_ERROR = 2     // file refused (strict mode), playback error

    private const val MAX_ENTRIES = 50
    private const val PREFS = "audio_path_log"
    private const val KEY = "entries"

    class Entry(val time: Long, val kind: Int, val head: String, val name: String, val detail: String)

    private val entries = ArrayList<Entry>()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val pending = ArrayList(entries)   // entries added before the first init (kept on top)
        entries.clear()
        try {
            val arr = JSONArray(p.getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(Entry(o.optLong("t"), o.optInt("k"), o.optString("h"), o.optString("n"), o.optString("d")))
            }
        } catch (_: Exception) { entries.clear() }
        entries.addAll(0, pending)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
    }

    @Synchronized
    fun add(kind: Int, head: String, name: String, detail: String) {
        entries.add(0, Entry(System.currentTimeMillis(), kind, head, name, detail))
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        val p = prefs ?: return
        try {
            val arr = JSONArray()
            for (e in entries) arr.put(JSONObject().put("t", e.time).put("k", e.kind).put("h", e.head).put("n", e.name).put("d", e.detail))
            p.edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) { }
    }

    @Synchronized
    fun snapshot(): List<Entry> = ArrayList(entries)
}
