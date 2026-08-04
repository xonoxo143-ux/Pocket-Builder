package com.libreseed.pocketbuild.runtime

import java.time.Instant
import java.util.UUID

enum class BuildStage {
    QUEUED,
    INSPECTING,
    PREPARING_WORKSPACE,
    CHECKING_TOOLCHAINS,
    BUILDING,
    SIGNING,
    VERIFYING_OUTPUT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class BuildJob(
    val id: String = UUID.randomUUID().toString(),
    val projectName: String,
    val workspaceRoot: String,
    val recipeId: String,
    val createdAt: Instant = Instant.now(),
)

data class BuildEvent(
    val jobId: String,
    val stage: BuildStage,
    val message: String,
    val progress: Float? = null,
    val timestamp: Instant = Instant.now(),
) {
    init {
        require(progress == null || progress in 0f..1f)
    }
}
