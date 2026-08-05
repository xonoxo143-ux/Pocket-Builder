package com.libreseed.pocketbuild.gradle

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.util.Xml
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.math.max

/** Installs the ARM64 JDK and Android SDK pieces needed to execute ordinary Gradle builds. */
class MobileGradleToolchain(private val context: Context) {
    data class Progress(val stage: String, val detail: String, val fraction: Float?)

    private val files = context.filesDir
    private val prefix = File(files, "usr")
    private val marker = File(files, MARKER)
    private val cache = File(context.cacheDir, "mobile-gradle-downloads")

    fun isReady(): Boolean = runCatching { environment(validate = true) }.isSuccess

    @Synchronized
    fun ensureInstalled(
        requiredPlatforms: Set<Int>,
        onProgress: (Progress) -> Unit = {},
    ): MobileGradleEnvironment {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "The first local Gradle toolchain supports ARM64 Android devices only."
        }
        check(context.packageName == EXPECTED_APPLICATION_ID) {
            "This build must use application id $EXPECTED_APPLICATION_ID so its relocatable JDK prefix is valid."
        }
        val platforms = requiredPlatforms.filter { it in MIN_PLATFORM..MAX_PLATFORM }.ifEmpty { setOf(DEFAULT_PLATFORM) }
        val baseInstalled = baseReady()
        val existingEnvironment = if (baseInstalled) environment(validate = true) else null
        val missingPlatforms = platforms.filter { api ->
            existingEnvironment == null || !File(existingEnvironment.sdk, "platforms/android-$api/android.jar").isFile
        }
        if (baseInstalled && missingPlatforms.isEmpty()) {
            onProgress(Progress("Toolchain ready", "JDK 17, Android SDK and ARM64 build tools are ready.", 1f))
            return checkNotNull(existingEnvironment)
        }
        val requiredFreeBytes = if (baseInstalled) MIN_PLATFORM_FREE_BYTES else MIN_FREE_BYTES
        check(StatFs(files.absolutePath).availableBytes >= requiredFreeBytes) {
            if (baseInstalled) "At least 512 MB of free app storage is required to add the missing Android SDK platform."
            else "At least 1.2 GB of free app storage is required for the local Android toolchain."
        }

