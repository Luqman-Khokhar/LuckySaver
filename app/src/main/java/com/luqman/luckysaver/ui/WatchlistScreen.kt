package com.luqman.luckysaver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.luqman.luckysaver.data.WatchInterval
import com.luqman.luckysaver.data.WatchedAccount
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    accounts: List<WatchedAccount>,
    enabled: Boolean,
    intervalHours: Int,
    adding: Boolean,
    error: String?,
    batteryUnrestricted: Boolean,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (WatchedAccount) -> Unit,
    onToggleAccount: (WatchedAccount, Boolean) -> Unit,
    onToggleWatching: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onCheckNow: () -> Unit,
    onFixBattery: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var intervalOpen by remember { mutableStateOf(false) }
    val selected = WatchInterval.of(intervalHours)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Story watchlist") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            // imePadding keeps the focused field above the keyboard; without it the app draws
            // edge to edge and the keyboard covers whatever you are typing into.
            Modifier.padding(padding).fillMaxSize().imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Save stories automatically", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Checks the accounts below and saves anything new before it expires.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = onToggleWatching)
                    }

                    ExposedDropdownMenuBox(
                        expanded = intervalOpen,
                        onExpandedChange = { intervalOpen = !intervalOpen },
                    ) {
                        OutlinedTextField(
                            value = selected.label,
                            onValueChange = {},
                            readOnly = true,
                            enabled = enabled,
                            label = { Text("How often") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalOpen) },
                            modifier = Modifier
                                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = intervalOpen, onDismissRequest = { intervalOpen = false }) {
                            WatchInterval.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(option.label)
                                            Text(
                                                option.risk,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        intervalOpen = false
                                        onIntervalChange(option.hours)
                                    },
                                )
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected.hours <= 2) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    ) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Risk",
                                modifier = Modifier.size(18.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(selected.risk, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Checking on a schedule is the clearest automated pattern this app produces. " +
                                        "Instagram can ask the account to verify itself, or restrict it. Use a " +
                                        "secondary account, and prefer longer intervals.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    if (enabled && !batteryUnrestricted) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Battery optimisation will stop these checks",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "Your phone can freeze background work. Allow LuckySaver to run unrestricted, " +
                                        "or checks will quietly stop happening.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(onClick = onFixBattery) { Text("Allow") }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Add account") },
                            placeholder = { Text("username") },
                            singleLine = true,
                            isError = error != null,
                            supportingText = error?.let { { Text(it) } },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = { onAdd(username); username = "" }
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { onAdd(username); username = "" },
                            enabled = username.isNotBlank() && !adding,
                        ) {
                            if (adding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("Add")
                        }
                    }

                    if (accounts.isNotEmpty()) {
                        TextButton(onClick = onCheckNow, enabled = enabled) { Text("Check now") }
                    }
                    HorizontalDivider()
                }
            }

            if (accounts.isEmpty()) {
                item {
                    Text(
                        "No accounts yet. Add one above and its stories will be saved for you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(accounts, key = { it.userId }) { account ->
                    ListItem(
                        headlineContent = { Text("@${account.username}") },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(
                                        if (account.lastCheckedAt == 0L) "Not checked yet"
                                        else "Checked " + DateFormat.getTimeInstance(DateFormat.SHORT)
                                            .format(Date(account.lastCheckedAt))
                                    )
                                    append(" · ${account.savedCount} saved")
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = account.enabled,
                                    onCheckedChange = { onToggleAccount(account, it) },
                                )
                                IconButton(onClick = { onRemove(account) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Stop watching @${account.username}")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
