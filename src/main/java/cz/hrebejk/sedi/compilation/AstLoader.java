/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.compilation;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loader for attributed Abstract Syntax Trees (AST) of Java source files using the JDK Compiler API.
 */
public class AstLoader {

    private final List<Path> classpath;

    /**
     * Creates a loader which resolves types only against the JDK.
     */
    public AstLoader() {
        this(List.of());
    }

    /**
     * Creates a loader which resolves types against the JDK and the given classpath.
     *
     * @param classpath jars and class directories used to resolve library types
     */
    public AstLoader(List<Path> classpath) {
        this.classpath = List.copyOf(classpath);
    }

    /**
     * Parses and attributes the given files in a single javac task. Classes of the project which the files
     * refer to are found in the providers, on demand.
     *
     * @param providers sources of the project; for a class present in several providers the first one wins.
     *                  Each path is looked up in the providers in order, the first provider having it is used.
     * @param paths     the files to analyze
     * @return one info per path, in the order of the paths, sharing the same {@link Trees}
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if no provider has one of the paths
     */
    public List<CompilationUnitInfo> load(List<SourceFileProvider> providers, List<Path> paths) throws IOException {
        List<JavaFileObject> files = new ArrayList<>();
        for (Path path : paths) {
            files.add(find(providers, path));
        }
        if (files.isEmpty()) {
            return List.of();
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("System Java compiler not found. Ensure running on a JDK, not a JRE.");
        }

        try (StandardJavaFileManager standard = compiler.getStandardFileManager(null, null, null)) {
            if (!classpath.isEmpty()) {
                standard.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            }

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    new SourceFileManager(standard, providers),
                    diagnostic -> { },
                    // Sources of the project win over class files, and no annotation processors are run.
                    List.of("-proc:none", "-Xprefer:source"),
                    null,
                    files
            );

            JavacTask javacTask = (JavacTask) task;
            Map<URI, CompilationUnitTree> units = new HashMap<>();
            for (CompilationUnitTree cut : javacTask.parse()) {
                units.put(cut.getSourceFile().toUri(), cut);
            }
            javacTask.analyze();

            Trees trees = Trees.instance(javacTask);
            DocTrees docTrees = DocTrees.instance(javacTask);

            List<CompilationUnitInfo> result = new ArrayList<>();
            for (JavaFileObject file : files) {
                result.add(new CompilationUnitInfo(units.get(file.toUri()), trees, docTrees));
            }
            return result;
        }
    }

    private static JavaFileObject find(List<SourceFileProvider> providers, Path path) throws IOException {
        for (SourceFileProvider provider : providers) {
            Optional<JavaFileObject> file = provider.get(path);
            if (file.isPresent()) {
                return file.get();
            }
        }
        throw new IllegalArgumentException("No source file provider has " + path);
    }
}
