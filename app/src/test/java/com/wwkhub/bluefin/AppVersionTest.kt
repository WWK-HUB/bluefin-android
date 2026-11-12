package com.wwkhub.bluefin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppVersionTest {
    @Test
    fun aboutVersionComesFromInstalledPackageInsteadOfInlinedBuildConfig() {
        val main = File("src/main/java/com/wwkhub/bluefin/MainActivity.kt").readText()
        assertTrue(main.contains("packageManager.getPackageInfo(packageName, 0).versionName"))
        assertFalse(main.contains("BuildConfig.VERSION_NAME"))

        val productionSources = File("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()
        assertTrue(productionSources.isNotEmpty())
        productionSources.forEach { source ->
            assertFalse(
                "${source.path} must read the installed version at runtime",
                source.readText().contains("BuildConfig.VERSION_NAME"),
            )
        }
    }
}
