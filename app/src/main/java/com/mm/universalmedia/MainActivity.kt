package com.mm.universalmedia

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.documentfile.provider.DocumentFile
import androidx.compose.runtime.livedata.observeAsState
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mm.universalmedia.worker.MediaWorker
import android.content.ClipData
import android.content.ClipboardManager
import java.text.DateFormat
import java.util.Date
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UniversalMediaApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UniversalMediaApp() {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val prefs = remember { context.getSharedPreferences("universal_media_prefs", 0) }
    var themeMode by rememberSaveable { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    var language by rememberSaveable { mutableStateOf(prefs.getString("language", "az") ?: "az") }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var outputTreeText by rememberSaveable { mutableStateOf(prefs.getString("output_tree", null)) }
    val outputTree = outputTreeText?.let(Uri::parse)

    LaunchedEffect(language) {
        val locales = if (language == "system") LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(language)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onSuccess {
                outputTreeText = uri.toString()
                prefs.edit().putString("output_tree", outputTreeText).apply()
            }.onFailure {
                Toast.makeText(context, R.string.error_output_permission, Toast.LENGTH_LONG).show()
            }
        }
    }

    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData(MediaWorker.UNIQUE_WORK_NAME)
        .observeAsState(initial = emptyList())
    // Only an ACTIVE job is shown; nothing from a finished job stays on screen (history keeps the files).
    val workInfo = workInfos.firstOrNull {
        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
    }
    val finishedInfo = workInfos.firstOrNull { it.state.isFinished }
    val isBusy = workInfo != null
    var watchedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { workManager.pruneWork() }
    LaunchedEffect(workInfo?.id) { workInfo?.let { watchedId = it.id.toString() } }
    LaunchedEffect(finishedInfo?.id, finishedInfo?.state) {
        val done = finishedInfo ?: return@LaunchedEffect
        if (done.id.toString() != watchedId) return@LaunchedEffect
        watchedId = null
        val message = when (done.state) {
            WorkInfo.State.SUCCEEDED -> done.outputData.getString(MediaWorker.KEY_RESULT_MESSAGE) ?: context.getString(R.string.status_completed)
            WorkInfo.State.FAILED -> done.outputData.getString(MediaWorker.KEY_RESULT_MESSAGE) ?: context.getString(R.string.status_failed)
            else -> context.getString(R.string.status_cancelled)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        workManager.pruneWork()
    }

    val isDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val colors = if (isDark) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.powered_by), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_download)) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Outlined.AudioFile, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_convert)) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_files)) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_settings)) }
                        )
                    }
                }
            ) { insets ->
                when (selectedTab) {
                    0 -> DownloadScreen(
                        modifier = Modifier.padding(insets),
                        outputTree = outputTree,
                        workInfo = workInfo,
                        busy = isBusy,
                        onChooseFolder = { folderPicker.launch(null) },
                        onCancel = { workManager.cancelUniqueWork(MediaWorker.UNIQUE_WORK_NAME) },
                        onStart = { url, format, quality ->
                            val parsedUrl = Uri.parse(url.trim())
                            if (parsedUrl.scheme?.lowercase(Locale.ROOT) != "https" || parsedUrl.host.isNullOrBlank()) {
                                Toast.makeText(context, R.string.error_invalid_url, Toast.LENGTH_LONG).show()
                            } else if (outputTree == null) {
                                Toast.makeText(context, R.string.error_choose_folder, Toast.LENGTH_LONG).show()
                            } else {
                                val request = OneTimeWorkRequestBuilder<MediaWorker>()
                                    .setInputData(workDataOf(
                                        MediaWorker.KEY_TASK to MediaWorker.TASK_DOWNLOAD,
                                        MediaWorker.KEY_URL to url.trim(),
                                        MediaWorker.KEY_FORMAT to format,
                                        MediaWorker.KEY_QUALITY to quality,
                                        MediaWorker.KEY_OUTPUT_TREE to outputTree.toString()
                                    ))
                                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                                workManager.enqueueUniqueWork(MediaWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
                            }
                        }
                    )
                    1 -> ConverterScreen(
                        modifier = Modifier.padding(insets),
                        outputTree = outputTree,
                        workInfo = workInfo,
                        busy = isBusy,
                        onChooseFolder = { folderPicker.launch(null) },
                        onCancel = { workManager.cancelUniqueWork(MediaWorker.UNIQUE_WORK_NAME) },
                        onStart = { uris, bitrate ->
                            if (outputTree == null) {
                                Toast.makeText(context, R.string.error_choose_folder, Toast.LENGTH_LONG).show()
                            } else if (uris.isEmpty()) {
                                Toast.makeText(context, R.string.error_no_files, Toast.LENGTH_LONG).show()
                            } else if (uris.size > 25 || uris.sumOf { it.toString().length } > 7500) {
                                Toast.makeText(context, R.string.file_limit, Toast.LENGTH_LONG).show()
                            } else {
                                val request = OneTimeWorkRequestBuilder<MediaWorker>()
                                    .setInputData(workDataOf(
                                        MediaWorker.KEY_TASK to MediaWorker.TASK_CONVERT,
                                        MediaWorker.KEY_QUALITY to bitrate,
                                        MediaWorker.KEY_OUTPUT_TREE to outputTree.toString(),
                                        MediaWorker.KEY_INPUT_URIS to uris.map(Uri::toString).toTypedArray()
                                    ))
                                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                                    .build()
                                workManager.enqueueUniqueWork(MediaWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
                            }
                        }
                    )
                    2 -> HistoryScreen(
                        modifier = Modifier.padding(insets),
                        refreshKey = isBusy
                    )
                    else -> SettingsScreen(
                        modifier = Modifier.padding(insets),
                        themeMode = themeMode,
                        language = language,
                        onThemeChanged = { mode ->
                            themeMode = mode
                            prefs.edit().putString("theme", mode).apply()
                        },
                        onLanguageChanged = { code ->
                            language = code
                            prefs.edit().putString("language", code).apply()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadScreen(
    modifier: Modifier,
    outputTree: Uri?,
    workInfo: WorkInfo?,
    busy: Boolean,
    onChooseFolder: () -> Unit,
    onCancel: () -> Unit,
    onStart: (String, String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf("MP4") }
    var quality by rememberSaveable { mutableStateOf("best") }
    var analysis by remember { mutableStateOf<MediaAnalysis?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var analysisError by remember { mutableStateOf<String?>(null) }

    val current = analysis
    // Only formats that the extractor really reported are offered.
    val qualities = if (format == "MP4") current?.heights.orEmpty().map { "${it}p" }.ifEmpty { listOf("best") }
    else listOf("320k", "256k", "192k", "128k")
    LaunchedEffect(format, current) {
        if (current != null && !current.hasVideo && format == "MP4") format = "MP3"
        if (quality !in qualities) quality = qualities.first()
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(stringResource(R.string.download_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.url_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; analysis = null; analysisError = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.url_placeholder)) },
                    singleLine = true,
                    enabled = !busy
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (text.isNotBlank()) { url = text.trim(); analysis = null; analysisError = null }
                        },
                        enabled = !busy && !analyzing
                    ) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.paste))
                    }
                    Button(
                        onClick = {
                            val parsed = Uri.parse(url.trim())
                            if (parsed.scheme?.lowercase(Locale.ROOT) != "https" || parsed.host.isNullOrBlank()) {
                                analysisError = context.getString(R.string.error_invalid_url)
                            } else scope.launch {
                                analyzing = true; analysisError = null; analysis = null
                                try {
                                    analysis = Analyzer.analyze(context, url)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Throwable) {
                                    val message = error.message.orEmpty()
                                    analysisError = message.substringAfter(": ", message).take(300)
                                        .ifBlank { context.getString(R.string.error_unexpected) }
                                } finally {
                                    analyzing = false
                                }
                            }
                        },
                        enabled = !busy && !analyzing && url.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (analyzing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (analyzing) R.string.analyzing else R.string.analyze))
                    }
                }
                analysisError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                if (current != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        current.thumbnail?.let {
                            Image(
                                bitmap = it.asImageBitmap(), contentDescription = null,
                                modifier = Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(current.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(Analyzer.formatDuration(current.durationSeconds), current.extractor)
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(stringResource(R.string.format_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = format == "MP4", onClick = { format = "MP4" }, label = { Text(stringResource(R.string.format_video)) }, enabled = !busy && current.hasVideo)
                        FilterChip(selected = format == "MP3", onClick = { format = "MP3" }, label = { Text(stringResource(R.string.format_audio)) }, enabled = !busy && current.hasAudio)
                    }
                    if (qualities != listOf("best")) Text(stringResource(R.string.quality_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (qualities != listOf("best")) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        qualities.forEach { q ->
                            FilterChip(
                                selected = quality == q, onClick = { quality = q }, enabled = !busy,
                                label = { Text(when (q) {
                                    "1440p" -> "2K (1440p)"
                                    "2160p" -> "4K (2160p)"
                                    "4320p" -> "8K (4320p)"
                                    else -> q
                                }) }
                            )
                        }
                    }
                }
                OutputFolderRow(outputTree = outputTree, onChoose = onChooseFolder, enabled = !busy)
                Button(
                    onClick = { onStart(url, format, quality) },
                    enabled = !busy && current != null && outputTree != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start_download))
                }
            }
        }
        JobStatusCard(workInfo = workInfo, busy = busy, onCancel = onCancel)
    }
}

