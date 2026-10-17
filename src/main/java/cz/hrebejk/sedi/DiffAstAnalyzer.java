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
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzer that inspects a Git repository, finds changed files, parses and analyzes
 * both old and new versions into attributed ASTs (CompilationUnitTree, Trees, DocTrees).
 */
public class DiffAstAnalyzer {

    private final Path repositoryPath;

    /**
     * Creates a new DiffAstAnalyzer for the given repository path.
     *
     * @param repositoryPath path to the Git repository
     */
    public DiffAstAnalyzer(Path repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

    /**
     * Analyzes all changed files in the repository, returning a list of FileDiffAnalysis records.
     *
     * @return list of FileDiffAnalysis containing old/new files, diffs, and ASTs
     * @throws IOException     if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */
    public List<FileDiffAnalysis> analyzeChanges() throws IOException, GitAPIException {
        GitInspector inspector = new GitInspector(repositoryPath);
        List<DiffEntry> diffs = inspector.getChanges();

        try (Repository repo = inspector.openRepository()) {
            Path oldTempDir = Files.createTempDirectory("sedi-old-ast");
            Path newTempDir = Files.createTempDirectory("sedi-new-ast");
            List<FileDiffAnalysis> results = new ArrayList<>();

            for (DiffEntry diff : diffs) {
                Path oldFile = extractBlob(repo, diff.getOldId(), diff.getOldPath(), oldTempDir);
                Path newFile = resolveNewFile(diff, newTempDir, repo);

                CompilationUnitResult oldResult = analyzeFile(oldFile);
                CompilationUnitResult newResult = analyzeFile(newFile);

                results.add(new FileDiffAnalysis(
                        oldFile,
                        newFile,
                        diff,
                        oldResult.compilationUnit,
                        newResult.compilationUnit,
                        oldResult.trees,
                        newResult.trees,
                        oldResult.docTrees,
                        newResult.docTrees
                ));
            }

            return results;
        }
    }

    private Path extractBlob(Repository repo, AbbreviatedObjectId objectId, String pathName, Path tempDir) throws IOException {
        if (objectId == null || objectId.toObjectId().equals(ObjectId.zeroId()) || "/dev/null".equals(pathName)) {
            return null;
        }
        ObjectId id = objectId.toObjectId();
        ObjectLoader loader = repo.open(id);
        Path target = tempDir.resolve(pathName);
        Files.createDirectories(target.getParent());
        try (var stream = loader.openStream()) {
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private Path resolveNewFile(DiffEntry diff, Path tempDir, Repository repo) throws IOException {
        String newPath = diff.getNewPath();
        if ("/dev/null".equals(newPath)) {
            return null;
        }
        Path workingTreeFile = repositoryPath.resolve(newPath);
        if (Files.exists(workingTreeFile)) {
            return workingTreeFile;
        }
        return extractBlob(repo, diff.getNewId(), newPath, tempDir);
    }

    private CompilationUnitResult analyzeFile(Path filePath) throws IOException {
        if (filePath == null || !Files.exists(filePath)) {
            return new CompilationUnitResult(null, null, null);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("System Java compiler not found.");
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> fileObjects = fileManager.getJavaFileObjectsFromPaths(List.of(filePath));
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, null, null, null, fileObjects);
            JavacTask javacTask = (JavacTask) task;
            Iterable<? extends CompilationUnitTree> cuts = javacTask.parse();
            javacTask.analyze();

            CompilationUnitTree cut = cuts.iterator().hasNext() ? cuts.iterator().next() : null;
            Trees trees = Trees.instance(javacTask);
            DocTrees docTrees = DocTrees.instance(javacTask);

            return new CompilationUnitResult(cut, trees, docTrees);
        }
    }

    /**
     * Internal container for compilation unit results.
     */
    private record CompilationUnitResult(
            CompilationUnitTree compilationUnit,
            Trees trees,
            DocTrees docTrees
    ) {
    }
}
