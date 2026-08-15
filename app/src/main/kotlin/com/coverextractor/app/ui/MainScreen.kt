package com.coverextractor.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coverextractor.app.ResultItem
import com.coverextractor.app.Status

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(results: List<ResultItem>, onPickFiles: () -> Unit, contentPadding: PaddingValues = PaddingValues()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("coverExtractor") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(bottom = contentPadding.calculateBottomPadding())
        ) {
            Button(
                onClick = onPickFiles,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Icon(Icons.Filled.LibraryMusic, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pick audio file(s)")
            }

            if (results.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Pick one or more audio files, or share them here from another app.\n\n" +
                            "Embedded cover art is saved to Pictures/coverExtractor exactly as stored " +
                            "in the file — no re-encoding, no quality loss.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results, key = { it.id }) { item ->
                        ResultRow(item)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(item: ResultItem) {
    ListItem(
        headlineContent = { Text(item.sourceName, maxLines = 1) },
        supportingContent = { Text(statusText(item.status)) },
        leadingContent = { StatusLeading(item.status) }
    )
}

@Composable
private fun StatusLeading(status: Status) {
    when (status) {
        is Status.Extracting -> CircularProgressIndicator(modifier = Modifier.size(40.dp))
        is Status.Saved -> {
            val thumb = status.thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
                )
            } else {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
            }
        }
        is Status.NoCoverFound -> Icon(Icons.Filled.BrokenImage, contentDescription = null)
        is Status.Error -> Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
    }
}

private fun statusText(status: Status): String = when (status) {
    is Status.Extracting -> "Extracting cover..."
    is Status.Saved -> "Saved to Pictures/coverExtractor"
    is Status.NoCoverFound -> "No embedded cover found"
    is Status.Error -> status.message
}
