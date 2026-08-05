package com.libreseed.pocketbuild

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libreseed.pocketbuild.godot.GodotProjectBuilder
import com.libreseed.pocketbuild.gradle.AndroidGradleProjectBuilder
import com.libreseed.pocketbuild.model.BuildConfirmation
import com.libreseed.pocketbuild.model.BuildSummary
import com.libreseed.pocketbuild.model.IncomingSource
import com.libreseed.pocketbuild.model.OperationErrorReport
import com.libreseed.pocketbuild.model.OperationHistoryItem
import com.libreseed.pocketbuild.model.OperationKind
import com.libreseed.pocketbuild.model.OperationLogLine
import com.libreseed.pocketbuild.model.OperationSeverity
import com.libreseed.pocketbuild.model.OperationStageStatus
import com.libreseed.pocketbuild.model.OperationStageSummary
import com.libreseed.pocketbuild.model.OperationStatus
import com.libreseed.pocketbuild.model.OperationUiState
import com.libreseed.pocketbuild.model.ProjectPreflightSummary
import com.libreseed.pocketbuild.model.SourceContainer
import com.libreseed.pocketbuild.model.SourceKind
import com.libreseed.pocketbuild.model.ToolchainPackSummary
import com.libreseed.pocketbuild.model.ToolchainState
import com.libreseed.pocketbuild.model.WorkspaceSummary
import com.libreseed.pocketbuild.runtime.OperationJournal
import com.libreseed.pocketbuild.source.AndroidSourceInspector
import com.libreseed.pocketbuild.workspace.WorkspaceManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PocketBuildViewModel(application: Application) : AndroidViewModel(application) {
    private val inspector = AndroidSourceInspector(application)
    private val workspaceManager = WorkspaceManager(application)
    private val godotBuilder = GodotProjectBuilder(application)
    private val gradleBuilder = AndroidGradleProjectBuilder(application)
    private val operationJournal = OperationJournal(application)
    private val _uiState = MutableStateFlow(
        PocketBuildUiState(
            operationHistory = runCatching { operationJournal.loadHistory(MAX_OPERATION_HISTORY) }
                .getOrDefault(emptyList()),
        ),
    )
    val uiState: StateFlow<PocketBuildUiState> = _uiState.asStateFlow()

    private var pendingBuild: PendingBuild? = null
    private var activeCancellation: AtomicBoolean? = null

    init {
        refreshGradleToolchainState()
    }

    fun acceptIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> sharedStreamUri(intent)
            else -> null
        }
        if (uri != null) inspect(uri)
    }

    @Suppress("DEPRECATION")
    private fun sharedStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    fun inspect(uri: Uri) {
        if (operationActive()) {
            _uiState.update { it.copy(notice = "Wait for the active operation to finish or cancel it first.") }
            return
        }
        pendingBuild = null
        _uiState.update {
            it.copy(
                isInspecting = true,
                selectedSource = null,
                workspace = null,
                preflight = null,
                buildConfirmation = null,
                latestBuild = null,
                completedOutputPath = null,
                needsFolderGrant = false,
                notice = "Inspecting source…",
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { inspector.inspect(uri) } }
            result.onSuccess { source ->
                _uiState.update {
                    it.copy(
                        isInspecting = false,
                        selectedSource = source,
                        notice = "Detected ${source.kind.displayName}",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isInspecting = false,
                        notice = error.message ?: "Could not inspect the selected source.",
                    )
                }
            }
        }
    }

    fun queueBuild() {
        val state = _uiState.value
        val source = state.selectedSource ?: return
        if (state.isInspecting || state.isPreparingWorkspace || operationActive()) {
            _uiState.update { it.copy(notice = "Another operation is already running.") }
            return
        }
        if (source.kind !in SUPPORTED_BUILD_KINDS) {
            _uiState.update {
                it.copy(
                    latestBuild = BuildSummary(
                        source.displayName,
                        "Unsupported",
                        null,
                        "PocketHost currently builds Godot projects and standard Android Gradle projects.",
                    ),
                    notice = "Choose a Godot or Android Gradle project.",
                )
            }
            return
        }

        state.workspace?.let { workspace ->
            requestBuild(source, workspace)
            return
        }
        if (source.container != SourceContainer.ARCHIVE) {
            _uiState.update {
                it.copy(
                    needsFolderGrant = true,
                    notice = "Choose the project folder so PocketHost can import all sibling files.",
                )
            }
            return
        }
        prepareArchiveWorkspace(source)
    }

    fun importProjectTree(uri: Uri) {
        val source = _uiState.value.selectedSource ?: return
        if (operationActive()) return
        val operationId = beginOperation(
            kind = OperationKind.WORKSPACE_IMPORT,
            title = "Import ${source.displayName}",
            firstStage = "Copying project folder",
            detail = "Copying the selected folder into private app storage.",
            cancellable = false,
            stages = listOf("Copying project folder", "Validating project", "Preparing preflight", "Workspace ready"),
        ) ?: return
        _uiState.update {
            it.copy(
                isPreparingWorkspace = true,
                needsFolderGrant = false,
                latestBuild = BuildSummary(source.displayName, "Preparing workspace", null, "Copying project files."),
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val workspace = workspaceManager.importTree(uri, source.displayName, source.kind)
                    updateOperation(operationId, "Preparing preflight", "Inspecting imported project requirements.", null, cancellable = false)
                    workspace to createPreflight(source, workspace)
                }
            }
            applyWorkspaceResult(source, operationId, result)
        }
    }

    fun installOrVerifyToolchain(id: String) {
        if (operationActive()) {
            _uiState.update { it.copy(notice = "Another build or toolchain operation is already running.") }
            return
        }

        if (id != GRADLE_TOOLCHAIN_ID) {
            val message = when (id) {
                "core" -> "PocketHost Core is bundled inside the signed APK and is ready."
                "godot-4.7-arm64" -> "The bundled Godot 4.7 ARM64 runtime is present."
                "local-signing" -> "Local APK signing support is bundled; its protected key is created when first needed."
                else -> "Unknown toolchain: $id"
            }
            _uiState.update { it.copy(notice = message) }
            return
        }

        val operationId = beginOperation(
            kind = OperationKind.TOOLCHAIN_INSTALL,
            title = "Local Android Gradle + Kotlin",
            firstStage = "Toolchain preflight",
            detail = "Checking architecture, storage, JDK, Android SDK and cached packages.",
            cancellable = false,
            stages = TOOLCHAIN_STAGES,
        ) ?: return
        setToolchainState(GRADLE_TOOLCHAIN_ID, ToolchainState.VERIFYING, "Starting installation…", 0f)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    gradleBuilder.installToolchain(setOf(36)) { progress ->
                        updateOperation(
                            operationId = operationId,
                            stage = progress.stage,
                            detail = progress.detail,
                            fraction = progress.fraction,
                            currentItem = progress.currentItem,
                            cancellable = false,
                            logLine = progress.logLine,
                        )
                        setToolchainState(
                            GRADLE_TOOLCHAIN_ID,
                            ToolchainState.VERIFYING,
                            "${progress.stage}: ${progress.detail}",
                            progress.fraction,
                        )
                    }
                }
            }
            result.onSuccess {
                refreshGradleToolchainState()
                finishOperationSuccess(operationId, "Toolchain ready", "JDK 17, Android SDK 36 and ARM64 build tools are installed and verified.")
                _uiState.update { current -> current.copy(notice = "Local Android Gradle and Kotlin toolchain installed and verified.") }
            }.onFailure { error ->
                refreshGradleToolchainState()
                finishOperationFailure(operationId, error, "Toolchain installation failed.", logPath = null)
            }
        }
    }

    fun confirmPendingBuild() {
        val build = pendingBuild ?: return
        pendingBuild = null
        _uiState.update { it.copy(buildConfirmation = null) }
        startBuild(build.source, build.workspace)
    }

    fun dismissBuildConfirmation() {
        pendingBuild = null
        _uiState.update { it.copy(buildConfirmation = null) }
    }

    fun cancelActiveOperation() {
        val operation = _uiState.value.activeOperation ?: return
        if (operation.status != OperationStatus.RUNNING || !operation.cancellable) {
            _uiState.update { it.copy(notice = "The current stage cannot be safely cancelled.") }
            return
        }
        val flag = activeCancellation
        if (flag == null) {
            _uiState.update { it.copy(notice = "Cancellation is not available for this operation.") }
            return
        }
        flag.set(true)
        updateOperation(
            operationId = operation.id,
            stage = operation.stageTitle,
            detail = "Cancellation requested. Waiting for the active process to stop safely…",
            fraction = operation.overallProgress,
            currentItem = operation.currentItem,
            cancellable = false,
            severity = OperationSeverity.WARNING,
        )
    }

    fun clearOperationReport() {
        if (_uiState.value.activeOperation?.status == OperationStatus.RUNNING) return
        _uiState.update { it.copy(activeOperation = null) }
    }

    private fun prepareArchiveWorkspace(source: IncomingSource) {
        val operationId = beginOperation(
            kind = OperationKind.WORKSPACE_IMPORT,
            title = "Import ${source.displayName}",
            firstStage = "Extracting archive",
            detail = "Safely extracting the selected archive into private app storage.",
            cancellable = false,
            stages = listOf("Extracting archive", "Validating project", "Preparing preflight", "Workspace ready"),
        ) ?: return
        _uiState.update {
            it.copy(
                isPreparingWorkspace = true,
                latestBuild = BuildSummary(source.displayName, "Preparing workspace", null, "Safely extracting and validating the project."),
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val workspace = workspaceManager.importArchive(source)
                    updateOperation(operationId, "Preparing preflight", "Inspecting imported project requirements.", null, cancellable = false)
                    workspace to createPreflight(source, workspace)
                }
            }
            applyWorkspaceResult(source, operationId, result)
        }
    }

    private fun applyWorkspaceResult(
        source: IncomingSource,
        operationId: String,
        result: Result<Pair<WorkspaceSummary, ProjectPreflightSummary>>,
    ) {
        result.onSuccess { (workspace, preflight) ->
            _uiState.update {
                it.copy(
                    isPreparingWorkspace = false,
                    workspace = workspace,
                    preflight = preflight,
                    latestBuild = BuildSummary(
                        projectName = source.displayName,
                        status = "Workspace ready",
                        outputName = null,
                        detail = "Imported ${workspace.importedFiles} files. Review preflight, then tap Build APK.",
                    ),
                    notice = "Workspace prepared. No project code has been executed yet.",
                )
            }
            finishOperationSuccess(operationId, "Workspace ready", "Imported ${workspace.importedFiles} files and completed preflight inspection.")
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    isPreparingWorkspace = false,
                    latestBuild = BuildSummary(source.displayName, "Import failed", null, error.message ?: "Workspace import failed."),
                    notice = error.message ?: "Workspace import failed.",
                )
            }
            finishOperationFailure(operationId, error, "Workspace import failed.")
        }
    }

    private fun createPreflight(source: IncomingSource, workspace: WorkspaceSummary): ProjectPreflightSummary {
        return when (workspace.sourceKind) {
            SourceKind.ANDROID_GRADLE -> {
                val requirements = gradleBuilder.inspectProject(workspace)
                ProjectPreflightSummary(
                    projectName = workspace.displayName,
                    sourceKind = workspace.sourceKind,
                    workspacePath = workspace.rootPath,
                    importedFiles = workspace.importedFiles,
                    importedBytes = workspace.importedBytes,
                    gradleVersion = requirements.gradleVersion,
                    gradleTask = requirements.task,
                    compileSdks = requirements.compileSdks,
                    distributionHost = requirements.distributionHost,
                    distributionChecksumDeclared = requirements.distributionSha256Sum != null,
                    warnings = source.warnings + requirements.warnings,
                )
            }
            else -> ProjectPreflightSummary(
                projectName = workspace.displayName,
                sourceKind = workspace.sourceKind,
                workspacePath = workspace.rootPath,
                importedFiles = workspace.importedFiles,
                importedBytes = workspace.importedBytes,
                warnings = source.warnings,
            )
        }
    }

    private fun requestBuild(source: IncomingSource, workspace: WorkspaceSummary) {
        if (source.kind == SourceKind.ANDROID_GRADLE) {
            pendingBuild = PendingBuild(source, workspace)
            _uiState.update {
                it.copy(
                    buildConfirmation = BuildConfirmation(
                        title = "Run this Gradle project?",
                        message = "Gradle wrappers, plugins and build scripts are executable project code. PocketBuild will run them inside its app sandbox after you confirm.",
                        riskDetails = listOf(
                            "The project can use PocketBuild's network access while resolving Gradle and Maven dependencies.",
                            "Build logic can read and modify this imported workspace and PocketBuild's private build caches.",
                            "The generated APK still goes through signature verification and Android's installer confirmation.",
                        ),
                    ),
                )
            }
        } else {
            startBuild(source, workspace)
        }
    }

    private fun startBuild(source: IncomingSource, workspace: WorkspaceSummary) {
        when (source.kind) {
            SourceKind.GODOT -> startGodotBuild(source, workspace)
            SourceKind.ANDROID_GRADLE -> startGradleBuild(source, workspace)
            else -> Unit
        }
    }

    private fun startGodotBuild(source: IncomingSource, workspace: WorkspaceSummary) {
        val operationId = beginOperation(
            kind = OperationKind.GODOT_BUILD,
            title = "Build ${source.displayName}",
            firstStage = "Preparing template",
            detail = "Loading the bundled Godot 4.7 ARM64 runtime.",
            cancellable = false,
            stages = listOf("Preparing template", "Packing project", "Creating APK", "Signing APK", "APK ready"),
        ) ?: return
        beginBuildState(source, "Preparing the bundled Godot 4.7 ARM64 runtime.")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    godotBuilder.build(workspace) { progress ->
                        reportBuildProgress(source, progress.stage, progress.detail, progress.fraction)
                        updateOperation(operationId, progress.stage, progress.detail, progress.fraction, cancellable = false)
                    }
                }
            }
            result.onSuccess { artifact ->
                val detail = "Packed ${artifact.packedFiles} files (${formatBytes(artifact.sourceBytes)}). Android will confirm installation."
                completeBuild(source, artifact.file, detail)
                finishOperationSuccess(operationId, "APK ready", detail, artifact.file.absolutePath)
            }.onFailure {
                failBuild(source, it, "Godot APK build failed.")
                finishOperationFailure(operationId, it, "Godot APK build failed.")
            }
        }
    }

    private fun startGradleBuild(source: IncomingSource, workspace: WorkspaceSummary) {
        val operationId = beginOperation(
            kind = OperationKind.GRADLE_BUILD,
            title = "Build ${source.displayName}",
            firstStage = "Inspecting Gradle project",
            detail = "Reading wrapper, plugins and Android SDK requirements.",
            cancellable = false,
            stages = GRADLE_STAGES,
        ) ?: return
        beginBuildState(source, "Checking the local JDK, Android SDK and Gradle wrapper.")
        val cancellation = AtomicBoolean(false)
        activeCancellation = cancellation
        setToolchainState(GRADLE_TOOLCHAIN_ID, ToolchainState.VERIFYING, "Checking requirements", 0f)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    gradleBuilder.build(workspace, cancellation) { progress ->
                        reportBuildProgress(source, progress.stage, progress.detail, progress.fraction)
                        updateOperation(
                            operationId = operationId,
                            stage = progress.stage,
                            detail = progress.detail,
                            fraction = progress.fraction,
                            currentItem = progress.currentItem,
                            cancellable = progress.cancellable,
                            logLine = progress.logLine,
                        )
                        if (progress.fraction != null && progress.fraction <= 0.72f) {
                            setToolchainState(
                                GRADLE_TOOLCHAIN_ID,
                                ToolchainState.VERIFYING,
                                "${progress.stage}: ${progress.detail}",
                                progress.fraction / 0.72f,
                            )
                        }
                    }
                }
            }
            activeCancellation = null
            result.onSuccess { artifact ->
                refreshGradleToolchainState()
                val detail = buildString {
                    append("Built locally with the project's Gradle wrapper")
                    artifact.gradleVersion?.let { append(" ($it)") }
                    append(". Full log: ").append(artifact.logFile.absolutePath)
                }
                completeBuild(source, artifact.file, detail)
                finishOperationSuccess(operationId, "APK ready", detail, artifact.file.absolutePath)
            }.onFailure { error ->
                refreshGradleToolchainState()
                val cancelled = cancellation.get() || error.message?.contains("cancel", ignoreCase = true) == true
                if (cancelled) {
                    cancelBuild(source, error.message ?: "Build cancelled.")
                    finishOperationCancelled(operationId, error.message ?: "Build cancelled.")
                } else {
                    failBuild(source, error, "Android Gradle build failed.")
                    finishOperationFailure(
                        operationId,
                        error,
                        "Android Gradle build failed.",
                        extractLogPath(error.message),
                    )
                }
            }
        }
    }

    private fun beginBuildState(source: IncomingSource, detail: String) {
        _uiState.update {
            it.copy(
                isBuilding = true,
                buildProgress = 0f,
                buildStage = "Starting build",
                completedOutputPath = null,
                latestBuild = BuildSummary(source.displayName, "Building", null, detail),
                notice = "Building APK on this device…",
            )
        }
    }

    private fun reportBuildProgress(source: IncomingSource, stage: String, detail: String, fraction: Float?) {
        _uiState.update { state ->
            state.copy(
                buildProgress = fraction,
                buildStage = stage,
                latestBuild = BuildSummary(source.displayName, stage, null, detail),
            )
        }
    }

    private fun completeBuild(source: IncomingSource, output: File, detail: String) {
        _uiState.update {
            it.copy(
                isBuilding = false,
                buildProgress = 1f,
                buildStage = "APK ready",
                latestBuild = BuildSummary(source.displayName, "APK ready", output.name, detail, output.absolutePath),
                completedOutputPath = output.absolutePath,
                notice = "APK created and signature verified.",
            )
        }
    }

    private fun cancelBuild(source: IncomingSource, message: String) {
        _uiState.update {
            it.copy(
                isBuilding = false,
                buildProgress = null,
                buildStage = "Cancelled",
                latestBuild = BuildSummary(source.displayName, "Build cancelled", null, message),
                notice = message,
            )
        }
    }

    private fun failBuild(source: IncomingSource, error: Throwable, fallback: String) {
        val message = error.message ?: fallback
        _uiState.update {
            it.copy(
                isBuilding = false,
                buildProgress = null,
                buildStage = "Failed",
                latestBuild = BuildSummary(source.displayName, "Build failed", null, message),
                notice = message,
            )
        }
    }

    private fun beginOperation(
        kind: OperationKind,
        title: String,
        firstStage: String,
        detail: String,
        cancellable: Boolean,
        stages: List<String>,
    ): String? {
        if (operationActive()) return null
        val now = System.currentTimeMillis()
        val initialLog = OperationLogLine(now, OperationSeverity.INFO, firstStage, detail)
        val stageModels = stages.distinct().map { stage ->
            OperationStageSummary(
                id = stageId(stage),
                title = stage,
                status = if (stage == firstStage) OperationStageStatus.RUNNING else OperationStageStatus.PENDING,
                detail = if (stage == firstStage) detail else "",
                startedAtMillis = if (stage == firstStage) now else null,
            )
        }
        val operation = OperationUiState(
            kind = kind,
            title = title,
            stageTitle = firstStage,
            detail = detail,
            cancellable = cancellable,
            stages = stageModels,
            logs = listOf(initialLog),
        )
        _uiState.update { it.copy(activeOperation = operation) }
        appendJournal(operation.id, initialLog)
        return operation.id
    }

    private fun updateOperation(
        operationId: String,
        stage: String,
        detail: String,
        fraction: Float?,
        currentItem: String? = null,
        cancellable: Boolean,
        logLine: String? = null,
        severity: OperationSeverity = OperationSeverity.INFO,
    ) {
        val now = System.currentTimeMillis()
        var journalLine: OperationLogLine? = null
        _uiState.update { state ->
            val current = state.activeOperation
            if (current == null || current.id != operationId || current.status != OperationStatus.RUNNING) return@update state
            val targetId = stageId(stage)
            var found = false
            val advanced = current.stages.map { item ->
                when {
                    item.id == targetId -> {
                        found = true
                        item.copy(
                            status = OperationStageStatus.RUNNING,
                            detail = detail,
                            startedAtMillis = item.startedAtMillis ?: now,
                            finishedAtMillis = null,
                        )
                    }
                    item.status == OperationStageStatus.RUNNING -> item.copy(
                        status = OperationStageStatus.SUCCEEDED,
                        finishedAtMillis = now,
                    )
                    else -> item
                }
            }.toMutableList()
            if (!found) {
                advanced += OperationStageSummary(
                    id = targetId,
                    title = stage,
                    status = OperationStageStatus.RUNNING,
                    detail = detail,
                    startedAtMillis = now,
                )
            }
            val message = logLine ?: detail
            val newLog = OperationLogLine(now, severity, stage, message)
            val duplicate = current.logs.lastOrNull()?.message == newLog.message && current.logs.lastOrNull()?.stage == stage
            val logs = if (duplicate) {
                current.logs
            } else {
                journalLine = newLog
                (current.logs + newLog).takeLast(MAX_OPERATION_LOG_LINES)
            }
            state.copy(
                activeOperation = current.copy(
                    overallProgress = fraction?.coerceIn(0f, 1f),
                    stageTitle = stage,
                    detail = detail,
                    currentItem = currentItem,
                    cancellable = cancellable,
                    stages = advanced,
                    logs = logs,
                ),
            )
        }
        journalLine?.let { appendJournal(operationId, it) }
    }

    private fun finishOperationSuccess(
        operationId: String,
        stage: String,
        detail: String,
        outputPath: String? = null,
    ) {
        finishOperation(operationId, OperationStatus.SUCCEEDED, stage, detail, outputPath = outputPath)
    }

    private fun finishOperationCancelled(operationId: String, detail: String) {
        finishOperation(operationId, OperationStatus.CANCELLED, "Cancelled", detail)
    }

    private fun finishOperationFailure(
        operationId: String,
        error: Throwable,
        fallback: String,
        logPath: String? = null,
    ) {
        val message = error.message ?: fallback
        finishOperation(
            operationId = operationId,
            status = OperationStatus.FAILED,
            stage = "Failed",
            detail = message,
            error = OperationErrorReport(
                summary = fallback,
                detail = message,
                exceptionType = error::class.java.name,
                logPath = logPath ?: operationJournal.logFile(operationId).absolutePath,
                existingDataSafe = true,
                retryRecommended = true,
            ),
        )
    }

    private fun finishOperation(
        operationId: String,
        status: OperationStatus,
        stage: String,
        detail: String,
        outputPath: String? = null,
        error: OperationErrorReport? = null,
    ) {
        val now = System.currentTimeMillis()
        val severity = when (status) {
            OperationStatus.FAILED -> OperationSeverity.ERROR
            OperationStatus.CANCELLED -> OperationSeverity.WARNING
            OperationStatus.RUNNING, OperationStatus.SUCCEEDED -> OperationSeverity.INFO
        }
        val terminalLog = OperationLogLine(now, severity, stage, detail)
        var historyToPersist: List<OperationHistoryItem>? = null
        _uiState.update { state ->
            val current = state.activeOperation
            if (current == null || current.id != operationId) return@update state
            val terminalStageStatus = when (status) {
                OperationStatus.SUCCEEDED -> OperationStageStatus.SUCCEEDED
                OperationStatus.FAILED -> OperationStageStatus.FAILED
                OperationStatus.CANCELLED -> OperationStageStatus.CANCELLED
                OperationStatus.RUNNING -> OperationStageStatus.RUNNING
            }
            val stages = current.stages.map {
                if (it.status == OperationStageStatus.RUNNING) {
                    it.copy(status = terminalStageStatus, detail = detail, finishedAtMillis = now)
                } else {
                    it
                }
            }
            val finished = current.copy(
                status = status,
                finishedAtMillis = now,
                overallProgress = if (status == OperationStatus.SUCCEEDED) 1f else current.overallProgress,
                stageTitle = stage,
                detail = detail,
                cancellable = false,
                stages = stages,
                logs = (current.logs + terminalLog).takeLast(MAX_OPERATION_LOG_LINES),
                error = error,
                outputPath = outputPath,
            )
            val history = OperationHistoryItem(
                id = finished.id,
                kind = finished.kind,
                title = finished.title,
                status = finished.status,
                startedAtMillis = finished.startedAtMillis,
                finishedAtMillis = now,
                finalStage = stage,
                detail = detail,
                outputPath = outputPath,
                errorSummary = error?.summary,
            )
            val nextHistory = (listOf(history) + state.operationHistory.filterNot { it.id == history.id })
                .take(MAX_OPERATION_HISTORY)
            historyToPersist = nextHistory
            state.copy(
                activeOperation = finished,
                operationHistory = nextHistory,
            )
        }
        appendJournal(operationId, terminalLog)
        historyToPersist?.let(::saveHistory)
    }

    private fun appendJournal(operationId: String, line: OperationLogLine) {
        runCatching { operationJournal.append(operationId, line) }
    }

    private fun saveHistory(history: List<OperationHistoryItem>) {
        runCatching { operationJournal.saveHistory(history) }
    }

    private fun refreshGradleToolchainState() {
        val snapshot = runCatching { gradleBuilder.toolchainSnapshot(setOf(36)) }.getOrNull()
        val ready = snapshot?.ready == true
        _uiState.update { current ->
            current.copy(
                toolchains = current.toolchains.map { pack ->
                    if (pack.id != GRADLE_TOOLCHAIN_ID) return@map pack
                    pack.copy(
                        state = if (ready) ToolchainState.READY else ToolchainState.MISSING,
                        sizeLabel = if (ready) "${formatBytes(snapshot.installedBytes)} installed" else "Downloaded on first build",
                        progress = null,
                        installedBytes = snapshot?.installedBytes,
                        cacheBytes = snapshot?.cacheBytes,
                        freeBytes = snapshot?.freeBytes,
                        lastVerifiedAtMillis = if (ready) System.currentTimeMillis() else pack.lastVerifiedAtMillis,
                        components = snapshot?.components.orEmpty(),
                    )
                },
            )
        }
    }

    private fun setToolchainState(
        id: String,
        state: ToolchainState,
        sizeLabel: String,
        progress: Float? = null,
    ) {
        _uiState.update { current ->
            current.copy(
                toolchains = current.toolchains.map { pack ->
                    if (pack.id == id) pack.copy(state = state, sizeLabel = sizeLabel, progress = progress?.coerceIn(0f, 1f)) else pack
                },
            )
        }
    }

    private fun operationActive(): Boolean = _uiState.value.activeOperation?.status == OperationStatus.RUNNING

    fun consumeCompletedOutput() {
        _uiState.update { it.copy(completedOutputPath = null) }
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun extractLogPath(message: String?): String? {
        if (message.isNullOrBlank()) return null
        return Regex("Build log: (.+)$").find(message)?.groupValues?.getOrNull(1)
    }

    private fun stageId(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "stage" }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KiB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }

    private data class PendingBuild(val source: IncomingSource, val workspace: WorkspaceSummary)

    companion object {
        private const val GRADLE_TOOLCHAIN_ID = "android-gradle-local"
        private const val MAX_OPERATION_LOG_LINES = 400
        private const val MAX_OPERATION_HISTORY = 30
        private val SUPPORTED_BUILD_KINDS = setOf(SourceKind.GODOT, SourceKind.ANDROID_GRADLE)
        private val TOOLCHAIN_STAGES = listOf(
            "Toolchain preflight",
            "Checking JDK",
            "Installing JDK",
            "Installing Android tools",
            "Relocating JDK",
            "Installing Android 36 SDK",
            "Toolchain ready",
        )
        private val GRADLE_STAGES = listOf(
            "Inspecting Gradle project",
            "Checking JDK",
            "Installing JDK",
            "Installing Android tools",
            "Relocating JDK",
            "Installing Android 36 SDK",
            "Running Gradle",
            "Finding APK",
            "Signing APK",
            "Verifying APK",
            "APK ready",
        )
    }
}

