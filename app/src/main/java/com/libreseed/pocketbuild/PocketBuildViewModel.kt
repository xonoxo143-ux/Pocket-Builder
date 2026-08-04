package com.libreseed.pocketbuild

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libreseed.pocketbuild.godot.GodotProjectBuilder
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
    private val _uiState = MutableStateFlow(PocketBuildUiState())
    val uiState: StateFlow<PocketBuildUiState> = _uiState.asStateFlow()

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
        if (source.kind != SourceKind.GODOT) {
            _uiState.update {
                it.copy(
                    latestBuild = BuildSummary(source.displayName, "Unsupported", null, "This APK currently builds Godot projects."),
                    notice = "Choose a Godot project or Godot project ZIP.",
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
                    notice = "Choose the project folder so PocketBuild can import all sibling files.",
                )
            }
            return
        }
        prepareArchiveWorkspace(source)
    }

    fun importProjectTree(uri: Uri) {
        val source = _uiState.value.selectedSource ?: return
        if (_uiState.value.isBuilding) return
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
        if (_uiState.value.isBuilding) return
        _uiState.update {
            it.copy(
                isBuilding = true,
                buildProgress = 0f,
                buildStage = "Starting build",
                completedOutputPath = null,
                latestBuild = BuildSummary(
                    source.displayName,
                    "Building",
                    null,
                    "Preparing the bundled Godot 4.7 ARM64 runtime.",
                ),
                notice = "Building APK on this device…",
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    godotBuilder.build(workspace) { progress ->
                        _uiState.update { state ->
                            state.copy(
                                buildProgress = progress.fraction,
                                buildStage = progress.stage,
                                latestBuild = BuildSummary(
                                    source.displayName,
                                    progress.stage,
                                    null,
                                    progress.detail,
                                ),
                            )
                        }
                    }
                }
            }
            result.onSuccess { artifact ->
                val detail = "Packed ${artifact.packedFiles} files (${formatBytes(artifact.sourceBytes)}). Tap through Android's installer to install the game."
                _uiState.update {
                    it.copy(
                        isBuilding = false,
                        buildProgress = 1f,
                        buildStage = "APK ready",
                        latestBuild = BuildSummary(
                            projectName = source.displayName,
                            status = "APK ready",
                            outputName = artifact.file.name,
                            detail = detail,
                            outputPath = artifact.file.absolutePath,
                        ),
                        completedOutputPath = artifact.file.absolutePath,
                        notice = "APK created and signature verified.",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBuilding = false,
                        buildProgress = null,
                        buildStage = null,
                        latestBuild = BuildSummary(
                            source.displayName,
                            "Build failed",
                            null,
                            error.message ?: "Godot APK build failed.",
                        ),
                        notice = error.message ?: "Godot APK build failed.",
                    )
                }
            }
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
            title = "PocketBuild Core",
            subtitle = "Secure source import, private workspaces and output handling",
            sizeLabel = "Bundled",
            state = ToolchainState.READY,
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
            subtitle = "Persistent signing key protected by Android Keystore",
            sizeLabel = "Ready",
            state = ToolchainState.READY,
        ),
    ),
)
