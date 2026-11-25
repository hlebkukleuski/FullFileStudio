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

package me.hlebkukleuski.fullfilestudio.buildlogic;

import com.android.build.api.dsl.LibraryExtension;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;
import org.jetbrains.kotlin.gradle.dsl.JvmTarget;
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension;

public final class AndroidLibraryConventionPlugin implements Plugin<Project> {
    @Override
    public void apply(Project target) {
        target.getPluginManager().apply("com.android.library");
        target.getPluginManager().apply("fullfilestudio.quality");

        target.getExtensions().configure(LibraryExtension.class, ext -> {
            ext.setCompileSdk(37);
            ext.getDefaultConfig().setMinSdk(30);
            ext.getDefaultConfig().setTestInstrumentationRunner("androidx.test.runner.AndroidJUnitRunner");
            ext.getCompileOptions().setSourceCompatibility(JavaVersion.VERSION_25);
            ext.getCompileOptions().setTargetCompatibility(JavaVersion.VERSION_25);
            ext.getLint().setAbortOnError(true);
            ext.getLint().setCheckReleaseBuilds(true);
        });

        target.getTasks().withType(Test.class).configureEach(test -> test.jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED"
        ));

        target.getExtensions().configure(KotlinAndroidProjectExtension.class, kotlin -> {
            kotlin.getCompilerOptions().getJvmTarget().set(JvmTarget.JVM_25);
            kotlin.getCompilerOptions().getAllWarningsAsErrors().set(true);
        });
    }
}
