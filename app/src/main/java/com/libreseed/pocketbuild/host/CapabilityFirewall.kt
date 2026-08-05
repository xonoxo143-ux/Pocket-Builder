package com.libreseed.pocketbuild.host

import android.app.Activity
import android.app.AlertDialog
import android.content.Context

/**
 * A deliberately simple firewall between replaceable project code and Android APIs.
 *
 * Project manifests must declare a capability before it can be requested. Low-risk display
 * operations are allowed automatically. Everything else receives an app-specific native dialog.
 * Permanent grants are stored per hosted app; especially sensitive operations always prompt.
 */
class CapabilityFirewall(context: Context) {
    private val preferences = context.getSharedPreferences("host-capability-grants", Context.MODE_PRIVATE)
    private val sessionGrants = mutableSetOf<String>()

    fun isGranted(app: HostedApp, capability: String): Boolean {
        if (capability in AUTOMATIC_CAPABILITIES) return true
        if (capability !in app.capabilities) return false
        val key = key(app, capability)
        return key in sessionGrants || preferences.getBoolean(key, false)
    }

    fun request(
        activity: Activity,
        app: HostedApp,
        capability: String,
        detail: String,
        callback: (Boolean) -> Unit,
    ) {
        if (capability in AUTOMATIC_CAPABILITIES) {
            callback(true)
            return
        }
        if (capability !in app.capabilities) {
            AlertDialog.Builder(activity)
                .setTitle("Capability blocked")
                .setMessage("${app.name} tried to use $capability, but that capability is not declared in its pocketapp.json manifest.")
                .setPositiveButton("Close") { _, _ -> callback(false) }
                .setOnCancelListener { callback(false) }
                .show()
            return
        }
        if (isGranted(app, capability) && capability !in ALWAYS_PROMPT) {
            callback(true)
            return
        }

        var resolved = false
        fun resolve(value: Boolean) {
            if (!resolved) {
                resolved = true
                callback(value)
            }
        }

        val message = buildString {
            append(app.name)
            append(" is requesting: ")
            append(capability)
            append(".\n\n")
            append(detail.ifBlank { description(capability) })
            if (capability in ALWAYS_PROMPT) {
                append("\n\nThis action is considered high-risk and will be confirmed every time.")
            } else {
                append("\n\nAn always-allow grant applies only to this hosted app id and can be cleared by deleting its data.")
            }
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle("Allow hosted app action?")
            .setMessage(message)
            .setNegativeButton("Deny") { _, _ -> resolve(false) }
            .setPositiveButton("Allow once") { _, _ ->
                sessionGrants += key(app, capability)
                resolve(true)
            }
            .setOnCancelListener { resolve(false) }

        if (capability !in ALWAYS_PROMPT) {
            builder.setNeutralButton("Always allow") { _, _ ->
                preferences.edit().putBoolean(key(app, capability), true).apply()
                resolve(true)
            }
        }
        builder.show()
    }

    fun revokeApp(appId: String) {
        val prefix = "$appId::"
        sessionGrants.removeAll { it.startsWith(prefix) }
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    private fun key(app: HostedApp, capability: String): String = "${app.id}::${app.root.name}::$capability"

    private fun description(capability: String): String = when (capability) {
        "network.fetch" -> "Allows the project to contact remote HTTP and HTTPS servers from its WebView session."
        "clipboard.read" -> "Allows reading the current clipboard, which may contain private text copied from another app."
        "clipboard.write" -> "Allows replacing the current clipboard text."
        "file.pick" -> "Opens Android's system picker. Only the file you choose is copied into this project's private shared area."
        "file.save" -> "Opens Android's create-document screen and writes the supplied project data to the location you choose."
        "share.text" -> "Opens Android's share sheet with text supplied by the project."
        "browser.open" -> "Opens an external URI in another Android app."
        "intent.launch" -> "Allows a generic Android intent assembled by the project. Review the action and URI carefully."
        "permissions.request" -> "Allows the project to trigger Android runtime permission dialogs. Android still requires your system-level approval."
        "host.update" -> "Allows the project to open a bundle picker and install a full or partial hosted-app update."
        "camera" -> "Allows camera capture through Web APIs after Android also grants camera permission."
        "microphone" -> "Allows microphone capture through Web APIs after Android also grants microphone permission."
        "location" -> "Allows location access through Web APIs after Android also grants location permission."
        else -> "This operation crosses from replaceable project code into the Android host."
    }

    companion object {
        private val AUTOMATIC_CAPABILITIES = setOf(
            "host.info",
            "ui.toast",
            "ui.fullscreen",
            "device.vibrate",
            "app.reload",
            "app.exit",
        )

        private val ALWAYS_PROMPT = setOf(
            "clipboard.read",
            "intent.launch",
            "permissions.request",
            "host.update",
            "package.install",
        )
    }
}
