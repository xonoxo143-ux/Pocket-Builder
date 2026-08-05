package com.libreseed.pocketbuild.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libreseed.pocketbuild.PocketBuildUiState
import com.libreseed.pocketbuild.model.BuildConfirmation
import com.libreseed.pocketbuild.model.IncomingSource
import com.libreseed.pocketbuild.model.OperationHistoryItem
import com.libreseed.pocketbuild.model.OperationSeverity
import com.libreseed.pocketbuild.model.OperationStageStatus
import com.libreseed.pocketbuild.model.OperationStatus
import com.libreseed.pocketbuild.model.OperationUiState
import com.libreseed.pocketbuild.model.ProjectPreflightSummary
import com.libreseed.pocketbuild.model.SourceContainer
import com.libreseed.pocketbuild.model.SourceKind
import com.libreseed.pocketbuild.model.ToolchainPackSummary
import com.libreseed.pocketbuild.model.ToolchainState
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

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
    onFolderSelected: (Uri) -> Unit,
    onToolchainAction: (String) -> Unit,
    onBuildConfirmed: () -> Unit,
    onBuildConfirmationDismissed: () -> Unit,
    onCancelOperation: () -> Unit,
    onClearOperation: () -> Unit,
    onNoticeDismissed: () -> Unit,
) {
    var destination by remember { mutableStateOf(Destination.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onSourceSelected)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(onFolderSelected)
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(notice, duration = SnackbarDuration.Short)
        onNoticeDismissed()
    }

    state.buildConfirmation?.let { confirmation ->
        BuildConfirmationDialog(
            confirmation = confirmation,
            onConfirm = onBuildConfirmed,
            onDismiss = onBuildConfirmationDismissed,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val wideLandscape = landscape && maxWidth >= 840.dp
        if (wideLandscape) {
            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    AppRail(destination = destination, onDestination = { destination = it })
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        state.activeOperation?.let { operation ->
                            OperationStrip(operation = operation, onOpen = { destination = Destination.BUILDS })
                        }
                        AppContent(
                            modifier = Modifier.weight(1f),
                            destination = destination,
                            state = state,
                            compact = false,
                            onOpenSource = { sourcePicker.launch(arrayOf("*/*")) },
                            onBuildRequested = onBuildRequested,
                            onGrantFolder = { folderPicker.launch(null) },
                            onToolchainAction = onToolchainAction,
                            onCancelOperation = onCancelOperation,
                            onClearOperation = onClearOperation,
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    OperationDiagnosticsPanel(
                        operation = state.activeOperation,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(if (maxWidth >= 1180.dp) 400.dp else 340.dp),
                        onCancel = onCancelOperation,
                        onClear = onClearOperation,
                        showTimeline = true,
                    )
                }
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
                Column(Modifier.fillMaxSize().padding(padding)) {
                    state.activeOperation?.let { operation ->
                        OperationStrip(operation = operation, onOpen = { destination = Destination.BUILDS })
                    }
                    AppContent(
                        modifier = Modifier.weight(1f),
                        destination = destination,
                        state = state,
                        compact = true,
                        onOpenSource = { sourcePicker.launch(arrayOf("*/*")) },
                        onBuildRequested = onBuildRequested,
                        onGrantFolder = { folderPicker.launch(null) },
                        onToolchainAction = onToolchainAction,
                        onCancelOperation = onCancelOperation,
                        onClearOperation = onClearOperation,
                    )
                }
            }
        }
    }
}

