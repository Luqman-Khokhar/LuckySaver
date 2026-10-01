package com.luqman.luckysaver.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.luqman.luckysaver.data.DarkMode
import com.luqman.luckysaver.data.Palette
import com.luqman.luckysaver.data.Quality
import com.luqman.luckysaver.data.Settings
import com.luqman.luckysaver.data.WatchInterval

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    loggedIn: Boolean,
    bubbleOn: Boolean,
    batteryRestricted: Boolean,
    onChange: ((Settings) -> Settings) -> Unit,
    onWatching: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onBubble: (Boolean) -> Unit,
    onFixBattery: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Settings") }, scrollBehavior = scroll) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp),
        ) {
            item { Section("Instagram account") }
            item {
                Row(Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(if (loggedIn) "Logged in" else "Not logged in") },
                        supportingContent = {
                            Text(
                                if (loggedIn) "Stories, highlights and private accounts you follow can be saved."
                                else "Only public posts and reels work. A secondary account is safest."
                            )
                        },
                        trailingContent = {
                            if (loggedIn) TextButton(onClick = onLogout) { Text("Log out") }
                            else TextButton(onClick = onLogin) { Text("Log in") }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            }

            item { Section("Story watchlist") }
            item {
                SwitchRow(
                    "Save stories automatically",
                    "Checks the accounts on the Stories tab and saves what's new.",
                    settings.storyWatchEnabled, onWatching,
                )
            }
            item { IntervalRow(settings.storyIntervalHours, enabled = settings.storyWatchEnabled, onInterval) }
            if (settings.storyWatchEnabled && batteryRestricted) item {
                ListItem(
                    headlineContent = { Text("Battery saver may stop checks") },
                    supportingContent = { Text("Let LuckySaver run in the background, or checks quietly stop.") },
                    trailingContent = { TextButton(onClick = onFixBattery) { Text("Allow") } },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }

            item { Section("Downloads") }
            item {
                SwitchRow(
                    "Download straight away",
                    "Single photos, reels and stories skip the preview. Carousels still ask.",
                    settings.autoDownload,
                ) { on -> onChange { it.copy(autoDownload = on) } }
            }
            item {
                SwitchRow("Skip anything already saved", "Never download the same item twice.", settings.skipDuplicates) { on ->
                    onChange { it.copy(skipDuplicates = on) }
                }
            }
            item {
                SwitchRow("Wi-Fi only", "Hold downloads until you're on Wi-Fi.", settings.wifiOnly) { on ->
                    onChange { it.copy(wifiOnly = on) }
                }
            }
            item {
                Choice(
                    title = "Quality",
                    options = Quality.entries,
                    selected = settings.quality,
                    label = { it.label },
                    note = "Smaller files use a lower resolution where Instagram offers one.",
                ) { q -> onChange { it.copy(quality = q) } }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        supportingText = { Text("Use {user} {code} {index} {date}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item { Section("Floating button") }
            item {
                SwitchRow(
                    "Show over other apps",
                    "Copy a link in Instagram, then tap the bubble to save it without switching apps.",
                    bubbleOn, onBubble,
                )
            }

            item { Section("Appearance") }
            item {
                val palettes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Palette.entries else listOf(Palette.JADE)
                Choice("Colors", palettes, settings.palette, { it.label }, null) { p -> onChange { it.copy(palette = p) } }
            }
            item {
                Choice("Theme", DarkMode.entries, settings.darkMode, { it.label }, null) { d -> onChange { it.copy(darkMode = d) } }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun <T> Choice(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    note: String?,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.selectableGroup().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null, modifier = Modifier.padding(12.dp))
                Text(label(option), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (note != null) Text(
            note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun IntervalRow(hours: Int, enabled: Boolean, onInterval: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = WatchInterval.of(hours)
    Column {
        ListItem(
            headlineContent = { Text("How often") },
            supportingContent = { Text("${selected.label}. ${selected.risk}") },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                supportingColor = if (selected.hours <= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.clickable(enabled = enabled, role = Role.DropdownList) { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            WatchInterval.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(option.risk, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; onInterval(option.hours) },
                )
            }
        }
    }
}
