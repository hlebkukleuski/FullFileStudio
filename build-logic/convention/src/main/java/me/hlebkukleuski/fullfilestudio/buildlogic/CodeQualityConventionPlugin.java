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

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class CodeQualityConventionPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        String projectPath = project.getPath();
        File projectDir = project.getProjectDir();

        TaskProvider<?> verifyTask = project.getTasks().register("verifyModuleArchitecture", task -> {
            task.setGroup("verification");
            task.setDescription("Verifies package-directory alignment, namespace hygiene, and licensing naming boundaries.");
            task.doLast(t -> {
                List<String> violations = new ArrayList<>();
                File srcDir = new File(projectDir, "src");
                if (!srcDir.isDirectory()) {
                    return;
                }
                File[] sourceSets = srcDir.listFiles(File::isDirectory);
                if (sourceSets == null) {
                    return;
                }
                for (File sourceSet : sourceSets) {
                    File javaRoot = new File(sourceSet, "java");
                    if (!javaRoot.isDirectory()) {
                        continue;
                    }
                    Path rootPath = javaRoot.toPath();
                    try (Stream<Path> walk = Files.walk(rootPath)) {
                        walk.filter(p -> Files.isRegularFile(p) && (p.toString().endsWith(".kt") || p.toString().endsWith(".java")))
                            .forEach(file -> inspectSourceFile(projectPath, rootPath, sourceSet.getName(), file, violations));
                    } catch (IOException e) {
                        throw new GradleException("Failed scanning " + javaRoot, e);
                    }
                }
                if (!violations.isEmpty()) {
                    throw new GradleException("Architecture & hygiene violations in " + projectPath + ":\n - " + String.join("\n - ", violations));
                }
            });
        });

        project.getTasks().matching(t -> t.getName().equals("check")).configureEach(checkTask ->
            checkTask.dependsOn(verifyTask)
        );
    }

    private static void inspectSourceFile(
        String projectPath,
        Path javaRoot,
        String sourceSetName,
        Path file,
        List<String> violations
    ) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String declaredPkg = "";
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("package ")) {
                    declaredPkg = trimmed.substring("package ".length()).replace(";", "").trim();
                    break;
                }
            }
            Path parent = file.getParent();
            String expectedPkg = parent == null ? "" : javaRoot.relativize(parent).toString().replace(File.separatorChar, '.');
            if (!declaredPkg.equals(expectedPkg)) {
                violations.add(file.getFileName() + " declares package '" + declaredPkg + "' but resides in '" + expectedPkg + "'");
            }
            if (declaredPkg.startsWith("com.omnireader")) {
                violations.add(file.getFileName() + " uses legacy 'com.omnireader' namespace: " + declaredPkg);
            }
            String fullContent = String.join("\n", lines);
            if (fullContent.contains("com.omnireader.")) {
                violations.add(file.getFileName() + " contains legacy 'com.omnireader.' reference");
            }
            if (sourceSetName.equals("main")) {
                for (int idx = 0; idx < lines.size(); idx++) {
                    String trimmed = lines.get(idx).trim();
                    if (trimmed.startsWith("import ") && trimmed.endsWith(".*")) {
                        violations.add(file.getFileName() + ":" + (idx + 1) + " uses forbidden wildcard import: " + trimmed);
                    }
                    if (trimmed.contains("printStackTrace()") || trimmed.contains("System.out.print")) {
                        violations.add(file.getFileName() + ":" + (idx + 1) + " uses forbidden console debug call in production source");
                    }
                }
            }
            if (projectPath.equals(":features:gpl-engines") && (file.getFileName().toString().contains("CleanRoom") || fullContent.contains("CleanRoom"))) {
                violations.add(file.getFileName() + " in GPL module must not carry misleading 'CleanRoom' naming");
            }
            if (projectPath.equals(":app") && (fullContent.contains("PlayFeaturePluginManager") || fullContent.contains("PlayFeatureBundle"))) {
                violations.add(file.getFileName() + " contains deprecated simulated Play Feature Delivery symbol");
            }
            if (projectPath.equals(":app") && (sourceSetName.equals("main") || sourceSetName.equals("googlePlay"))) {
                String content = String.join("\n", lines);
                if (content.contains("REQUEST_INSTALL_PACKAGES") || content.contains("Tls13Https")) {
                    violations.add(file.getFileName() + " in " + sourceSetName + " leaks standalone-only self-update symbols");
                }
            }
        } catch (IOException e) {
            violations.add("Unreadable file " + file + ": " + e.getMessage());
        }
    }
}
