package com.libreseed.pocketbuild.host.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var selectedId by remember { mutableStateOf<String?>(apps.firstOrNull()?.id) }
    LaunchedEffect(apps) {
        if (apps.none { it.id == selectedId }) selectedId = apps.firstOrNull()?.id
    }
    val selected = apps.firstOrNull { it.id == selectedId }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideLandscape = maxWidth > maxHeight && maxWidth >= 840.dp
        if (wideLandscape) {
            Row(Modifier.fillMaxSize()) {
                HostNavigationPane(
                    apps = apps,
                    selectedId = selectedId,
                    busy = busy,
                    status = status,
                    onDismissStatus = onDismissStatus,
                    onImport = onImport,
                    onOpenBuilder = onOpenBuilder,
                    onSelect = { selectedId = it.id },
                    modifier = Modifier.fillMaxHeight().width(360.dp),
                )
                Surface(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant) {}
                HostDetailPane(
                    app = selected,
                    busy = busy,
                    onRun = onRun,
                    onRollback = onRollback,
                    onDelete = onDelete,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { HostHeader() }
                item { HostActions(busy, onImport, onOpenBuilder) }
                status?.let { item { StatusCard(status, busy, onDismissStatus) } }
                item { Text("Hosted apps", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                if (apps.isEmpty()) {
                    item { EmptyHostCard() }
                } else {
                    items(apps, key = { it.id }) { app ->
                        HostedAppCard(app, busy, onRun, onRollback, onDelete)
                    }
                }
                item { RuntimeFoundationCard() }
            }
        }
    }
}

@Composable
private fun HostNavigationPane(
    apps: List<HostedApp>,
    selectedId: String?,
    busy: Boolean,
    status: String?,
    onDismissStatus: () -> Unit,
    onImport: () -> Unit,
    onOpenBuilder: () -> Unit,
    onSelect: (HostedApp) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HostHeader() }
        item { HostActions(busy, onImport, onOpenBuilder) }
        status?.let { item { StatusCard(status, busy, onDismissStatus) } }
        item { Text("Hosted apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (apps.isEmpty()) {
            item { EmptyHostCard() }
        } else {
            items(apps, key = { it.id }) { app ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onSelect(app) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (app.id == selectedId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(app.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${app.version} · ${app.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HostDetailPane(
    app: HostedApp?,
    busy: Boolean,
    onRun: (HostedApp) -> Unit,
    onRollback: (HostedApp) -> Unit,
    onDelete: (HostedApp) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (app == null) {
            item {
                Text("No hosted app selected", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Import a bundle to create the first replaceable project.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            item {
                Text(app.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Text("${app.id} · ${app.version}", color = MaterialTheme.colorScheme.primary)
                if (app.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(app.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onRun(app) }, enabled = !busy) { Text("Run app") }
                    OutlinedButton(onClick = { onRollback(app) }, enabled = app.canRollback && !busy) { Text("Rollback") }
                    if (app.id != HostStore.DEMO_ID) {
                        TextButton(onClick = { onDelete(app) }, enabled = !busy) { Text("Delete") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Declared capabilities", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (app.capabilities.isEmpty()) {
                            Text("No native capabilities declared.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            app.capabilities.sorted().forEach { capability ->
                                Text("• $capability")
                            }
                        }
                        HorizontalDivider()
                        Text("Native access remains behind PocketHost capability grants and Android confirmation gates.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { RuntimeFoundationCard() }
    }
}

@Composable
private fun HostHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("PocketHost", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        Text(
            "Replaceable games, simulations, tools and experiments inside one stable Android shell.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HostActions(busy: Boolean, onImport: () -> Unit, onOpenBuilder: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Load an app or update", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Full bundles replace the active project; hash-guarded patches preserve rollback.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onImport, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
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

@Composable
private fun StatusCard(status: String, busy: Boolean, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(status, modifier = Modifier.weight(1f))
            if (!busy) TextButton(onClick = onDismiss) { Text("Dismiss") }
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
            if (app.description.isNotBlank()) Text(app.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (app.capabilities.isNotEmpty()) {
                Text(
                    app.capabilities.sorted().take(8).joinToString("  ·  ") + if (app.capabilities.size > 8) "  ·  +${app.capabilities.size - 8}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onRollback(app) }, enabled = app.canRollback && !busy) { Text("Rollback") }
                if (app.id != HostStore.DEMO_ID) TextButton(onClick = { onDelete(app) }, enabled = !busy) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun RuntimeFoundationCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Runtime foundation", fontWeight = FontWeight.Bold)
            Text("HTML, CSS, JavaScript, Canvas, WebGL, WebAudio, WebAssembly, workers and local storage run inside the bundled Android WebView.")
            HorizontalDivider()
            Text("Native Android access passes through project declarations, per-project grants, and confirmation gates. Unknown remote navigation is blocked by default.")
        }
    }
}

@Composable
private fun EmptyHostCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("No apps installed", fontWeight = FontWeight.Bold)
            Text("Import a bundle to create the first hosted project.")
        }
    }
}
