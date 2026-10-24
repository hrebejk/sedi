/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves the classpath of a simple Maven project by running {@code mvn dependency:build-classpath}.
 */
public class MavenClasspath {

    private MavenClasspath() {
    }

    /**
     * Resolves the classpath (including test scope dependencies) of the Maven project in the given directory.
     *
     * @param projectDir directory containing {@code pom.xml}
     * @return the classpath entries, empty if the project has no dependencies
     * @throws IOException if Maven cannot be run or fails
     */
    public static List<Path> resolve(Path projectDir) throws IOException {
        Path outputFile = Files.createTempFile("sedi-classpath", ".txt");
        try {
            Process process = new ProcessBuilder(
                    "mvn", "-q", "-B", "dependency:build-classpath",
                    "-Dmdep.outputFile=" + outputFile.toAbsolutePath(),
                    "-Dmdep.includeScope=test")
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String log = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("mvn dependency:build-classpath failed with exit code " + exit + ":\n" + log);
            }
            return parse(Files.readString(outputFile));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running mvn", e);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    /**
     * Splits a platform classpath string into paths.
     *
     * @param classpath classpath separated by {@link File#pathSeparator}
     * @return the individual entries
     */
    static List<Path> parse(String classpath) {
        return Arrays.stream(classpath.trim().split(File.pathSeparator))
                .filter(e -> !e.isBlank())
                .map(Path::of)
                .toList();
    }
}
