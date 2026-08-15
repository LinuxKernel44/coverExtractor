package com.coverextractor.app.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.coverextractor.app.CoverExtractor
import com.coverextractor.app.CoverResult
import com.coverextractor.app.ExtractedCover
import com.coverextractor.app.ImageUtils

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "m4b", "mp4", "aac", "ogg", "oga", "opus",
    "wma", "ape", "wav", "wv", "aiff", "aif", "alac", "3gp", "3ga", "amr"
)

data class ScannedFile(val uri: Uri, val displayName: String)

private data class FileTags(val album: String?, val trackTitle: String?, val artist: String?)

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
     * Reports progress after every file via [onProgress]. [files] may span several folders — dedup
     * happens purely by cover hash, so the same album found under two different roots still
     * collapses into one entry.
     */
    fun scanAndGroup(
        context: Context,
        repository: LibraryRepository,
        files: List<ScannedFile>,
        onProgress: (ScanProgress) -> Unit
    ): List<LibraryEntry> {
        val entriesById = LinkedHashMap<String, LibraryEntry>()
        // Tracks whether an entry's title still needs a better source (i.e. it was derived from a
        // filename because the file that created it had no album/title tag), so a later file
        // sharing the same cover can upgrade it once a real tag turns up.
        val titleIsFromTag = HashMap<String, Boolean>()

        files.forEachIndexed { index, file ->
            val (cover, tags) = extractCoverAndTags(context, file.uri)
            if (cover != null) {
                val id = ImageUtils.sha256Hex(cover.bytes)
                val candidateTitle = tags.album ?: tags.trackTitle
                val existing = entriesById[id]
                if (existing == null) {
                    repository.writeCoverBytes(id, cover.extension, cover.bytes)
                    titleIsFromTag[id] = candidateTitle != null
                    entriesById[id] = LibraryEntry(
                        id = id,
                        title = candidateTitle ?: titleFromFileName(file.displayName),
                        subtitle = subtitleFor(tags.artist, 1),
                        mimeType = cover.mimeType,
                        extension = cover.extension,
                        sourceFiles = listOf(LibrarySourceFile(file.uri.toString(), file.displayName))
                    )
                } else {
                    val updatedFiles = existing.sourceFiles + LibrarySourceFile(file.uri.toString(), file.displayName)
                    val upgradeTitle = titleIsFromTag[id] != true && candidateTitle != null
                    if (upgradeTitle) titleIsFromTag[id] = true
                    entriesById[id] = existing.copy(
                        title = if (upgradeTitle) candidateTitle!! else existing.title,
                        subtitle = subtitleFor(tags.artist, updatedFiles.size, existing.subtitle),
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

    /**
     * Reads embedded-cover bytes and ALBUM/TITLE/ARTIST tags together from a single
     * `MediaMetadataRetriever` session; only falls back to [CoverExtractor]'s binary parsers (and
     * only for the cover, tags aren't recoverable there) when the retriever found no picture.
     */
    private fun extractCoverAndTags(context: Context, uri: Uri): Pair<ExtractedCover?, FileTags> {
        val retriever = MediaMetadataRetriever()
        var picture: ByteArray? = null
        var tags = FileTags(null, null, null)
        try {
            retriever.setDataSource(context, uri)
            picture = retriever.embeddedPicture
            tags = FileTags(
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.ifBlank { null },
                trackTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null },
                artist = (
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    )?.trim()?.ifBlank { null }
            )
        } catch (e: Exception) {
            // Fall through: an unreadable/unsupported file still gets a chance via the byte parsers.
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        val cover = picture?.let { CoverExtractor.wrapRawPicture(it) }
            ?: (CoverExtractor.extractFallbackOnly(context, uri) as? CoverResult.Found)?.cover

        return cover to tags
    }

    private fun subtitleFor(artist: String?, trackCount: Int, previousSubtitle: String? = null): String {
        val effectiveArtist = artist ?: previousSubtitle?.substringBefore(" • ", "").takeUnless { it.isNullOrBlank() }
        val trackPart = "$trackCount ${if (trackCount == 1) "track" else "tracks"}"
        return if (effectiveArtist != null) "$effectiveArtist • $trackPart" else trackPart
    }

    private fun titleFromFileName(displayName: String): String {
        val withoutExtension = displayName.substringBeforeLast('.', displayName)
        return withoutExtension.replace('_', ' ').replace('-', ' ').trim().ifBlank { displayName }
    }
}
