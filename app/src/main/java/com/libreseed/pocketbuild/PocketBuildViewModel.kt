package com.libreseed.pocketbuild

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libreseed.pocketbuild.model.BuildSummary
import com.libreseed.pocketbuild.model.IncomingSource
import com.libreseed.pocketbuild.model.ToolchainPackSummary
import com.libreseed.pocketbuild.model.ToolchainState
import com.libreseed.pocketbuild.source.AndroidSourceInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PocketBuildViewModel(application: Application) : AndroidViewModel(application) {
    private val inspector = AndroidSourceInspector(application)
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
        _uiState.update { it.copy(isInspecting = true, notice = "Inspecting source…") }
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
        _uiState.update {
            it.copy(
                latestBuild = BuildSummary(
                    projectName = source.displayName,
                    status = "Planning",
                    outputName = null,
                    detail = "The build coordinator is not wired to a toolchain yet.",
                ),
                notice = "Build plan created. Toolchain execution is the next implementation slice.",
            )
        }
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }
}

data class PocketBuildUiState(
    val isInspecting: Boolean = false,
    val selectedSource: IncomingSource? = null,
    val latestBuild: BuildSummary? = null,
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
