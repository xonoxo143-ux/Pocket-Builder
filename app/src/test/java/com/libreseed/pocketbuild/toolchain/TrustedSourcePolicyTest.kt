package com.libreseed.pocketbuild.toolchain

import org.junit.Assert.assertEquals
import org.junit.Test

class TrustedSourcePolicyTest {
    private val policy = TrustedSourcePolicy(setOf("downloads.gradle.org"))

    @Test
    fun acceptsPinnedHttpsHost() {
        val uri = policy.validate("https://downloads.gradle.org/distributions/gradle.zip")
        assertEquals("downloads.gradle.org", uri.host)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHttp() {
        policy.validate("http://downloads.gradle.org/distributions/gradle.zip")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownHost() {
        policy.validate("https://example.com/toolchain.zip")
    }
}