data class PocketBuildUiState(
    val isInspecting: Boolean = false,
    val isPreparingWorkspace: Boolean = false,
    val isBuilding: Boolean = false,
    val buildProgress: Float? = null,
    val buildStage: String? = null,
    val needsFolderGrant: Boolean = false,
    val selectedSource: IncomingSource? = null,
    val latestBuild: BuildSummary? = null,
    val workspace: WorkspaceSummary? = null,
    val preflight: ProjectPreflightSummary? = null,
    val buildConfirmation: BuildConfirmation? = null,
    val activeOperation: OperationUiState? = null,
    val operationHistory: List<OperationHistoryItem> = emptyList(),
    val completedOutputPath: String? = null,
    val notice: String? = null,
    val toolchains: List<ToolchainPackSummary> = listOf(
        ToolchainPackSummary(
            id = "core",
            title = "PocketHost Core",
            subtitle = "Secure source import, private workspaces and output handling",
            sizeLabel = "Bundled",
            state = ToolchainState.READY,
        ),
        ToolchainPackSummary(
            id = "android-gradle-local",
            title = "Local Android Gradle + Kotlin",
            subtitle = "ARM64 OpenJDK 17, Gradle wrapper, Android SDK and Maven dependency cache",
            sizeLabel = "Downloaded on first build",
            state = ToolchainState.MISSING,
        ),
        ToolchainPackSummary(
            id = "godot-4.7-arm64",
            title = "Godot 4.7 ARM64 Runtime",
            subtitle = "Official Godot runtime used to produce standalone game APKs",
            sizeLabel = "Bundled",
            state = ToolchainState.READY,
        ),
        ToolchainPackSummary(
            id = "local-signing",
            title = "Local APK Signing",
            subtitle = "Persistent generated-app key protected by Android Keystore",
            sizeLabel = "Ready",
            state = ToolchainState.READY,
        ),
    ),
)
