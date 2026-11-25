import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://gnu.org>.
 */

plugins {
    id("fullfilestudio.android.application")
    alias(libs.plugins.compose.compiler)
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

android {
    namespace = "me.hlebkukleuski.fullfilestudio"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.hlebkukleuski.fullfilestudio"
        minSdk = 30
        targetSdk = 37
        versionCode = 4
        versionName = "0.9.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("onnx", "ort")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,LICENSE*,NOTICE*}"
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("googlePlay") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_CUSTOM_SERVER_DOWNLOADS", "false")
            buildConfigField("String", "SERVER_BASE_URL", "\"https://hlebkukleuski.github.io/FullFileStudio\"")
        }
        create("standalone") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_CUSTOM_SERVER_DOWNLOADS", "true")
            buildConfigField("String", "SERVER_BASE_URL", "\"https://hlebkukleuski.github.io/FullFileStudio\"")
        }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val releaseSigningConfig = if (keystorePropertiesFile.exists()) {
        val props = Properties()
        keystorePropertiesFile.inputStream().use { stream -> props.load(stream) }
        signingConfigs.create("release") {
            storeFile = rootProject.file(props.getProperty("storeFile") ?: "release-key.jks")
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    } else {
        null
    }

    signingConfigs.getByName("debug") {
        enableV1Signing = false
        enableV2Signing = true
        enableV3Signing = true
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseSigningConfig
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

androidComponents {
    beforeVariants { variantBuilder ->
        variantBuilder.enableAndroidTest = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.foundation)
    implementation(libs.foundation.layout)
    implementation(libs.material3)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)

    implementation(libs.androidx.appcompat)

    implementation(libs.androidx.collection)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    api(project(":core:engine-api"))
    implementation(project(":core:security"))
    implementation(project(":core:engine-media"))
    implementation(project(":core:engine-database"))
    implementation(project(":core:engine-document"))
    implementation(project(":features:office"))
    implementation(project(":features:cadvector"))
    implementation(project(":features:archives"))
    implementation(project(":features:gpl-engines"))

    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.onnxruntime.jvm)
}