@Composable
private fun ConverterScreen(
    modifier: Modifier,
    outputTree: Uri?,
    workInfo: WorkInfo?,
    busy: Boolean,
    onChooseFolder: () -> Unit,
    onCancel: () -> Unit,
    onStart: (List<Uri>, String) -> Unit
) {
    val context = LocalContext.current
    val selected = remember { mutableStateListOf<Uri>() }
    var bitrate by rememberSaveable { mutableStateOf("320k") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var limitReached = false
        uris.forEach { uri ->
            if (uri !in selected) {
                if (selected.size >= 25) {
                    limitReached = true
                } else {
                    val persisted = runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }.isSuccess
                    if (persisted) selected.add(uri)
                    else Toast.makeText(context, R.string.error_output_permission, Toast.LENGTH_LONG).show()
                }
            }
        }
        if (limitReached) Toast.makeText(context, R.string.file_limit, Toast.LENGTH_LONG).show()
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(stringResource(R.string.converter_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.bitrate_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                QualityDropdown(bitrate, listOf("320k", "256k", "192k", "128k"), !busy) { bitrate = it }
                OutputFolderRow(outputTree, onChooseFolder, !busy)
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("video/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.VideoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.select_videos))
                }
                if (selected.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.selected_count, selected.size), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { selected.clear() }, enabled = !busy) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.clear))
                        }
                    }
                    selected.forEach { uri ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(queryDisplayName(context, uri), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { selected.remove(uri) }, enabled = !busy) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.remove))
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.no_files_selected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = { onStart(selected.toList(), bitrate) },
                    enabled = !busy && selected.isNotEmpty() && outputTree != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.AudioFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start_conversion))
                }
            }
        }
        JobStatusCard(workInfo = workInfo, busy = busy, onCancel = onCancel)
    }
}

