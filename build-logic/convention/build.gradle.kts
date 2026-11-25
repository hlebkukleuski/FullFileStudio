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
    `java-gradle-plugin`
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "fullfilestudio.android.library"
            implementationClass = "me.hlebkukleuski.fullfilestudio.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "fullfilestudio.android.application"
            implementationClass = "me.hlebkukleuski.fullfilestudio.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("codeQuality") {
            id = "fullfilestudio.quality"
            implementationClass = "me.hlebkukleuski.fullfilestudio.buildlogic.CodeQualityConventionPlugin"
        }
    }
}
