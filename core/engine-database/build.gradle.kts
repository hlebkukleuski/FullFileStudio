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
    id("fullfilestudio.android.library")
}

android {
    namespace = "me.hlebkukleuski.fullfilestudio.core.database"
}

dependencies {
    api(project(":core:engine-api"))
    implementation(project(":core:security"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.collection)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.brotli:dec:0.1.2")

    testImplementation(libs.junit)
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation(libs.robolectric)
    testImplementation("com.github.luben:zstd-jni:1.5.7-28")
    testImplementation("org.xerial.snappy:snappy-java:1.1.10.12")
    testImplementation("at.yawk.lz4:lz4-java:1.14.1")
    testImplementation(libs.androidx.test.core)
}
