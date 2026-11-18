import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wwkhub.bluefin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wwkhub.bluefin"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "0.12.0-rc1"

        // Update sources: product decision 2026-11-16 — GitHub Releases only for
        // now (mainland users are told a proxy may be needed). The ordered
        // multi-source client stays: a future domestic primary is a config
        // change here. The channel tag is a maintained pre-release holding the
        // current manifest + APK; test builds override both sources with
        // -PupdatePrimary/-PupdateFallback and -PupdateAllowInsecureLan=true
        // (honored in debug builds only).
        buildConfigField(
            "String",
            "UPDATE_PRIMARY_MANIFEST_URL",
            "\"${providers.gradleProperty("updatePrimary").orNull
                ?: "https://github.com/WWK-HUB/bluefin-android/releases/download/update-channel/update-manifest.json"}\"",
        )
        buildConfigField(
            "String",
            "UPDATE_FALLBACK_MANIFEST_URL",
            "\"${providers.gradleProperty("updateFallback").orNull ?: ""}\"",
        )
        buildConfigField(
            "boolean",
            "UPDATE_ALLOW_INSECURE_LAN",
            "${providers.gradleProperty("updateAllowInsecureLan").orNull == "true"}",
        )

        // Cloud TTS worker address: ordinary configuration, never a credential.
        // Empty by default — the user pastes their deployed Worker URL in
        // settings (or bakes it here with -PcloudTtsBase=https://...). The
        // Fish Audio API key lives ONLY in the worker secret, never here.
        buildConfigField(
            "String",
            "CLOUD_TTS_BASE_URL",
            "\"${providers.gradleProperty("cloudTtsBase").orNull ?: ""}\"",
        )

        // sherpa-onnx ships four ABIs (~67 MB total); it remains for offline
        // ASR and the on-demand ZipVoice fallback. Distribution packages
        // arm64 only; -PvoiceX86=true adds x86_64 for emulator verification.
        ndk {
            abiFilters += if (providers.gradleProperty("voiceX86").orNull == "true") {
                listOf("arm64-v8a", "x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Offline ASR engine (Apache-2.0). Prebuilt AAR from the v2.0.1 GitHub
    // release; the Chinese model itself is downloaded on demand, not bundled.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val repoRoot = rootProject.projectDir.parentFile
val androidPackDir = repoRoot.resolve("android-pack")

tasks.register("syncAndroidPack") {
    group = "bluefin"
    description = "Copy android-pack/ into assets/bluefin when the Route B pack is present."
    doLast {
        val source = listOf(
            androidPackDir,
            androidPackDir.resolve("bluefin"),
            androidPackDir.resolve("assets/bluefin"),
        ).firstOrNull { it.resolve("index.html").isFile }
        if (source == null) {
            logger.lifecycle("syncAndroidPack: android-pack index.html not found; assets/bluefin left unchanged")
            return@doLast
        }
        val dest = project.file("src/main/assets/bluefin")
        dest.mkdirs()
        logger.lifecycle("syncAndroidPack: $source -> $dest")
        project.copy {
            from(source)
            into(dest)
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }
}

tasks.register("verifyPreviewAssets") {
    group = "bluefin"
    description = "Fail the delivery build when Route B index.html is missing."
    dependsOn("syncAndroidPack")
    doLast {
        val routeB = project.file("src/main/assets/bluefin/index.html")
        val routeA = project.file("src/main/assets/www/bluefin-preview.html")
        val requireRouteB = providers.gradleProperty("requireRouteB").orNull == "true"
        if (requireRouteB && !routeB.isFile) {
            throw GradleException(
                "Route B is required but app/src/main/assets/bluefin/index.html is missing. " +
                    "Place the split pack at android-pack/ (index.html, figure.js, figure.json, kit/, assets/) " +
                    "or copy it into app/src/main/assets/bluefin/."
            )
        }
        if (!routeB.isFile && !routeA.isFile) {
            logger.warn("Neither Route B nor Route A is packaged. The APK will open an error screen.")
        } else if (routeB.isFile) {
            logger.lifecycle("Route B entry present: ${routeB.absolutePath}")
        } else {
            logger.warn("Route B missing. Route A is available for local self-test only.")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn("verifyPreviewAssets")
}

afterEvaluate {
    tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }.configureEach {
        dependsOn(tasks.named("syncAndroidPack"))
    }
}
