package com.topjohnwu.magisk.ui.install

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.ui.component.tvFocusFrame
import com.topjohnwu.magisk.ui.component.verticalScrollbar
import java.io.File

private data class TvStorageRoot(
    val label: String,
    val directory: File,
)

private data class TvFileEntry(
    val file: File,
    val isDirectory: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvFilePickerDialog(
    onDismiss: () -> Unit,
    onFileSelected: (File) -> Unit,
    systemPickerAvailable: Boolean,
    onOpenSystemPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val readPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permissionRefresh++
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        permissionRefresh++
    }

    val hasStorageAccess = remember(permissionRefresh) {
        hasSharedStorageAccess(context)
    }
    val roots = remember(hasStorageAccess, permissionRefresh) {
        if (hasStorageAccess) buildStorageRoots(context) else emptyList()
    }

    var activeRoot by remember { mutableStateOf<TvStorageRoot?>(null) }
    var currentDirectory by remember { mutableStateOf<File?>(null) }
    var lastRootPath by remember { mutableStateOf<String?>(null) }
    var desiredEntryPath by remember { mutableStateOf<String?>(null) }

    val entries = remember(currentDirectory, permissionRefresh) {
        currentDirectory?.let(::listPatchEntries).orEmpty()
    }
    val rootFocusIndex = remember(roots, lastRootPath) {
        val remembered = roots.indexOfFirst { it.directory.absolutePath == lastRootPath }
        when {
            remembered >= 0 -> remembered
            roots.isNotEmpty() -> 0
            else -> -1
        }
    }
    val entryFocusIndex = remember(entries, desiredEntryPath) {
        val remembered = entries.indexOfFirst { it.file.absolutePath == desiredEntryPath }
        when {
            remembered >= 0 -> remembered
            entries.isNotEmpty() -> 0
            else -> -1
        }
    }

    val permissionFocusRequester = remember { FocusRequester() }
    val listFocusRequester = remember(
        currentDirectory,
        roots.size,
        entries.size,
        rootFocusIndex,
        entryFocusIndex,
    ) { FocusRequester() }

    fun returnToRoots() {
        activeRoot?.directory?.absolutePath?.let { lastRootPath = it }
        desiredEntryPath = null
        currentDirectory = null
        activeRoot = null
    }

    fun navigateUp() {
        val current = currentDirectory
        val root = activeRoot?.directory
        if (current == null) {
            onDismiss()
            return
        }
        if (root == null || samePath(current, root)) {
            returnToRoots()
            return
        }
        val parent = current.parentFile
        if (parent == null || !isInsideRoot(parent, root)) {
            returnToRoots()
        } else {
            desiredEntryPath = current.absolutePath
            currentDirectory = parent
        }
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intents = listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${context.packageName}".toUri()
                },
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
            var launched = false
            for (intent in intents) {
                try {
                    settingsLauncher.launch(intent)
                    launched = true
                    break
                } catch (_: ActivityNotFoundException) {
                    // Try the less specific settings screen below.
                }
            }
            if (!launched) {
                Toast.makeText(
                    context,
                    R.string.tv_file_picker_permission_unavailable,
                    Toast.LENGTH_LONG,
                ).show()
            }
        } else {
            readPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    BackHandler { navigateUp() }

    LaunchedEffect(
        hasStorageAccess,
        currentDirectory,
        roots.size,
        entries.size,
        rootFocusIndex,
        entryFocusIndex,
    ) {
        val requester = if (hasStorageAccess) listFocusRequester else permissionFocusRequester
        runCatching { requester.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.tv_file_picker_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (currentDirectory != null) navigateUp() else onDismiss()
                            },
                            modifier = Modifier.tvFocusFrame(shape = RoundedCornerShape(14.dp)),
                        ) {
                            Icon(
                                imageVector = if (currentDirectory != null) {
                                    Icons.AutoMirrored.Filled.ArrowBack
                                } else {
                                    Icons.Default.Close
                                },
                                contentDescription = stringResource(android.R.string.cancel),
                            )
                        }
                    },
                    actions = {
                        if (systemPickerAvailable && hasStorageAccess) {
                            FilledTonalButton(
                                onClick = onOpenSystemPicker,
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .tvFocusFrame(shape = RoundedCornerShape(16.dp)),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.tv_file_picker_system))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            if (!hasStorageAccess) {
                StoragePermissionContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    focusRequester = permissionFocusRequester,
                    onGrantAccess = ::requestStorageAccess,
                    systemPickerAvailable = systemPickerAvailable,
                    onOpenSystemPicker = onOpenSystemPicker,
                )
                return@Scaffold
            }

            val listState = rememberLazyListState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 28.dp, vertical = 16.dp),
            ) {
                Text(
                    text = currentDirectory?.absolutePath
                        ?: stringResource(R.string.tv_file_picker_supported_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .padding(bottom = 12.dp),
                )

                if (currentDirectory == null) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScrollbar(listState, contentPadding = PaddingValues(vertical = 8.dp)),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(
                            items = roots,
                            key = { _, root -> root.directory.absolutePath },
                        ) { index, root ->
                            PickerRow(
                                title = root.label,
                                subtitle = root.directory.absolutePath,
                                isDirectory = true,
                                modifier = if (index == rootFocusIndex) {
                                    Modifier.focusRequester(listFocusRequester)
                                } else {
                                    Modifier
                                },
                                onClick = {
                                    lastRootPath = root.directory.absolutePath
                                    activeRoot = root
                                    desiredEntryPath = null
                                    currentDirectory = root.directory
                                },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScrollbar(listState, contentPadding = PaddingValues(vertical = 8.dp)),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "..") {
                            PickerRow(
                                title = stringResource(R.string.tv_file_picker_up),
                                subtitle = activeRoot?.label.orEmpty(),
                                isDirectory = true,
                                modifier = if (entryFocusIndex < 0) {
                                    Modifier.focusRequester(listFocusRequester)
                                } else {
                                    Modifier
                                },
                                onClick = ::navigateUp,
                            )
                        }
                        itemsIndexed(
                            items = entries,
                            key = { _, entry -> entry.file.absolutePath },
                        ) { index, entry ->
                            PickerRow(
                                title = entry.file.name,
                                subtitle = if (entry.isDirectory) {
                                    stringResource(R.string.tv_file_picker_folder)
                                } else {
                                    Formatter.formatShortFileSize(context, entry.file.length())
                                },
                                isDirectory = entry.isDirectory,
                                modifier = if (index == entryFocusIndex) {
                                    Modifier.focusRequester(listFocusRequester)
                                } else {
                                    Modifier
                                },
                                onClick = {
                                    if (entry.isDirectory) {
                                        desiredEntryPath = null
                                        currentDirectory = entry.file
                                    } else {
                                        onFileSelected(entry.file)
                                    }
                                },
                            )
                        }
                        if (entries.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = stringResource(R.string.tv_file_picker_no_files),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoragePermissionContent(
    focusRequester: FocusRequester,
    onGrantAccess: () -> Unit,
    systemPickerAvailable: Boolean,
    onOpenSystemPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(horizontal = 48.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.tv_file_picker_storage_access_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.tv_file_picker_storage_access_message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onGrantAccess,
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .tvFocusFrame(shape = RoundedCornerShape(16.dp)),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tv_file_picker_grant_access))
                }
                if (systemPickerAvailable) {
                    FilledTonalButton(
                        onClick = onOpenSystemPicker,
                        modifier = Modifier.tvFocusFrame(shape = RoundedCornerShape(16.dp)),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tv_file_picker_system))
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    isDirectory: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusFrame(shape = RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = if (isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun hasSharedStorageAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@Suppress("DEPRECATION")
private fun buildStorageRoots(context: Context): List<TvStorageRoot> {
    val roots = linkedMapOf<String, TvStorageRoot>()

    fun addRoot(label: String, directory: File?) {
        directory ?: return
        val file = runCatching { directory.canonicalFile }.getOrElse { directory.absoluteFile }
        if (!file.exists() || !file.isDirectory || !file.canRead()) return
        roots.putIfAbsent(file.absolutePath, TvStorageRoot(label, file))
    }

    val primary = Environment.getExternalStorageDirectory()
    addRoot(context.getString(R.string.tv_file_picker_downloads), File(primary, Environment.DIRECTORY_DOWNLOADS))
    addRoot(context.getString(R.string.tv_file_picker_internal_storage), primary)

    context.getExternalFilesDirs(null).forEach { externalDir ->
        externalDir ?: return@forEach
        val marker = "/Android/data/"
        val absolute = externalDir.absolutePath
        val markerIndex = absolute.indexOf(marker)
        if (markerIndex <= 0) return@forEach
        val storageRoot = File(absolute.substring(0, markerIndex))
        if (!samePath(storageRoot, primary)) {
            addRoot(context.getString(R.string.tv_file_picker_removable_storage), storageRoot)
        }
    }

    return roots.values.toList()
}

private fun listPatchEntries(directory: File): List<TvFileEntry> {
    val files = runCatching { directory.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
    return files.asSequence()
        .filterNot { it.name.startsWith('.') }
        .filter { it.canRead() }
        .filter { it.isDirectory || (it.isFile && isPatchCandidate(it.name)) }
        .map { TvFileEntry(it, it.isDirectory) }
        .sortedWith(
            compareByDescending<TvFileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
        )
        .toList()
}

private fun isPatchCandidate(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".img") ||
        lower.endsWith(".bin") ||
        lower.endsWith(".tar") ||
        lower.endsWith(".tar.md5") ||
        lower.endsWith(".zip")
}

private fun samePath(first: File, second: File): Boolean {
    return runCatching { first.canonicalPath == second.canonicalPath }
        .getOrElse { first.absolutePath == second.absolutePath }
}

private fun isInsideRoot(candidate: File, root: File): Boolean {
    val candidatePath = runCatching { candidate.canonicalPath }.getOrElse { candidate.absolutePath }
    val rootPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
    return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
}
