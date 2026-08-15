package com.coverextractor.app.library

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coverextractor.app.ExtractedCover
import com.coverextractor.app.GallerySaver
import com.coverextractor.app.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LibraryRepository(application)
    private val thumbnailCache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 200
    }

    var folderUri by mutableStateOf(repository.getFolderUri())
        private set
    var entries by mutableStateOf<List<LibraryEntry>>(emptyList())
        private set
    var scanProgress by mutableStateOf<ScanProgress?>(null)
        private set
    var searchQuery by mutableStateOf("")
        private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    private var selectionModeActive by mutableStateOf(false)
    val isSelectionMode: Boolean get() = selectionModeActive || selectedIds.isNotEmpty()

    init {
        repository.loadCachedEntries()?.let { entries = it }
    }

    val filteredEntries: List<LibraryEntry>
        get() = if (searchQuery.isBlank()) {
            entries
        } else {
            entries.filter {
                it.title.contains(searchQuery, ignoreCase = true) || it.subtitle.contains(searchQuery, ignoreCase = true)
            }
        }

    fun chooseFolder(uri: Uri) {
        repository.setFolderUri(uri)
        folderUri = uri
        entries = emptyList()
        thumbnailCache.clear()
        startScan()
    }

    fun startScan() {
        val tree = folderUri ?: return
        viewModelScope.launch {
            scanProgress = ScanProgress(0, 0, done = false)
            val context = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                val files = LibraryScanner.listAudioFiles(context, tree)
                LibraryScanner.scanAndGroup(context, repository, files) { progress ->
                    viewModelScope.launch { scanProgress = progress }
                }
            }
            withContext(Dispatchers.IO) { repository.saveEntries(result) }
            entries = result
            scanProgress = null
        }
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    fun enterSelectionMode() {
        selectionModeActive = true
    }

    fun toggleSelection(id: String) {
        selectionModeActive = true
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun clearSelection() {
        selectionModeActive = false
        selectedIds = emptySet()
    }

    fun thumbnailFor(entry: LibraryEntry): Bitmap? {
        thumbnailCache[entry.id]?.let { return it }
        val bytes = repository.readCoverBytes(entry) ?: return null
        val bitmap = ImageUtils.decodeSampledThumbnail(bytes, 300) ?: return null
        thumbnailCache[entry.id] = bitmap
        return bitmap
    }

    fun fullBytesFor(entry: LibraryEntry): ByteArray? = repository.readCoverBytes(entry)

    suspend fun saveEntryToGallery(entry: LibraryEntry): Uri? = withContext(Dispatchers.IO) {
        val bytes = repository.readCoverBytes(entry) ?: return@withContext null
        GallerySaver.save(getApplication(), sanitizeName(entry.title), ExtractedCover(bytes, entry.mimeType, entry.extension))
    }

    /** Saves every currently selected entry; returns how many succeeded. */
    suspend fun saveSelectedToGallery(): Int = withContext(Dispatchers.IO) {
        val targets = entries.filter { it.id in selectedIds }
        var saved = 0
        targets.forEach { entry ->
            val bytes = repository.readCoverBytes(entry)
            if (bytes != null) {
                val uri = GallerySaver.save(
                    getApplication(),
                    sanitizeName(entry.title),
                    ExtractedCover(bytes, entry.mimeType, entry.extension)
                )
                if (uri != null) saved++
            }
        }
        withContext(Dispatchers.Main) { clearSelection() }
        saved
    }

    private fun sanitizeName(title: String): String {
        val sanitized = title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim()
        return sanitized.ifBlank { "cover" }.take(80)
    }
}
