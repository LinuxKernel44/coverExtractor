package com.coverextractor.app.library

data class LibrarySourceFile(val uri: String, val displayName: String)

/**
 * One unique embedded cover found while scanning a folder. Several audio files that share the
 * exact same image (e.g. every track of one album) collapse into a single entry.
 */
data class LibraryEntry(
    val id: String, // sha256 of the cover bytes; also the cache file's base name
    val title: String,
    val subtitle: String,
    val mimeType: String,
    val extension: String,
    val sourceFiles: List<LibrarySourceFile>
)

data class ScanProgress(val filesScanned: Int, val coversFound: Int, val done: Boolean)
