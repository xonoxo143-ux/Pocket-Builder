package com.libreseed.pocketbuild.gradle

import android.content.Context
import android.os.StatFs
import com.android.apksig.ApkVerifier
import com.libreseed.pocketbuild.godot.ApkSigningService
import com.libreseed.pocketbuild.model.SourceKind
import com.libreseed.pocketbuild.model.ToolchainComponentSummary
import com.libreseed.pocketbuild.model.ToolchainState
import com.libreseed.pocketbuild.model.WorkspaceSummary
import com.libreseed.pocketbuild.runtime.ProcessLine
import com.libreseed.pocketbuild.runtime.ProcessSpec
import com.libreseed.pocketbuild.runtime.ProcessSupervisor
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class AndroidGradleProjectBuilder(private val context: Context) {
    data class Progress(
        val stage: String,
        val detail: String,
        val fraction: Float?,
        val currentItem: String? = null,
        val logLine: String? = null,
        val cancellable: Boolean = false,
    )

    data class Artifact(
        val file: File,
        val sourceApk: File,
        val logFile: File,
        val gradleVersion: String?,
    )

    data class ToolchainSnapshot(
        val ready: Boolean,
        val installedBytes: Long,
        val cacheBytes: Long,
        val freeBytes: Long,
        val components: List<ToolchainComponentSummary>,
    )

    private val inspector = GradleProjectInspector()
    private val toolchain = MobileGradleToolchain(context)
    private val supervisor = ProcessSupervisor()
    private val signer = ApkSigningService()

    fun inspectProject(workspace: WorkspaceSummary): GradleProjectRequirements {
        require(workspace.sourceKind == SourceKind.ANDROID_GRADLE) { "This inspector requires an Android Gradle project." }
        return inspector.inspect(File(workspace.rootPath))
    }

    fun isToolchainReady(requiredPlatforms: Set<Int> = setOf(36)): Boolean {
        if (!toolchain.isReady()) return false
        val sdk = File(context.filesDir, "usr/android-sdk")
        return requiredPlatforms.all { File(sdk, "platforms/android-$it/android.jar").isFile }
    }

    fun toolchainSnapshot(requiredPlatforms: Set<Int> = setOf(36)): ToolchainSnapshot {
        val prefix = File(context.filesDir, "usr")
        val cache = File(context.cacheDir, "mobile-gradle-downloads")
        val java = File(prefix, "lib/jvm/java-17-openjdk/bin/java")
        val aapt2 = File(prefix, "android-sdk/build-tools/34.0.4/aapt2")
        val trustStore = File(prefix, "lib/jvm/java-17-openjdk/lib/security/cacerts")
        val components = buildList {
            add(
                ToolchainComponentSummary(
                    name = "OpenJDK",
                    version = "17",
                    state = if (java.isFile && java.canExecute()) ToolchainState.READY else ToolchainState.MISSING,
                    detail = if (java.isFile && java.canExecute()) "Java launcher present and executable" else "Java launcher missing",
                ),
            )
            add(
                ToolchainComponentSummary(
                    name = "ARM64 AAPT2",
                    version = "34.0.4",
                    state = if (aapt2.isFile && aapt2.canExecute()) ToolchainState.READY else ToolchainState.MISSING,
                    detail = if (aapt2.isFile && aapt2.canExecute()) "Android-compatible resource compiler present" else "AAPT2 missing",
                ),
            )
            add(
                ToolchainComponentSummary(
                    name = "Java trust store",
                    state = if (trustStore.isFile && trustStore.length() > 0) ToolchainState.READY else ToolchainState.MISSING,
                    detail = if (trustStore.isFile && trustStore.length() > 0) "TLS certificate store present" else "TLS certificate store missing",
                ),
            )
            requiredPlatforms.sorted().forEach { api ->
                val jar = File(prefix, "android-sdk/platforms/android-$api/android.jar")
                add(
                    ToolchainComponentSummary(
                        name = "Android SDK Platform",
                        version = "API $api",
                        state = if (jar.isFile) ToolchainState.READY else ToolchainState.MISSING,
                        detail = if (jar.isFile) "android.jar present" else "Platform download required",
                    ),
                )
            }
        }
        val ready = components.all { it.state == ToolchainState.READY }
        return ToolchainSnapshot(
            ready = ready,
            installedBytes = directoryBytes(prefix),
            cacheBytes = directoryBytes(cache),
            freeBytes = StatFs(context.filesDir.absolutePath).availableBytes,
            components = components,
        )
    }

    fun installToolchain(
        requiredPlatforms: Set<Int> = setOf(36),
        onProgress: (Progress) -> Unit = {},
    ): MobileGradleEnvironment {
        return toolchain.ensureInstalled(requiredPlatforms) { progress ->
            onProgress(
                Progress(
                    stage = progress.stage,
                    detail = progress.detail,
                    fraction = progress.fraction,
                    currentItem = progress.detail.substringBefore(':').takeIf { ':' in progress.detail },
                    cancellable = false,
                ),
            )
        }
    }

    fun build(
        workspace: WorkspaceSummary,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Progress) -> Unit = {},
    ): Artifact {
        require(workspace.sourceKind == SourceKind.ANDROID_GRADLE) { "This builder requires an Android Gradle project." }
        val root = File(workspace.rootPath)
        onProgress(
            Progress(
                "Inspecting Gradle project",
                "Reading wrapper, plugins and Android SDK requirements.",
                0.01f,
                cancellable = false,
            ),
        )
        val requirements = inspector.inspect(root)
        val environment = toolchain.ensureInstalled(requirements.compileSdks) { progress ->
            onProgress(
                Progress(
                    stage = progress.stage,
                    detail = progress.detail,
                    fraction = progress.fraction?.times(0.72f),
                    currentItem = progress.detail.substringBefore(':').takeIf { ':' in progress.detail },
                    cancellable = false,
                ),
            )
        }
        check(!cancelled.get()) { "Build cancelled before Gradle execution." }

        val logs = File(context.filesDir, "build-logs").apply { mkdirs() }
        val buildStartedAt = System.currentTimeMillis()
        val logFile = File(logs, "${safeName(workspace.displayName)}-$buildStartedAt.log")
        val outputDirectory = File(context.filesDir, "builds").apply { mkdirs() }
        val command = gradleCommand(requirements, environment)
        val processEnvironment = gradleEnvironment(environment)

        onProgress(
            Progress(
                "Running Gradle",
                "${requirements.gradleVersion?.let { "Gradle $it · " }.orEmpty()}${requirements.task}",
                0.74f,
                currentItem = requirements.task,
                cancellable = true,
            ),
        )
        logFile.bufferedWriter().use { log ->
            log.appendLine("PocketHost local Gradle build")
            log.appendLine("Project: ${root.absolutePath}")
            log.appendLine("Distribution: ${requirements.distributionUrl}")
            log.appendLine("Distribution checksum declared: ${requirements.distributionSha256Sum != null}")
            log.appendLine("Command: ${command.joinToString(" ")}")
            val result = supervisor.run(
                ProcessSpec(
                    command = command,
                    workingDirectory = root,
                    environment = processEnvironment,
                    inheritEnvironment = true,
                    timeout = Duration.ofMinutes(90),
                ),
                cancelled = cancelled,
            ) { line: ProcessLine ->
                val prefix = line.stream.name.lowercase()
                val rendered = "[$prefix] ${line.text}"
                log.appendLine(rendered)
                log.flush()
                if (line.text.isNotBlank()) {
                    onProgress(
                        Progress(
                            stage = "Running Gradle",
                            detail = line.text.takeLast(500),
                            fraction = 0.80f,
                            currentItem = requirements.task,
                            logLine = rendered,
                            cancellable = true,
                        ),
                    )
                }
            }
            check(!result.cancelled) { "Gradle build cancelled." }
            check(!result.timedOut) { "Gradle build exceeded the 90-minute limit." }
            check(result.exitCode == 0) {
                "Gradle exited with code ${result.exitCode}. Build log: ${logFile.absolutePath}"
            }
            check(result.outputDrainComplete) { "Gradle finished, but its complete output could not be captured." }
        }

        check(!cancelled.get()) { "Build cancelled after Gradle execution." }
        onProgress(Progress("Finding APK", "Inspecting current Gradle output directories.", 0.94f, cancellable = false))
        val sourceApk = findBestApk(root, buildStartedAt)
            ?: error("Gradle succeeded but did not produce a new installable APK under build/outputs/apk.")
        val verified = ApkVerifier.Builder(sourceApk).build().verify().isVerified
        val destination = File(outputDirectory, "${safeName(workspace.displayName)}-gradle-debug.apk")
        destination.delete()
        if (verified) {
            sourceApk.copyTo(destination, overwrite = true)
        } else {
            onProgress(Progress("Signing APK", "Applying the device-local PocketHost signing key.", 0.97f, cancellable = false))
            signer.sign(sourceApk, destination)
        }
        onProgress(Progress("Verifying APK", "Checking the final APK signature and structure.", 0.99f, cancellable = false))
        val verification = ApkVerifier.Builder(destination).build().verify()
        check(verification.isVerified) {
            "The generated APK failed signature verification: ${verification.errors.joinToString()}"
        }
        onProgress(Progress("APK ready", destination.name, 1f, currentItem = destination.name, cancellable = false))
        return Artifact(destination, sourceApk, logFile, requirements.gradleVersion)
    }

    private fun gradleCommand(requirements: GradleProjectRequirements, env: MobileGradleEnvironment): List<String> {
        return listOf(
            env.java.absolutePath,
            "-Dorg.gradle.appname=gradlew",
            "-Dorg.gradle.native=false",
            "-Dorg.gradle.vfs.watch=false",
            "-Djava.io.tmpdir=${env.temp.absolutePath}",
            "-Duser.home=${env.home.absolutePath}",
            "-Djavax.net.ssl.trustStore=${env.trustStore.absolutePath}",
            "-Djavax.net.ssl.trustStorePassword=changeit",
            "-classpath",
            requirements.wrapperJar.absolutePath,
            "org.gradle.wrapper.GradleWrapperMain",
            "--no-daemon",
            "--stacktrace",
            "--console=plain",
            "--warning-mode=all",
            "-Pandroid.aapt2FromMavenOverride=${env.aapt2.absolutePath}",
            requirements.task,
        )
    }

    private fun gradleEnvironment(env: MobileGradleEnvironment): Map<String, String> {
        val libraries = listOf(
            File(env.prefix, "lib").absolutePath,
            File(env.javaHome, "lib").absolutePath,
            File(env.javaHome, "lib/server").absolutePath,
        ).joinToString(":")
        return mapOf(
            "PREFIX" to env.prefix.absolutePath,
            "HOME" to env.home.absolutePath,
            "TMPDIR" to env.temp.absolutePath,
            "JAVA_HOME" to env.javaHome.absolutePath,
            "ANDROID_HOME" to env.sdk.absolutePath,
            "ANDROID_SDK_ROOT" to env.sdk.absolutePath,
            "GRADLE_USER_HOME" to env.gradleHome.absolutePath,
            "LD_LIBRARY_PATH" to libraries,
            "PATH" to listOf(
                File(env.javaHome, "bin").absolutePath,
                File(env.sdk, "build-tools/36.0.0").absolutePath,
                File(env.prefix, "bin").absolutePath,
                "/system/bin",
                "/system/xbin",
            ).joinToString(":"),
            "GRADLE_OPTS" to listOf(
                "-Dorg.gradle.native=false",
                "-Dorg.gradle.vfs.watch=false",
                "-Djava.io.tmpdir=${env.temp.absolutePath}",
                "-Djavax.net.ssl.trustStore=${env.trustStore.absolutePath}",
                "-Djavax.net.ssl.trustStorePassword=changeit",
            ).joinToString(" "),
        )
    }

    private fun findBestApk(root: File, buildStartedAt: Long): File? {
        val freshnessFloor = buildStartedAt - 2_000L
        return root.walkTopDown()
            .filter { file ->
                file.isFile && file.extension.equals("apk", true) &&
                    file.lastModified() >= freshnessFloor &&
                    file.invariantSeparatorsPath.contains("/build/outputs/apk/") &&
                    !file.name.contains("androidTest", true) && !file.name.contains("unaligned", true)
            }
            .sortedWith(
                compareByDescending<File> { it.name.contains("debug", true) }
                    .thenByDescending { it.lastModified() }
                    .thenByDescending { it.length() },
            )
            .firstOrNull()
    }

    private fun directoryBytes(root: File): Long {
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun safeName(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.')
        .take(48)
        .ifBlank { "android-project" }
}
