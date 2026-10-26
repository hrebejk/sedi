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

import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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
     * Loads compilation analyses containing compilation units, trees, and doc trees.
     *
     * @param sourcePaths list of paths to Java source files
     * @return list of CompilationAnalysis
     * @throws IOException if an I/O error occurs
     */
    public List<CompilationUnitInfo> loadCompilationAnalyses(List<Path> sourcePaths) throws IOException {
        return run(fileManager -> fileManager.getJavaFileObjectsFromPaths(sourcePaths), List.of());
    }

    /**
     * Loads the compilation analysis of one source file. Classes of the project which the file refers to are
     * found in the given layers, on demand.
     *
     * @param unit   the file to analyze
     * @param layers sources of the other classes of the project; for a class present in several layers the
     *               first one wins
     * @return the analysis of the file
     * @throws IOException if an I/O error occurs
     */
    public CompilationUnitInfo analyze(JavaFileObject unit, List<SourceFileManager.Layer> layers) throws IOException {
        return run(fileManager -> List.of(unit), layers).getFirst();
    }

    private List<CompilationUnitInfo> run(
            Function<StandardJavaFileManager, Iterable<? extends JavaFileObject>> units,
            List<SourceFileManager.Layer> layers) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("System Java compiler not found. Ensure running on a JDK, not a JRE.");
        }

        try (StandardJavaFileManager standard = compiler.getStandardFileManager(null, null, null)) {
            if (!classpath.isEmpty()) {
                standard.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            }
            JavaFileManager fileManager = layers.isEmpty() ? standard : new SourceFileManager(standard, layers);

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostic -> { },
                    // Sources of the project win over class files, and no annotation processors are run.
                    List.of("-proc:none", "-Xprefer:source"),
                    null,
                    units.apply(standard)
            );

            JavacTask javacTask = (JavacTask) task;
            Iterable<? extends CompilationUnitTree> compilationUnits = javacTask.parse();
            javacTask.analyze();

            Trees trees = Trees.instance(javacTask);
            DocTrees docTrees = DocTrees.instance(javacTask);

            List<CompilationUnitInfo> list = new ArrayList<>();
            for (CompilationUnitTree cut : compilationUnits) {
                list.add(new CompilationUnitInfo(cut, trees, docTrees));
            }
            return list;
        }
    }
}
