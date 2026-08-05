package com.libreseed.pocketbuild.model

import java.util.UUID

enum class OperationKind {
    WORKSPACE_IMPORT,
    TOOLCHAIN_INSTALL,
    GODOT_BUILD,
    GRADLE_BUILD,
}

enum class OperationStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

enum class OperationStageStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

enum class OperationSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class OperationStageSummary(
    val id: String,
    val title: String,
    val status: OperationStageStatus = OperationStageStatus.PENDING,
    val detail: String = "",
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
)

data class OperationLogLine(
    val timestampMillis: Long = System.currentTimeMillis(),
    val severity: OperationSeverity = OperationSeverity.INFO,
    val stage: String,
    val message: String,
)

data class OperationErrorReport(
    val summary: String,
    val detail: String,
    val exceptionType: String? = null,
    val logPath: String? = null,
    val existingDataSafe: Boolean = true,
    val retryRecommended: Boolean = true,
)

data class OperationUiState(
    val id: String = UUID.randomUUID().toString(),
    val kind: OperationKind,
    val title: String,
    val status: OperationStatus = OperationStatus.RUNNING,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val finishedAtMillis: Long? = null,
    val overallProgress: Float? = null,
    val stageTitle: String,
    val detail: String,
    val currentItem: String? = null,
    val completedBytes: Long? = null,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val cancellable: Boolean = true,
    val stages: List<OperationStageSummary> = emptyList(),
    val logs: List<OperationLogLine> = emptyList(),
    val error: OperationErrorReport? = null,
    val outputPath: String? = null,
)

data class OperationHistoryItem(
    val id: String,
    val kind: OperationKind,
    val title: String,
    val status: OperationStatus,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val finalStage: String,
    val detail: String,
    val outputPath: String? = null,
    val errorSummary: String? = null,
)

data class ProjectPreflightSummary(
    val projectName: String,
    val sourceKind: SourceKind,
    val workspacePath: String,
    val importedFiles: Int,
    val importedBytes: Long,
    val gradleVersion: String? = null,
    val gradleTask: String? = null,
    val compileSdks: Set<Int> = emptySet(),
    val distributionHost: String? = null,
    val distributionChecksumDeclared: Boolean = false,
    val warnings: List<String> = emptyList(),
)

data class BuildConfirmation(
    val title: String,
    val message: String,
    val riskDetails: List<String>,
)

data class ToolchainComponentSummary(
    val name: String,
    val version: String? = null,
    val state: ToolchainState,
    val detail: String,
)
