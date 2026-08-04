package com.libreseed.pocketbuild.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.libreseed.pocketbuild.PocketBuildUiState
import com.libreseed.pocketbuild.model.IncomingSource
import com.libreseed.pocketbuild.model.SourceKind
import com.libreseed.pocketbuild.model.ToolchainPackSummary
import com.libreseed.pocketbuild.model.ToolchainState

private enum class Destination(val label: String, val glyph: String) {
    HOME("Home", "H"),
    PROJECTS("Projects", "P"),
    BUILDS("Builds", "B"),
    TOOLCHAINS("Toolchains", "T"),
    SETTINGS("Settings", "S"),
}

@Composable
fun PocketBuildApp(
    state: PocketBuildUiState,
    onSourceSelected: (Uri) -> Unit,
    onBuildRequested: () -> Unit,
    onNoticeDismissed: () -> Unit,
) {
    var destination by remember { mutableStateOf(Destination.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onSourceSelected)
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        onNoticeDismissed()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 720.dp
        if (useRail) {
            Row(Modifier.fillMaxSize()) {
                AppRail(destination = destination, onDestination = { destination = it })
                AppContent(
                    modifier = Modifier.weight(1f),
                    destination = destination,
                    state = state,
                    onOpenSource = { sourcePicker.launch(arrayOf("*/*")) },
                    onBuildRequested = onBuildRequested,
                    snackbarHostState = snackbarHostState,
                    hostSnackbarInline = true,
                )
            }
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { NavGlyph(item.glyph) },
                                label = { Text(item.label, maxLines = 1) },
                            )
                        }
                    }
                },
            ) { padding ->
                AppContent(
                    modifier = Modifier.padding(padding),
                    destination = destination,
                    state = state,
                    onOpenSource = { sourcePicker.launch(arrayOf("*/*")) },
                    onBuildRequested = onBuildRequested,
                    snackbarHostState = snackbarHostState,
                    hostSnackbarInline = false,
                )
            }
        }
    }
}

@Composable
private fun AppRail(destination: Destination, onDestination: (Destination) -> Unit) {
    NavigationRail(Modifier.fillMaxHeight()) {
        Spacer(Modifier.height(12.dp))
        Destination.entries.forEach { item ->
            NavigationRailItem(
                selected = destination == item,
                onClick = { onDestination(item) },
                icon = { NavGlyph(item.glyph) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun NavGlyph(text: String) {
    Surface(
        modifier = Modifier.size(28.dp),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AppContent(
    modifier: Modifier,
    destination: Destination,
    state: PocketBuildUiState,
    onOpenSource: () -> Unit,
    onBuildRequested: () -> Unit,
    snackbarHostState: SnackbarHostState,
    hostSnackbarInline: Boolean,
) {
    Box(modifier.fillMaxSize()) {
        when (destination) {
            Destination.HOME -> HomeScreen(state, onOpenSource, onBuildRequested)
            Destination.PROJECTS -> ProjectsScreen(state, onOpenSource, onBuildRequested)
            Destination.BUILDS -> BuildsScreen(state)
            Destination.TOOLCHAINS -> ToolchainsScreen(state.toolchains)
            Destination.SETTINGS -> SettingsScreen()
        }
        if (hostSnackbarInline && snackbarHostState.currentSnackbarData != null) {
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun HomeScreen(state: PocketBuildUiState, onOpenSource: () -> Unit, onBuildRequested: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("PocketBuild", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Open a project, inspect it, then build without leaving your phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { OpenSourceCard(state.isInspecting, onOpenSource) }
        state.selectedSource?.let { source ->
            item { SourceCard(source, onBuildRequested) }
        }
        item {
            SectionTitle("Toolchain readiness")
            Spacer(Modifier.height(8.dp))
            state.toolchains.take(3).forEach { pack ->
                ToolchainRow(pack)
                Spacer(Modifier.height(8.dp))
            }
        }
        state.latestBuild?.let { build ->
            item {
                SectionTitle("Latest build")
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(build.projectName, fontWeight = FontWeight.SemiBold)
                        Text(build.status, color = MaterialTheme.colorScheme.primary)
                        Text(build.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenSourceCard(isInspecting: Boolean, onOpenSource: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Open a project or archive", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Godot projects, Gradle projects, ZIP archives, and PocketBuild recipes are recognized.")
            Button(onClick = onOpenSource, enabled = !isInspecting) {
                if (isInspecting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Inspecting")
                } else {
                    Text("Choose source")
                }
            }
        }
    }
}

@Composable
private fun SourceCard(source: IncomingSource, onBuildRequested: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(source.kind.displayName, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onBuildRequested, enabled = source.kind != SourceKind.UNKNOWN) {
                    Text("Build APK")
                }
            }
            source.projectRootHint?.let { Text("Project root: ${it.ifBlank { "/" }}") }
            source.warnings.forEach { warning ->
                Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ProjectsScreen(state: PocketBuildUiState, onOpenSource: () -> Unit, onBuildRequested: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Projects", "Imported and recently opened build workspaces") }
        item { Button(onClick = onOpenSource) { Text("Import project") } }
        state.selectedSource?.let { item { SourceCard(it, onBuildRequested) } }
            ?: item { EmptyCard("No projects yet", "Open a project file or ZIP to create the first workspace.") }
    }
}

@Composable
private fun BuildsScreen(state: PocketBuildUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Builds", "Live progress, logs, outputs and previous results") }
        state.latestBuild?.let { build ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(build.projectName, fontWeight = FontWeight.Bold)
                        Text(build.status, color = MaterialTheme.colorScheme.primary)
                        Text(build.detail)
                    }
                }
            }
        } ?: item { EmptyCard("No builds yet", "A build will appear here after you choose a supported project.") }
    }
}

@Composable
private fun ToolchainsScreen(toolchains: List<ToolchainPackSummary>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Toolchains", "Install only the build packs required by your projects") }
        items(toolchains, key = { it.id }) { pack -> ToolchainCard(pack) }
    }
}

@Composable
private fun ToolchainCard(pack: ToolchainPackSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pack.title, fontWeight = FontWeight.Bold)
                    Text(pack.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusLabel(pack.state)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(pack.sizeLabel)
                TextButton(onClick = { }) {
                    Text(if (pack.state == ToolchainState.READY) "Verify" else "Install")
                }
            }
        }
    }
}

@Composable
private fun ToolchainRow(pack: ToolchainPackSummary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(pack.title, fontWeight = FontWeight.SemiBold)
            Text(pack.sizeLabel, style = MaterialTheme.typography.bodySmall)
        }
        StatusLabel(pack.state)
    }
}

@Composable
private fun StatusLabel(state: ToolchainState) {
    val text = when (state) {
        ToolchainState.READY -> "Ready"
        ToolchainState.MISSING -> "Missing"
        ToolchainState.UPDATE_AVAILABLE -> "Update"
        ToolchainState.VERIFYING -> "Checking"
    }
    Text(text, color = if (state == ToolchainState.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
}

@Composable
private fun SettingsScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Settings", "Downloads, storage, signing and build safety") }
        item { EmptyCard("Defaults are conservative", "Large downloads require confirmation. Unknown Gradle projects will be inspected before execution.") }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun EmptyCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
