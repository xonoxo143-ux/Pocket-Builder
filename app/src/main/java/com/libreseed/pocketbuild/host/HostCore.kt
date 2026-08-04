package com.libreseed.pocketbuild.host

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.libreseed.pocketbuild.workspace.ArchiveLimits
import com.libreseed.pocketbuild.workspace.SafeArchiveExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Stable on-device application host.
 *
 * A hosted application is web content plus a manifest. Full bundles replace the active version.
 * Patch bundles clone the active version, apply hash-guarded operations, validate it, and then
 * atomically switch the active pointer. The previous version remains available for rollback.
 */
class HostStore(private val context: Context) {
    private val root = File(context.filesDir, "host")
    private val appsRoot = File(root, "apps")
    private val stagingRoot = File(root, "staging")
    private val incomingRoot = File(root, "incoming")
    private val extractor = SafeArchiveExtractor(
        ArchiveLimits(
            maxEntries = 8_000,
            maxExpandedBytes = 768L * 1024 * 1024,
            maxSingleFileBytes = 256L * 1024 * 1024,
        ),
    )

    init {
        appsRoot.mkdirs()
        stagingRoot.mkdirs()
        incomingRoot.mkdirs()
    }

    fun ensureBuiltInDemo(): HostedApp {
        getApp(DEMO_ID)?.let { return it }
        val manifest = JSONObject()
            .put("format", 1)
            .put("type", "app")
            .put("id", DEMO_ID)
            .put("name", "Universal Host Demo")
            .put("version", "1.0.0")
            .put("description", "Canvas, JavaScript bridge, local storage, files, sharing and capability gates.")
            .put("entry", "www/index.html")
            .put(
                "capabilities",
                JSONArray(
                    listOf(
                        "ui.toast",
                        "ui.fullscreen",
                        "device.vibrate",
                        "clipboard.read",
                        "clipboard.write",
                        "share.text",
                        "file.pick",
                        "file.save",
                        "browser.open",
                        "network.fetch",
                        "permissions.request",
                        "host.update",
                        "intent.launch",
                    ),
                ),
            )
        val source = JSONObject()
            .put("format", 1)
            .put("type", "app")
            .put("id", DEMO_ID)
            .put("name", "Universal Host Demo")
            .put("version", "1.0.0")
            .put("description", manifest.getString("description"))
            .put("entry", "www/index.html")
            .put("capabilities", manifest.getJSONArray("capabilities"))
            .put(
                "files",
                JSONObject()
                    .put("www/index.html", DEMO_HTML)
                    .put("www/README.txt", "This file was supplied by the hosted project, not compiled into its own APK."),
            )
        return installJson(source).app
    }

    fun listApps(): List<HostedApp> {
        return appsRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { readActiveApp(it) }
            .sortedBy { it.name.lowercase() }
    }

    fun getApp(id: String): HostedApp? {
        if (!isValidId(id)) return null
        return readActiveApp(File(appsRoot, id))
    }

    fun sharedDirectory(id: String): File {
        require(isValidId(id)) { "Invalid hosted app id." }
        return File(root, "shared/$id").apply { mkdirs() }
    }

