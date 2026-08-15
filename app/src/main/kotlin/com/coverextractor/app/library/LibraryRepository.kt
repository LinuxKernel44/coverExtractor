package com.coverextractor.app.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the chosen SAF folders and the last scan's results, so reopening the Library tab is
 * instant instead of re-scanning every tree again. The actual cover bytes live as individual
 * files under [coversDir], keyed by their content hash ([LibraryEntry.id]) - the JSON index only
 * stores small metadata, never the image bytes themselves.
 */
class LibraryRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
    private val indexFile = File(context.filesDir, "library_index.json")
    private val coversDir = File(context.filesDir, "library_covers").apply { mkdirs() }

    fun getFolderUris(): List<Uri> {
        val raw = prefs.getString(KEY_FOLDER_URIS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { Uri.parse(array.getString(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Adds a folder (if not already tracked) and takes a persistent read grant on it. */
    fun addFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val current = getFolderUris().map { it.toString() }
        if (uri.toString() in current) return
        saveFolderUris(current + uri.toString())
        clearCache()
    }

    fun removeFolder(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Permission may already be gone (e.g. user revoked it from Settings) - fine to ignore.
        }
        val remaining = getFolderUris().map { it.toString() }.filter { it != uri.toString() }
        saveFolderUris(remaining)
        clearCache()
    }

    private fun saveFolderUris(uris: List<String>) {
        val array = JSONArray()
        uris.forEach { array.put(it) }
        prefs.edit().putString(KEY_FOLDER_URIS, array.toString()).apply()
    }

    /** Best-effort human-readable name for a SAF tree URI, e.g. ".../tree/primary:Music/Rock" -> "Rock". */
    fun folderDisplayName(uri: Uri): String {
        val decoded = Uri.decode(uri.lastPathSegment ?: uri.toString())
        return decoded.substringAfterLast('/').ifBlank { decoded }
    }

    fun clearCache() {
        indexFile.delete()
        coversDir.listFiles()?.forEach { it.delete() }
    }

    fun coverCacheFile(entry: LibraryEntry): File = File(coversDir, "${entry.id}.${entry.extension}")

    fun writeCoverBytes(id: String, extension: String, bytes: ByteArray) {
        File(coversDir, "$id.$extension").writeBytes(bytes)
    }

    fun readCoverBytes(entry: LibraryEntry): ByteArray? = try {
        coverCacheFile(entry).readBytes()
    } catch (e: Exception) {
        null
    }

    fun loadCachedEntries(): List<LibraryEntry>? {
        if (!indexFile.exists()) return null
        return try {
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).map { i -> array.getJSONObject(i).toEntry() }
        } catch (e: Exception) {
            null
        }
    }

    fun saveEntries(entries: List<LibraryEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        indexFile.writeText(array.toString())
    }

    private fun LibraryEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("subtitle", subtitle)
        put("mimeType", mimeType)
        put("extension", extension)
        val files = JSONArray()
        sourceFiles.forEach { f ->
            files.put(JSONObject().apply {
                put("uri", f.uri)
                put("displayName", f.displayName)
            })
        }
        put("sourceFiles", files)
    }

    private fun JSONObject.toEntry(): LibraryEntry {
        val filesArray = getJSONArray("sourceFiles")
        val files = (0 until filesArray.length()).map { i ->
            val o = filesArray.getJSONObject(i)
            LibrarySourceFile(o.getString("uri"), o.getString("displayName"))
        }
        return LibraryEntry(
            id = getString("id"),
            title = getString("title"),
            subtitle = getString("subtitle"),
            mimeType = getString("mimeType"),
            extension = getString("extension"),
            sourceFiles = files
        )
    }

    companion object {
        private const val KEY_FOLDER_URIS = "folder_uris"
    }
}
