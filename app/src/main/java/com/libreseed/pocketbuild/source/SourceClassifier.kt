package com.libreseed.pocketbuild.source

import com.libreseed.pocketbuild.model.SourceKind

object SourceClassifier {
    private const val MAX_ARCHIVE_ENTRIES = 20_000

    fun classifyFileName(fileName: String, mimeType: String? = null): SourceKind {
        val normalized = fileName.trim().lowercase()
        return when {
            normalized == "project.godot" || normalized.endsWith(".godot") -> SourceKind.GODOT
            normalized == "build.gradle" ||
                normalized == "settings.gradle" ||
                normalized.endsWith(".gradle") ||
                normalized.endsWith(".gradle.kts") -> SourceKind.ANDROID_GRADLE
            normalized.endsWith(".pocketbuild") -> SourceKind.POCKETBUILD_RECIPE
            normalized.endsWith(".zip") ||
                normalized.endsWith(".godotpack") ||
                mimeType == "application/zip" ||
                mimeType == "application/x-zip-compressed" -> SourceKind.ZIP_UNKNOWN
            else -> SourceKind.UNKNOWN
        }
    }

    fun classifyArchiveEntries(entries: Sequence<String>): ArchiveClassification {
        var count = 0
        var godotRoot: String? = null
        var gradleRoot: String? = null
        var recipeRoot: String? = null
        val warnings = mutableListOf<String>()

        for (rawEntry in entries) {
            count += 1
            if (count > MAX_ARCHIVE_ENTRIES) {
                warnings += "Archive has more than $MAX_ARCHIVE_ENTRIES entries."
                break
            }

            val entry = rawEntry.replace('\\', '/').trimStart('/')
            if (!isSafeArchivePath(rawEntry)) {
                warnings += "Unsafe archive path: $rawEntry"
                continue
            }

            val lower = entry.lowercase()
            val parent = entry.substringBeforeLast('/', missingDelimiterValue = "")
            when {
                lower.endsWith("project.godot") && lower.substringAfterLast('/') == "project.godot" -> {
                    godotRoot = chooseShallowerRoot(godotRoot, parent)
                }
                lower.endsWith("settings.gradle") || lower.endsWith("settings.gradle.kts") -> {
                    gradleRoot = chooseShallowerRoot(gradleRoot, parent)
                }
                lower.endsWith("pocketbuild.json") -> {
                    recipeRoot = chooseShallowerRoot(recipeRoot, parent)
                }
            }
        }

        val kind = when {
            recipeRoot != null -> SourceKind.POCKETBUILD_RECIPE
            godotRoot != null -> SourceKind.GODOT
            gradleRoot != null -> SourceKind.ANDROID_GRADLE
            else -> SourceKind.ZIP_UNKNOWN
        }
        val root = when (kind) {
            SourceKind.POCKETBUILD_RECIPE -> recipeRoot
            SourceKind.GODOT -> godotRoot
            SourceKind.ANDROID_GRADLE -> gradleRoot
            else -> null
        }
        return ArchiveClassification(kind = kind, projectRootHint = root, warnings = warnings)
    }

    fun isSafeArchivePath(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.startsWith("~/")) return false
        if (Regex("^[A-Za-z]:/").containsMatchIn(normalized)) return false
        val segments = normalized.split('/')
        return segments.none { it == ".." }
    }

    private fun chooseShallowerRoot(current: String?, candidate: String): String {
        if (current == null) return candidate
        val currentDepth = current.count { it == '/' }
        val candidateDepth = candidate.count { it == '/' }
        return if (candidateDepth < currentDepth) candidate else current
    }
}

data class ArchiveClassification(
    val kind: SourceKind,
    val projectRootHint: String?,
    val warnings: List<String>,
)