private data class HistoryEntry(val item: HistoryItem, val document: DocumentFile)

@Composable
private fun HistoryScreen(modifier: Modifier, refreshKey: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<HistoryEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LaunchedEffect(refreshKey) {
        loading = true
        entries = withContext(Dispatchers.IO) {
            val loaded = HistoryStore.load(context).mapNotNull { item ->
                runCatching {
                    DocumentFile.fromSingleUri(context, Uri.parse(item.uri))?.takeIf { it.exists() }
                        ?.let { HistoryEntry(item, it) }
                }.getOrNull()
            }
            // Files removed outside the app silently drop out of the history.
            HistoryStore.retain(context, loaded.map { it.item.uri }.toSet())
            loaded
        }
        loading = false
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch(Dispatchers.IO) { HistoryStore.clear(context) }
                    entries = emptyList()
                    Toast.makeText(context, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.history_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_file_title)) },
            text = { Text(stringResource(R.string.delete_file_confirm, target.item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            runCatching { target.document.delete() }.getOrDefault(false).also {
                                if (it) HistoryStore.remove(context, target.item.uri)
                            }
                        }
                        if (deleted) {
                            entries = entries.filterNot { it.item.uri == target.item.uri }
                            Toast.makeText(context, R.string.file_deleted, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, R.string.error_delete_file, Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(stringResource(R.string.files_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    Column(
        modifier = modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.files_title), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.history_clear))
                }
            }
        }
        when {
            loading && entries.isEmpty() -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            entries.isEmpty() -> Text(stringResource(R.string.files_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.item.uri }) { entry ->
                    val file = entry.document
                    val mime = file.type ?: if (entry.item.name.endsWith(".mp3", true)) "audio/mpeg" else "video/mp4"
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (mime.startsWith("audio")) Icons.Outlined.AudioFile else Icons.Outlined.VideoLibrary,
                                contentDescription = null, tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(
                                    formatFileSize(file.length()) + " · " + dateFormat.format(Date(entry.item.time)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(file.uri, mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(Intent.createChooser(intent, null)) }
                                    .onFailure { Toast.makeText(context, R.string.error_open_file, Toast.LENGTH_LONG).show() }
                            }) { Text(stringResource(R.string.files_open)) }
                            IconButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = mime
                                    putExtra(Intent.EXTRA_STREAM, file.uri)
                                    clipData = ClipData.newRawUri(null, file.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(Intent.createChooser(send, null)) }
                                    .onFailure { Toast.makeText(context, R.string.error_open_file, Toast.LENGTH_LONG).show() }
                            }) {
                                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share))
                            }
                            IconButton(onClick = { deleteTarget = entry }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.files_delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024.0 && unit < units.lastIndex)
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    themeMode: String,
    language: String,
    onThemeChanged: (String) -> Unit,
    onLanguageChanged: (String) -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.theme_label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SettingRadio(stringResource(R.string.theme_system), themeMode == "system") { onThemeChanged("system") }
                SettingRadio(stringResource(R.string.theme_light), themeMode == "light") { onThemeChanged("light") }
                SettingRadio(stringResource(R.string.theme_dark), themeMode == "dark") { onThemeChanged("dark") }
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.language_label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SettingRadio(stringResource(R.string.language_system), language == "system") { onLanguageChanged("system") }
                SettingRadio(stringResource(R.string.language_az), language == "az") { onLanguageChanged("az") }
                SettingRadio(stringResource(R.string.language_en), language == "en") { onLanguageChanged("en") }
                SettingRadio(stringResource(R.string.language_ru), language == "ru") { onLanguageChanged("ru") }
                SettingRadio(stringResource(R.string.language_tr), language == "tr") { onLanguageChanged("tr") }
            }
        }
        Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualityDropdown(quality: String, choices: List<String>, enabled: Boolean, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = quality,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = { onSelected(item); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun OutputFolderRow(outputTree: Uri?, onChoose: () -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val folderName = remember(outputTree) {
        outputTree?.let { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it)?.name }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.output_folder), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(folderName ?: stringResource(R.string.folder_not_selected),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onChoose, enabled = enabled) {
            Text(stringResource(if (outputTree == null) R.string.choose else R.string.change))
        }
    }
}

