package com.libreseed.pocketbuild.toolchain

import java.io.File
import java.security.MessageDigest
import java.util.Properties

class ToolchainStore(private val root: File) {
    fun quickAudit(definition: ToolchainPackDefinition): PackAudit {
        val packRoot = File(root, definition.id)
        if (!packRoot.isDirectory) {
            return PackAudit(definition.id, definition.version, null, PackHealth.NOT_INSTALLED, "Pack is not installed.")
        }

        val metadata = readMetadata(packRoot)
            ?: return PackAudit(definition.id, definition.version, null, PackHealth.CORRUPT, "Installed-pack metadata is missing.")
        val installedVersion = metadata.getProperty("version")
        if (installedVersion != definition.version) {
            return PackAudit(
                definition.id,
                definition.version,
                installedVersion,
                PackHealth.VERSION_MISMATCH,
                "Installed version does not match the catalog.",
            )
        }

        val missing = definition.artifacts.filterNot { File(packRoot, it.relativePath).isFile }
        if (missing.isNotEmpty()) {
            return PackAudit(
                definition.id,
                definition.version,
                installedVersion,
                PackHealth.MISSING_FILES,
                "Missing ${missing.size} required file(s).",
            )
        }

        return PackAudit(definition.id, definition.version, installedVersion, PackHealth.READY, "Pack is ready.")
    }

    fun fullAudit(definition: ToolchainPackDefinition): PackAudit {
        val quick = quickAudit(definition)
        if (quick.health != PackHealth.READY) return quick
        val packRoot = File(root, definition.id)
        val corrupt = definition.artifacts.firstOrNull { artifact ->
            sha256(File(packRoot, artifact.relativePath)) != artifact.sha256.lowercase()
        }
        return if (corrupt == null) {
            quick.copy(detail = "Pack files match the pinned digests.")
        } else {
            quick.copy(health = PackHealth.CORRUPT, detail = "Checksum mismatch: ${corrupt.relativePath}")
        }
    }

    fun promote(staging: File, definition: ToolchainPackDefinition) {
        require(staging.isDirectory) { "Toolchain staging directory is missing." }
        val audit = fullAuditFromDirectory(staging, definition)
        check(audit.health == PackHealth.READY) { audit.detail }

        root.mkdirs()
        val destination = File(root, definition.id)
        val backup = File(root, ".${definition.id}.previous")
        backup.deleteRecursively()
        if (destination.exists()) {
            check(destination.renameTo(backup)) { "Cannot preserve previous toolchain pack." }
        }
        if (!staging.renameTo(destination)) {
            if (backup.exists()) backup.renameTo(destination)
            error("Cannot promote verified toolchain pack.")
        }
        backup.deleteRecursively()
    }

    fun writeMetadata(directory: File, definition: ToolchainPackDefinition) {
        val properties = Properties().apply {
            setProperty("id", definition.id)
            setProperty("version", definition.version)
        }
        File(directory, METADATA_FILE).outputStream().use { properties.store(it, null) }
    }

    private fun fullAuditFromDirectory(directory: File, definition: ToolchainPackDefinition): PackAudit {
        val metadata = readMetadata(directory)
            ?: return PackAudit(definition.id, definition.version, null, PackHealth.CORRUPT, "Staged metadata is missing.")
        val installedVersion = metadata.getProperty("version")
        if (installedVersion != definition.version) {
            return PackAudit(
                definition.id,
                definition.version,
                installedVersion,
                PackHealth.VERSION_MISMATCH,
                "Staged version mismatch.",
            )
        }
        for (artifact in definition.artifacts) {
            val file = File(directory, artifact.relativePath)
            if (!file.isFile) {
                return PackAudit(
                    definition.id,
                    definition.version,
                    installedVersion,
                    PackHealth.MISSING_FILES,
                    "Missing ${artifact.relativePath}",
                )
            }
            if (sha256(file) != artifact.sha256.lowercase()) {
                return PackAudit(
                    definition.id,
                    definition.version,
                    installedVersion,
                    PackHealth.CORRUPT,
                    "Checksum mismatch: ${artifact.relativePath}",
                )
            }
        }
        return PackAudit(definition.id, definition.version, installedVersion, PackHealth.READY, "Staged pack is verified.")
    }

    private fun readMetadata(directory: File): Properties? {
        val file = File(directory, METADATA_FILE)
        if (!file.isFile) return null
        return runCatching {
            Properties().apply { file.inputStream().use(::load) }
        }.getOrNull()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val METADATA_FILE = ".pocketbuild-pack"
    }
}
