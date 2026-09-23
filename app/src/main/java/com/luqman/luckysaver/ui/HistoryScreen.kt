package com.luqman.luckysaver.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import coil3.compose.AsyncImage
import com.luqman.luckysaver.data.DownloadEntity
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(history: List<DownloadEntity>?, onBack: () -> Unit, onForget: (DownloadEntity) -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                history == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                history.isEmpty() -> Text(
                    "Nothing saved yet. Share a post from Instagram to LuckySaver, or paste a link on the home screen.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(history, key = { it.key }) { e ->
                        ListItem(
                            modifier = Modifier.clickable {
                                val intent = Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(Uri.parse(e.uri), if (e.isVideo) "video/*" else "image/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
                                }
                            },
                            leadingContent = {
                                AsyncImage(
                                    model = Uri.parse(e.uri),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(56.dp),
                                )
                            },
                            headlineContent = { Text("@${e.owner}") },
                            supportingContent = {
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.savedAt)))
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { onForget(e) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove from history")
                                    }
                                }
                            },
                            overlineContent = {
                                Icon(
                                    if (e.isVideo) Icons.Default.Movie else Icons.Default.Image,
                                    contentDescription = if (e.isVideo) "Video" else "Photo",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