@Composable
private fun JobStatusCard(workInfo: WorkInfo?, busy: Boolean, onCancel: () -> Unit) {
    if (workInfo == null || !busy) return
    val progress = workInfo.progress.getInt(MediaWorker.KEY_PROGRESS, -1)
    val status = workInfo.progress.getString(MediaWorker.KEY_STATUS) ?: stringResource(R.string.status_starting)
    val done = workInfo.progress.getLong(MediaWorker.KEY_DONE, -1L)
    val total = workInfo.progress.getLong(MediaWorker.KEY_TOTAL, -1L)
    val speed = workInfo.progress.getLong(MediaWorker.KEY_SPEED, -1L)
    val eta = workInfo.progress.getInt(MediaWorker.KEY_ETA, -1)

    val sizeLine = listOfNotNull(
        if (progress >= 0) "$progress%" else null,
        when {
            done > 0L && total > 0L -> "${formatFileSize(done)} / ${formatFileSize(total)}"
            done > 0L -> formatFileSize(done)
            else -> null
        }
    ).joinToString("  ·  ")
    val etaText = if (eta >= 0 && speed > 0L) stringResource(R.string.eta_left, formatEta(eta)) else null
    val speedLine = listOfNotNull(
        if (speed > 0L) "${formatFileSize(speed)}/s" else null,
        etaText
    ).joinToString("  ·  ")

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.task_status), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Close, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cancel))
                }
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (progress >= 0) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (sizeLine.isNotEmpty()) Text(sizeLine, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (speedLine.isNotEmpty()) Text(speedLine, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatEta(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
    return uri.lastPathSegment ?: context.getString(R.string.video_fallback_name)
}

private fun lightColorScheme() = androidx.compose.material3.lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF2457D6),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = androidx.compose.ui.graphics.Color(0xFF52637D),
    background = androidx.compose.ui.graphics.Color(0xFFF5F7FB),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE9EEF8)
)

private fun darkColorScheme() = androidx.compose.material3.darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFA9BCFF),
    secondary = androidx.compose.ui.graphics.Color(0xFFBBC7E0),
    background = androidx.compose.ui.graphics.Color(0xFF101522),
    surface = androidx.compose.ui.graphics.Color(0xFF171F2E),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF222D40)
)
