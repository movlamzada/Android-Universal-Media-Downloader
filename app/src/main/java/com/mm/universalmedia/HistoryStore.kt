package com.mm.universalmedia

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One saved file in the history list. Only metadata lives here; the media itself stays in the user's folder. */
data class HistoryItem(val uri: String, val name: String, val size: Long, val time: Long)

/** Tiny JSON-file history. Clearing it never touches the saved media files. */
object HistoryStore {
    private const val MAX_ITEMS = 500

    private fun file(context: Context) = File(context.applicationContext.filesDir, "history.json")

    @Synchronized
    fun load(context: Context): List<HistoryItem> = runCatching {
        val source = file(context)
        if (!source.isFile) return emptyList()
        val array = JSONArray(source.readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val uri = item.optString("uri")
            if (uri.isBlank()) null
            else HistoryItem(uri, item.optString("name", "media"), item.optLong("size"), item.optLong("time"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(context: Context, item: HistoryItem) {
        save(context, listOf(item) + load(context).filterNot { it.uri == item.uri })
    }

    @Synchronized
    fun remove(context: Context, uri: String) = save(context, load(context).filterNot { it.uri == uri })

    @Synchronized
    fun retain(context: Context, uris: Set<String>) = save(context, load(context).filter { it.uri in uris })

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    private fun save(context: Context, items: List<HistoryItem>) {
        val array = JSONArray()
        items.take(MAX_ITEMS).forEach {
            array.put(JSONObject().put("uri", it.uri).put("name", it.name).put("size", it.size).put("time", it.time))
        }
        val target = file(context)
        val temp = File(target.parentFile, "history.json.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }
}
