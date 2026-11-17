package com.wwkhub.bluefin.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android-side install plumbing. The system PackageInstaller always shows its
 * own confirmation; nothing here installs silently.
 */
internal object UpdateInstaller {
    const val APK_MIME = "application/vnd.android.package-archive"

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun signersOf(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptySet()
        return UpdatePolicy.certDigests(signatures.map { it.toByteArray() })
    }

    fun installedSigners(context: Context): Set<String> = signersOf(
        packageInfo(context, context.packageName, signingFlags()),
    )

    fun installedVersionCode(context: Context): Int {
        val info = packageInfo(context, context.packageName, 0) ?: return 0
        return androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info).toInt()
    }

    /** Package name and versionCode read from the downloaded archive itself. */
    fun archiveIdentity(context: Context, file: File): Pair<String, Int>? {
        val info = packageInfo(context, file.absolutePath, signingFlags()) ?: return null
        val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info).toInt()
        return info.packageName to code
    }

    fun archiveSigners(context: Context, file: File): Set<String> = signersOf(
        packageInfo(context, file.absolutePath, signingFlags()),
    )

    private fun packageInfo(context: Context, target: String, flags: Int): PackageInfo? = try {
        if (target.startsWith("/")) {
            context.packageManager.getPackageArchiveInfo(target, flags)
        } else {
            context.packageManager.getPackageInfo(target, flags)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}