/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.Trees;
import cz.hrebejk.sedi.compilation.*;
import cz.hrebejk.sedi.util.GitInspector;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Analyzer that inspects a Git repository, finds changed files, parses and analyzes
 * both old and new versions into attributed ASTs (CompilationUnitTree, Trees, DocTrees).
 * <p>
 * Old versions are read straight from git objects, nothing is copied. The other classes of the project which
 * a changed file refers to are found by javac on demand: in the working tree (new versions when analyzing the
 * working copy), in the git tree of the compared commits, and, for the old versions of the working copy, in
 * the old versions of the changed files. The ASTs stay usable until the analyzer is closed.
 */
public class DiffAstAnalyzer implements AutoCloseable {

    private static final List<String> CONVENTIONAL_ROOTS = List.of("src/main/java", "src/test/java");

    private final Path repositoryPath;
    private final AstLoader astLoader;
    private final List<String> sourceRoots;
    private Git git;

    /**
     * Creates a new DiffAstAnalyzer for the given repository path.
     *
     * @param repositoryPath path to the Git repository
     */
    public DiffAstAnalyzer(Path repositoryPath) {
        this(repositoryPath, new AstLoader());
    }

    /**
     * Creates a new DiffAstAnalyzer which attributes the sources using the given loader. The source roots
     * of the project are {@code src/main/java} and {@code src/test/java} if present, else the repository root.
     *
     * @param repositoryPath path to the Git repository
     * @param astLoader      loader used to parse and attribute the old and new versions of files
     */
    public DiffAstAnalyzer(Path repositoryPath, AstLoader astLoader) {
        this.repositoryPath = repositoryPath;
        this.astLoader = astLoader;
        List<String> roots = CONVENTIONAL_ROOTS.stream()
                .filter(root -> Files.isDirectory(repositoryPath.resolve(root))).toList();
        this.sourceRoots = roots.isEmpty() ? List.of("") : roots;
    }

    /**
     * Releases the repository the sources were read from. The ASTs returned earlier must not be used afterwards.
     */
    @Override
    public void close() {
        if (git != null) {
            git.close();
            git = null;
        }
    }

    /**
     * Analyzes all changed files in the repository, returning a list of FileDiffAnalysis records.
     *
     * @return list of FileDiffAnalysis containing old/new files, diffs, and ASTs
     * @throws IOException     if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */
    public List<FileDiffAnalysis> analyzeChanges() throws IOException, GitAPIException {
        return analyzeChanges(path -> true);
    }

    /**
     * Analyzes the changed files accepted by the filter.
     *
     * @param filter accepts repository relative paths ({@code /} separated); a change is analyzed if
     *               either its old or new path is accepted
     * @return list of FileDiffAnalysis containing old/new files, diffs, and ASTs
     * @throws IOException     if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */
    public List<FileDiffAnalysis> analyzeChanges(Predicate<String> filter) throws IOException, GitAPIException {
        return analyze(new GitInspector(repositoryPath).getChanges(), filter, null);
    }

    /**
     * Analyzes the Java files changed by a commit (compared with its first parent) or a range of commits.
     * Both versions of every file, and the classes they refer to, are read from the git objects, never from
     * the working tree.
     *
     * @param revision any revision git understands: SHA, abbreviated SHA, tag, branch, {@code HEAD~2}, ...
     * @param filter   accepts repository relative paths ({@code /} separated)
     * @return list of FileDiffAnalysis containing old/new files, diffs, and ASTs
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if the revision cannot be resolved to a commit
     */
    public List<FileDiffAnalysis> analyzeCommit(String revision, Predicate<String> filter) throws IOException {
        GitInspector inspector = new GitInspector(repositoryPath);
        GitInspector.Range range = inspector.resolveRange(revision);
        return analyze(inspector.getChanges(revision), filter, range);
    }

    private Repository repository() throws IOException {
        if (git == null) {
            git = Git.open(repositoryPath.toFile());
        }
        return git.getRepository();
    }

    /** @param range the compared commits, {@code null} to analyze the working copy */
    private List<FileDiffAnalysis> analyze(List<DiffEntry> allDiffs, Predicate<String> filter,
                                           GitInspector.Range range) throws IOException {
        List<DiffEntry> diffs = allDiffs.stream()
                .filter(d -> filter.test(d.getOldPath()) || filter.test(d.getNewPath()))
                .toList();
        Repository repo = repository();

        List<SourceFileManager.Layer> oldLayers = new ArrayList<>();
        List<SourceFileManager.Layer> newLayers = new ArrayList<>();
        if (range == null) {
            SourceFileManager.Layer workingTree = SourceLayers.directories(
                    sourceRoots.stream().map(r -> r.isEmpty() ? repositoryPath : repositoryPath.resolve(r)).toList());
            List<SourceFile> oldVersions = new ArrayList<>();
            for (DiffEntry diff : allDiffs) {
                SourceFile old = blob(repo, diff.getOldId(), diff.getOldPath());
                if (old != null && old.binaryName() != null) {
                    oldVersions.add(old);
                }
            }
            // The old classes the old versions of the changed files refer to are their old versions.
            oldLayers.add(SourceLayers.fixed(oldVersions));
            oldLayers.add(workingTree);
            newLayers.add(workingTree);
        } else {
            if (range.oldCommit() != null) {
                oldLayers.add(SourceLayers.gitTree(repo, range.oldCommit(), sourceRoots));
            }
            newLayers.add(SourceLayers.gitTree(repo, range.newCommit(), sourceRoots));
        }

        List<FileDiffAnalysis> results = new ArrayList<>();
        for (DiffEntry diff : diffs) {
            CompilationUnitResult oldResult = analyzeFile(blob(repo, diff.getOldId(), diff.getOldPath()), oldLayers);
            CompilationUnitResult newResult = analyzeFile(newUnit(repo, diff, range == null), newLayers);
            results.add(new FileDiffAnalysis(
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

    /** The git blob of a file version, {@code null} for a missing version (added or deleted file). */
    private SourceFile blob(Repository repo, AbbreviatedObjectId objectId, String path) {
        if (objectId == null || objectId.toObjectId().equals(ObjectId.zeroId()) || DiffEntry.DEV_NULL.equals(path)) {
            return null;
        }
        return SourceFile.ofBlob(repo, objectId.toObjectId(), path, SourceLayers.binaryName(path, sourceRoots));
    }

    private SourceFile newUnit(Repository repo, DiffEntry diff, boolean preferWorkingTree) {
        String newPath = diff.getNewPath();
        if (DiffEntry.DEV_NULL.equals(newPath)) {
            return null;
        }
        Path workingTreeFile = repositoryPath.resolve(newPath);
        if (preferWorkingTree && Files.exists(workingTreeFile)) {
            return SourceFile.ofPath(workingTreeFile, SourceLayers.binaryName(newPath, sourceRoots));
        }
        return blob(repo, diff.getNewId(), newPath);
    }

    private CompilationUnitResult analyzeFile(JavaFileObject unit, List<SourceFileManager.Layer> layers)
            throws IOException {
        if (unit == null) {
            return new CompilationUnitResult(null, null, null);
        }
        CompilationUnitInfo analysis = astLoader.analyze(unit, layers);
        return new CompilationUnitResult(analysis.compilationUnit(), analysis.trees(), analysis.docTrees());
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
