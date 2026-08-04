package com.libreseed.pocketbuild.model

import android.net.Uri

enum class SourceKind(val displayName: String) {
    GODOT("Godot project"),
    ANDROID_GRADLE("Android Gradle project"),
    POCKETBUILD_RECIPE("PocketBuild recipe"),
    ZIP_UNKNOWN("Unrecognized archive"),
    UNKNOWN("Unsupported source"),
}

data class IncomingSource(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val kind: SourceKind,
    val projectRootHint: String? = null,
    val warnings: List<String> = emptyList(),
)

enum class ToolchainState {
    READY,
    MISSING,
    UPDATE_AVAILABLE,
    VERIFYING,
}

data class ToolchainPackSummary(
    val id: String,
    val title: String,
    val subtitle: String,
    val sizeLabel: String,
    val state: ToolchainState,
)

data class BuildSummary(
    val projectName: String,
    val status: String,
    val outputName: String?,
    val detail: String,
)
