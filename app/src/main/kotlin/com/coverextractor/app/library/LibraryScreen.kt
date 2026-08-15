package com.coverextractor.app.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch

private val ALPHABET = listOf("#") + ('A'..'Z').map { it.toString() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    contentPadding: PaddingValues,
    onOpenPreview: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.addFolder(uri) }

    var searchActive by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        topBar = {
            if (viewModel.isSelectionMode) {
                TopAppBar(
                    title = { Text("${viewModel.selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                        }
                    }
                )
            } else if (searchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = viewModel.searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholder = { Text("Search covers") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { searchActive = false; viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Library") },
                    actions = {
                        if (viewModel.entries.isNotEmpty()) {
                            IconButton(onClick = { searchActive = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = { viewModel.enterSelectionMode() }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = "Select")
                            }
                        }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Rescan folders") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                enabled = viewModel.folderUris.isNotEmpty(),
                                onClick = { menuExpanded = false; viewModel.startScan() }
                            )
                            DropdownMenuItem(
                                text = { Text("Manage folders") },
                                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                onClick = { menuExpanded = false; onOpenSettings() }
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (viewModel.isSelectionMode) {
                LibrarySelectionBar(
                    count = viewModel.selectedIds.size,
                    onSaveAll = {
                        scope.launch {
                            val saved = viewModel.saveSelectedToGallery()
                            snackbarHostState.showSnackbar("Saved $saved cover(s) to Pictures/coverExtractor")
                        }
                    },
                    contentPadding = contentPadding
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                viewModel.folderUris.isEmpty() -> ChooseFolderState { folderPickerLauncher.launch(null) }
                viewModel.scanProgress != null -> ScanningState(viewModel.scanProgress!!)
                viewModel.entries.isEmpty() -> EmptyLibraryState(onRescan = { viewModel.startScan() })
                else -> LibraryGrid(
                    viewModel = viewModel,
                    onTap = { entry ->
                        if (viewModel.isSelectionMode) viewModel.toggleSelection(entry.id) else onOpenPreview(entry.id)
                    },
                    onLongPress = { entry -> viewModel.toggleSelection(entry.id) },
                    bottomInset = contentPadding.calculateBottomPadding()
                )
            }
        }
    }
}

@Composable
private fun ChooseFolderState(onChoose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "No folders added",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )
        Text(
            "Choose a folder to scan for audio files — subfolders are included automatically. " +
                "You can add more folders later from Manage folders.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onChoose, modifier = Modifier.padding(top = 24.dp)) {
            Text("Choose folder")
        }
    }
}

@Composable
private fun ScanningState(progress: ScanProgress) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Text(
            "Scanning… ${progress.filesScanned} file(s), ${progress.coversFound} cover(s) found",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
private fun EmptyLibraryState(onRescan: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "No embedded covers found in this folder",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )
        Button(onClick = onRescan) { Text("Rescan") }
    }
}

@Composable
private fun LibraryGrid(
    viewModel: LibraryViewModel,
    onTap: (LibraryEntry) -> Unit,
    onLongPress: (LibraryEntry) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp
) {
    val entries = viewModel.filteredEntries
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // First grid index (in the 3-column flat item list) whose title starts with each letter.
    val letterIndex = remember(entries) {
        val map = HashMap<String, Int>()
        entries.forEachIndexed { index, entry ->
            val first = entry.title.firstOrNull()?.uppercaseChar()
            val key = if (first != null && first in 'A'..'Z') first.toString() else "#"
            if (key !in map) map[key] = index
        }
        map
    }

    Row(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, bottomInset + 8.dp)
        ) {
            items(entries, key = { it.id }) { entry ->
                LibraryTile(
                    entry = entry,
                    thumbnail = viewModel.thumbnailFor(entry),
                    selected = entry.id in viewModel.selectedIds,
                    selectionMode = viewModel.isSelectionMode,
                    onClick = { onTap(entry) },
                    onLongClick = { onLongPress(entry) }
                )
            }
        }

        AlphabetScrubber(
            availableLetters = letterIndex.keys,
            onLetterSelected = { letter ->
                letterIndex[letter]?.let { index -> scope.launch { gridState.scrollToItem(index) } }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTile(
    entry: LibraryEntry,
    thumbnail: android.graphics.Bitmap?,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(6.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null)
                }
            }
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                )
            }
        }
        Text(
            entry.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            entry.subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AlphabetScrubber(availableLetters: Set<String>, onLetterSelected: (String) -> Unit) {
    var heightPx by remember { mutableStateOf(1f) }

    fun selectForY(y: Float) {
        val fraction = (y / heightPx).coerceIn(0f, 0.999f)
        val index = (fraction * ALPHABET.size).toInt().coerceIn(0, ALPHABET.size - 1)
        val letter = ALPHABET[index]
        if (letter in availableLetters) onLetterSelected(letter)
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(end = 4.dp)
            .onGloballyPositioned { heightPx = it.size.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(availableLetters) {
                detectTapGestures { offset -> selectForY(offset.y) }
            }
            .pointerInput(availableLetters) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> selectForY(offset.y) },
                    onVerticalDrag = { change, _ -> selectForY(change.position.y) }
                )
            },
        verticalArrangement = Arrangement.Center
    ) {
        ALPHABET.forEach { letter ->
            Text(
                letter,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (letter in availableLetters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun LibrarySelectionBar(count: Int, onSaveAll: () -> Unit, contentPadding: PaddingValues) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp, 12.dp, 16.dp, 12.dp + contentPadding.calculateBottomPadding()),
        horizontalArrangement = Arrangement.Center
    ) {
        Button(onClick = onSaveAll, enabled = count > 0) {
            Text("Save $count cover${if (count == 1) "" else "s"}")
        }
    }
}
