/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.compilation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A part of a project whose sources are compiled together, such as a Maven module. Root paths are repository
 * relative and {@code /} separated, {@code ""} standing for the repository root.
 *
 * @param roots       the source roots of the module itself; a file below one of them belongs to the module
 * @param sourceRoots the roots javac finds the classes the files of the module refer to in: the module's own
 *                    ones and those of the modules it depends on
 * @param loader      the loader compiling the files of the module, against its classpath
 */
public record SourceModule(List<String> roots, List<String> sourceRoots, AstLoader loader) {

    private static final List<String> CONVENTIONAL_ROOTS = List.of("src/main/java", "src/test/java");

    /** Makes the lists unmodifiable. */
    public SourceModule {
        roots = List.copyOf(roots);
        sourceRoots = List.copyOf(sourceRoots);
    }

    /**
     * The module of a project which is compiled as a whole: its source roots are the given ones, or, if none are
     * given, {@code src/main/java} and {@code src/test/java} if present, else the repository root.
     *
     * @param repositoryPath path to the repository the roots are relative to
     * @param roots          repository relative ({@code /} separated) source roots, may be empty
     * @param loader         the loader compiling the files of the project
     * @return the module having the roots both as its own and as its source roots
     */
    public static SourceModule of(Path repositoryPath, List<String> roots, AstLoader loader) {
        List<String> found = roots;
        if (found.isEmpty()) {
            found = CONVENTIONAL_ROOTS.stream().filter(root -> Files.isDirectory(repositoryPath.resolve(root))).toList();
            if (found.isEmpty()) {
                found = List.of("");
            }
        }
        return new SourceModule(found, found, loader);
    }

    /**
     * Whether the file is in one of the module's own roots, and how specifically: the length of the longest
     * root containing it, {@code -1} if none does.
     *
     * @param path repository relative ({@code /} separated) path of a file
     * @return the length of the longest of the module's roots the file is below, {@code -1} if it is below none
     */
    public int match(String path) {
        int best = -1;
        for (String root : roots) {
            if ((root.isEmpty() || path.startsWith(root + "/")) && root.length() > best) {
                best = root.length();
            }
        }
        return best;
    }
}
