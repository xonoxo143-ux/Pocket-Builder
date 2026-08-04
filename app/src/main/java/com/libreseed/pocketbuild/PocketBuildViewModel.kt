package com.libreseed.pocketbuild

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libreseed.pocketbuild.godot.GodotProjectBuilder
import com.libreseed.pocketbuild.gradle.AndroidGradleProjectBuilder
import com.libreseed.pocketbuild.model.BuildSummary
import com.libreseed.pocketbuild.model.IncomingSource
import com.libreseed.pocketbuild.model.SourceContainer
import com.libreseed.pocketbuild.model.SourceKind
import com.libreseed.pocketbuild.model.ToolchainPackSummary
import com.libreseed.pocketbuild.model.ToolchainState
import com.libreseed.pocketbuild.model.WorkspaceSummary
import com.libreseed.pocketbuild.source.AndroidSourceInspector
import com.libreseed.pocketbuild.workspace.WorkspaceManager
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
    private val _uiState = MutableStateFlow(PocketBuildUiState())
    val uiState: StateFlow<PocketBuildUiState> = _uiState.asStateFlow()

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
        if (_uiState.value.isBuilding) return
        _uiState.update {
            it.copy(
                isInspecting = true,
                selectedSource = null,
                workspace = null,
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
        if (state.isBuilding || state.isPreparingWorkspace) return
        if (toolchainOperationActive()) {
            _uiState.update { it.copy(notice = "Wait for the current toolchain operation to finish.") }
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
            startBuild(source, workspace)
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
        if (_uiState.value.isBuilding || toolchainOperationActive()) return
        _uiState.update {
            it.copy(
                isPreparingWorkspace = true,
                needsFolderGrant = false,
                notice = "Importing project folder…",
                latestBuild = BuildSummary(
                    source.displayName,
                    "Preparing workspace",
                    null,
                    "Copying the project into private app storage.",
                ),
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { workspaceManager.importTree(uri, source.displayName, source.kind) }
            }
            applyWorkspaceResult(source, result)
        }
    }

    fun installOrVerifyToolchain(id: String) {
        val state = _uiState.value
        if (state.isBuilding || state.isPreparingWorkspace || toolchainOperationActive()) {
            _uiState.update { it.copy(notice = "Another build or toolchain operation is already running.") }
            return
        }

        if (id != GRADLE_TOOLCHAIN_ID) {
            val message = when (id) {
                "core" -> "PocketHost Core is bundled inside the signed APK and is ready."
                "godot-4.7-arm64" -> "The bundled Godot 4.7 ARM64 runtime passed APK build verification."
                "local-signing" -> "Local APK signing support is bundled; its protected key is created when first needed."
                else -> "Unknown toolchain: $id"
            }
            _uiState.update { it.copy(notice = message) }
            return
        }

        setToolchainState(GRADLE_TOOLCHAIN_ID, ToolchainState.VERIFYING, "Starting installation…")
        _uiState.update { it.copy(notice = "Starting the local Android Gradle and Kotlin toolchain…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    gradleBuilder.installToolchain(setOf(36)) { progress ->
                        val detail = buildString {
                            append(progress.stage)
                            if (progress.detail.isNotBlank()) append(": ").append(progress.detail)
                            progress.fraction?.let { append(" · ").append((it * 100).toInt().coerceIn(0, 100)).append('%') }
                        }
                        setToolchainState(GRADLE_TOOLCHAIN_ID, ToolchainState.VERIFYING, detail)
                    }
                }
            }
            result.onSuccess {
                setToolchainState(GRADLE_TOOLCHAIN_ID, ToolchainState.READY, "Installed and verified")
                _uiState.update { current ->
                    current.copy(notice = "Local Android Gradle and Kotlin toolchain installed and verified.")
                }
            }.onFailure { error ->
                val message = error.message ?: "Toolchain installation failed."
                setToolchainState(
                    GRADLE_TOOLCHAIN_ID,
                    ToolchainState.MISSING,
                    "Install failed: ${message.take(180)}",
                )
                _uiState.update { current -> current.copy(notice = message) }
            }
        }
    }

    private fun prepareArchiveWorkspace(source: IncomingSource) {
        _uiState.update {
            it.copy(
                isPreparingWorkspace = true,
                notice = "Preparing private workspace…",
                latestBuild = BuildSummary(
                    source.displayName,
                    "Preparing workspace",
                    null,
                    "Safely extracting and validating the project.",
                ),
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { workspaceManager.importArchive(source) } }
            applyWorkspaceResult(source, result)
        }
    }

    private fun applyWorkspaceResult(source: IncomingSource, result: Result<WorkspaceSummary>) {
        result.onSuccess { workspace ->
            _uiState.update {
                it.copy(
                    isPreparingWorkspace = false,
                    workspace = workspace,
                    latestBuild = BuildSummary(
                        projectName = source.displayName,
                        status = "Workspace ready",
                        outputName = null,
                        detail = "Imported ${workspace.importedFiles} files. Starting the APK build.",
                    ),
                    notice = "Workspace prepared successfully.",
                )
            }
            startBuild(source, workspace)
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    isPreparingWorkspace = false,
                    latestBuild = BuildSummary(
                        source.displayName,
                        "Import failed",
                        null,
                        error.message ?: "Workspace import failed.",
                    ),
                    notice = error.message ?: "Workspace import failed.",
                )
            }
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
        if (!beginBuild(source, "Preparing the bundled Godot 4.7 ARM64 runtime.")) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    godotBuilder.build(workspace) { progress ->
                        reportProgress(source, progress.stage, progress.detail, progress.fraction)
                    }
                }
            }
            result.onSuccess { artifact ->
                completeBuild(
                    source = source,
                    output = artifact.file,
                    detail = "Packed ${artifact.packedFiles} files (${formatBytes(artifact.sourceBytes)}). Tap through Android's installer to install the game.",
                )
            }.onFailure { failBuild(source, it, "Godot APK build failed.") }
        }
    }

    private fun startGradleBuild(source: IncomingSource, workspace: WorkspaceSummary) {
        if (!beginBuild(source, "Checking the local JDK, Android SDK and Gradle wrapper.")) return
        setToolchainState(GRADLE_TOOLCHAIN_ID, ToolchainState.VERIFYING, "Installing or checking")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    gradleBuilder.build(workspace) { progress ->
                        reportProgress(source, progress.stage, progress.detail, progress.fraction)
                        if (progress.fraction != null && progress.fraction <= 0.72f) {
                            setToolchainState(
                                GRADLE_TOOLCHAIN_ID,
                                ToolchainState.VERIFYING,
                                "${progress.stage}: ${progress.detail}",
                            )
                        }
                    }
                }
            }
            result.onSuccess { artifact ->
                setToolchainState(GRADLE_TOOLCHAIN_ID, ToolchainState.READY, "Installed and verified")
                completeBuild(
                    source = source,
                    output = artifact.file,
                    detail = buildString {
                        append("Built locally with the project's Gradle wrapper")
                        artifact.gradleVersion?.let { append(" ($it)") }
                        append(". Log: ").append(artifact.logFile.name)
                    },
                )
            }.onFailure {
                refreshGradleToolchainState()
                failBuild(source, it, "Android Gradle build failed.")
            }
        }
    }

    private fun beginBuild(source: IncomingSource, detail: String): Boolean {
        if (_uiState.value.isBuilding || toolchainOperationActive()) return false
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
        return true
    }

    private fun reportProgress(source: IncomingSource, stage: String, detail: String, fraction: Float?) {
        _uiState.update { state ->
            state.copy(
                buildProgress = fraction,
                buildStage = stage,
                latestBuild = BuildSummary(source.displayName, stage, null, detail),
            )
        }
    }

    private fun completeBuild(source: IncomingSource, output: java.io.File, detail: String) {
        _uiState.update {
            it.copy(
                isBuilding = false,
                buildProgress = 1f,
                buildStage = "APK ready",
                latestBuild = BuildSummary(
                    projectName = source.displayName,
                    status = "APK ready",
                    outputName = output.name,
                    detail = detail,
                    outputPath = output.absolutePath,
                ),
                completedOutputPath = output.absolutePath,
                notice = "APK created and signature verified.",
            )
        }
    }

    private fun failBuild(source: IncomingSource, error: Throwable, fallback: String) {
        val message = error.message ?: fallback
        _uiState.update {
            it.copy(
                isBuilding = false,
                buildProgress = null,
                buildStage = null,
                latestBuild = BuildSummary(source.displayName, "Build failed", null, message),
                notice = message,
            )
        }
    }

    private fun refreshGradleToolchainState() {
        val ready = runCatching { gradleBuilder.isToolchainReady() }.getOrDefault(false)
        setToolchainState(
            GRADLE_TOOLCHAIN_ID,
            if (ready) ToolchainState.READY else ToolchainState.MISSING,
            if (ready) "Installed and verified" else "Downloaded on first build",
        )
    }

    private fun toolchainOperationActive(): Boolean {
        return _uiState.value.toolchains.any { it.state == ToolchainState.VERIFYING }
    }

    private fun setToolchainState(id: String, state: ToolchainState, sizeLabel: String) {
        _uiState.update { current ->
            current.copy(
                toolchains = current.toolchains.map { pack ->
                    if (pack.id == id) pack.copy(state = state, sizeLabel = sizeLabel) else pack
                },
            )
        }
    }

    fun consumeCompletedOutput() {
        _uiState.update { it.copy(completedOutputPath = null) }
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024) return "%.1f KiB".format(kib)
        return "%.1f MiB".format(kib / 1024.0)
    }

    companion object {
        private const val GRADLE_TOOLCHAIN_ID = "android-gradle-local"
        private val SUPPORTED_BUILD_KINDS = setOf(SourceKind.GODOT, SourceKind.ANDROID_GRADLE)
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
