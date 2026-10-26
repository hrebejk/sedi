/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import cz.hrebejk.sedi.compilation.*;
import cz.hrebejk.sedi.util.GitInspector;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Analyzer that inspects a Git repository, finds changed files, parses and analyzes
 * both old and new versions into attributed ASTs (CompilationUnitTree, Trees, DocTrees).
 * <p>
 * Old versions are read straight from git objects, nothing is copied. The other classes of the project which
 * a changed file refers to are found by javac on demand: in the git trees of the compared commits, or, for
 * the new versions when analyzing the working copy, on disk, since what is on disk is what builds. The
 * working copy is compared with HEAD, whether the changes are staged or not. The ASTs stay usable until the
 * analyzer is closed.
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
        GitInspector inspector = new GitInspector(repositoryPath);
        GitInspector.Range range = inspector.resolveWorkingCopy();
        return analyze(inspector.getChanges(range), filter, range);
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

    /** @param range the compared versions; a {@code null} new commit stands for the working copy */
    private List<FileDiffAnalysis> analyze(List<DiffEntry> allDiffs, Predicate<String> filter,
                                           GitInspector.Range range) throws IOException {
        List<DiffEntry> diffs = allDiffs.stream()
                .filter(d -> filter.test(d.getOldPath()) || filter.test(d.getNewPath()))
                .toList();
        Repository repo = repository();

        List<SourceFileProvider> oldProviders = new ArrayList<>();
        List<SourceFileProvider> newProviders = new ArrayList<>();
        if (range.oldCommit() != null) {
            oldProviders.add(SourceFileProvider.gitTree(repo, range.oldCommit(), sourceRoots));
        }
        newProviders.add(range.newCommit() == null
                ? SourceFileProvider.directories(repositoryPath, sourceRoots)
                : SourceFileProvider.gitTree(repo, range.newCommit(), sourceRoots));

        List<String> oldPaths = new ArrayList<>();
        List<String> newPaths = new ArrayList<>();
        for (DiffEntry diff : diffs) {
            oldPaths.add(DiffEntry.DEV_NULL.equals(diff.getOldPath()) ? null : diff.getOldPath());
            newPaths.add(DiffEntry.DEV_NULL.equals(diff.getNewPath()) ? null : diff.getNewPath());
        }
        List<CompilationUnitInfo> oldInfos = load(oldProviders, oldPaths);
        List<CompilationUnitInfo> newInfos = load(newProviders, newPaths);

        List<FileDiffAnalysis> results = new ArrayList<>();
        for (int i = 0; i < diffs.size(); i++) {
            results.add(new FileDiffAnalysis(diffs.get(i), oldInfos.get(i), newInfos.get(i)));
        }
        return results;
    }

    /** Loads the files in a single compilation; the result has a {@code null} for every {@code null} path. */
    private List<CompilationUnitInfo> load(List<SourceFileProvider> providers, List<String> paths)
            throws IOException {
        List<Path> present = paths.stream().filter(Objects::nonNull).map(Path::of).toList();
        Iterator<CompilationUnitInfo> loaded = astLoader.load(providers, present).iterator();
        List<CompilationUnitInfo> result = new ArrayList<>();
        for (String path : paths) {
            result.add(path == null ? null : loaded.next());
        }
        return result;
    }
}
