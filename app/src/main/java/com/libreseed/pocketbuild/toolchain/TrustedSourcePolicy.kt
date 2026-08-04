package com.libreseed.pocketbuild.toolchain

import java.net.URI

class TrustedSourcePolicy(
    private val allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
) {
    fun validate(url: String): URI {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Toolchain downloads must use HTTPS." }
        require(uri.userInfo == null) { "Credential-bearing download URLs are not allowed." }
        require(uri.fragment == null) { "Download URLs may not contain fragments." }
        val host = uri.host?.lowercase() ?: error("Download URL has no host.")
        require(host in allowedHosts) { "Untrusted toolchain host: $host" }
        return uri
    }

    companion object {
        val DEFAULT_ALLOWED_HOSTS = setOf(
            "dl.google.com",
            "redirector.gvt1.com",
            "services.gradle.org",
            "downloads.gradle.org",
            "repo1.maven.org",
            "repo.maven.apache.org",
            "maven.google.com",
            "github.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "godotengine.org",
            "downloads.tuxfamily.org",
        )
    }
}
