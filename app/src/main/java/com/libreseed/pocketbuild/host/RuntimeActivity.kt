package com.libreseed.pocketbuild.host

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.libreseed.pocketbuild.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class RuntimeActivity : ComponentActivity() {
    private lateinit var store: HostStore
    private lateinit var app: HostedApp
    private lateinit var firewall: CapabilityFirewall
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var origin: String
    private lateinit var sharedDirectory: File

    private var pendingPermissionNames: List<String> = emptyList()
    private var pendingPermissionResult: ((Map<String, Boolean>) -> Unit)? = null
    private var pendingBridgeFileResult: ((JSONObject) -> Unit)? = null
    private var pendingSaveText: String? = null
    private var pendingSaveReply: Reply? = null
    private var pendingWebFileCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { platformResults ->
        val callback = pendingPermissionResult
        val names = pendingPermissionNames
        pendingPermissionResult = null
        pendingPermissionNames = emptyList()
        val result = names.associateWith { name ->
            permissionForName(name)?.let { permission ->
                platformResults[permission] ?: (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)
            } ?: true
        }
        callback?.invoke(result)
    }

    private val bridgeFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingBridgeFileResult
        pendingBridgeFileResult = null
        if (callback == null) return@registerForActivityResult
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            callback(JSONObject().put("cancelled", true))
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) { runCatching { copyPickedFile(uri) } }
            copied.onSuccess(callback).onFailure { callback(JSONObject().put("error", it.message ?: "File import failed.")) }
        }
    }

    private val bridgeSavePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val reply = pendingSaveReply
        val text = pendingSaveText
        pendingSaveReply = null
        pendingSaveText = null
        val uri = result.data?.data
        if (reply == null || text == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            reply.success(JSONObject().put("cancelled", true))
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val write = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(text) }
                        ?: error("The selected destination could not be opened.")
                }
            }
            write.onSuccess { reply.success(JSONObject().put("saved", true).put("uri", uri.toString())) }
                .onFailure { reply.failure(it.message ?: "Save failed.") }
        }
    }

    private val updatePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val reply = pendingUpdateReply
        pendingUpdateReply = null
        if (reply == null) return@registerForActivityResult
        if (uri == null) {
            reply.success(JSONObject().put("cancelled", true))
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.install(uri) } }
            result.onSuccess { installed ->
                reply.success(
                    JSONObject()
                        .put("id", installed.app.id)
                        .put("version", installed.app.version)
                        .put("kind", installed.kind.name.lowercase()),
                )
                Toast.makeText(this@RuntimeActivity, "Installed ${installed.app.name} ${installed.app.version}", Toast.LENGTH_LONG).show()
                if (installed.app.id == app.id) recreate()
            }.onFailure { reply.failure(it.message ?: "Update installation failed.") }
        }
    }
    private var pendingUpdateReply: Reply? = null

    private val webFileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingWebFileCallback
        pendingWebFileCallback = null
        callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = HostStore(this)
        firewall = CapabilityFirewall(this)
        val appId = intent.getStringExtra(EXTRA_APP_ID)
        app = appId?.let(store::getApp) ?: run {
            Toast.makeText(this, "Hosted app is missing or damaged.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        sharedDirectory = store.sharedDirectory(app.id)
        origin = "https://app-${digest(app.id).take(16)}.pocket.invalid"
        buildChrome()
        configureRuntime()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> leaveFullscreen()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
        webView.loadUrl("$origin/app/${app.entry}")
    }

    override fun onDestroy() {
        pendingWebFileCallback?.onReceiveValue(null)
        pendingWebFileCallback = null
        if (::webView.isInitialized) {
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun buildChrome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.rgb(14, 20, 33))
        }
        val title = TextView(this).apply {
            text = "${app.name}  ·  ${app.version}"
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
        }
        bar.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(toolbarButton("Reload") { webView.reload() })
        bar.addView(toolbarButton("Apps") { finish() })
        webView = WebView(this)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun toolbarButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun configureRuntime() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(Uri.parse(origin).host!!)
            .addPathHandler("/app/", WebViewAssetLoader.InternalStoragePathHandler(this, app.root))
            .addPathHandler("/shared/", WebViewAssetLoader.InternalStoragePathHandler(this, sharedDirectory))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setGeolocationEnabled(true)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.setBackgroundColor(Color.BLACK)
        webView.removeJavascriptInterface("searchBoxJavaBridge_")
        webView.removeJavascriptInterface("accessibility")
        webView.removeJavascriptInterface("accessibilityTraversal")
        webView.webViewClient = RuntimeClient()
        webView.webChromeClient = RuntimeChrome()

        val allowedOrigin = setOf(origin)
        val modernBridge = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (modernBridge) {
            WebViewCompat.addWebMessageListener(
                webView,
                "PocketNative",
                allowedOrigin,
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        if (!isMainFrame || sourceOrigin.toString() != origin) return
                        val payload = message.data ?: return
                        handleMessage(payload, ProxyReply(replyProxy))
                    }
                },
            )
        } else {
            webView.addJavascriptInterface(LegacyBridge(), "PocketNative")
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, BOOTSTRAP_SCRIPT, allowedOrigin)
        }
    }

    private fun handleMessage(payload: String, transport: TransportReply) {
        if (payload.length > 256 * 1024) {
            transport.send(JSONObject().put("id", "").put("ok", false).put("error", "Bridge request is too large.").toString())
            return
        }
        val message = runCatching { JSONObject(payload) }.getOrElse {
            transport.send(JSONObject().put("id", "").put("ok", false).put("error", "Invalid bridge JSON.").toString())
            return
        }
        val id = message.optString("id")
        val action = message.optString("action")
        val args = message.optJSONObject("args") ?: JSONObject()
        if (id.isBlank() || action.isBlank()) return
        val reply = Reply(id, transport)
        runOnUiThread {
            runCatching { executeAction(action, args, reply) }
                .onFailure { reply.failure(it.message ?: "Host action failed.") }
        }
    }

    private fun executeAction(action: String, args: JSONObject, reply: Reply) {
        when (action) {
            "host.info" -> reply.success(
                JSONObject()
                    .put("coreApi", 1)
                    .put("hostVersion", BuildConfig.VERSION_NAME)
                    .put("app", JSONObject().put("id", app.id).put("name", app.name).put("version", app.version))
                    .put("android", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("capabilities", JSONArray(app.capabilities.sorted())),
            )

            "ui.toast" -> {
                Toast.makeText(this, args.optString("text").take(500), Toast.LENGTH_SHORT).show()
                reply.success(JSONObject().put("shown", true))
            }

            "ui.fullscreen" -> {
                setProjectFullscreen(args.optBoolean("enabled", true))
                reply.success(JSONObject().put("fullscreen", args.optBoolean("enabled", true)))
            }

            "device.vibrate" -> {
                vibrate(args.optLong("milliseconds", 45L).coerceIn(1L, 2_000L))
                reply.success(JSONObject().put("vibrated", true))
            }

            "network.request" -> requestCapability(
                "network.fetch",
                "The project wants to contact remote servers for this runtime session.",
                reply,
            ) { reply.success(JSONObject().put("allowed", true)) }

            "clipboard.write" -> requestCapability("clipboard.write", "Text: ${args.optString("text").take(200)}", reply) {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(app.name, args.optString("text").take(1_000_000)))
                reply.success(JSONObject().put("written", true))
            }

            "clipboard.read" -> requestCapability("clipboard.read", "The current clipboard contents will be returned to the project.", reply) {
                val clipboard = getSystemService(ClipboardManager::class.java)
                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                reply.success(JSONObject().put("text", text.take(1_000_000)))
            }

            "share.text" -> requestCapability("share.text", args.optString("text").take(300), reply) {
                val intent = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, args.optString("subject").take(200))
                    .putExtra(Intent.EXTRA_TEXT, args.optString("text").take(1_000_000))
                startActivity(Intent.createChooser(intent, args.optString("title", "Share from ${app.name}")))
                reply.success(JSONObject().put("opened", true))
            }

            "browser.open" -> {
                val uri = parseExternalUri(args.getString("uri"))
                requestCapability("browser.open", uri.toString(), reply) {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    reply.success(JSONObject().put("opened", true))
                }
            }

            "intent.launch" -> launchGenericIntent(args, reply)
            "permissions.request" -> requestDeclaredPermissions(args, reply)
            "file.pick" -> openBridgeFilePicker(args, reply)
            "file.save" -> saveBridgeText(args, reply)
            "host.update" -> requestCapability("host.update", "A selected bundle may fully replace or patch a hosted project.", reply) {
                if (pendingUpdateReply != null) {
                    reply.failure("Another update picker is already open.")
                } else {
                    pendingUpdateReply = reply
                    updatePicker.launch(arrayOf("application/zip", "application/json", "text/json", "*/*"))
                }
            }

            "package.install" -> installSharedApk(args, reply)
            "app.reload" -> {
                webView.reload()
                reply.success(JSONObject().put("reloading", true))
            }
            "app.exit" -> {
                reply.success(JSONObject().put("closing", true))
                finish()
            }
            else -> reply.failure("Unknown or unavailable host action: $action")
        }
    }

    private fun requestCapability(capability: String, detail: String, reply: Reply, action: () -> Unit) {
        firewall.request(this, app, capability, detail) { allowed ->
            if (allowed) action() else reply.failure("Capability denied: $capability")
        }
    }

    private fun launchGenericIntent(args: JSONObject, reply: Reply) {
        val action = args.optString("action", Intent.ACTION_VIEW).take(200)
        val uri = args.optString("uri").takeIf { it.isNotBlank() }?.let(::parseExternalUri)
        val detail = buildString {
            append("Action: ").append(action)
            uri?.let { append("\nURI: ").append(it) }
            args.optString("package").takeIf { it.isNotBlank() }?.let { append("\nPackage: ").append(it) }
        }
        requestCapability("intent.launch", detail, reply) {
            val intent = Intent(action).apply {
                uri?.let { data = it }
                args.optString("mime").takeIf { it.isNotBlank() }?.let { type = it.take(200) }
                args.optString("package").takeIf { it.isNotBlank() }?.let { setPackage(it.take(200)) }
                addCategory(Intent.CATEGORY_DEFAULT)
                val extras = args.optJSONObject("extras")
                extras?.keys()?.forEach { key ->
                    when (val value = extras.get(key)) {
                        is Boolean -> putExtra(key.take(100), value)
                        is Int -> putExtra(key.take(100), value)
                        is Long -> putExtra(key.take(100), value)
                        is Double -> putExtra(key.take(100), value)
                        else -> putExtra(key.take(100), value.toString().take(10_000))
                    }
                }
            }
            runCatching { startActivity(intent) }
                .onSuccess { reply.success(JSONObject().put("launched", true)) }
                .onFailure { reply.failure(it.message ?: "No Android app could handle that intent.") }
        }
    }

    private fun requestDeclaredPermissions(args: JSONObject, reply: Reply) {
        val array = args.optJSONArray("permissions") ?: JSONArray()
        val names = buildList {
            for (index in 0 until array.length()) add(array.getString(index))
        }.distinct().take(16)
        requestCapability("permissions.request", names.joinToString(), reply) {
            requestNativePermissions(names) { results ->
                reply.success(JSONObject(results.mapValues { it.value }))
            }
        }
    }

    private fun openBridgeFilePicker(args: JSONObject, reply: Reply) {
        requestCapability("file.pick", "Android's document picker will open.", reply) {
            if (pendingBridgeFileResult != null) {
                reply.failure("Another file picker is already open.")
                return@requestCapability
            }
            pendingBridgeFileResult = { result ->
                if (result.has("error")) reply.failure(result.getString("error")) else reply.success(result)
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(args.optString("mime", "*/*"))
            bridgeFilePicker.launch(intent)
        }
    }

    private fun saveBridgeText(args: JSONObject, reply: Reply) {
        val text = args.optString("text")
        check(text.length <= 8 * 1024 * 1024) { "Text save payload exceeds 8 MB." }
        requestCapability("file.save", "Android's create-document screen will open.", reply) {
            if (pendingSaveReply != null) {
                reply.failure("Another save request is already open.")
                return@requestCapability
            }
            pendingSaveText = text
            pendingSaveReply = reply
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(args.optString("mime", "text/plain"))
                .putExtra(Intent.EXTRA_TITLE, safeFileName(args.optString("name", "pocket-project.txt")))
            bridgeSavePicker.launch(intent)
        }
    }

    private fun installSharedApk(args: JSONObject, reply: Reply) {
        val name = safeFileName(args.getString("name"))
        val apk = File(sharedDirectory, name).canonicalFile
        check(apk.parentFile == sharedDirectory.canonicalFile && apk.isFile && apk.extension.equals("apk", true)) {
            "The requested APK is not present in this project's shared directory."
        }
        requestCapability("package.install", "Install Android package: ${apk.name} (${apk.length()} bytes)", reply) {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
            reply.success(JSONObject().put("installerOpened", true))
        }
    }

    private fun requestNativePermissions(names: List<String>, callback: (Map<String, Boolean>) -> Unit) {
        if (pendingPermissionResult != null) {
            callback(names.associateWith { false })
            return
        }
        val platformPermissions = names.mapNotNull(::permissionForName).distinct()
        if (platformPermissions.isEmpty()) {
            callback(names.associateWith { true })
            return
        }
        val missing = platformPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            callback(names.associateWith { true })
            return
        }
        pendingPermissionNames = names
        pendingPermissionResult = callback
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun permissionForName(name: String): String? = when (name) {
        "camera" -> Manifest.permission.CAMERA
        "microphone" -> Manifest.permission.RECORD_AUDIO
        "location", "location.fine" -> Manifest.permission.ACCESS_FINE_LOCATION
        "location.coarse" -> Manifest.permission.ACCESS_COARSE_LOCATION
        "contacts.read" -> Manifest.permission.READ_CONTACTS
        "contacts.write" -> Manifest.permission.WRITE_CONTACTS
        "calendar.read" -> Manifest.permission.READ_CALENDAR
        "calendar.write" -> Manifest.permission.WRITE_CALENDAR
        "activity" -> if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else null
        "bodySensors" -> Manifest.permission.BODY_SENSORS
        "notifications" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        "bluetooth.scan" -> if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        "bluetooth.connect" -> if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else null
        "bluetooth.advertise" -> if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_ADVERTISE else null
        "nearbyWifi" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        "media.images" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        "media.video" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        "media.audio" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        "phone.call" -> Manifest.permission.CALL_PHONE
        else -> null
    }

    private fun copyPickedFile(uri: Uri): JSONObject {
        val metadata = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) Pair(cursor.getString(0), if (cursor.isNull(1)) -1L else cursor.getLong(1)) else null
        }
        val requestedName = safeFileName(metadata?.first ?: "selected-file")
        var destination = File(sharedDirectory, requestedName)
        if (destination.exists()) destination = File(sharedDirectory, "${System.currentTimeMillis()}-$requestedName")
        var bytes = 0L
        contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes += read
                    check(bytes <= 512L * 1024 * 1024) { "Selected file exceeds the 512 MB shared-file limit." }
                    output.write(buffer, 0, read)
                }
            }
        } ?: error("Selected file could not be opened.")
        return JSONObject()
            .put("cancelled", false)
            .put("name", destination.name)
            .put("size", bytes)
            .put("mime", contentResolver.getType(uri) ?: "application/octet-stream")
            .put("url", "$origin/shared/${destination.name}")
    }

    private fun parseExternalUri(value: String): Uri {
        val uri = Uri.parse(value.take(8_192))
        val scheme = uri.scheme?.lowercase() ?: error("URI has no scheme.")
        check(scheme != "file" && scheme != "content") { "Projects cannot construct file:// or content:// URIs directly." }
        return uri
    }

    private fun vibrate(milliseconds: Long) {
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
                VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(milliseconds)
        }
    }

    private fun setProjectFullscreen(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        (window.decorView as FrameLayout).addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setProjectFullscreen(true)
    }

    private fun leaveFullscreen() {
        val view = fullscreenView ?: return
        (window.decorView as FrameLayout).removeView(view)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        setProjectFullscreen(false)
    }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._ -]+"), "-")
        .trim('.', ' ')
        .take(100)
        .ifBlank { "file" }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class RuntimeClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            assetLoader.shouldInterceptRequest(request.url)?.let { return it }
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                if (firewall.isGranted(app, "network.fetch")) return null
                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    403,
                    "Blocked by PocketHost",
                    mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream("Remote network access is blocked until the project requests network.fetch.".toByteArray()),
                )
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.toString().startsWith(origin)) return false
            val capability = if (uri.scheme in setOf("http", "https")) "browser.open" else "intent.launch"
            firewall.request(this@RuntimeActivity, app, capability, "Navigation target: $uri") { allowed ->
                if (allowed) runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(BOOTSTRAP_SCRIPT, null)
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            AlertDialog.Builder(this@RuntimeActivity)
                .setTitle("Runtime process stopped")
                .setMessage("Android ended the WebView renderer${if (detail.didCrash()) " after a crash" else " to reclaim resources"}. The hosted app can be restarted safely.")
                .setNegativeButton("Close") { _, _ -> finish() }
                .setPositiveButton("Restart") { _, _ -> recreate() }
                .show()
            return true
        }
    }

    private inner class RuntimeChrome : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            pendingWebFileCallback?.onReceiveValue(null)
            pendingWebFileCallback = filePathCallback
            return runCatching {
                webFileChooser.launch(fileChooserParams.createIntent())
                true
            }.getOrElse {
                pendingWebFileCallback = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val requested = request.resources.toList()
                val capabilityNames = buildList {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in requested) add("camera")
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in requested) add("microphone")
                }
                if (capabilityNames.isEmpty() || capabilityNames.any { it !in app.capabilities }) {
                    request.deny()
                    return@runOnUiThread
                }
                confirmCapabilities(capabilityNames, 0) { allowed ->
                    if (!allowed) {
                        request.deny()
                        return@confirmCapabilities
                    }
                    requestNativePermissions(capabilityNames) { results ->
                        val granted = buildList {
                            if (results["camera"] == true) add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                            if (results["microphone"] == true) add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        }
                        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
                    }
                }
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            firewall.request(this@RuntimeActivity, app, "location", "Web geolocation request from $origin") { allowed ->
                if (!allowed) {
                    callback.invoke(origin, false, false)
                    return@request
                }
                requestNativePermissions(listOf("location.fine")) { results ->
                    callback.invoke(origin, results["location.fine"] == true, false)
                }
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)
        override fun onHideCustomView() = leaveFullscreen()
    }

    private fun confirmCapabilities(capabilities: List<String>, index: Int, callback: (Boolean) -> Unit) {
        if (index >= capabilities.size) {
            callback(true)
            return
        }
        val capability = capabilities[index]
        firewall.request(this, app, capability, "Web runtime request for $capability") { allowed ->
            if (!allowed) callback(false) else confirmCapabilities(capabilities, index + 1, callback)
        }
    }

    private inner class LegacyBridge {
        @JavascriptInterface
        fun postMessage(payload: String) {
            handleMessage(payload, LegacyReply())
        }
    }

    private interface TransportReply { fun send(payload: String) }

    private class ProxyReply(private val proxy: JavaScriptReplyProxy) : TransportReply {
        override fun send(payload: String) = proxy.postMessage(payload)
    }

    private inner class LegacyReply : TransportReply {
        override fun send(payload: String) {
            webView.post {
                webView.evaluateJavascript("window.__pocketHostReceive(${JSONObject.quote(payload)});", null)
            }
        }
    }

    private class Reply(private val id: String, private val transport: TransportReply) {
        fun success(value: Any? = JSONObject.NULL) {
            transport.send(JSONObject().put("id", id).put("ok", true).put("result", value ?: JSONObject.NULL).toString())
        }
        fun failure(message: String) {
            transport.send(JSONObject().put("id", id).put("ok", false).put("error", message.take(2_000)).toString())
        }
    }

    companion object {
        const val EXTRA_APP_ID = "hosted_app_id"

        private val BOOTSTRAP_SCRIPT = """
            (() => {
              if (window.pocket) return;
              const pending = new Map();
              let sequence = 0;
              window.__pocketNetworkAllowed = false;
              window.__pocketHostReceive = raw => {
                let message;
                try { message = typeof raw === 'string' ? JSON.parse(raw) : raw; } catch (_) { return; }
                const job = pending.get(message.id);
                if (!job) return;
                pending.delete(message.id);
                if (message.ok) job.resolve(message.result); else job.reject(new Error(message.error || 'Host action failed'));
              };
              if (window.PocketNative) window.PocketNative.onmessage = event => window.__pocketHostReceive(event.data);
              window.pocket = Object.freeze({
                call(action, args = {}) {
                  return new Promise((resolve, reject) => {
                    const id = Date.now().toString(36) + '-' + (++sequence).toString(36);
                    pending.set(id, {resolve, reject});
                    window.PocketNative.postMessage(JSON.stringify({id, action, args}));
                  }).then(result => {
                    if (action === 'network.request' && result && result.allowed) window.__pocketNetworkAllowed = true;
                    return result;
                  });
                }
              });
              const local = url => {
                try { return new URL(url, location.href).origin === location.origin; } catch (_) { return false; }
              };
              const originalFetch = window.fetch.bind(window);
              window.fetch = (resource, options) => {
                const url = typeof resource === 'string' ? resource : resource.url;
                if (!local(url) && !window.__pocketNetworkAllowed) return Promise.reject(new Error('network.fetch has not been granted'));
                return originalFetch(resource, options);
              };
              const NativeXHR = window.XMLHttpRequest;
              window.XMLHttpRequest = function() {
                const xhr = new NativeXHR();
                const open = xhr.open;
                xhr.open = function(method, url, ...rest) {
                  if (!local(url) && !window.__pocketNetworkAllowed) throw new Error('network.fetch has not been granted');
                  return open.call(this, method, url, ...rest);
                };
                return xhr;
              };
              const NativeWebSocket = window.WebSocket;
              window.WebSocket = function(url, protocols) {
                if (!window.__pocketNetworkAllowed) throw new Error('network.fetch has not been granted');
                return protocols === undefined ? new NativeWebSocket(url) : new NativeWebSocket(url, protocols);
              };
              window.WebSocket.prototype = NativeWebSocket.prototype;
              const nativeBeacon = navigator.sendBeacon?.bind(navigator);
              if (nativeBeacon) navigator.sendBeacon = (url, data) => {
                if (!local(url) && !window.__pocketNetworkAllowed) return false;
                return nativeBeacon(url, data);
              };
              queueMicrotask(() => window.dispatchEvent(new Event('pocketready')));
            })();
        """.trimIndent()
    }
}