        if (!baseInstalled) installBase(onProgress)
        val env = environment(validate = true)
        missingPlatforms.sorted().forEachIndexed { index, api ->
            val base = 0.84f + (index.toFloat() / max(1, missingPlatforms.size)) * 0.13f
            installPlatform(api, env.sdk, base, onProgress)
        }
        writeMarker()
        onProgress(Progress("Toolchain ready", "JDK 17, Android SDK and ARM64 build tools are ready.", 1f))
        return environment(validate = true)
    }

    private fun baseReady(): Boolean {
        if (!marker.isFile || marker.readText().trim() != TOOLCHAIN_VERSION) return false
        return runCatching { environment(validate = true) }.isSuccess
    }

    private fun installBase(onProgress: (Progress) -> Unit) {
        val oldPrefix = File(files, ".usr.previous")
        val stage = File(files, ".usr.staging-${UUID.randomUUID()}")
        val stagePrefix = File(stage, "usr")
        stage.deleteRecursively()
        check(stagePrefix.mkdirs()) { "Cannot create toolchain staging directory." }
        cache.mkdirs()

        try {
            onProgress(Progress("Checking JDK", "Reading the ARM64 package catalog.", 0.01f))
            installTermuxPackages(stage, onProgress)

            onProgress(Progress("Installing Android tools", "Downloading Android-compatible ARM64 Build Tools 34.0.4.", 0.63f))
            val buildArchive = File(cache, "build-tools-34.0.4-aarch64.tar.xz")
            download(
                BUILD_TOOLS_URL,
                buildArchive,
                expectedDigest = BUILD_TOOLS_SHA256.takeIf { it.isNotBlank() },
                algorithm = "SHA-256",
                maxBytes = 400L * 1024 * 1024,
            ) { bytes ->
                onProgress(Progress("Installing Android tools", "Downloaded ${formatBytes(bytes)}.", 0.63f))
            }
            installBuildTools(buildArchive, stagePrefix)

            onProgress(Progress("Relocating JDK", "Adapting the Android JDK to PocketHost's private prefix.", 0.76f))
            patchPrefix(stagePrefix, TERMUX_PREFIX.toByteArray(), POCKET_PREFIX.toByteArray())
            installTrustStore(stagePrefix)
            createRuntimeDirectories(stagePrefix)
            createBuildToolAliases(stagePrefix)
            validateStagedBase(stagePrefix)

            oldPrefix.deleteRecursively()
            if (prefix.exists()) check(prefix.renameTo(oldPrefix)) { "Cannot preserve the previous local toolchain." }
            if (!stagePrefix.renameTo(prefix)) {
                if (oldPrefix.exists()) oldPrefix.renameTo(prefix)
                error("Cannot activate the verified local toolchain.")
            }
            oldPrefix.deleteRecursively()
            writeMarker()
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun installTermuxPackages(stageRoot: File, onProgress: (Progress) -> Unit) {
        val indexFile = File(cache, "termux-Packages.gz")
        download(
            TERMUX_INDEX_URL,
            indexFile,
            expectedDigest = null,
            algorithm = "SHA-256",
            maxBytes = 32L * 1024 * 1024,
        ) { bytes -> onProgress(Progress("Checking JDK", "Package catalog: ${formatBytes(bytes)}", 0.02f)) }
        val index = GZIPInputStream(indexFile.inputStream().buffered()).bufferedReader().use { parsePackageIndex(it.readText()) }
        val packages = resolvePackages(index, listOf("openjdk-17", "ca-certificates"))
        check(packages.any { it.name == "openjdk-17" }) { "The ARM64 repository does not contain OpenJDK 17." }

        var completed = 0L
        val total = packages.sumOf { it.size ?: 0L }.takeIf { it > 0 }
        packages.forEachIndexed { packageIndex, pkg ->
            val destination = File(cache, pkg.filename.substringAfterLast('/'))
            val packageBase = 0.04f + (packageIndex.toFloat() / max(1, packages.size)) * 0.53f
            download(
                TERMUX_REPOSITORY + pkg.filename,
                destination,
                expectedDigest = pkg.sha256,
                algorithm = "SHA-256",
                maxBytes = 350L * 1024 * 1024,
            ) { current ->
                val detail = if (total == null) {
                    "${pkg.name}: ${formatBytes(current)}"
                } else {
                    "${pkg.name}: ${formatBytes(completed + current)} / ${formatBytes(total)}"
                }
                onProgress(Progress("Installing JDK", detail, packageBase))
            }
            extractDebianPackage(destination, stageRoot)
            completed += pkg.size ?: destination.length()
        }
    }

    private fun installBuildTools(archive: File, stagePrefix: File) {
        val extracted = File(archive.parentFile, ".build-tools-${UUID.randomUUID()}")
        extracted.deleteRecursively()
        check(extracted.mkdirs()) { "Cannot create Build Tools extraction directory." }
        try {
            extractTarXz(archive.inputStream().buffered(), extracted)
            val aapt2 = extracted.walkTopDown().firstOrNull { it.isFile && it.name == "aapt2" }
                ?: error("The ARM64 Build Tools archive does not contain aapt2.")
            val source = aapt2.parentFile ?: error("Invalid Build Tools layout.")
            val destination = File(stagePrefix, "android-sdk/build-tools/$BUILD_TOOLS_VERSION")
            destination.deleteRecursively()
            check(source.copyRecursively(destination, overwrite = true)) { "Cannot install Android Build Tools." }
            File(destination, "aapt2").setExecutable(true, false)
        } finally {
            extracted.deleteRecursively()
        }
    }

    private fun createBuildToolAliases(stagePrefix: File) {
        val root = File(stagePrefix, "android-sdk/build-tools")
        val original = File(root, BUILD_TOOLS_VERSION)
        listOf("35.0.0", "36.0.0").forEach { version ->
            val alias = File(root, version)
            alias.deleteRecursively()
            check(original.copyRecursively(alias, overwrite = true)) { "Cannot create Build Tools $version compatibility alias." }
            File(alias, "aapt2").setExecutable(true, false)
            val properties = File(alias, "source.properties")
            if (properties.isFile) {
                properties.writeText(properties.readText().replace(Regex("Pkg.Revision\\s*=.*"), "Pkg.Revision=$version"))
            }
        }
    }

    private fun installPlatform(api: Int, sdk: File, baseProgress: Float, onProgress: (Progress) -> Unit) {
        val platform = loadPlatformCatalog()[api]
            ?: error("Android SDK Platform $api is not available in the stable Google repository.")
        val archive = File(cache, platform.url.substringAfterLast('/'))
        download(
            platform.url,
            archive,
            expectedDigest = platform.checksum,
            algorithm = platform.algorithm,
            maxBytes = 300L * 1024 * 1024,
        ) { bytes ->
            onProgress(Progress("Installing Android $api SDK", "Downloaded ${formatBytes(bytes)}.", baseProgress))
        }
        val temp = File(cache, ".platform-$api-${UUID.randomUUID()}")
        temp.deleteRecursively()
        check(temp.mkdirs()) { "Cannot create platform extraction directory." }
        try {
            extractZip(archive.inputStream().buffered(), temp, 512L * 1024 * 1024)
            val androidJar = temp.walkTopDown().firstOrNull { it.isFile && it.name == "android.jar" }
                ?: error("Android Platform $api archive does not contain android.jar.")
            val source = androidJar.parentFile ?: error("Invalid Android Platform layout.")
            val target = File(sdk, "platforms/android-$api")
            val staging = File(sdk, "platforms/.android-$api.staging")
            staging.deleteRecursively()
            check(source.copyRecursively(staging, overwrite = true)) { "Cannot stage Android Platform $api." }
            check(File(staging, "android.jar").isFile) { "Staged Android Platform $api is incomplete." }
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Cannot activate Android Platform $api." }
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun loadPlatformCatalog(): Map<Int, PlatformPackage> {
        var lastError: Throwable? = null
        for (version in 4 downTo 1) {
            val file = File(cache, "repository2-$version.xml")
            val result = runCatching {
                download(
                    "https://dl.google.com/android/repository/repository2-$version.xml",
                    file,
                    expectedDigest = null,
                    algorithm = "SHA-256",
                    maxBytes = 32L * 1024 * 1024,
                )
                parsePlatformCatalog(file)
            }
            if (result.isSuccess && result.getOrThrow().isNotEmpty()) return result.getOrThrow()
            lastError = result.exceptionOrNull()
        }
        throw IllegalStateException("Could not read Google's Android SDK repository catalog.", lastError)
    }

    private fun parsePlatformCatalog(file: File): Map<Int, PlatformPackage> {
        val candidates = mutableMapOf<Int, PlatformPackage>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(file.inputStream().buffered(), "UTF-8")
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "remotePackage") {
                val path = parser.getAttributeValue(null, "path").orEmpty()
                val api = Regex("platforms;android-(\\d+)").matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
                if (api != null) {
                    parseRemotePlatform(parser, api)?.let { candidate ->
                        val current = candidates[api]
                        if (current == null || candidate.revision > current.revision) candidates[api] = candidate
                    }
                    event = parser.eventType
                    continue
                }
            }
            event = parser.next()
        }
        return candidates
    }

    private fun parseRemotePlatform(parser: XmlPullParser, api: Int): PlatformPackage? {
        val startDepth = parser.depth
        var revision = 0
        var stable = true
        var url: String? = null
        var checksum: String? = null
        var algorithm = "SHA-1"
        var size: Long? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == startDepth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name.substringAfter(':')) {
                "major" -> revision = parser.nextText().trim().toIntOrNull() ?: revision
                "channelRef" -> stable = parser.getAttributeValue(null, "ref").orEmpty() == "channel-0"
                "url" -> if (url == null) url = parser.nextText().trim()
                "checksum" -> if (checksum == null) {
                    algorithm = when (parser.getAttributeValue(null, "type")?.lowercase()) {
                        "sha256" -> "SHA-256"
                        "sha1", null -> "SHA-1"
                        else -> "SHA-1"
                    }
                    checksum = parser.nextText().trim()
                }
                "size" -> if (size == null) size = parser.nextText().trim().toLongOrNull()
            }
        }
        val relative = url ?: return null
        val digest = checksum ?: return null
        if (!stable) return null
        return PlatformPackage(
            api = api,
            revision = revision,
            url = "https://dl.google.com/android/repository/$relative",
            checksum = digest,
            algorithm = algorithm,
            size = size,
        )
    }

    private fun installTrustStore(stagePrefix: File) {
        val destination = File(stagePrefix, "lib/jvm/java-17-openjdk/lib/security/cacerts")
        if (Files.isSymbolicLink(destination.toPath())) destination.delete()
        destination.parentFile?.mkdirs()
        context.assets.open("toolchain/cacerts").use { input ->
            destination.outputStream().buffered().use(input::copyTo)
        }
        check(destination.length() > 0) { "Bundled Java trust store is empty." }
    }

    private fun createRuntimeDirectories(stagePrefix: File) {
        listOf("home", "tmp", "var/tmp", "var/cache", "android-sdk/platforms").forEach {
            File(stagePrefix, it).mkdirs()
        }
        val license = File(stagePrefix, "android-sdk/licenses/android-sdk-license")
        license.parentFile?.mkdirs()
        license.writeText(
            listOf(
                "24333f8a63b6825ea9c5514f83c2829b004d1fee",
                "d56f5187479451eabf01fb78af6dfcb131a6481e",
                "8933bad161af4178b1185d1a37fbf41ea5269c55",
            ).joinToString("\n", postfix = "\n"),
        )
    }

    private fun validateStagedBase(stagePrefix: File) {
        val java = File(stagePrefix, "lib/jvm/java-17-openjdk/bin/java")
        val aapt2 = File(stagePrefix, "android-sdk/build-tools/$BUILD_TOOLS_VERSION/aapt2")
        check(java.isFile && java.setExecutable(true, false)) { "Staged Java launcher is missing or not executable." }
        check(aapt2.isFile && aapt2.setExecutable(true, false)) { "Staged ARM64 aapt2 is missing or not executable." }
        check(File(stagePrefix, "lib/jvm/java-17-openjdk/lib/modules").isFile) { "Staged OpenJDK runtime image is incomplete." }
    }

    private fun environment(validate: Boolean): MobileGradleEnvironment {
        val javaHome = File(prefix, "lib/jvm/java-17-openjdk")
        val java = File(javaHome, "bin/java")
        val sdk = File(prefix, "android-sdk")
        val aapt2 = File(sdk, "build-tools/$BUILD_TOOLS_VERSION/aapt2")
        val trustStore = File(javaHome, "lib/security/cacerts")
        if (validate) {
            check(java.isFile && java.canExecute()) { "Local OpenJDK 17 is missing." }
            check(aapt2.isFile && aapt2.canExecute()) { "ARM64 aapt2 is missing." }
            check(trustStore.isFile) { "Java trust store is missing." }
        }
        return MobileGradleEnvironment(
            prefix = prefix,
            javaHome = javaHome,
            java = java,
            sdk = sdk,
            aapt2 = aapt2,
            trustStore = trustStore,
            home = File(prefix, "home").apply { mkdirs() },
            temp = File(prefix, "tmp").apply { mkdirs() },
            gradleHome = File(prefix, "var/gradle").apply { mkdirs() },
        )
    }

    private fun extractDebianPackage(deb: File, targetRoot: File) {
        ArArchiveInputStream(BufferedInputStream(FileInputStream(deb))).use { ar ->
            var entry = ar.nextArEntry
            while (entry != null) {
                if (entry.name.startsWith("data.tar")) {
                    val data: InputStream = when {
                        entry.name.endsWith(".xz") -> XZCompressorInputStream(ar, true)
                        entry.name.endsWith(".gz") -> GzipCompressorInputStream(ar, true)
                        entry.name.endsWith(".bz2") -> BZip2CompressorInputStream(ar, true)
                        entry.name == "data.tar" -> ar
                        else -> error("Unsupported Debian payload compression: ${entry.name}")
                    }
                    extractTermuxTar(data, targetRoot)
                    return
                }
                entry = ar.nextArEntry
            }
        }
        error("Debian package ${deb.name} has no data archive.")
    }

    private fun extractTermuxTar(input: InputStream, targetRoot: File) {
        val symlinks = mutableListOf<Pair<File, String>>()
        val hardLinks = mutableListOf<Pair<File, String>>()
        TarArchiveInputStream(BufferedInputStream(input)).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val raw = entry.name.removePrefix("./")
                if (raw.startsWith(TERMUX_ARCHIVE_PREFIX)) {
                    val relative = raw.removePrefix(TERMUX_ARCHIVE_PREFIX)
                    val target = safeResolve(targetRoot, relative)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> symlinks += target to relocateLink(entry.linkName)
                        entry.isLink -> hardLinks += target to entry.linkName.removePrefix("./").removePrefix(TERMUX_ARCHIVE_PREFIX)
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output -> copyBounded(tar, output, MAX_EXTRACTED_FILE) }
                            applyMode(target, entry.mode)
                        }
                    }
                }
                entry = tar.nextTarEntry
            }
        }
        symlinks.forEach { (target, link) ->
            target.parentFile?.mkdirs()
            target.delete()
            Os.symlink(link, target.absolutePath)
        }
        hardLinks.forEach { (target, relative) ->
            val source = safeResolve(targetRoot, relative)
            check(source.exists()) { "Hard-link source is missing: $relative" }
            target.parentFile?.mkdirs()
            target.delete()
            runCatching { Os.link(source.absolutePath, target.absolutePath) }
                .getOrElse { source.copyTo(target, overwrite = true) }
        }
    }

    private fun extractTarXz(input: InputStream, target: File) {
        XZCompressorInputStream(input, true).use { xz -> extractGenericTar(xz, target) }
    }

    private fun extractGenericTar(input: InputStream, targetRoot: File) {
        val symlinks = mutableListOf<Pair<File, String>>()
        TarArchiveInputStream(BufferedInputStream(input)).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val target = safeResolve(targetRoot, entry.name.removePrefix("./"))
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> symlinks += target to entry.linkName
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output -> copyBounded(tar, output, MAX_EXTRACTED_FILE) }
                        applyMode(target, entry.mode)
                    }
                }
                entry = tar.nextTarEntry
            }
        }
        symlinks.forEach { (target, link) ->
            target.parentFile?.mkdirs()
            target.delete()
            Os.symlink(link, target.absolutePath)
        }
    }

    private fun extractZip(input: InputStream, targetRoot: File, maxBytes: Long) {
        var total = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = safeResolve(targetRoot, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output ->
                        total += copyBounded(zip, output, maxBytes - total)
                    }
                    check(total <= maxBytes) { "Expanded ZIP exceeds ${formatBytes(maxBytes)}." }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, maxBytes: Long): Long {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= maxBytes) { "Extracted file exceeds ${formatBytes(maxBytes)}." }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable(mode and 0b100100100 != 0, false)
        file.setWritable(mode and 0b010010010 != 0, false)
        file.setExecutable(mode and 0b001001001 != 0, false)
    }

    private fun relocateLink(link: String): String = link.replace(TERMUX_PREFIX, POCKET_PREFIX)

    private fun patchPrefix(root: File, old: ByteArray, replacement: ByteArray) {
        require(old.size == replacement.size)
        root.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.forEach { file ->
            patchFile(file, old, replacement)
        }
    }

    private fun patchFile(file: File, old: ByteArray, replacement: ByteArray) {
        if (file.length() < old.size) return
        FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.allocate(1024 * 1024)
            var offset = 0L
            while (offset < channel.size()) {
                buffer.clear()
                val read = channel.read(buffer, offset)
                if (read <= 0) break
                val bytes = buffer.array()
                var index = 0
                while (index <= read - old.size) {
                    var matches = true
                    for (needle in old.indices) {
                        if (bytes[index + needle] != old[needle]) {
                            matches = false
                            break
                        }
                    }
                    if (matches) {
                        channel.write(ByteBuffer.wrap(replacement), offset + index)
                        replacement.copyInto(bytes, index)
                        index += replacement.size
                    } else {
                        index += 1
                    }
                }
                offset += max(1, read - old.size + 1)
            }
        }
    }

    private fun parsePackageIndex(text: String): Map<String, PackageRecord> {
        val records = mutableMapOf<String, PackageRecord>()
        text.split("\n\n").forEach { block ->
            val fields = linkedMapOf<String, String>()
            var current: String? = null
            block.lineSequence().forEach { line ->
                if ((line.startsWith(' ') || line.startsWith('\t')) && current != null) {
                    fields[current!!] = fields[current!!].orEmpty() + " " + line.trim()
                } else {
                    val separator = line.indexOf(": ")
                    if (separator > 0) {
                        current = line.substring(0, separator)
                        fields[current!!] = line.substring(separator + 2)
                    }
                }
            }
            val name = fields["Package"] ?: return@forEach
            val filename = fields["Filename"] ?: return@forEach
            val sha = fields["SHA256"] ?: return@forEach
            records[name] = PackageRecord(
                name = name,
                version = fields["Version"].orEmpty(),
                filename = filename,
                sha256 = sha,
                size = fields["Size"]?.toLongOrNull(),
                dependencies = parseDependencies(fields["Pre-Depends"]) + parseDependencies(fields["Depends"]),
                provides = parseDependencies(fields["Provides"]).flatten().toSet(),
            )
        }
        return records
    }

    private fun parseDependencies(value: String?): List<List<String>> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',').mapNotNull { group ->
            group.split('|').mapNotNull { raw ->
                raw.trim().substringBefore(' ').substringBefore('(').substringBefore(':').takeIf { it.isNotBlank() }
            }.takeIf { it.isNotEmpty() }
        }
    }

    private fun resolvePackages(index: Map<String, PackageRecord>, roots: List<String>): List<PackageRecord> {
        val providers = mutableMapOf<String, String>()
        index.values.forEach { record -> record.provides.forEach { providers.putIfAbsent(it, record.name) } }
        val result = mutableListOf<PackageRecord>()
        val queue = ArrayDeque(roots)
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val requested = queue.removeFirst()
            val actual = if (requested in index) requested else providers[requested]
                ?: error("Required JDK package is unavailable: $requested")
            if (!seen.add(actual)) continue
            val record = index.getValue(actual)
            result += record
            record.dependencies.forEach { alternatives ->
                val selected = alternatives.firstOrNull { it in index || it in providers }
                    ?: error("No available package satisfies ${alternatives.joinToString(" | ")}")
                queue.addLast(selected)
            }
        }
        return result
    }

    private fun download(
        url: String,
        destination: File,
        expectedDigest: String?,
        algorithm: String,
        maxBytes: Long,
        onBytes: (Long) -> Unit = {},
    ) {
        destination.parentFile?.mkdirs()
        if (destination.isFile && destination.length() in 1..maxBytes && expectedDigest != null) {
            if (digest(destination, algorithm).equals(expectedDigest, ignoreCase = true)) {
                onBytes(destination.length())
                return
            }
            destination.delete()
        }
        val partial = File(destination.absolutePath + ".part")
        partial.delete()
        var current = URI(url)
        repeat(6) { redirects ->
            require(current.scheme.equals("https", true)) { "Toolchain downloads require HTTPS." }
            require(current.host?.lowercase() in TRUSTED_HOSTS) { "Untrusted toolchain host: ${current.host}" }
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "PocketHost/0.6")
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    check(redirects < 5) { "Too many toolchain download redirects." }
                    current = current.resolve(connection.getHeaderField("Location") ?: error("Redirect has no location."))
                    return@repeat
                }
                check(code in 200..299) { "Toolchain download failed with HTTP $code." }
                var total = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            check(total <= maxBytes) { "Toolchain download exceeds ${formatBytes(maxBytes)}." }
                            output.write(buffer, 0, read)
                            onBytes(total)
                        }
                    }
                }
                check(total > 0) { "Toolchain download was empty." }
                expectedDigest?.let { expected ->
                    check(digest(partial, algorithm).equals(expected, ignoreCase = true)) {
                        "$algorithm checksum mismatch for ${destination.name}."
                    }
                }
                destination.delete()
                check(partial.renameTo(destination)) { "Cannot finalize ${destination.name}." }
                return
            } finally {
                connection.disconnect()
            }
        }
        error("Toolchain download failed.")
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
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

    private fun safeResolve(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/').removePrefix("./").trim('/')
        val canonicalRoot = root.canonicalFile
        if (normalized.isBlank() || normalized == ".") return canonicalRoot
        require(normalized.split('/').none { it == ".." || it.isBlank() || it == "." }) { "Unsafe archive path: $relative" }
        val target = File(canonicalRoot, normalized).canonicalFile
        require(target == canonicalRoot || target.path.startsWith(canonicalRoot.path + File.separator)) {
            "Archive path escapes its root: $relative"
        }
        return target
    }

    private fun writeMarker() {
        val temporary = File(files, "$MARKER.tmp")
        val backup = File(files, "$MARKER.backup")
        temporary.writeText(TOOLCHAIN_VERSION)
        backup.delete()
        if (marker.exists()) check(marker.renameTo(backup)) { "Cannot preserve the previous toolchain marker." }
        if (!temporary.renameTo(marker)) {
            if (backup.exists()) backup.renameTo(marker)
            error("Cannot write toolchain marker.")
        }
        backup.delete()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
        else -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
    }

    private data class PackageRecord(
        val name: String,
        val version: String,
        val filename: String,
        val sha256: String,
        val size: Long?,
        val dependencies: List<List<String>>,
        val provides: Set<String>,
    )

    private data class PlatformPackage(
        val api: Int,
        val revision: Int,
        val url: String,
        val checksum: String,
        val algorithm: String,
        val size: Long?,
    )

    companion object {
        private const val TOOLCHAIN_VERSION = "mobile-gradle-v1"
        private const val MARKER = ".pocket-gradle-toolchain"
        private const val EXPECTED_APPLICATION_ID = "com.pocket"
        private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        private const val POCKET_PREFIX = "/data/data/com.pocket/files/usr"
        private const val TERMUX_ARCHIVE_PREFIX = "data/data/com.termux/files/"
        private const val TERMUX_REPOSITORY = "https://packages-cf.termux.dev/apt/termux-main/"
        private const val TERMUX_INDEX_URL = "${TERMUX_REPOSITORY}dists/stable/main/binary-aarch64/Packages.gz"
        private const val BUILD_TOOLS_VERSION = "34.0.4"
        private const val BUILD_TOOLS_URL = "https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/v34.0.4/build-tools-34.0.4-aarch64.tar.xz"
        // Filled from the release probe in CI. A blank value still uses HTTPS and archive validation.
        private const val BUILD_TOOLS_SHA256 = "4bbbdeee608ff8d3a2c1534d0a5270305a02b1f200403a536c75ac84c41e52fa"
        private const val DEFAULT_PLATFORM = 36
        private const val MIN_PLATFORM = 21
        private const val MAX_PLATFORM = 36
        private const val MAX_EXTRACTED_FILE = 512L * 1024 * 1024
        private const val MIN_FREE_BYTES = 1_200L * 1024 * 1024
        private const val MIN_PLATFORM_FREE_BYTES = 512L * 1024 * 1024
        private val TRUSTED_HOSTS = setOf(
            "packages-cf.termux.dev",
            "github.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "dl.google.com",
            "redirector.gvt1.com",
        )
    }
}

data class MobileGradleEnvironment(
    val prefix: File,
    val javaHome: File,
    val java: File,
    val sdk: File,
    val aapt2: File,
    val trustStore: File,
    val home: File,
    val temp: File,
    val gradleHome: File,
)
