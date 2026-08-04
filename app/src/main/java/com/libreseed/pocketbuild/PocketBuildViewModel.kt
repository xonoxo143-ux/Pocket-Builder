package com.libreseed.pocketbuild

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libreseed.pocketbuild.model.BuildSummary
import com.libreseed.pocketbuild.model.IncomingSource
import com.libreseed.pocketbuild.model.SourceContainer
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
        _uiState.update {
            it.copy(
                isInspecting = true,
                selectedSource = null,
                workspace = null,
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
        val source = _uiState.value.selectedSource ?: return
        if (_uiState.value.workspace != null) {
            _uiState.update {
                it.copy(
                    latestBuild = BuildSummary(
                        projectName = source.displayName,
                        status = "Waiting for builder pack",
                        outputName = null,
                        detail = "The private workspace is ready. Toolchain installation and execution are the next implementation slice.",
                    ),
                    notice = "Workspace is ready; the builder runtime is not connected yet.",
                )
            }
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
                        detail = "Imported ${workspace.importedFiles} files. Builder execution is the next stage.",
                    ),
                    notice = "Workspace prepared successfully.",
                )
            }
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

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }
}

data class PocketBuildUiState(
    val isInspecting: Boolean = false,
    val isPreparingWorkspace: Boolean = false,
    val needsFolderGrant: Boolean = false,
    val selectedSource: IncomingSource? = null,
    val latestBuild: BuildSummary? = null,
    val workspace: WorkspaceSummary? = null,
    val notice: String? = null,
    val toolchains: List<ToolchainPackSummary> = listOf(
        ToolchainPackSummary(
            id = "core",
            title = "PocketBuild Core",
            subtitle = "Source inspection, workspaces, signing and install flow",
            sizeLabel = "Bundled",
            state = ToolchainState.READY,
        ),
        ToolchainPackSummary(
            id = "godot-4.7-export",
            title = "Godot 4.7 Export Pack",
            subtitle = "Prebuilt Android templates for fast Godot APK exports",
            sizeLabel = "Not installed",
            state = ToolchainState.MISSING,
        ),
        ToolchainPackSummary(
            id = "android-gradle",
            title = "Android Gradle Pack",
            subtitle = "JDK, SDK, Build Tools and Gradle runtime",
            sizeLabel = "Not installed",
            state = ToolchainState.MISSING,
        ),
    ),
)