afterEvaluate {
    val packageUniversalTask = tasks.findByName("packageStandaloneReleaseUniversalApk")
    val assembleReleaseTask = tasks.findByName("assembleStandaloneRelease")
    val redirectTask = tasks.findByName("createStandaloneReleaseApkListingFileRedirect")

    val appExt = extensions.getByType(com.android.build.api.dsl.ApplicationExtension::class.java)
    val vCode = appExt.defaultConfig.versionCode ?: 1
    val vName = appExt.defaultConfig.versionName ?: "0.9.0"

    // Set to true ONLY when preparing and publishing an official public release
    val isPublishReleaseNotes = true
    val docsVersionFile = rootProject.file("docs/version.json")
    val docsIndexFile = rootProject.file("docs/index.html")
    val docsChangelogFile = rootProject.file("docs/changelog.html")
    val changelogFile = rootProject.file("CHANGELOG.txt")
    val releaseNotesFile = rootProject.file("RELEASE_NOTES.txt")

    val generateVersionJsonTask = tasks.register("generateVersionJson") {
        inputs.property("versionCode", vCode)
        inputs.property("versionName", vName)
        inputs.property("isPublishReleaseNotes", isPublishReleaseNotes)
        outputs.file(docsVersionFile)
        outputs.file(docsIndexFile)
        outputs.file(docsChangelogFile)
        outputs.file(changelogFile)

        doLast {
            if (!isPublishReleaseNotes) {
                logger.lifecycle("isPublishReleaseNotes = false: Skipping automated CHANGELOG and docs update.")
                return@doLast
            }
            val customRelNotes = if (releaseNotesFile.exists()) {
                releaseNotesFile.readText().lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
            } else ""
            val relNotesText = if (customRelNotes.isNotEmpty()) customRelNotes else "FullFileStudio v$vName Release"

            if (docsVersionFile.exists()) {
                docsVersionFile.writeText(
                    """{
  "applicationId": "me.hlebkukleuski.fullfilestudio",
  "license": "GPL-3.0-or-later",
  "versionCode": $vCode,
  "versionName": "$vName",
  "apkUrl": "https://github.com/hlebkukleuski/FullFileStudio/releases/download/v$vName/fullfilestudio-v$vName-imp-release.apk",
  "releaseNotes": "$relNotesText"
}
"""
                )
            }
            if (docsIndexFile.exists()) {
                val oldHtml = docsIndexFile.readText()
                val updatedHtml = oldHtml.replace(
                    Regex("""https://github\.com/hlebkukleuski/FullFileStudio/releases/(?:latest/download|download/v[0-9.]+)/fullfilestudio-v[0-9.]+-(?:hashtagfreeinternet|imp)-release\.apk"""),
                    "https://github.com/hlebkukleuski/FullFileStudio/releases/download/v${vName}/fullfilestudio-v${vName}-imp-release.apk"
                ).replace(
                    Regex("""https://github\.com/hlebkukleuski/FullFileStudio/releases/latest/download/fullfilestudio-[a-z0-9.\-]+\.apk"""),
                    "https://github.com/hlebkukleuski/FullFileStudio/releases/download/v${vName}/fullfilestudio-v${vName}-imp-release.apk"
                )
                docsIndexFile.writeText(updatedHtml)
            }
            if (changelogFile.exists()) {
                val existingText = changelogFile.readText()
                val entryPrefix = "$vName - "
                val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date())
                val newEntry = "$vName - $dateStr - $relNotesText"

                val lines = existingText.lines().toMutableList()
                val existingIndex = lines.indexOfFirst { it.startsWith(entryPrefix) }
                if (existingIndex >= 0) {
                    lines[existingIndex] = newEntry
                    changelogFile.writeText(lines.joinToString("\n"))
                } else {
                    changelogFile.writeText("$newEntry\n$existingText")
                }
            }

            if (docsChangelogFile.exists() && changelogFile.exists()) {
                val changelogLines = changelogFile.readText().lines().filter { it.isNotBlank() }
                val cardsHtml = changelogLines.joinToString("\n\n") { line ->
                    val parts = line.split(" - ", limit = 3)
                    val ver = parts.getOrNull(0)?.trim() ?: ""
                    val date = parts.getOrNull(1)?.trim() ?: ""
                    val desc = parts.getOrNull(2)?.trim() ?: ""
                    """        <div class="changelog-card">
            <div>
                <span class="version-badge">v$ver</span>
                <span class="release-date">$date</span>
            </div>
            <div class="release-title">FullFileStudio v$ver Release</div>
            <div class="release-desc">
                $desc
            </div>
        </div>"""
                }

                val fullHtml = """<!--
  Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program. If not, see <https://gnu.org>.
-->
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Release History — FullFileStudio</title>
    <link rel="icon" type="image/png" href="favicon.png">
    <link rel="apple-touch-icon" href="favicon.png">
    <meta property="og:title" content="Release History — FullFileStudio">
    <meta property="og:description" content="Complete version changelog for FullFileStudio on Android">
    <meta property="og:image" content="https://fullfile.studio/og-image.png">
    <meta property="og:type" content="website">
    <meta name="twitter:card" content="summary">
    <meta name="twitter:image" content="https://fullfile.studio/og-image.png">
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; background: #101014; color: #f5f7fa; margin: 0; padding: 0; line-height: 1.6; }
        .container { max-width: 800px; margin: 0 auto; padding: 40px 20px; text-align: left; }
        h1 { font-size: 2.5rem; color: #00E676; margin-bottom: 10px; text-align: center; }
        p.subtitle { font-size: 1.1rem; color: #a6adc8; margin-bottom: 30px; text-align: center; }
        .nav-btn {
            display: inline-flex;
            align-items: center;
            gap: 8px;
            padding: 10px 20px;
            background: #1e1e2e;
            color: #00E676;
            border: 1px solid #00E676;
            border-radius: 12px;
            font-weight: 600;
            font-size: 0.95rem;
            text-decoration: none;
            transition: all 0.2s ease;
            margin-bottom: 28px;
        }
        .nav-btn:hover {
            background: #00E676;
            color: #101014;
            transform: translateY(-2px);
            box-shadow: 0 4px 12px rgba(0, 230, 118, 0.25);
        }
        .nav-icon { width: 18px; height: 18px; }
        .changelog-card { background: #1e1e2e; border: 1px solid #313244; border-radius: 14px; padding: 24px; margin-bottom: 20px; }
        .version-badge { display: inline-block; background: #00E676; color: #101014; font-weight: bold; padding: 4px 12px; border-radius: 8px; font-size: 0.95rem; margin-right: 12px; }
        .release-date { color: #a6adc8; font-size: 0.95rem; }
        .release-title { font-weight: bold; font-size: 1.2rem; color: #cdd6f4; margin-top: 14px; margin-bottom: 8px; }
        .release-desc { color: #bac2de; font-size: 0.98rem; }
        .legal-footer { margin-top: 60px; font-size: 0.9rem; color: #6c7086; border-top: 1px solid #313244; padding-top: 20px; text-align: center; }
        .legal-footer a { color: #00E676; text-decoration: none; margin: 0 10px; transition: color 0.2s; }
        .legal-footer a:hover { color: #00C853; text-decoration: underline; }
    </style>
</head>
<body>
    <div class="container">
        <a href="index.html" class="nav-btn">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <line x1="19" y1="12" x2="5" y2="12"></line>
                <polyline points="12 19 5 12 12 5"></polyline>
            </svg>
            <span>Home</span>
        </a>

        <h1>Release History</h1>
        <p class="subtitle">Complete version changelog for FullFileStudio on Android</p>

$cardsHtml

        <div class="legal-footer">
            <p><a href="https://github.com/hlebkukleuski/FullFileStudio" target="_blank" rel="noopener">GitHub Repository</a></p>
            <p>Developed by Hleb Kukleuski &bull; Licensed under GNU GPL v3.0</p>
            <p>
                <a href="index.html">Home</a> |
                <a href="privacy.html">Privacy Policy</a> |
                <a href="terms.html">Terms &amp; License Notice</a> |
                <a href="licenses.html">Open Source Notices</a>
            </p>
        </div>
    </div>
</body>
</html>
"""
                docsChangelogFile.writeText(fullHtml)
            }
        }
    }

    tasks.matching {
        (it.name.startsWith("assemble") || it.name.startsWith("bundle")) && it.name.contains("Release")
    }.configureEach {
        finalizedBy(generateVersionJsonTask)
    }

    if (assembleReleaseTask != null) {
        val targetReleaseDir = layout.buildDirectory.dir("outputs/apk/standalone/release").get().asFile
        val copyUniversalApk = tasks.register<Copy>("copyStandaloneUniversalReleaseApk") {
            from(targetReleaseDir) {
                include("app-standalone-release.apk")
            }
            into(targetReleaseDir)
            rename { "fullfilestudio-v${vName}-imp-release.apk" }
        }
        assembleReleaseTask.finalizedBy(copyUniversalApk)
        redirectTask?.mustRunAfter(copyUniversalApk)
    }

    val bundleGooglePlayReleaseTask = tasks.findByName("bundleGooglePlayRelease")
    if (bundleGooglePlayReleaseTask != null) {
        val targetAabDir = layout.buildDirectory.dir("outputs/bundle/googlePlayRelease").get().asFile
        val copyGooglePlayAab = tasks.register<Copy>("copyGooglePlayReleaseAab") {
            from(targetAabDir) {
                include("app-googlePlay-release.aab")
            }
            into(targetAabDir)
            rename { "fullfilestudio-v${vName}-googlePlay-release.aab" }
        }
        bundleGooglePlayReleaseTask.finalizedBy(copyGooglePlayAab)
        tasks.findByName("produceGooglePlayReleaseBundleIdeListingFile")?.mustRunAfter(copyGooglePlayAab)
    }
}