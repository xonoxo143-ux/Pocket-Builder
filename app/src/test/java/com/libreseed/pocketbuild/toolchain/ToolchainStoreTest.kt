package com.libreseed.pocketbuild.toolchain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class ToolchainStoreTest {
    @Test
    fun verifiesInstalledPack() {
        val root = Files.createTempDirectory("pb-toolchains").toFile()
        val pack = root.resolve("demo")
        pack.mkdirs()
        val payload = pack.resolve("data/tool.txt")
        payload.parentFile.mkdirs()
        payload.writeText("trusted")
        val definition = ToolchainPackDefinition(
            id = "demo",
            version = "1",
            displayName = "Demo",
            architectures = setOf("arm64-v8a"),
            artifacts = listOf(
                ToolchainArtifact(
                    relativePath = "data/tool.txt",
                    downloadUrl = "https://downloads.gradle.org/tool.txt",
                    sha256 = sha256("trusted".toByteArray()),
                    compressedBytes = 7,
                    expandedBytes = 7,
                ),
            ),
        )
        val store = ToolchainStore(root)
        store.writeMetadata(pack, definition)
        assertEquals(PackHealth.READY, store.fullAudit(definition).health)
        payload.writeText("changed")
        assertEquals(PackHealth.CORRUPT, store.fullAudit(definition).health)
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
