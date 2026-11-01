package com.mm.universalmedia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Result of URL analysis: only formats the extractor really found are exposed. */
data class MediaAnalysis(
    val title: String,
    val durationSeconds: Long,
    val extractor: String,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val heights: List<Int>,
    val thumbnail: Bitmap?
)

object Analyzer {
    private const val MAX_THUMBNAIL_BYTES = 4 * 1024 * 1024

    suspend fun analyze(context: Context, url: String): MediaAnalysis = withContext(Dispatchers.IO) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        val json = JSONObject(Python.getInstance().getModule("media_engine").callAttr("analyze_url", url.trim()).toString())
        val heights = json.optJSONArray("heights")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } }.orEmpty()
        MediaAnalysis(
            title = json.optString("title", "media"),
            durationSeconds = json.optDouble("duration", 0.0).toLong(),
            extractor = json.optString("extractor"),
            hasVideo = json.optBoolean("has_video", true),
            hasAudio = json.optBoolean("has_audio", true),
            heights = heights,
            thumbnail = loadThumbnail(json.optString("thumbnail"))
        )
    }

    private fun loadThumbnail(address: String): Bitmap? {
        if (!address.startsWith("https://", ignoreCase = true)) return null
        return runCatching {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            try {
                if (connection.responseCode != 200 || connection.contentLength > MAX_THUMBNAIL_BYTES) return null
                val sink = java.io.ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        if (sink.size() > MAX_THUMBNAIL_BYTES) return null
                    }
                }
                val bytes = sink.toByteArray()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
