package com.libreseed.pocketbuild.toolchain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class ToolchainInstallerTest {
    @Test
    fun installsAndPromotesVerifiedPack() {
        val bytes = "toolchain".toByteArray()
        val definition = ToolchainPackDefinition(
            id = "demo",
            version = "1",
            displayName = "Demo",
            architectures = setOf("arm64-v8a"),
            artifacts = listOf(
                ToolchainArtifact(
                    relativePath = "data/payload.bin",
                    downloadUrl = "https://trusted.test/payload.bin",
                    sha256 = sha256(bytes),
                    compressedBytes = bytes.size.toLong(),
                    expandedBytes = bytes.size.toLong(),
                ),
            ),
        )
        val root = Files.createTempDirectory("pb-installer").toFile()
        val client = DownloadClient { _: URI, destination: File, cancelled: AtomicBoolean, onBytes: (Long) -> Unit ->
            check(!cancelled.get())
            destination.writeBytes(bytes)
            onBytes(bytes.size.toLong())
        }
        val installer = ToolchainInstaller(root, TrustedSourcePolicy(setOf("trusted.test")), client)
        val result = installer.install(definition)
        assertEquals(PackHealth.READY, result.health)
        assertEquals("toolchain", root.resolve("demo/data/payload.bin").readText())
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsChecksumMismatch() {
        val definition = ToolchainPackDefinition(
            id = "demo",
            version = "1",
            displayName = "Demo",
            architectures = setOf("arm64-v8a"),
            artifacts = listOf(
                ToolchainArtifact(
                    relativePath = "payload.bin",
                    downloadUrl = "https://trusted.test/payload.bin",
                    sha256 = "0".repeat(64),
                    compressedBytes = 3,
                    expandedBytes = 3,
                ),
            ),
        )
        val root = Files.createTempDirectory("pb-installer-bad").toFile()
        val client = DownloadClient { _, destination, _, _ -> destination.writeText("bad") }
        ToolchainInstaller(root, TrustedSourcePolicy(setOf("trusted.test")), client).install(definition)
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
