package com.libreseed.pocketbuild.source

import com.libreseed.pocketbuild.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceClassifierTest {
    @Test
    fun recognizesDirectFiles() {
        assertEquals(SourceKind.GODOT, SourceClassifier.classifyFileName("project.godot"))
        assertEquals(SourceKind.ANDROID_GRADLE, SourceClassifier.classifyFileName("build.gradle.kts"))
        assertEquals(SourceKind.ZIP_UNKNOWN, SourceClassifier.classifyFileName("game.zip"))
    }

    @Test
    fun detectsGodotProjectInsideArchive() {
        val result = SourceClassifier.classifyArchiveEntries(
            sequenceOf("game/README.md", "game/project.godot", "game/main.tscn"),
        )
        assertEquals(SourceKind.GODOT, result.kind)
        assertEquals("game", result.projectRootHint)
    }

    @Test
    fun explicitPocketBuildRecipeTakesPriority() {
        val result = SourceClassifier.classifyArchiveEntries(
            sequenceOf("project.godot", "pocketbuild.json"),
        )
        assertEquals(SourceKind.POCKETBUILD_RECIPE, result.kind)
    }

    @Test
    fun blocksZipSlipPaths() {
        assertFalse(SourceClassifier.isSafeArchivePath("../outside.txt"))
        assertFalse(SourceClassifier.isSafeArchivePath("folder/../../outside.txt"))
        assertFalse(SourceClassifier.isSafeArchivePath("/absolute/path"))
        assertTrue(SourceClassifier.isSafeArchivePath("project/assets/icon.png"))
    }
}
