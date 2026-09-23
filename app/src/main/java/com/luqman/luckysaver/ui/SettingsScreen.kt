package com.luqman.luckysaver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import com.luqman.luckysaver.data.Quality
import com.luqman.luckysaver.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onOpenWatchlist: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSwitch(
                title = "Download straight away",
                body = "Skip the preview for single photos, reels and stories. Carousels still ask which items you want.",
                checked = settings.autoDownload,
            ) { on -> onChange { it.copy(autoDownload = on) } }

            SettingSwitch(
                title = "Skip anything already saved",
                body = "Never download the same post twice.",
                checked = settings.skipDuplicates,
            ) { on -> onChange { it.copy(skipDuplicates = on) } }

            SettingSwitch(
                title = "Wi-Fi only",
                body = "Hold downloads until you're on Wi-Fi.",
                checked = settings.wifiOnly,
            ) { on -> onChange { it.copy(wifiOnly = on) } }

            HorizontalDivider()

            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Quality", style = MaterialTheme.typography.titleSmall)
                Quality.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = settings.quality == option,
                                role = Role.RadioButton,
                                onClick = { onChange { it.copy(quality = option) } },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = settings.quality == option, onClick = null)
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Smaller files pick a lower resolution where Instagram offers one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenWatchlist)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Story watchlist", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (settings.storyWatchEnabled)
                            "On · checking every ${settings.storyIntervalHours}h"
                        else "Off · save stories from chosen accounts before they expire",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            OutlinedTextField(
                value = settings.folder,
                onValueChange = { value -> onChange { it.copy(folder = value) } },
                label = { Text("Album name") },
                supportingText = { Text("Saved to Pictures/${settings.folder.ifBlank { "LuckySaver" }}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = settings.fileNameTemplate,
                onValueChange = { value -> onChange { it.copy(fileNameTemplate = value) } },
                label = { Text("File name") },
                supportingText = { Text("Tokens: {user} {code} {index} {date}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
