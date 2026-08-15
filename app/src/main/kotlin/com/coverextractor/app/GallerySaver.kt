package com.coverextractor.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

private const val GALLERY_SUBDIR = "coverExtractor"

/**
 * Writes the exact extracted image bytes into the device gallery, unchanged, under
 * Pictures/coverExtractor. No decoding/re-encoding happens here, so the saved file is
 * byte-for-byte identical to what was embedded in the audio file.
 */
object GallerySaver {

    fun save(context: Context, baseName: String, cover: ExtractedCover): Uri? {
        val displayName = "$baseName.${cover.extension}"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, displayName, cover)
        } else {
            saveLegacy(context, baseName, cover)
        }
    }

    private fun saveViaMediaStore(context: Context, displayName: String, cover: ExtractedCover): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, cover.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_SUBDIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(cover.bytes) } ?: return null
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, baseName: String, cover: ExtractedCover): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), GALLERY_SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) return null

        var outFile = File(dir, "$baseName.${cover.extension}")
        var counter = 1
        while (outFile.exists()) {
            outFile = File(dir, "$baseName ($counter).${cover.extension}")
            counter++
        }

        return try {
            outFile.outputStream().use { it.write(cover.bytes) }
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf(cover.mimeType), null)
            Uri.fromFile(outFile)
        } catch (e: Exception) {
            null
        }
    }
}
