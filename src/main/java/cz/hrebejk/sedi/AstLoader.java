/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
     * Loads the compiled compilation units (AST) for the given Java source files.
     *
     * @param sourcePaths list of paths to Java source files
     * @return list of parsed CompilationUnitTree representing the ASTs
     * @throws IOException if an I/O error occurs
     */
    public List<CompilationUnitTree> loadAst(List<Path> sourcePaths) throws IOException {
        List<CompilationAnalysis> analyses = loadCompilationAnalyses(sourcePaths);
        List<CompilationUnitTree> cuts = new ArrayList<>();
        for (CompilationAnalysis analysis : analyses) {
            cuts.add(analysis.compilationUnit());
        }
        return cuts;
    }

    /**
     * Loads compilation analyses containing compilation units, trees, and doc trees.
     *
     * @param sourcePaths list of paths to Java source files
     * @return list of CompilationAnalysis
     * @throws IOException if an I/O error occurs
     */
    public List<CompilationAnalysis> loadCompilationAnalyses(List<Path> sourcePaths) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("System Java compiler not found. Ensure running on a JDK, not a JRE.");
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            if (!classpath.isEmpty()) {
                fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            }
            Iterable<? extends JavaFileObject> fileObjects = fileManager.getJavaFileObjectsFromPaths(sourcePaths);

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostic -> { },
                    null,
                    null,
                    fileObjects
            );

            JavacTask javacTask = (JavacTask) task;
            Iterable<? extends CompilationUnitTree> compilationUnits = javacTask.parse();
            javacTask.analyze();

            Trees trees = Trees.instance(javacTask);
            DocTrees docTrees = DocTrees.instance(javacTask);

            List<CompilationAnalysis> list = new ArrayList<>();
            for (CompilationUnitTree cut : compilationUnits) {
                list.add(new CompilationAnalysis(cut, trees, docTrees));
            }
            return list;
        }
    }
}
