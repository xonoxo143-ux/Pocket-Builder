package com.libreseed.pocketbuild.host.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libreseed.pocketbuild.host.HostStore
import com.libreseed.pocketbuild.host.HostedApp

@Composable
fun HostScreen(
    apps: List<HostedApp>,
    busy: Boolean,
    status: String?,
    onDismissStatus: () -> Unit,
    onImport: () -> Unit,
    onRun: (HostedApp) -> Unit,
    onRollback: (HostedApp) -> Unit,
    onDelete: (HostedApp) -> Unit,
    onOpenBuilder: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(status) {
        val message = status ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        onDismissStatus()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("PocketHost", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                Text(
                    "A stable Android shell for replaceable games, simulations, tools and experiments.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Load an app or update", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Import a .pocketapp/.pbu ZIP or a JSON bundle. Full versions replace the active project; hash-guarded patches update individual files and preserve rollback.",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = onImport, enabled = !busy) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(9.dp))
                                    Text("Working")
                                } else {
                                    Text("Choose bundle")
                                }
                            }
                            OutlinedButton(onClick = onOpenBuilder, enabled = !busy) { Text("APK builder") }
                        }
                    }
                }
            }
            item {
                Text("Hosted apps", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            if (apps.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text("No apps installed", fontWeight = FontWeight.Bold)
                            Text("Import a bundle to create the first hosted project.")
                        }
                    }
                }
            } else {
                items(apps, key = { it.id }) { app ->
                    HostedAppCard(app, busy, onRun, onRollback, onDelete)
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Runtime foundation", fontWeight = FontWeight.Bold)
                        Text("HTML, CSS, JavaScript, Canvas, WebGL, WebAudio, WebAssembly, workers and local storage run inside the bundled Android WebView.")
                        HorizontalDivider()
                        Text("Native Android access passes through project declarations, per-project grants, and confirmation gates. Unknown remote navigation is blocked by default.")
                    }
                }
            }
        }
    }
}

@Composable
private fun HostedAppCard(
    app: HostedApp,
    busy: Boolean,
    onRun: (HostedApp) -> Unit,
    onRollback: (HostedApp) -> Unit,
    onDelete: (HostedApp) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${app.id} · ${app.version}", color = MaterialTheme.colorScheme.primary)
                }
                Button(onClick = { onRun(app) }, enabled = !busy) { Text("Run") }
            }
            if (app.description.isNotBlank()) {
                Text(app.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (app.capabilities.isNotEmpty()) {
                Text(
                    app.capabilities.sorted().take(8).joinToString("  ·  ") + if (app.capabilities.size > 8) "  ·  +${app.capabilities.size - 8}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onRollback(app) }, enabled = app.canRollback && !busy) { Text("Rollback") }
                if (app.id != HostStore.DEMO_ID) {
                    TextButton(onClick = { onDelete(app) }, enabled = !busy) { Text("Delete") }
                }
            }
        }
    }
}
