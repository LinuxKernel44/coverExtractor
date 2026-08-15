package com.coverextractor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.coverextractor.app.ui.MainScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import androidx.compose.runtime.mutableStateListOf

sealed interface Status {
    object Extracting : Status
    data class Saved(val thumbnail: Bitmap?, val savedUri: Uri) : Status
    object NoCoverFound : Status
    data class Error(val message: String) : Status
}

data class ResultItem(val id: String, val sourceName: String, val status: Status)

class MainActivity : ComponentActivity() {

    private val results = mutableStateListOf<ResultItem>()

    // Serializes MediaStore inserts: when a batch shares a base filename (e.g. several formats
    // of the same track), each insert+publish must fully finish before the next starts, or
    // MediaStore's auto-rename-on-conflict can't see the previous entry yet and both writers can
    // race on the same pending row.
    private val gallerySaveMutex = Mutex()

    private val pickFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) handleUris(uris)
        }

    private val requestStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* handled lazily on save */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P) {
            requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        results = results,
                        onPickFiles = { pickFilesLauncher.launch(arrayOf("*/*")) }
                    )
                }
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { listOf(it) } ?: emptyList()
            Intent.ACTION_SEND -> intent.parcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) } ?: emptyList()
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) handleUris(uris)
    }

    private fun handleUris(uris: List<Uri>) {
        uris.forEach { uri ->
            val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "audio file"
            val baseName = sanitizeBaseName(displayName)
            val itemId = UUID.randomUUID().toString()
            results.add(0, ResultItem(itemId, displayName, Status.Extracting))

            lifecycleScope.launch {
                val newStatus = processOne(uri, baseName)
                val index = results.indexOfFirst { it.id == itemId }
                if (index >= 0) results[index] = results[index].copy(status = newStatus)
            }
        }
    }

    private suspend fun processOne(uri: Uri, baseName: String): Status {
        val outcome = withContext(Dispatchers.IO) { CoverExtractor.extract(this@MainActivity, uri) }
        return when (outcome) {
            is CoverResult.Found -> {
                if (needsLegacyStoragePermission() && !hasStoragePermission()) {
                    Status.Error("Storage permission required on Android 8-9")
                } else {
                    val savedUri = gallerySaveMutex.withLock {
                        withContext(Dispatchers.IO) { GallerySaver.save(this@MainActivity, baseName, outcome.cover) }
                    }
                    if (savedUri != null) {
                        val thumbnail = withContext(Dispatchers.Default) { decodeSampledThumbnail(outcome.cover.bytes) }
                        Status.Saved(thumbnail, savedUri)
                    } else {
                        Status.Error("Could not save to gallery")
                    }
                }
            }
            is CoverResult.NotFound -> Status.NoCoverFound
            is CoverResult.Failed -> Status.Error(outcome.message)
        }
    }

    private fun needsLegacyStoragePermission() = Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P

    private fun hasStoragePermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitizeBaseName(displayName: String): String {
        val withoutExtension = displayName.substringBeforeLast('.', displayName)
        val sanitized = withoutExtension.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim()
        return sanitized.ifBlank { "cover" }.take(80)
    }

    private fun decodeSampledThumbnail(bytes: ByteArray, reqSize: Int = 256): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqSize && bounds.outHeight / (sample * 2) >= reqSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (e: Exception) {
        null
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(name, T::class.java) else getParcelableExtra(name)

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Intent.parcelableArrayListExtraCompat(name: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(name, T::class.java) else getParcelableArrayListExtra(name)
