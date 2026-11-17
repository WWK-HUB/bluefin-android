package com.wwkhub.bluefin.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val manifest = UpdateManifest(
        versionCode = 28,
        versionName = "0.10.0-rc2",
        apkUrl = "https://update.example.cn/a.apk",
        sha256 = "b".repeat(64),
        sizeBytes = null,
        notes = null,
    )

    @Test
    fun onlyStrictlyHigherVersionCodesAreUpdates() {
        assertTrue(UpdatePolicy.isNewer(28, 26))
        assertFalse(UpdatePolicy.isNewer(26, 26))
        assertFalse(UpdatePolicy.isNewer(24, 26))
    }

    @Test
    fun archiveMustBeThisAppAtThePromisedNewerVersion() {
        assertEquals(
            UpdatePolicy.ArchiveCheck.Ok,
            UpdatePolicy.checkArchive("com.wwkhub.bluefin", manifest, "com.wwkhub.bluefin", 28, 26),
        )
        assertEquals(
            "PACKAGE",
            (UpdatePolicy.checkArchive("com.wwkhub.bluefin", manifest, "android", 28, 26)
                as UpdatePolicy.ArchiveCheck.Rejected).reason,
        )
        assertEquals(
            "VERSION",
            (UpdatePolicy.checkArchive("com.wwkhub.bluefin", manifest, "com.wwkhub.bluefin", 24, 26)
                as UpdatePolicy.ArchiveCheck.Rejected).reason,
        )
        assertEquals(
            "NOT_NEWER",
            (UpdatePolicy.checkArchive("com.wwkhub.bluefin", manifest, "com.wwkhub.bluefin", 28, 28)
                as UpdatePolicy.ArchiveCheck.Rejected).reason,
        )
        assertTrue(
            UpdatePolicy.checkArchive("com.wwkhub.bluefin", manifest, null, null, 26)
                is UpdatePolicy.ArchiveCheck.Rejected,
        )
    }

    @Test
    fun shaComparisonIsCaseInsensitiveHex() {
        val digest = UpdatePolicy.sha256Hex("bluefin".toByteArray())
        assertEquals(64, digest.length)
        assertTrue(UpdatePolicy.shaMatches(digest.uppercase(), digest))
        assertFalse(UpdatePolicy.shaMatches("c".repeat(64), digest))
    }

    @Test
    fun sourceLabelsNameGitHubForTheConnectivityCaveat() {
        assertEquals(
            "GitHub",
            UpdatePolicy.sourceLabel("https://github.com/WWK-HUB/bluefin-android/releases/download/update-channel/update-manifest.json"),
        )
        assertEquals("国内主源", UpdatePolicy.sourceLabel("https://update.example.cn/manifest.json"))
    }

    @Test
    fun signatureContinuityRequiresTheSameSignerSet() {
        val a = UpdatePolicy.certDigests(listOf("cert-a".toByteArray()))
        val a2 = UpdatePolicy.certDigests(listOf("cert-a".toByteArray()))
        val b = UpdatePolicy.certDigests(listOf("cert-b".toByteArray()))
        assertTrue(UpdatePolicy.signaturesMatch(a, a2))
        assertFalse(UpdatePolicy.signaturesMatch(a, b))
        assertFalse(UpdatePolicy.signaturesMatch(emptySet(), a))
        assertFalse(UpdatePolicy.signaturesMatch(a, emptySet()))
    }
}