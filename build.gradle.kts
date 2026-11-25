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
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

tasks.register("verifyZeroSplitPackages") {
    group = "verification"
    description = "Verifies that no Kotlin/Java package is split across multiple Gradle modules."
    val moduleMainRoots = subprojects.associate { sub ->
        sub.path to sub.file("src/main/java")
    }
    doLast {
        val pkgToModules = linkedMapOf<String, MutableSet<String>>()
        for ((modPath, javaRoot) in moduleMainRoots) {
            if (!javaRoot.isDirectory) continue
            javaRoot.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val pkg = file.useLines { lines ->
                        lines.firstOrNull { it.trim().startsWith("package ") }
                            ?.trim()
                            ?.removePrefix("package ")
                            ?.removeSuffix(";")
                            ?.trim()
                    }.orEmpty()
                    if (pkg.isNotEmpty()) {
                        pkgToModules.getOrPut(pkg) { linkedSetOf() }.add(modPath)
                    }
                }
        }
        val splits = pkgToModules.filterValues { it.size > 1 }
        if (splits.isNotEmpty()) {
            val details = splits.entries.joinToString("\n") { (pkg, mods) -> " - $pkg -> $mods" }
            throw GradleException("Split packages detected across Gradle modules:\n$details")
        }
    }
}
