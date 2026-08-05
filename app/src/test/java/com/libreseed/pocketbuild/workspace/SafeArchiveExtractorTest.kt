package com.libreseed.pocketbuild.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeArchiveExtractorTest {
    @Test
    fun stripsOuterProjectFolder() {
        val archive = zipOf(
            "game/project.godot" to "config_version=5",
            "game/scenes/main.tscn" to "[gd_scene]",
        )
        val destination = Files.createTempDirectory("pb-extract").toFile()
        val stats = SafeArchiveExtractor().extract(ByteArrayInputStream(archive), destination, "game")
        assertEquals(2, stats.files)
        assertTrue(destination.resolve("project.godot").isFile)
        assertTrue(destination.resolve("scenes/main.tscn").isFile)
    }

    @Test
    fun ignoresFilesOutsideDetectedProjectRoot() {
        val archive = zipOf(
            "game/project.godot" to "config_version=5",
            "game/scenes/main.tscn" to "[gd_scene]",
            "unrelated.txt" to "must not be imported",
            "other-project/settings.gradle.kts" to "rootProject.name = \"Other\"",
        )
        val destination = Files.createTempDirectory("pb-extract-root").toFile()
        val stats = SafeArchiveExtractor().extract(ByteArrayInputStream(archive), destination, "game")
        assertEquals(2, stats.files)
        assertTrue(destination.resolve("project.godot").isFile)
        assertFalse(destination.resolve("unrelated.txt").exists())
        assertFalse(destination.resolve("other-project").exists())
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsZipSlip() {
        val archive = zipOf("../escape.txt" to "bad")
        val destination = Files.createTempDirectory("pb-extract-bad").toFile()
        SafeArchiveExtractor().extract(ByteArrayInputStream(archive), destination)
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((path, content) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
