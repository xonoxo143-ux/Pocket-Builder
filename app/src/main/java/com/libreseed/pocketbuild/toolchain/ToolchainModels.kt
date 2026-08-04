package com.libreseed.pocketbuild.toolchain

enum class ToolchainFileMode {
    DATA,
    EXECUTABLE_APK_PAYLOAD,
}

data class ToolchainArtifact(
    val relativePath: String,
    val downloadUrl: String,
    val sha256: String,
    val compressedBytes: Long?,
    val expandedBytes: Long?,
    val mode: ToolchainFileMode = ToolchainFileMode.DATA,
) {
    init {
        require(relativePath.isNotBlank())
        require(!relativePath.startsWith('/'))
        require(relativePath.split('/').none { it == ".." })
        require(sha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(compressedBytes == null || compressedBytes >= 0)
        require(expandedBytes == null || expandedBytes >= 0)
    }
}

data class ToolchainPackDefinition(
    val id: String,
    val version: String,
    val displayName: String,
    val architectures: Set<String>,
    val artifacts: List<ToolchainArtifact>,
    val requiredByDefault: Boolean = false,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*")))
        require(version.isNotBlank())
        require(displayName.isNotBlank())
        require(architectures.isNotEmpty())
        require(artifacts.map { it.relativePath }.distinct().size == artifacts.size)
    }
}

enum class PackHealth {
    NOT_INSTALLED,
    READY,
    VERSION_MISMATCH,
    MISSING_FILES,
    CORRUPT,
}

data class PackAudit(
    val packId: String,
    val expectedVersion: String,
    val installedVersion: String?,
    val health: PackHealth,
    val detail: String,
)
