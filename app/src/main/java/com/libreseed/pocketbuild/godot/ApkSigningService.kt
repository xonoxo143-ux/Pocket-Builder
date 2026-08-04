package com.libreseed.pocketbuild.godot

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Signs generated packages with a persistent key held by Android Keystore. */
class ApkSigningService {
    fun sign(unsignedApk: File, signedApk: File) {
        require(unsignedApk.isFile) { "The unsigned APK is missing." }
        val signerMaterial = loadOrCreateSigner()
        signedApk.parentFile?.mkdirs()
        signedApk.delete()

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "PocketBuild",
            signerMaterial.privateKey,
            listOf(signerMaterial.certificate),
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()

        val verification = ApkVerifier.Builder(signedApk).build().verify()
        check(verification.isVerified) {
            val detail = verification.errors.joinToString("; ") { it.toString() }
            "Generated APK signature verification failed${if (detail.isBlank()) "." else ": $detail"}"
        }
    }

    private fun loadOrCreateSigner(): SignerMaterial {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=PocketBuild Local,O=LibreSeed"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - DAY_MS))
                    .setCertificateNotAfter(Date(now + THIRTY_YEARS_MS))
                    .build(),
            )
            generator.generateKeyPair()
        }

        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: error("PocketBuild signing key is unavailable.")
        val certificate = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
            ?: error("PocketBuild signing certificate is unavailable.")
        return SignerMaterial(privateKey, certificate)
    }

    private data class SignerMaterial(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pocketbuild-local-apk-signing-v1"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val THIRTY_YEARS_MS = 30L * 365 * DAY_MS
    }
}
