package com.wwkhub.bluefin.update

import java.security.MessageDigest

/**
 * Pure update decisions: version gating, archive identity and signature
 * continuity. Everything here is JVM-testable; the Android plumbing lives in
 * [UpdateInstaller] and [UpdateCoordinator].
 */
internal object UpdatePolicy {
    const val MAX_APK_BYTES = 200L * 1024L * 1024L
    const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L

    fun isNewer(manifestCode: Int, installedCode: Int): Boolean = manifestCode > installedCode

    /** User-facing source name; GitHub carries the mainland connectivity caveat. */
    fun sourceLabel(url: String): String =
        if (url.lowercase().contains("github.com")) "GitHub" else "国内主源"

    sealed class ArchiveCheck {
        object Ok : ArchiveCheck()
        data class Rejected(val reason: String) : ArchiveCheck()
    }

    /**
     * The downloaded archive must be this app, at exactly the version the
     * manifest promises, and newer than what is installed. A manifest that
     * lies about any of these never reaches the system installer.
     */
    fun checkArchive(
        expectedPackage: String,
        manifest: UpdateManifest,
        archivePackage: String?,
        archiveCode: Int?,
        installedCode: Int,
    ): ArchiveCheck {
        if (archivePackage == null || archiveCode == null) {
            return ArchiveCheck.Rejected("安装包无法读取。")
        }
        if (archivePackage != expectedPackage) return ArchiveCheck.Rejected("PACKAGE")
        if (archiveCode != manifest.versionCode) return ArchiveCheck.Rejected("VERSION")
        if (!isNewer(archiveCode, installedCode)) return ArchiveCheck.Rejected("NOT_NEWER")
        return ArchiveCheck.Ok
    }

    fun sha256Hex(data: ByteArray): String = digestHex(data)

    fun digestHex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun shaMatches(expected: String, actual: String): Boolean =
        expected.trim().lowercase() == actual.trim().lowercase()

    /** SHA-256 digests of the signing certificates, for set comparison. */
    fun certDigests(certificates: List<ByteArray>): Set<String> =
        certificates.mapTo(LinkedHashSet()) { digestHex(it) }

    /**
     * Overwrite installs only succeed with the same signer, so a mismatch is
     * reported as a migration gap instead of being handed to the installer.
     */
    fun signaturesMatch(installed: Set<String>, archive: Set<String>): Boolean =
        installed.isNotEmpty() && installed == archive
}