package com.mm.universalmedia.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.mm.universalmedia.HistoryItem
import com.mm.universalmedia.HistoryStore
import com.mm.universalmedia.MediaDownloaderApp
import com.mm.universalmedia.PythonProgressCallback
import com.mm.universalmedia.R
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class MediaWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private var lastProgressAt = 0L
    private var lastKnownPercent = 0
    private var lastStatus = ""
    private var postStart = 85

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(foregroundInfo(0, applicationContext.getString(R.string.status_starting)))
            val treeText = inputData.getString(KEY_OUTPUT_TREE).orEmpty()
            if (treeText.isBlank()) {
                return@withContext failure(applicationContext.getString(R.string.error_choose_folder))
            }
            val treeUri = Uri.parse(treeText)
            when (inputData.getString(KEY_TASK)) {
                TASK_DOWNLOAD -> download(treeUri)
                TASK_CONVERT -> convertBatch(treeUri)
                else -> failure(applicationContext.getString(R.string.error_unknown_task))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (isStopped) {
                Result.failure(workDataOf(KEY_RESULT_MESSAGE to applicationContext.getString(R.string.status_cancelled)))
            } else {
                failure(userMessage(error))
            }
        } finally {
            // CoroutineWorker.onStopped() is final. WorkManager cancels this coroutine;
            // perform native FFmpeg cleanup here instead of overriding that method.
            if (isStopped) {
                runCatching { FFmpegKit.cancel() }
            }
        }
    }

    private suspend fun download(treeUri: Uri): Result {
        val url = inputData.getString(KEY_URL).orEmpty().trim()
        val format = inputData.getString(KEY_FORMAT).orEmpty().uppercase(Locale.ROOT)
        val quality = inputData.getString(KEY_QUALITY).orEmpty()
        if (!url.startsWith("https://", true)) {
            return failure(applicationContext.getString(R.string.error_invalid_url))
        }
        if (format !in setOf("MP4", "MP3")) {
            return failure(applicationContext.getString(R.string.error_invalid_format))
        }

        // Stable directory per (url, format, quality): a retried or re-enqueued job finds the
        // partial `.part` / yt-dlp fragments from the interrupted attempt and resumes them.
        pruneStaleJobDirs()
        val jobKey = sha256Text("$url|$format|$quality").take(24)
        val jobDir = File(applicationContext.filesDir, "media-jobs/$jobKey").apply { mkdirs() }
        var finished = false
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
            val downloadWeight = if (format == "MP3") 0.70 else 0.85
            postStart = (downloadWeight * 100).toInt()
            val bridge = object : PythonProgressCallback {
                override fun isCancelled(): Boolean = this@MediaWorker.isStopped

                override fun onProgress(percent: Double, stage: String, detail: String) {
                    if (isStopped) return
                    when (stage) {
                        "progress" -> {
                            val stats = runCatching { JSONObject(detail) }.getOrNull()
                            val overall = if (percent < 0) -1 else (percent * downloadWeight).toInt().coerceIn(0, 99)
                            report(
                                overall, applicationContext.getString(R.string.status_downloading),
                                stats?.optLong("d", -1) ?: -1, stats?.optLong("t", -1) ?: -1,
                                stats?.optLong("s", -1) ?: -1, stats?.optInt("e", -1) ?: -1
                            )
                        }
                        "download_finished" -> report(postStart, applicationContext.getString(R.string.status_download_finished))
                        "analyzing" -> report(0, applicationContext.getString(R.string.status_starting))
                    }
                }
            }
            report(0, applicationContext.getString(R.string.status_starting))
            val module = Python.getInstance().getModule("media_engine")
            val rawResult = module.callAttr(
                "download_media", url, format, quality, jobDir.absolutePath, bridge
            ).toString()
            ensureActive()

            val downloadResult = JSONObject(rawResult)
            val baseName = safeBaseName(downloadResult.optString("display_name").ifBlank { "media" })
            val outExt = if (format == "MP3") "mp3" else "mp4"
            val duration = downloadResult.optDouble("duration_seconds", 0.0)
            val mp3Bitrate = if (quality in BITRATES) quality else "320k"
            val finalTemp = when (downloadResult.optString("kind")) {
                "single" -> {
                    val downloaded = requireJobFile(jobDir, downloadResult.getString("path"))
                    if (!downloaded.isFile || downloaded.length() <= 0L) {
                        return failure(applicationContext.getString(R.string.error_download_output_missing))
                    }
                    val info = probe(downloaded) ?: throw IllegalStateException("The downloaded file is not a valid media file.")
                    if (format == "MP3") {
                        val mp3 = File(jobDir, "${UUID.randomUUID()}.mp3")
                        encodeMp3(downloaded, mp3, mp3Bitrate) { fraction ->
                            report(postStart + (fraction * (99 - postStart)).toInt(), applicationContext.getString(R.string.status_converting))
                        }
                        mp3
                    } else if (info.format.orEmpty().contains("mp4") && downloaded.length() > 0) {
                        // Already an MP4/MOV container: no re-processing, only the extension is normalised.
                        downloaded
                    } else {
                        // WebM/MKV/unknown container (typical for Facebook & co.): remux into a real MP4.
                        val mp4 = File(jobDir, "${UUID.randomUUID()}.mp4")
                        muxToMp4(listOf(downloaded), mp4, info.duration?.toDoubleOrNull() ?: duration)
                        downloaded.delete()
                        mp4
                    }
                }
                "merge" -> {
                    val video = requireJobFile(jobDir, downloadResult.getString("video_path"))
                    val audio = requireJobFile(jobDir, downloadResult.getString("audio_path"))
                    for (file in listOf(video, audio)) {
                        if (!file.isFile || file.length() <= 0L || probe(file) == null) {
                            throw IllegalStateException("A downloaded stream is not a valid media file.")
                        }
                    }
                    if (format == "MP3") {
                        val mp3 = File(jobDir, "${UUID.randomUUID()}.mp3")
                        encodeMp3(audio, mp3, mp3Bitrate) { fraction ->
                            report(postStart + (fraction * (99 - postStart)).toInt(), applicationContext.getString(R.string.status_converting))
                        }
                        mp3
                    } else {
                        val mp4 = File(jobDir, "${UUID.randomUUID()}.mp4")
                        muxToMp4(listOf(video, audio), mp4, duration)
                        video.delete(); audio.delete()
                        mp4
                    }
                }
                "adaptive" -> {
                    val manifestUrl = downloadResult.getString("url")
                    val token = UUID.randomUUID().toString()
                    val partial = File(jobDir, "$token.$outExt.part")
                    val output = File(jobDir, "$token.$outExt")
                    downloadAdaptiveStream(manifestUrl, partial, format, mp3Bitrate, duration)
                    if (!partial.renameTo(output)) {
                        partial.delete()
                        throw IllegalStateException("Could not atomically commit the completed adaptive stream.")
                    }
                    output
                }
                else -> throw IllegalStateException("The media engine returned an unsupported result type.")
            }

            ensureActive()
            if (!finalTemp.isFile || finalTemp.length() <= 0L) {
                return failure(applicationContext.getString(R.string.error_download_output_missing))
            }
            report(99, applicationContext.getString(R.string.status_saving))
            val saved = saveToTree(finalTemp, treeUri, baseName, outExt)
            recordHistory(saved)
            report(100, applicationContext.getString(R.string.status_completed))
            finished = true
            return Result.success(
                workDataOf(KEY_RESULT_MESSAGE to applicationContext.getString(R.string.download_saved, saved.name))
            )
        } catch (error: CancellationException) {
            throw error  // paused/stopped: keep partial data so the next run resumes
        } catch (error: Throwable) {
            if (isResumable(error) && runAttemptCount < MAX_AUTO_RETRIES && !isStopped) {
                report(lastKnownPercent, applicationContext.getString(R.string.status_resuming))
                return Result.retry()  // WorkManager exponential backoff; partial files are kept
            }
            // Permanent failure (403/404/DRM/integrity/...): remove incomplete data, never show it as done.
            finished = true
            throw error
        } finally {
            if (finished) jobDir.deleteRecursively()
        }
    }

    /** Short localized message; the technical detail stays out of the UI. */
    private fun userMessage(error: Throwable): String {
        val text = generateSequence(error) { it.cause }.joinToString(" ") { "${it.javaClass.simpleName} ${it.message}" }
        val low = text.lowercase(Locale.ROOT)
        val res = when {
            "accessdenied" in low || "http 403" in low -> R.string.error_access_denied
            "authenticationrequired" in low || "sign-in" in low -> R.string.error_auth_required
            "drm" in low -> R.string.error_drm
            "ratelimited" in low -> R.string.error_rate_limited
            "live streams" in low -> R.string.error_live
            "not supported by the bundled" in low -> R.string.error_unsupported_site
            "no downloadable" in low || "quality is not available" in low || "no audio stream" in low -> R.string.error_no_format
            "unavailable" in low || "was removed" in low || "http 404" in low -> R.string.error_unavailable
            "failed to produce mp4" in low -> R.string.error_mp4_failed
            "integrity" in low || "incomplete" in low || "corrupt" in low || "valid media file" in low -> R.string.error_integrity
            "unknownhost" in low || "timed out" in low || "unreachable" in low || "network" in low || "connection" in low -> R.string.error_network
            else -> R.string.error_unexpected
        }
        return applicationContext.getString(res)
    }

    /** Network-level or truncated-transfer failures that a later attempt can resume. */
    private fun isResumable(error: Throwable): Boolean {
        val text = generateSequence(error) { it.cause }.joinToString(" ") { "${it.javaClass.simpleName} ${it.message}" }
        if (listOf("AccessDenied", "AuthenticationRequired", "IntegrityFailure", "DRM").any { text.contains(it) }) return false
        return listOf(
            "IncompleteTransfer", "Incomplete transfer", "ended early", "timed out", "Connection reset",
            "RateLimited", "Network is unreachable", "UnknownHost", "SocketTimeout", "ConnectException"
        ).any { text.contains(it, ignoreCase = true) }
    }

    /** Drop abandoned partial data (user cancelled or never retried) after three days. */
    private fun pruneStaleJobDirs() {
        val root = File(applicationContext.filesDir, "media-jobs")
        val cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        root.listFiles()?.forEach { if (it.isDirectory && it.lastModified() < cutoff) it.deleteRecursively() }
    }

    private fun sha256Text(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private suspend fun convertBatch(treeUri: Uri): Result {
        val inputUris = inputData.getStringArray(KEY_INPUT_URIS)?.toList().orEmpty()
        val bitrate = inputData.getString(KEY_QUALITY).orEmpty()
        if (inputUris.isEmpty()) return failure(applicationContext.getString(R.string.error_no_files))
        if (bitrate !in setOf("320k", "256k", "192k", "128k")) {
            return failure(applicationContext.getString(R.string.error_invalid_bitrate))
        }

        val workDir = File(applicationContext.cacheDir, "convert-jobs/${id}").apply { mkdirs() }
        var converted = 0
        val failures = mutableListOf<String>()
        try {
            inputUris.forEachIndexed { index, rawUri ->
                ensureActive()
                val uri = Uri.parse(rawUri)
                var source: File? = null
                var output: File? = null
                try {
                    val displayName = displayName(uri)
                    val prefix = applicationContext.getString(R.string.batch_item, index + 1, inputUris.size)
                    report((index * 100 / inputUris.size), "$prefix · ${applicationContext.getString(R.string.status_preparing)} $displayName")
                    val sourceFile = copyUriToCache(uri, workDir)
                    source = sourceFile
                    val outputFile = File(workDir, "${UUID.randomUUID()}.mp3")
                    output = outputFile
                    encodeMp3(sourceFile, outputFile, bitrate) { fraction ->
                        val overall = ((index + fraction) * 100.0 / inputUris.size).toInt().coerceIn(0, 99)
                        report(overall, "$prefix · ${applicationContext.getString(R.string.status_converting)}")
                    }
                    ensureActive()
                    recordHistory(saveToTree(outputFile, treeUri, safeBaseName(displayName), "mp3"))
                    converted++
                } catch (error: Throwable) {
                    if (isStopped) throw CancellationException("Work cancelled")
                    failures += "${displayName(uri)}: ${error.message ?: applicationContext.getString(R.string.error_unexpected)}"
                } finally {
                    source?.delete()
                    output?.delete()
                }
            }
        } finally {
            workDir.deleteRecursively()
        }

        return when {
            converted > 0 && failures.isEmpty() -> {
                report(100, applicationContext.getString(R.string.status_completed))
                Result.success(workDataOf(KEY_RESULT_MESSAGE to applicationContext.getString(R.string.batch_success, converted)))
            }
            converted > 0 -> {
                val detail = failures.take(4).joinToString("\n")
                Result.success(workDataOf(KEY_RESULT_MESSAGE to applicationContext.getString(R.string.batch_partial, converted, failures.size, detail)))
            }
            else -> failure(failures.take(4).joinToString("\n").ifBlank {
                applicationContext.getString(R.string.error_conversion_failed)
            })
        }
    }

    /** Accept only files produced inside this worker's private temporary directory. */
    private fun requireJobFile(jobDir: File, rawPath: String): File {
        val root = jobDir.canonicalFile
        val file = File(rawPath).canonicalFile
        if (file.parentFile != root) {
            throw IllegalStateException("The downloader returned a file outside its temporary directory")
        }
        return file
    }

    private suspend fun downloadAdaptiveStream(
        manifestUrl: String,
        output: File,
        format: String,
        quality: String,
        durationSeconds: Double
    ) {
        require(manifestUrl.startsWith("https://", ignoreCase = true)) {
            "Only HTTPS adaptive-stream manifests are supported."
        }
        require(format in setOf("MP4", "MP3")) { "Unsupported output format." }
        if (format == "MP3" && quality !in setOf("320k", "256k", "192k", "128k")) {
            throw IllegalArgumentException(applicationContext.getString(R.string.error_invalid_bitrate))
        }

        val args = mutableListOf(
            "-hide_banner", "-nostdin", "-y",
            "-rw_timeout", "20000000",
            "-protocol_whitelist", "https,tls,tcp,crypto,data",
            "-i", manifestUrl,
            "-map", "0:v:0?", "-map", "0:a:0?"
        )
        if (format == "MP4") {
            args += listOf("-c", "copy", "-movflags", "+faststart", "-fs", "21474836480", "-f", "mp4", output.absolutePath)
        } else {
            args += listOf(
                "-vn", "-c:a", "libmp3lame", "-b:a", quality,
                "-id3v2_version", "3", "-fs", "21474836480", "-f", "mp3", output.absolutePath
            )
        }

        report(5, applicationContext.getString(R.string.status_starting))
        val session = suspendCancellableCoroutine<FFmpegSession> { continuation ->
            val ffmpegSession = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { completed -> if (continuation.isActive) continuation.resume(completed) },
                null,
                { statistics ->
                    if (!isStopped) {
                        // Adaptive VOD duration varies by manifest; report active work
                        // without pretending that segment transfer is byte-accurate.
                        val timelineMs = statistics.time.toDouble()
                        val bounded = if (durationSeconds > 0.0) {
                            val fraction = (timelineMs / (durationSeconds * 1000.0)).coerceIn(0.0, 1.0)
                            (8 + fraction * 82.0).toInt().coerceIn(8, 90)
                        } else 15
                        report(if (durationSeconds > 0.0) bounded else -1, applicationContext.getString(R.string.status_downloading))
                    }
                }
            )
            continuation.invokeOnCancellation { ffmpegSession.cancel() }
        }

        ensureActive()
        if (!ReturnCode.isSuccess(session.returnCode) || !output.isFile || output.length() <= 0L) {
            output.delete()
            throw IllegalStateException(
                "Adaptive stream download failed. The manifest may be unavailable, live, encrypted, " +
                    "or use codecs incompatible with the selected MP4/MP3 output."
            )
        }
        if (output.length() >= 20L * 1024L * 1024L * 1024L) {
            output.delete()
            throw IllegalStateException("The media stream exceeds the 20 GiB application limit.")
        }
        val verifiedInfo = runCatching { FFprobeKit.getMediaInformation(output.absolutePath).mediaInformation }
            .getOrNull()
        if (verifiedInfo == null) {
            output.delete()
            throw IllegalStateException("The completed adaptive stream failed media-container validation.")
        }
        report(99, applicationContext.getString(R.string.status_download_finished))
    }

    private fun probe(file: File) = runCatching { FFprobeKit.getMediaInformation(file.absolutePath).mediaInformation }.getOrNull()

    private fun recordHistory(saved: DocumentFile) {
        runCatching {
            HistoryStore.add(
                applicationContext,
                HistoryItem(saved.uri.toString(), saved.name ?: "media", saved.length(), System.currentTimeMillis())
            )
        }
    }

    /**
     * Produces a real MP4 from one (remux) or two (video + audio) inputs.
     * Order: lossless stream copy -> hardware H.264 -> MPEG-4 fallback, so the file always opens.
     */
    private suspend fun muxToMp4(inputs: List<File>, output: File, durationSeconds: Double) {
        val attempts = listOf(
            listOf("-c", "copy", "-strict", "-2"),
            listOf("-c:v", "h264_mediacodec", "-b:v", "8M", "-c:a", "aac", "-b:a", "192k"),
            listOf("-c:v", "mpeg4", "-q:v", "3", "-c:a", "aac", "-b:a", "192k")
        )
        var lastLog = ""
        for ((index, codecArgs) in attempts.withIndex()) {
            ensureActive()
            output.delete()
            val args = mutableListOf("-hide_banner", "-nostdin", "-y")
            for (input in inputs) { args.add("-i"); args.add(input.absolutePath) }
            args.addAll(if (inputs.size == 2) listOf("-map", "0:v:0", "-map", "1:a:0") else listOf("-map", "0:v:0", "-map", "0:a:0?"))
            args.addAll(codecArgs)
            args.addAll(listOf("-movflags", "+faststart", "-f", "mp4", output.absolutePath))
            val label = applicationContext.getString(if (inputs.size == 2) R.string.status_merging else R.string.status_converting)
            val session = runFfmpeg(args, durationSeconds, label)
            ensureActive()
            if (ReturnCode.isSuccess(session.returnCode) && output.isFile && output.length() > 0L && probe(output) != null) return
            lastLog = session.output.orEmpty().lineSequence().toList().takeLast(3).joinToString(" ").take(300)
            if (index == 0 && output.isFile) output.delete()
        }
        output.delete()
        throw IllegalStateException("Failed to produce MP4. $lastLog".trim())
    }

    private suspend fun runFfmpeg(args: List<String>, durationSeconds: Double, label: String): FFmpegSession =
        suspendCancellableCoroutine<FFmpegSession> { continuation ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { completed -> if (continuation.isActive) continuation.resume(completed) },
                null,
                { statistics ->
                    if (!isStopped && durationSeconds > 0.0) {
                        val fraction = (statistics.time.toDouble() / (durationSeconds * 1000.0)).coerceIn(0.0, 1.0)
                        report(postStart + (fraction * (99 - postStart)).toInt(), label)
                    }
                }
            )
            continuation.invokeOnCancellation { session.cancel() }
        }

    private suspend fun encodeMp3(input: File, output: File, bitrate: String, onProgress: (Double) -> Unit) {
        val safeBitrate = bitrate.takeIf { it in setOf("320k", "256k", "192k", "128k") }
            ?: throw IllegalArgumentException(applicationContext.getString(R.string.error_invalid_bitrate))
        val durationSeconds = runCatching {
            FFprobeKit.getMediaInformation(input.absolutePath)
                .mediaInformation?.duration?.toDoubleOrNull() ?: 0.0
        }.getOrDefault(0.0)

        val args = arrayOf(
            "-hide_banner", "-nostdin", "-y", "-i", input.absolutePath,
            "-vn", "-map_metadata", "0", "-c:a", "libmp3lame",
            "-b:a", safeBitrate, "-id3v2_version", "3", output.absolutePath
        )

        val session = suspendCancellableCoroutine<FFmpegSession> { continuation ->
            val ffmpegSession = FFmpegKit.executeWithArgumentsAsync(
                args,
                { completed ->
                    if (continuation.isActive) continuation.resume(completed)
                },
                null,
                { statistics ->
                    if (!isStopped && durationSeconds > 0.0) {
                        val processedMs = statistics.time.toDouble()
                        onProgress((processedMs / (durationSeconds * 1000.0)).coerceIn(0.0, 1.0))
                    }
                }
            )
            continuation.invokeOnCancellation { ffmpegSession.cancel() }
        }

        if (isStopped) throw CancellationException("Work cancelled")
        if (!ReturnCode.isSuccess(session.returnCode) || !output.isFile || output.length() == 0L) {
            output.delete()
            val logTail = session.output.orEmpty().lineSequence().toList().takeLast(5).joinToString(" ").take(450)
            throw IllegalStateException(logTail.ifBlank { applicationContext.getString(R.string.error_conversion_failed) })
        }
    }

    private fun copyUriToCache(uri: Uri, directory: File): File {
        val name = displayName(uri)
        val extension = name.substringAfterLast('.', "bin")
            .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(8).ifBlank { "bin" }
        val target = File(directory, "input-${UUID.randomUUID()}.$extension")
        val partial = File(directory, "${target.name}.part")
        try {
            val input = applicationContext.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException(applicationContext.getString(R.string.error_cannot_read_file, name))
            input.use { source -> partial.outputStream().buffered().use { sink ->
                source.copyTo(sink)
                sink.flush()
            } }
            if (partial.length() <= 0L) {
                throw IllegalStateException(applicationContext.getString(R.string.error_empty_file, name))
            }
            if (!partial.renameTo(target)) {
                throw IllegalStateException("Could not atomically stage the selected source file.")
            }
            return target
        } catch (error: Throwable) {
            partial.delete()
            target.delete()
            throw error
        }
    }

    private fun saveToTree(source: File, treeUri: Uri, baseName: String, ext: String): DocumentFile {
        val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
            ?: throw IllegalStateException(applicationContext.getString(R.string.error_output_permission))
        if (!root.canWrite()) throw IllegalStateException(applicationContext.getString(R.string.error_output_permission))

        // The extension is appended AFTER shortening the title, so it can never be cut off.
        val finalName = uniqueDocumentName(root, "${safeFileName(baseName).take(MAX_BASE_NAME)}.$ext")
        val mime = if (ext == "mp3") "audio/mpeg" else "video/mp4"
        val stagingName = ".pending-${UUID.randomUUID()}.$ext"
        val staging = root.createFile(mime, stagingName)
            ?: throw IllegalStateException(applicationContext.getString(R.string.error_create_output, stagingName))
        try {
            val output = applicationContext.contentResolver.openOutputStream(staging.uri, "w")
                ?: throw IllegalStateException(applicationContext.getString(R.string.error_write_output, stagingName))
            val copiedBytes = source.inputStream().use { src ->
                output.use { dst ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        val read = src.read(buffer)
                        if (read < 0) break
                        dst.write(buffer, 0, read)
                        total += read
                    }
                    dst.flush()
                    total
                }
            }
            if (copiedBytes != source.length()) {
                throw IllegalStateException("The saved file size does not match the source file.")
            }
            if (!staging.renameTo(finalName)) {
                throw IllegalStateException(
                    "The selected folder does not support safe atomic rename. Choose a local device folder and retry."
                )
            }
            return root.findFile(finalName)
                ?: throw IllegalStateException("The saved file could not be located after commit.")
        } catch (error: Throwable) {
            staging.delete()
            throw error
        }
    }

    private fun uniqueDocumentName(root: DocumentFile, requested: String): String {
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        var candidate = requested
        var index = 1
        while (root.findFile(candidate) != null) {
            candidate = "$base ($index)$ext"
            index++
        }
        return candidate
    }

    private fun displayName(uri: Uri): String {
        applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video"
    }

    private fun safeBaseName(name: String): String = safeFileName(name)

    private fun safeFileName(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim().trim('.').trim().ifBlank { "media" }

    private fun ensureActive() {
        if (isStopped) throw CancellationException("Work cancelled")
    }

    /** percent < 0 = indeterminate. Sizes/speed/eta of -1 mean "unknown". */
    @Synchronized
    private fun report(percent: Int, status: String, done: Long = -1, total: Long = -1, speed: Long = -1, eta: Int = -1) {
        val now = SystemClock.elapsedRealtime()
        val bounded = if (percent < 0) -1 else percent.coerceIn(0, 100)
        val changed = status != lastStatus || bounded == 100
        if (!changed && now - lastProgressAt < 300L) return
        lastProgressAt = now
        lastStatus = status
        if (bounded >= 0) lastKnownPercent = bounded
        runBlocking(Dispatchers.IO) {
            setProgress(workDataOf(
                KEY_PROGRESS to bounded, KEY_STATUS to status,
                KEY_DONE to done, KEY_TOTAL to total, KEY_SPEED to speed, KEY_ETA to eta
            ))
        }
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(
                NOTIFICATION_ID,
                buildNotification(bounded, status, done, total)
            )
        }
    }

    private fun foregroundInfo(progress: Int, status: String): ForegroundInfo {
        val notification = buildNotification(progress, status)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(progress: Int, status: String, done: Long = -1, total: Long = -1) =
        NotificationCompat.Builder(applicationContext, MediaDownloaderApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(
                if (progress >= 0) "$status · $progress%" + (if (total > 0) " · ${sizeText(done)} / ${sizeText(total)}" else "")
                else status
            )
            .setOnlyAlertOnce(true)
            .setOngoing(progress < 100)
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    private fun sizeText(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do { value /= 1024.0; unit++ } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }

    private fun failure(message: String): Result = Result.failure(workDataOf(KEY_RESULT_MESSAGE to message))

    companion object {
        const val UNIQUE_WORK_NAME = "universal-media-task"
        const val KEY_TASK = "task"
        const val TASK_DOWNLOAD = "download"
        const val TASK_CONVERT = "convert"
        const val KEY_URL = "url"
        const val KEY_FORMAT = "format"
        const val KEY_QUALITY = "quality"
        const val KEY_OUTPUT_TREE = "output_tree"
        const val KEY_INPUT_URIS = "input_uris"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_DONE = "done_bytes"
        const val KEY_TOTAL = "total_bytes"
        const val KEY_SPEED = "speed_bps"
        const val KEY_ETA = "eta_seconds"
        const val KEY_RESULT_MESSAGE = "result_message"
        private const val NOTIFICATION_ID = 6201
        private const val MAX_AUTO_RETRIES = 4
        private const val MAX_BASE_NAME = 120
        private const val COPY_BUFFER = 1 shl 20
        private val BITRATES = setOf("320k", "256k", "192k", "128k")
    }
}