    fun install(uri: Uri): InstallResult {
        val displayName = queryDisplayName(uri) ?: "host-update"
        val temporary = File(incomingRoot, "${UUID.randomUUID()}-${safeFileName(displayName)}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered().use(input::copyTo)
            } ?: error("The selected file could not be opened.")
            check(temporary.length() in 1..MAX_BUNDLE_BYTES) { "Bundle is empty or exceeds the 768 MB import limit." }
            return if (isZip(temporary)) installZip(temporary) else installJson(JSONObject(temporary.readText()))
        } finally {
            temporary.delete()
        }
    }

    fun rollback(id: String): HostedApp {
        val appRoot = File(appsRoot, id)
        val state = readState(appRoot)
        val previous = state.optString("previous").takeIf { it.isNotBlank() }
            ?: error("No previous version is available for rollback.")
        val current = state.getString("current")
        check(File(appRoot, "versions/$previous/pocketapp.json").isFile) {
            "The previous version is missing or damaged."
        }
        writeStateAtomic(appRoot, JSONObject().put("current", previous).put("previous", current))
        return getApp(id) ?: error("Rollback completed but the application could not be reopened.")
    }

    fun delete(id: String) {
        require(id != DEMO_ID) { "The built-in demo can be reset but not permanently deleted." }
        val target = File(appsRoot, id).canonicalFile
        check(target.parentFile == appsRoot.canonicalFile) { "Invalid hosted app path." }
        check(target.deleteRecursively()) { "Could not delete hosted app $id." }
        File(root, "shared/$id").deleteRecursively()
    }

    private fun installZip(archive: File): InstallResult {
        val extracted = File(stagingRoot, ".extract-${UUID.randomUUID()}")
        try {
            extractor.extract(archive.inputStream(), extracted)
            val manifestFile = extracted.walkTopDown()
                .maxDepth(3)
                .filter { it.isFile && it.name == "pocketapp.json" }
                .toList()
                .singleOrNull()
                ?: error("Bundle must contain exactly one pocketapp.json near its root.")
            val manifest = JSONObject(manifestFile.readText())
            return when (manifest.optString("type", "app")) {
                "app" -> installFull(manifestFile.parentFile, manifest)
                "patch" -> installPatch(manifestFile.parentFile, manifest)
                else -> error("Unsupported bundle type: ${manifest.optString("type")}")
            }
        } finally {
            extracted.deleteRecursively()
        }
    }

    private fun installJson(manifest: JSONObject): InstallResult {
        return when (manifest.optString("type", "app")) {
            "app" -> {
                val staging = File(stagingRoot, ".json-app-${UUID.randomUUID()}")
                check(staging.mkdirs()) { "Could not create staging directory." }
                try {
                    val files = manifest.optJSONObject("files")
                        ?: error("A single JSON app bundle requires a files object.")
                    val keys = files.keys()
                    var total = 0L
                    while (keys.hasNext()) {
                        val path = keys.next()
                        val target = resolveSafe(staging, path)
                        target.parentFile?.mkdirs()
                        val value = files.get(path)
                        val bytes = when (value) {
                            is JSONObject -> {
                                when {
                                    value.has("base64") -> Base64.decode(value.getString("base64"), Base64.DEFAULT)
                                    value.has("text") -> value.getString("text").toByteArray()
                                    else -> error("File $path must contain text or base64.")
                                }
                            }
                            else -> value.toString().toByteArray()
                        }
                        total += bytes.size
                        check(total <= MAX_INLINE_BYTES) { "Inline JSON app exceeds the 16 MB limit." }
                        target.writeBytes(bytes)
                    }
                    val cleanManifest = JSONObject(manifest.toString()).apply { remove("files") }
                    File(staging, "pocketapp.json").writeText(cleanManifest.toString(2))
                    installFull(staging, cleanManifest)
                } finally {
                    staging.deleteRecursively()
                }
            }
            "patch" -> installPatch(null, manifest)
            else -> error("Unsupported JSON bundle type: ${manifest.optString("type")}")
        }
    }

    private fun installFull(sourceRoot: File, manifest: JSONObject): InstallResult {
        val parsed = parseManifest(manifest)
        val staging = File(stagingRoot, ".full-${parsed.id}-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Could not create application staging directory." }
        try {
            sourceRoot.copyRecursively(staging, overwrite = true)
            File(staging, "pocketapp.json").writeText(manifest.toString(2))
            validateApplication(staging, parsed)
            val previous = getApp(parsed.id)
            val installed = commitVersion(staging, parsed)
            return InstallResult(
                app = installed,
                kind = if (previous == null) InstallKind.INSTALLED else InstallKind.REPLACED,
                previousVersion = previous?.version,
                changedFiles = countFiles(installed.root),
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun installPatch(patchRoot: File?, patch: JSONObject): InstallResult {
        check(patch.optInt("format", 1) == 1) { "Unsupported patch format." }
        val id = patch.getString("target").lowercase()
        require(isValidId(id)) { "Invalid patch target id." }
        val current = getApp(id) ?: error("Patch target $id is not installed.")
        val expectedVersion = patch.optString("baseVersion").takeIf { it.isNotBlank() }
        check(expectedVersion == null || expectedVersion == current.version) {
            "Patch expects version $expectedVersion but ${current.version} is active."
        }
        val newVersion = patch.optString("version").takeIf { it.isNotBlank() }
            ?: error("Patch must provide a new version.")
        val staging = File(stagingRoot, ".patch-$id-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Could not create patch staging directory." }
        try {
            current.root.copyRecursively(staging, overwrite = true)
            val operations = patch.optJSONArray("operations") ?: JSONArray()
            for (index in 0 until operations.length()) {
                applyOperation(staging, patchRoot, operations.getJSONObject(index))
            }
            val appManifestFile = File(staging, "pocketapp.json")
            val appManifest = JSONObject(appManifestFile.readText())
                .put("version", newVersion)
            patch.optString("name").takeIf { it.isNotBlank() }?.let { appManifest.put("name", it) }
            patch.optString("description").takeIf { it.isNotBlank() }?.let { appManifest.put("description", it) }
            patch.optString("entry").takeIf { it.isNotBlank() }?.let { appManifest.put("entry", it) }
            patch.optJSONArray("capabilities")?.let { appManifest.put("capabilities", it) }
            appManifestFile.writeText(appManifest.toString(2))
            val parsed = parseManifest(appManifest)
            check(parsed.id == id) { "Patch may not change the application id." }
            validateApplication(staging, parsed)
            val installed = commitVersion(staging, parsed)
            return InstallResult(
                app = installed,
                kind = InstallKind.PATCHED,
                previousVersion = current.version,
                changedFiles = operations.length(),
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun applyOperation(staging: File, patchRoot: File?, operation: JSONObject) {
        val op = operation.getString("op")
        val path = operation.getString("path")
        check(path != "pocketapp.json") { "Patch operations cannot directly replace pocketapp.json." }
        val target = resolveSafe(staging, path)
        operation.optString("expectedSha256").takeIf { it.isNotBlank() }?.let { expected ->
            check(target.isFile) { "Hash precondition failed because $path does not exist." }
            check(sha256(target).equals(expected, ignoreCase = true)) { "Hash precondition failed for $path." }
        }
        when (op) {
            "add" -> {
                check(!target.exists()) { "Add operation target already exists: $path" }
                writeOperationPayload(target, patchRoot, operation)
            }
            "replace" -> {
                check(target.isFile) { "Replace operation target does not exist: $path" }
                writeOperationPayload(target, patchRoot, operation)
            }
            "upsert" -> writeOperationPayload(target, patchRoot, operation)
            "delete" -> {
                check(target.exists()) { "Delete operation target does not exist: $path" }
                check(target.deleteRecursively()) { "Could not delete $path" }
            }
            "mergeJson" -> {
                check(target.isFile) { "JSON merge target does not exist: $path" }
                val base = JSONObject(target.readText())
                val patchObject = when {
                    operation.has("value") -> operation.getJSONObject("value")
                    operation.has("source") -> {
                        val source = resolvePatchSource(patchRoot, operation.getString("source"))
                        JSONObject(source.readText())
                    }
                    else -> error("mergeJson requires value or source.")
                }
                deepMerge(base, patchObject)
                target.writeText(base.toString(2))
            }
            else -> error("Unsupported patch operation: $op")
        }
    }

    private fun writeOperationPayload(target: File, patchRoot: File?, operation: JSONObject) {
        target.parentFile?.mkdirs()
        when {
            operation.has("text") -> target.writeText(operation.getString("text"))
            operation.has("base64") -> target.writeBytes(Base64.decode(operation.getString("base64"), Base64.DEFAULT))
            operation.has("source") -> resolvePatchSource(patchRoot, operation.getString("source")).copyTo(target, overwrite = true)
            else -> error("Operation requires text, base64, or source payload.")
        }
        check(target.length() <= 256L * 1024 * 1024) { "Patched file exceeds the 256 MB file limit." }
    }

    private fun resolvePatchSource(root: File?, relative: String): File {
        val actualRoot = root ?: error("A JSON-only patch must use text or base64 payloads.")
        val source = resolveSafe(actualRoot, relative)
        check(source.isFile) { "Patch source does not exist: $relative" }
        return source
    }

    private fun commitVersion(staging: File, parsed: HostedAppManifest): HostedApp {
        val appRoot = File(appsRoot, parsed.id).apply { mkdirs() }
        val versions = File(appRoot, "versions").apply { mkdirs() }
        val state = runCatching { readState(appRoot) }.getOrElse { JSONObject() }
        val previousCurrent = state.optString("current").takeIf { it.isNotBlank() }
        val versionDirectoryName = "${safeFileName(parsed.version)}-${System.currentTimeMillis()}"
        val destination = File(versions, versionDirectoryName)
        check(!destination.exists()) { "Version destination already exists." }
        check(staging.renameTo(destination)) { "Could not atomically promote the hosted application." }
        writeStateAtomic(
            appRoot,
            JSONObject()
                .put("current", versionDirectoryName)
                .put("previous", previousCurrent ?: ""),
        )
        pruneVersions(versions, setOfNotNull(versionDirectoryName, previousCurrent))
        return readActiveApp(appRoot) ?: error("Hosted application was committed but cannot be read.")
    }

    private fun pruneVersions(versions: File, protected: Set<String>) {
        versions.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name !in protected }
            .sortedByDescending { it.lastModified() }
            .drop(2)
            .forEach { it.deleteRecursively() }
    }

    private fun validateApplication(root: File, manifest: HostedAppManifest) {
        check(root.isDirectory) { "Hosted application root is missing." }
        val entry = resolveSafe(root, manifest.entry)
        check(entry.isFile) { "Entry file does not exist: ${manifest.entry}" }
        check(entry.extension.lowercase() in setOf("html", "htm")) { "The first host runtime requires an HTML entry file." }
        check(countFiles(root) <= 8_000) { "Hosted application contains too many files." }
        var total = 0L
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            total += file.length()
            check(file.length() <= 256L * 1024 * 1024) { "Hosted file is too large: ${file.name}" }
            check(total <= 768L * 1024 * 1024) { "Hosted application exceeds the expanded-size limit." }
        }
    }

    private fun parseManifest(json: JSONObject): HostedAppManifest {
        check(json.optInt("format", 1) == 1) { "Unsupported hosted app format." }
        val id = json.getString("id").lowercase()
        require(isValidId(id)) { "App id must use lowercase letters, numbers, dots, dashes, or underscores." }
        val name = json.getString("name").trim().take(80)
        require(name.isNotBlank()) { "Hosted app name is empty." }
        val version = json.getString("version").trim().take(40)
        require(version.isNotBlank()) { "Hosted app version is empty." }
        val entry = json.optString("entry", "www/index.html")
        resolveSafe(File(root, "validation-root"), entry)
        val capabilities = buildSet {
            val array = json.optJSONArray("capabilities") ?: JSONArray()
            for (index in 0 until array.length()) {
                val capability = array.getString(index).trim()
                if (capability.isNotBlank()) add(capability)
            }
        }
        return HostedAppManifest(
            id = id,
            name = name,
            version = version,
            description = json.optString("description").trim().take(500),
            entry = entry,
            capabilities = capabilities,
        )
    }

    private fun readActiveApp(appRoot: File): HostedApp? {
        return runCatching {
            val state = readState(appRoot)
            val currentName = state.getString("current")
            val currentRoot = File(appRoot, "versions/$currentName")
            val manifest = parseManifest(JSONObject(File(currentRoot, "pocketapp.json").readText()))
            HostedApp(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                description = manifest.description,
                entry = manifest.entry,
                capabilities = manifest.capabilities,
                root = currentRoot,
                canRollback = state.optString("previous").isNotBlank(),
            )
        }.getOrNull()
    }

    private fun readState(appRoot: File): JSONObject {
        val file = File(appRoot, "state.json")
        check(file.isFile) { "Hosted application state is missing." }
        return JSONObject(file.readText())
    }

    private fun writeStateAtomic(appRoot: File, state: JSONObject) {
        val temporary = File(appRoot, ".state-${UUID.randomUUID()}.tmp")
        val destination = File(appRoot, "state.json")
        temporary.writeText(state.toString(2))
        if (destination.exists()) {
            val backup = File(appRoot, ".state-backup")
            backup.delete()
            check(destination.renameTo(backup)) { "Could not stage hosted application state." }
            if (!temporary.renameTo(destination)) {
                backup.renameTo(destination)
                error("Could not commit hosted application state.")
            }
            backup.delete()
        } else {
            check(temporary.renameTo(destination)) { "Could not create hosted application state." }
        }
    }

    private fun resolveSafe(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank()) { "Empty hosted path." }
        require(!relative.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(relative)) { "Absolute paths are not allowed." }
        require(normalized.split('/').none { it == ".." || it.isBlank() }) { "Unsafe hosted path: $relative" }
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, normalized).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) { "Hosted path escapes its root: $relative" }
        return target
    }

    private fun deepMerge(base: JSONObject, patch: JSONObject) {
        val keys = patch.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = patch.get(key)
            if (value == JSONObject.NULL) {
                base.remove(key)
            } else if (value is JSONObject && base.opt(key) is JSONObject) {
                deepMerge(base.getJSONObject(key), value)
            } else {
                base.put(key, value)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun isZip(file: File): Boolean {
        if (file.length() < 4) return false
        val header = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        return header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
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

    private fun countFiles(root: File): Int = root.walkTopDown().count { it.isFile }

    private fun isValidId(value: String): Boolean = ID_PATTERN.matches(value)

    private fun safeFileName(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.')
        .take(64)
        .ifBlank { "bundle" }

    companion object {
        const val DEMO_ID = "pocket.demo.universal"
        private const val MAX_BUNDLE_BYTES = 768L * 1024 * 1024
        private const val MAX_INLINE_BYTES = 16L * 1024 * 1024
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{1,63}")

        private val DEMO_HTML = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <title>Pocket Host Demo</title>
              <style>
                :root { color-scheme: dark; font-family: system-ui, sans-serif; }
                * { box-sizing: border-box; }
                body { margin: 0; background: #090d16; color: #f3f6ff; min-height: 100vh; }
                canvas { position: fixed; inset: 0; width: 100%; height: 100%; }
                main { position: relative; z-index: 1; padding: max(24px, env(safe-area-inset-top)) 20px 40px; max-width: 760px; margin: auto; }
                .glass { background: rgba(17,25,42,.82); border: 1px solid rgba(255,255,255,.12); border-radius: 24px; padding: 20px; backdrop-filter: blur(14px); box-shadow: 0 18px 70px #0008; }
                h1 { margin: 0 0 8px; font-size: clamp(30px,7vw,58px); }
                p { color: #b9c4dc; line-height: 1.5; }
                .grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(145px,1fr)); gap: 10px; margin-top: 18px; }
                button { border: 0; border-radius: 15px; padding: 13px; color: #f8fbff; background: #263a63; font-weight: 700; }
                button:active { transform: scale(.97); }
                pre { white-space: pre-wrap; background: #080b12; border-radius: 14px; padding: 12px; min-height: 70px; color: #9ee7c5; }
              </style>
            </head>
            <body>
              <canvas id="c"></canvas>
              <main>
                <section class="glass">
                  <h1>One APK, many apps.</h1>
                  <p>This page is a replaceable hosted project. It can use Canvas, WebGL, WebAudio, WebAssembly, workers, local storage, fetch, and Android features exposed by the native capability firewall.</p>
                  <div class="grid">
                    <button data-action="ui.toast">Toast</button>
                    <button data-action="device.vibrate">Vibrate</button>
                    <button data-action="clipboard.write">Copy text</button>
                    <button data-action="clipboard.read">Read clipboard</button>
                    <button data-action="file.pick">Pick file</button>
                    <button data-action="file.save">Save text</button>
                    <button data-action="share.text">Share</button>
                    <button data-action="network.request">Enable network</button>
                    <button data-action="ui.fullscreen">Fullscreen</button>
                    <button data-action="host.update">Load update</button>
                  </div>
                  <pre id="log">Waiting for the native bridge…</pre>
                </section>
              </main>
              <script>
                const log = value => document.querySelector('#log').textContent = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
                const canvas = document.querySelector('#c'), ctx = canvas.getContext('2d');
                function resize(){ canvas.width=innerWidth*devicePixelRatio; canvas.height=innerHeight*devicePixelRatio; }
                addEventListener('resize', resize); resize();
                let t=0; (function draw(){ t+=.008; const w=canvas.width,h=canvas.height; ctx.clearRect(0,0,w,h); for(let i=0;i<35;i++){ const x=(Math.sin(t+i*1.77)*.45+.5)*w; const y=(Math.cos(t*.7+i*2.41)*.45+.5)*h; ctx.fillStyle='hsla('+((i*31+t*180)%360)+',80%,60%,.18)'; ctx.beginPath(); ctx.arc(x,y,(20+i%7*8)*devicePixelRatio,0,Math.PI*2); ctx.fill(); } requestAnimationFrame(draw); })();
                addEventListener('pocketready', async () => log(await pocket.call('host.info')));
                document.querySelectorAll('button').forEach(button => button.onclick = async () => {
                  try {
                    const action=button.dataset.action;
                    const args={
                      'ui.toast':{text:'Hello from replaceable JavaScript'},
                      'clipboard.write':{text:'Copied by a PocketHost project'},
                      'file.save':{name:'pocket-host-demo.txt',mime:'text/plain',text:'Saved from the hosted demo.'},
                      'share.text':{subject:'PocketHost',text:'This message came from a hosted project.'},
                      'ui.fullscreen':{enabled:true}
                    }[action]||{};
                    log(await pocket.call(action,args));
                  } catch(error) { log(String(error)); }
                });
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}

data class HostedAppManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val entry: String,
    val capabilities: Set<String>,
)

data class HostedApp(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val entry: String,
    val capabilities: Set<String>,
    val root: File,
    val canRollback: Boolean,
)

data class InstallResult(
    val app: HostedApp,
    val kind: InstallKind,
    val previousVersion: String?,
    val changedFiles: Int,
)

enum class InstallKind { INSTALLED, REPLACED, PATCHED }