@Composable
private fun BuildConfirmationDialog(
    confirmation: BuildConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(confirmation.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(confirmation.message)
                confirmation.riskDetails.forEach { detail ->
                    Text("• $detail", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Run build") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AppRail(destination: Destination, onDestination: (Destination) -> Unit) {
    NavigationRail(Modifier.fillMaxHeight().widthIn(min = 92.dp)) {
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
        modifier = Modifier.size(30.dp),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OperationStrip(operation: OperationUiState, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(operation.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    OperationStatusText(operation.status)
                }
                Text(
                    "${operation.stageTitle} · ${operation.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OperationProgress(operation.overallProgress, operation.status == OperationStatus.RUNNING)
            }
            Text("Details", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AppContent(
    modifier: Modifier,
    destination: Destination,
    state: PocketBuildUiState,
    compact: Boolean,
    onOpenSource: () -> Unit,
    onBuildRequested: () -> Unit,
    onGrantFolder: () -> Unit,
    onToolchainAction: (String) -> Unit,
    onCancelOperation: () -> Unit,
    onClearOperation: () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        when (destination) {
            Destination.HOME -> HomeScreen(state, onOpenSource, onBuildRequested, onGrantFolder)
            Destination.PROJECTS -> ProjectsScreen(state, onOpenSource, onBuildRequested, onGrantFolder)
            Destination.BUILDS -> BuildsScreen(state, compact, onCancelOperation, onClearOperation)
            Destination.TOOLCHAINS -> ToolchainsScreen(state.toolchains, onToolchainAction)
            Destination.SETTINGS -> SettingsScreen()
        }
    }
}

@Composable
private fun HomeScreen(
    state: PocketBuildUiState,
    onOpenSource: () -> Unit,
    onBuildRequested: () -> Unit,
    onGrantFolder: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(
                "PocketBuild",
                "Landscape-first local project host and APK builder with visible preflight, progress, logs and failures.",
            )
        }
        item { OpenSourceCard(state.isInspecting, state.activeOperation?.status == OperationStatus.RUNNING, onOpenSource) }
        state.selectedSource?.let { source ->
            item { SourceCard(source, state, onBuildRequested, onGrantFolder) }
        }
        state.preflight?.let { preflight -> item { PreflightCard(preflight) } }
        item {
            SectionTitle("Toolchain readiness")
            Spacer(Modifier.height(8.dp))
            state.toolchains.take(3).forEach { pack ->
                ToolchainRow(pack)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (state.operationHistory.isNotEmpty()) {
            item { SectionTitle("Recent operations") }
            items(state.operationHistory.take(4), key = { it.id }) { history -> HistoryRow(history) }
        }
    }
}

@Composable
private fun OpenSourceCard(isInspecting: Boolean, operationRunning: Boolean, onOpenSource: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Open a project or archive", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Godot and Android Gradle projects can be inspected before any project code runs.")
            Button(onClick = onOpenSource, enabled = !isInspecting && !operationRunning) {
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
private fun SourceCard(
    source: IncomingSource,
    state: PocketBuildUiState,
    onBuildRequested: () -> Unit,
    onGrantFolder: () -> Unit,
) {
    val operationRunning = state.activeOperation?.status == OperationStatus.RUNNING
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(source.kind.displayName, color = MaterialTheme.colorScheme.primary)
                }
                val needsFolder = source.container == SourceContainer.SINGLE_FILE && state.workspace == null
                Button(
                    onClick = if (needsFolder) onGrantFolder else onBuildRequested,
                    enabled = source.kind != SourceKind.UNKNOWN && source.kind != SourceKind.ZIP_UNKNOWN && !state.isPreparingWorkspace && !operationRunning,
                ) {
                    Text(
                        when {
                            needsFolder -> "Grant folder"
                            state.workspace == null -> "Prepare workspace"
                            else -> "Build APK"
                        },
                    )
                }
            }
            source.projectRootHint?.let { Text("Detected project root: ${it.ifBlank { "/" }}") }
            source.warnings.forEach { warning ->
                Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.workspace?.let { workspace ->
                Text(
                    "Workspace ready · ${workspace.importedFiles} files · ${formatBytes(workspace.importedBytes)}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "No project code runs until you press Build APK and confirm the Gradle preflight.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreflightCard(preflight: ProjectPreflightSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Project preflight", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            DetailRow("Type", preflight.sourceKind.displayName)
            DetailRow("Files", "${preflight.importedFiles} · ${formatBytes(preflight.importedBytes)}")
            preflight.gradleVersion?.let { DetailRow("Gradle", it) }
            preflight.gradleTask?.let { DetailRow("Task", it) }
            if (preflight.compileSdks.isNotEmpty()) DetailRow("Android SDK", preflight.compileSdks.sorted().joinToString())
            preflight.distributionHost?.let { DetailRow("Distribution host", it) }
            if (preflight.sourceKind == SourceKind.ANDROID_GRADLE) {
                DetailRow("Wrapper checksum", if (preflight.distributionChecksumDeclared) "Declared" else "Not declared")
            }
            if (preflight.warnings.isNotEmpty()) {
                HorizontalDivider()
                Text("Warnings", fontWeight = FontWeight.Bold)
                preflight.warnings.forEach { warning ->
                    Text("• $warning", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ProjectsScreen(
    state: PocketBuildUiState,
    onOpenSource: () -> Unit,
    onBuildRequested: () -> Unit,
    onGrantFolder: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Projects", "Imported workspaces and explicit build preflight") }
        item { Button(onClick = onOpenSource, enabled = state.activeOperation?.status != OperationStatus.RUNNING) { Text("Import project") } }
        state.selectedSource?.let { item { SourceCard(it, state, onBuildRequested, onGrantFolder) } }
            ?: item { EmptyCard("No project selected", "Open a project file or ZIP to create a private workspace.") }
        state.preflight?.let { item { PreflightCard(it) } }
    }
}

@Composable
private fun BuildsScreen(
    state: PocketBuildUiState,
    compact: Boolean,
    onCancelOperation: () -> Unit,
    onClearOperation: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Builds", "Live stages, raw output, persistent errors and recent results") }
        if (compact) {
            item {
                OperationDiagnosticsPanel(
                    operation = state.activeOperation,
                    modifier = Modifier.fillMaxWidth(),
                    onCancel = onCancelOperation,
                    onClear = onClearOperation,
                    showTimeline = true,
                )
            }
        } else {
            state.activeOperation?.let { operation -> item { StageTimelineCard(operation) } }
                ?: item { EmptyCard("No active operation", "Start a workspace import, toolchain install or APK build.") }
        }
        state.latestBuild?.let { build ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Latest build", fontWeight = FontWeight.Bold)
                        Text(build.projectName, style = MaterialTheme.typography.titleMedium)
                        Text(build.status, color = MaterialTheme.colorScheme.primary)
                        Text(build.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        build.outputPath?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
        }
        item { SectionTitle("History") }
        if (state.operationHistory.isEmpty()) {
            item { EmptyCard("No completed operations", "Finished, failed and cancelled operations will remain visible here.") }
        } else {
            items(state.operationHistory, key = { it.id }) { history -> HistoryRow(history) }
        }
    }
}

@Composable
private fun ToolchainsScreen(
    toolchains: List<ToolchainPackSummary>,
    onToolchainAction: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Toolchains", "Component inventory, storage, verification and repair") }
        items(toolchains, key = { it.id }) { pack -> ToolchainCard(pack, onToolchainAction) }
    }
}

@Composable
private fun ToolchainCard(pack: ToolchainPackSummary, onToolchainAction: (String) -> Unit) {
    val working = pack.state == ToolchainState.VERIFYING
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pack.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(pack.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusLabel(pack.state)
            }
            pack.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            Text(pack.sizeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pack.installedBytes != null || pack.cacheBytes != null || pack.freeBytes != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pack.installedBytes?.let { Metric("Installed", formatBytes(it), Modifier.weight(1f)) }
                    pack.cacheBytes?.let { Metric("Cache", formatBytes(it), Modifier.weight(1f)) }
                    pack.freeBytes?.let { Metric("Free", formatBytes(it), Modifier.weight(1f)) }
                }
            }
            if (pack.components.isNotEmpty()) {
                HorizontalDivider()
                pack.components.forEach { component ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(buildString {
                                append(component.name)
                                component.version?.let { append(" · ").append(it) }
                            }, fontWeight = FontWeight.SemiBold)
                            Text(component.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusLabel(component.state)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onToolchainAction(pack.id) }, enabled = !working) {
                    if (working) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Working")
                    } else {
                        Text(if (pack.state == ToolchainState.READY) "Verify / repair" else "Install")
                    }
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
            pack.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        StatusLabel(pack.state)
    }
}

@Composable
private fun OperationDiagnosticsPanel(
    operation: OperationUiState?,
    modifier: Modifier,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    showTimeline: Boolean,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        if (operation == null) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Operation console", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("No operation is active. Progress, stage timing, errors and live output will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Surface
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(operation.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OperationStatusText(operation.status)
                }
                Text(formatDuration(operation.startedAtMillis, operation.finishedAtMillis), style = MaterialTheme.typography.bodySmall)
            }
            OperationProgress(operation.overallProgress, operation.status == OperationStatus.RUNNING)
            DetailRow("Stage", operation.stageTitle)
            operation.currentItem?.let { DetailRow("Current", it) }
            Text(operation.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (operation.completedBytes != null || operation.totalBytes != null || operation.bytesPerSecond != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    operation.completedBytes?.let { Metric("Transferred", formatBytes(it), Modifier.weight(1f)) }
                    operation.totalBytes?.let { Metric("Total", formatBytes(it), Modifier.weight(1f)) }
                    operation.bytesPerSecond?.let { Metric("Speed", "${formatBytes(it)}/s", Modifier.weight(1f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (operation.status == OperationStatus.RUNNING) {
                    OutlinedButton(onClick = onCancel, enabled = operation.cancellable) {
                        Text(if (operation.cancellable) "Cancel safely" else "Cannot cancel this stage")
                    }
                } else {
                    OutlinedButton(onClick = onClear) { Text("Clear console") }
                }
            }
            operation.error?.let { ErrorReportCard(it.summary, it.detail, it.exceptionType, it.logPath, it.existingDataSafe, it.retryRecommended) }
            if (showTimeline) StageTimeline(operation)
            HorizontalDivider()
            Text("Live log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (operation.logs.isEmpty()) {
                Text("No log lines yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        operation.logs.takeLast(80).forEach { line ->
                            Text(
                                "${formatClock(line.timestampMillis)} [${line.severity.name.lowercase()}] ${line.stage}: ${line.message}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (line.severity) {
                                    OperationSeverity.ERROR -> MaterialTheme.colorScheme.error
                                    OperationSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                                    OperationSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageTimelineCard(operation: OperationUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Stage timeline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            StageTimeline(operation)
        }
    }
}

@Composable
private fun StageTimeline(operation: OperationUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Stages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        operation.stages.forEach { stage ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    when (stage.status) {
                        OperationStageStatus.PENDING -> "○"
                        OperationStageStatus.RUNNING -> "●"
                        OperationStageStatus.SUCCEEDED -> "✓"
                        OperationStageStatus.FAILED -> "×"
                        OperationStageStatus.CANCELLED -> "−"
                    },
                    modifier = Modifier.width(28.dp),
                    color = when (stage.status) {
                        OperationStageStatus.FAILED -> MaterialTheme.colorScheme.error
                        OperationStageStatus.RUNNING, OperationStageStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Bold,
                )
                Column(Modifier.weight(1f)) {
                    Text(stage.title, fontWeight = if (stage.status == OperationStageStatus.RUNNING) FontWeight.Bold else FontWeight.Normal)
                    if (stage.detail.isNotBlank()) Text(stage.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val elapsed = stage.startedAtMillis?.let { start ->
                    stage.finishedAtMillis?.let { finish -> formatElapsed(finish - start) }
                }
                elapsed?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun ErrorReportCard(
    summary: String,
    detail: String,
    exceptionType: String?,
    logPath: String?,
    existingDataSafe: Boolean,
    retryRecommended: Boolean,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(summary, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(detail, color = MaterialTheme.colorScheme.onErrorContainer)
                    exceptionType?.let { Text("Exception: $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    logPath?.let { Text("Log: $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text(
                if (existingDataSafe) "Existing workspace and active toolchain were preserved." else "Existing data may require inspection.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (retryRecommended) "Retry is reasonable after correcting the reported cause." else "Do not retry until the underlying issue is understood.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HistoryRow(history: OperationHistoryItem) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(history.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${history.finalStage} · ${history.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatDateTime(history.finishedAtMillis), style = MaterialTheme.typography.labelSmall)
            }
            OperationStatusText(history.status)
        }
    }
}

@Composable
private fun SettingsScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Settings", "Downloads, storage, signing, confirmations and diagnostics") }
        item {
            EmptyCard(
                "Operational defaults",
                "Gradle execution requires an explicit confirmation. Errors remain in the operation console. Non-cancellable stages say so instead of presenting a fake cancel button.",
            )
        }
        item {
            EmptyCard(
                "Landscape-first layout",
                "Phones use a navigation rail, main workspace and persistent diagnostics panel when landscape width permits. Portrait remains a functional fallback.",
            )
        }
    }
}

@Composable
private fun OperationProgress(progress: Float?, running: Boolean) {
    when {
        progress != null -> LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        running -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else -> Unit
    }
}

@Composable
private fun OperationStatusText(status: OperationStatus) {
    val text = when (status) {
        OperationStatus.RUNNING -> "Running"
        OperationStatus.SUCCEEDED -> "Succeeded"
        OperationStatus.FAILED -> "Failed"
        OperationStatus.CANCELLED -> "Cancelled"
    }
    val color = when (status) {
        OperationStatus.FAILED -> MaterialTheme.colorScheme.error
        OperationStatus.CANCELLED -> MaterialTheme.colorScheme.tertiary
        OperationStatus.RUNNING, OperationStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
    }
    Text(text, color = color, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StatusLabel(state: ToolchainState) {
    val text = when (state) {
        ToolchainState.READY -> "Ready"
        ToolchainState.MISSING -> "Missing"
        ToolchainState.UPDATE_AVAILABLE -> "Update"
        ToolchainState.VERIFYING -> "Working"
    }
    val color = when (state) {
        ToolchainState.READY, ToolchainState.VERIFYING -> MaterialTheme.colorScheme.primary
        ToolchainState.MISSING, ToolchainState.UPDATE_AVAILABLE -> MaterialTheme.colorScheme.error
    }
    Text(text, color = color, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(132.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, modifier = Modifier.weight(1f)) }
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

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

private fun formatClock(timestamp: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestamp))

private fun formatDateTime(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

private fun formatDuration(startedAt: Long, finishedAt: Long?): String = formatElapsed(max(0L, (finishedAt ?: System.currentTimeMillis()) - startedAt))

private fun formatElapsed(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val minutes = seconds / 60
    val remainder = seconds % 60
    return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
}
