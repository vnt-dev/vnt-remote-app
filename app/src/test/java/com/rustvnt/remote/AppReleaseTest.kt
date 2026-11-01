package com.rustvnt.remote

import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    private fun version(value: String) = requireNotNull(ReleaseVersion.parse(value))

    @Test fun comparesNumericVersionsAndIgnoresBuildMetadata() {
        assertTrue(version("v0.10.0") > version("0.9.9"))
        assertTrue(version("1.0.0") > version("0.99.99"))
        assertEquals(0, version("v1.2.3+build.42").compareTo(version("1.2.3")))
        assertTrue(version("1.2.2") < version("1.2.3"))
    }

    @Test fun followsSemverPrereleaseOrdering() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        ordered.zipWithNext().forEach { (a,b) -> assertTrue("$a < $b", version(a) < version(b)) }
    }

    @Test fun rejectsUnusableTags() {
        listOf("", "latest", "v1.2", "1.2.3.4", "01.2.3", "1.2.3-01", "1.2.3+").forEach {
            assertNull(it, ReleaseVersion.parse(it))
        }
    }

    @Test fun choosesDeviceAbiThenUniversalAndNeverDebugApks() {
        val arm = ReleaseAsset("VNTRemote-arm64-v8a-release.apk", "arm")
        val x86 = ReleaseAsset("VNTRemote-x86_64-release.apk", "x86")
        val universal = ReleaseAsset("VNTRemote-universal-release.apk", "universal")
        val debug = ReleaseAsset("VNTRemote-arm64-v8a-debug.apk", "debug")
        val release = AppRelease("v1.0.0", "", listOf(universal, x86, debug, arm))
        assertEquals(arm, release.apk(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(x86, release.apk(listOf("x86_64", "arm64-v8a")))
        assertEquals(universal, release.copy(assets=listOf(x86, universal)).apk(listOf("arm64-v8a")))
        assertNull(release.copy(assets=listOf(debug, x86)).apk(listOf("arm64-v8a")))
    }
}
