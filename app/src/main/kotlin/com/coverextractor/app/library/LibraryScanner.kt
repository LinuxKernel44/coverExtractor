package com.coverextractor.app.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.coverextractor.app.CoverExtractor
import com.coverextractor.app.CoverResult
import com.coverextractor.app.ImageUtils

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "m4b", "mp4", "aac", "ogg", "oga", "opus",
    "wma", "ape", "wav", "wv", "aiff", "aif", "alac", "3gp", "3ga", "amr"
)

data class ScannedFile(val uri: Uri, val displayName: String)

object LibraryScanner {

    /** Walks every subfolder of [treeUri] via SAF and returns every file that looks like audio. */
    fun listAudioFiles(context: Context, treeUri: Uri): List<ScannedFile> {
        val results = mutableListOf<ScannedFile>()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        walk(context, treeUri, rootDocId, results)
        return results
    }

    private fun walk(context: Context, treeUri: Uri, docId: String, out: MutableList<ScannedFile>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val cursor = try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (e: Exception) {
            null
        } ?: return

        cursor.use {
            while (it.moveToNext()) {
                val childDocId = it.getString(0)
                val name = it.getString(1) ?: continue
                val mime = it.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(context, treeUri, childDocId, out)
                } else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                    out.add(ScannedFile(docUri, name))
                }
            }
        }
    }

    /**
     * Extracts covers from every scanned file and groups files that share byte-identical art into
     * one [LibraryEntry], writing each unique cover's bytes to the repository's on-disk cache.
     * Reports progress after every file via [onProgress].
     */
    fun scanAndGroup(
        context: Context,
        repository: LibraryRepository,
        files: List<ScannedFile>,
        onProgress: (ScanProgress) -> Unit
    ): List<LibraryEntry> {
        val entriesById = LinkedHashMap<String, LibraryEntry>()

        files.forEachIndexed { index, file ->
            val outcome = CoverExtractor.extract(context, file.uri)
            if (outcome is CoverResult.Found) {
                val cover = outcome.cover
                val id = ImageUtils.sha256Hex(cover.bytes)
                val existing = entriesById[id]
                if (existing == null) {
                    repository.writeCoverBytes(id, cover.extension, cover.bytes)
                    entriesById[id] = LibraryEntry(
                        id = id,
                        title = titleFromFileName(file.displayName),
                        subtitle = "1 track",
                        mimeType = cover.mimeType,
                        extension = cover.extension,
                        sourceFiles = listOf(LibrarySourceFile(file.uri.toString(), file.displayName))
                    )
                } else {
                    val updatedFiles = existing.sourceFiles + LibrarySourceFile(file.uri.toString(), file.displayName)
                    entriesById[id] = existing.copy(
                        subtitle = "${updatedFiles.size} tracks",
                        sourceFiles = updatedFiles
                    )
                }
            }
            onProgress(ScanProgress(index + 1, entriesById.size, done = false))
        }

        val entries = entriesById.values.sortedBy { it.title.lowercase() }
        onProgress(ScanProgress(files.size, entries.size, done = true))
        return entries
    }

    private fun titleFromFileName(displayName: String): String {
        val withoutExtension = displayName.substringBeforeLast('.', displayName)
        return withoutExtension.replace('_', ' ').replace('-', ' ').trim().ifBlank { displayName }
    }
}
