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
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "me.hlebkukleuski.fullfilestudio"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.hlebkukleuski.fullfilestudio"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.9.0"

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

    dynamicFeatures += setOf(
        ":features:office",
        ":features:cadvector",
        ":features:archives"
    )

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val releaseSigningConfig = if (keystorePropertiesFile.exists()) {
        val props = Properties()
        keystorePropertiesFile.inputStream().use { stream -> props.load(stream) }
        signingConfigs.create("release") {
            storeFile = rootProject.file(props.getProperty("storeFile") ?: "release-key.jks")
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        signingConfigs.getByName("debug")
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.foundation)
    implementation(libs.foundation.layout)
    implementation(libs.material3)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)

    implementation(libs.play.feature.delivery)
    implementation(libs.play.feature.delivery.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.play.services.base)

    implementation(libs.androidx.collection)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    api(project(":core:engine-api"))

    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}

afterEvaluate {
    val packageUniversalTask = tasks.findByName("packageStandaloneReleaseUniversalApk")
    val assembleReleaseTask = tasks.findByName("assembleStandaloneRelease")
    val redirectTask = tasks.findByName("createStandaloneReleaseApkListingFileRedirect")

    val appExt = extensions.getByType(com.android.build.api.dsl.ApplicationExtension::class.java)
    val vCode = appExt.defaultConfig.versionCode ?: 1
    val vName = appExt.defaultConfig.versionName ?: "0.9.0"
    val docsVersionFile = rootProject.file("docs/version.json")
    val docsIndexFile = rootProject.file("docs/index.html")
    val changelogFile = rootProject.file("CHANGELOG.txt")

    val generateVersionJsonTask = tasks.register("generateVersionJson") {
        inputs.property("versionCode", vCode)
        inputs.property("versionName", vName)
        outputs.file(docsVersionFile)
        outputs.file(docsIndexFile)
        outputs.file(changelogFile)

        doLast {
            if (docsVersionFile.exists()) {
                docsVersionFile.writeText(
                    """{
  "applicationId": "me.hlebkukleuski.fullfilestudio",
  "license": "GPL-3.0-or-later",
  "versionCode": $vCode,
  "versionName": "$vName",
  "apkUrl": "https://github.com/hlebkukleuski/FullFileStudio/releases/download/v$vName/fullfilestudio-v$vName-hashtagfreeinternet-release.apk",
  "releaseNotes": "FullFileStudio v$vName Release"
}
"""
                )
            }
            if (docsIndexFile.exists()) {
                val oldHtml = docsIndexFile.readText()
                val updatedHtml = oldHtml.replace(
                    Regex("""https://github\.com/hlebkukleuski/FullFileStudio/releases/(?:latest/download|download/v[0-9.]+)/fullfilestudio-v[0-9.]+-hashtagfreeinternet-release\.apk"""),
                    "https://github.com/hlebkukleuski/FullFileStudio/releases/download/v$vName/fullfilestudio-v$vName-hashtagfreeinternet-release.apk"
                ).replace(
                    Regex("""https://github\.com/hlebkukleuski/FullFileStudio/releases/latest/download/fullfilestudio-[a-z0-9.\-]+\.apk"""),
                    "https://github.com/hlebkukleuski/FullFileStudio/releases/download/v$vName/fullfilestudio-v$vName-hashtagfreeinternet-release.apk"
                )
                docsIndexFile.writeText(updatedHtml)
            }
            if (changelogFile.exists()) {
                val existingText = changelogFile.readText()
                val entryPrefix = "$vName - "
                val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date())
                val newEntry = "$vName - $dateStr - FullFileStudio First Public Release"

                val lines = existingText.lines().toMutableList()
                val existingIndex = lines.indexOfFirst { it.startsWith(entryPrefix) }
                if (existingIndex >= 0) {
                    lines[existingIndex] = newEntry
                    changelogFile.writeText(lines.joinToString("\n"))
                } else {
                    changelogFile.writeText("$newEntry\n$existingText")
                }
            }
        }
    }

    tasks.matching {
        (it.name.startsWith("assemble") || it.name.startsWith("bundle")) && it.name.contains("Release")
    }.configureEach {
        finalizedBy(generateVersionJsonTask)
    }

    if (packageUniversalTask != null) {
        val targetReleaseDir = layout.buildDirectory.dir("outputs/apk/standalone/release").get().asFile
        val copyUniversalApk = tasks.register<Copy>("copyStandaloneUniversalReleaseApk") {
            dependsOn(packageUniversalTask)
            from(layout.buildDirectory.dir("outputs/apk_from_bundle/standaloneRelease")) {
                include("*.apk")
            }
            into(targetReleaseDir)
            rename { "fullfilestudio-v${vName}-hashtagfreeinternet-release.apk" }
        }
        packageUniversalTask.finalizedBy(copyUniversalApk)
        assembleReleaseTask?.finalizedBy(copyUniversalApk)
        redirectTask?.mustRunAfter(copyUniversalApk)
    }
}