package com.libreseed.pocketbuild.gradle

import java.io.File
import java.net.URI
import java.util.Properties

class GradleProjectInspector {
    fun inspect(root: File): GradleProjectRequirements {
        require(root.isDirectory) { "Gradle workspace is missing." }
        require(File(root, "settings.gradle").isFile || File(root, "settings.gradle.kts").isFile) {
            "The workspace does not contain settings.gradle or settings.gradle.kts."
        }
        val wrapperJar = File(root, "gradle/wrapper/gradle-wrapper.jar")
        val wrapperProperties = File(root, "gradle/wrapper/gradle-wrapper.properties")
        require(wrapperJar.isFile) {
            "This project does not include gradle/wrapper/gradle-wrapper.jar. Regenerate or include the Gradle wrapper before importing it."
        }
        require(wrapperProperties.isFile) { "Gradle wrapper properties are missing." }

        val properties = Properties().apply { wrapperProperties.inputStream().use(::load) }
        val distributionUrl = properties.getProperty("distributionUrl")
            ?: error("Gradle wrapper distributionUrl is missing.")
        val distribution = URI(distributionUrl)
        require(distribution.scheme.equals("https", true)) { "Gradle distributions must use HTTPS." }
        require(distribution.host?.lowercase() in GRADLE_HOSTS) {
            "Untrusted Gradle distribution host: ${distribution.host}"
        }
        val distributionSha256 = properties.getProperty("distributionSha256Sum")
            ?.trim()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }

        val scripts = root.walkTopDown()
            .maxDepth(5)
            .filter { file ->
                file.isFile && file.length() <= MAX_SCRIPT_BYTES &&
                    (file.name.endsWith(".gradle") || file.name.endsWith(".gradle.kts") ||
                        file.name == "gradle.properties" || file.name == "libs.versions.toml")
            }
            .toList()
        val platforms = linkedSetOf<Int>()
        scripts.forEach { file ->
            val text = runCatching { file.readText() }.getOrDefault("")
            COMPILE_SDK_PATTERNS.forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                    match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 21..36 }?.let(platforms::add)
                }
            }
            if (file.name == "libs.versions.toml") {
                text.lineSequence().forEach { line ->
                    if (line.substringBefore('=').contains("compile", true) && line.substringBefore('=').contains("sdk", true)) {
                        Regex("\\b(2[1-9]|3[0-6])\\b").find(line)?.value?.toIntOrNull()?.let(platforms::add)
                    }
                }
            }
        }
        if (platforms.isEmpty()) platforms += DEFAULT_PLATFORM

        val warnings = buildList {
            add("Gradle wrapper JARs and build scripts execute project-supplied JVM code inside PocketBuild's app sandbox.")
            if (distributionSha256 == null) {
                add("The Gradle wrapper does not declare distributionSha256Sum, so Gradle cannot pin the distribution archive by checksum.")
            }
            if (platforms.any { it != DEFAULT_PLATFORM }) {
                add("This project requests Android SDK ${platforms.sorted().joinToString()}; missing platforms will be downloaded before the build.")
            }
        }

        return GradleProjectRequirements(
            root = root,
            wrapperJar = wrapperJar,
            wrapperProperties = wrapperProperties,
            distributionUrl = distributionUrl,
            distributionHost = distribution.host.orEmpty(),
            distributionSha256Sum = distributionSha256,
            gradleVersion = Regex("gradle-([0-9][0-9A-Za-z._-]*)-(?:all|bin)\\.zip")
                .find(distributionUrl)?.groupValues?.getOrNull(1),
            compileSdks = platforms,
            task = "assembleDebug",
            warnings = warnings,
        )
    }

    companion object {
        private const val DEFAULT_PLATFORM = 36
        private const val MAX_SCRIPT_BYTES = 4L * 1024 * 1024
        private val GRADLE_HOSTS = setOf("services.gradle.org", "downloads.gradle.org", "github.com")
        private val COMPILE_SDK_PATTERNS = listOf(
            Regex("\\bcompileSdk(?:Version)?\\s*(?:=|\\s)\\s*(\\d{2})\\b"),
            Regex("\\bcompileSdk\\s*\\{[^}]*?apiLevel\\s*=\\s*(\\d{2})", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("\\bandroid\\.compileSdk\\s*=\\s*(\\d{2})\\b"),
        )
    }
}

data class GradleProjectRequirements(
    val root: File,
    val wrapperJar: File,
    val wrapperProperties: File,
    val distributionUrl: String,
    val distributionHost: String,
    val distributionSha256Sum: String?,
    val gradleVersion: String?,
    val compileSdks: Set<Int>,
    val task: String,
    val warnings: List<String>,
)
