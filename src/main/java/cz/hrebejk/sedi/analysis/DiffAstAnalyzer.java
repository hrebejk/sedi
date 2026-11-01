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
    private final GitInspector inspector;
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
        this(repositoryPath, astLoader, GitInspector.DEFAULT_RENAME_SCORE);
    }

    /**
     * Creates a new DiffAstAnalyzer which attributes the sources using the given loader, see
     * {@link #DiffAstAnalyzer(Path, AstLoader)}.
     *
     * @param repositoryPath path to the Git repository
     * @param astLoader      loader used to parse and attribute the old and new versions of files
     * @param renameScore    the least similarity, in percent (0 - 100), of an added and a deleted file for git to
     *                       report them as a rename
     * @throws IllegalArgumentException if the score is not between 0 and 100
     */
    public DiffAstAnalyzer(Path repositoryPath, AstLoader astLoader, int renameScore) {
        this.inspector = new GitInspector(repositoryPath, renameScore);
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
     * Analyzes all changed files in the repository.
     *
     * @return the analysis of the diff: hashes, renames and API changes of every changed file
     * @throws IOException     if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */
    public DiffAnalysis analyzeChanges() throws IOException, GitAPIException {
        return analyzeChanges(path -> true);
    }

    /**
     * Analyzes the changed files accepted by the filter. The renames are found in all the changed Java files,
     * so the accepted files are analyzed the same as when all the files are.
     *
     * @param filter accepts repository relative paths ({@code /} separated); a change is in the result if
     *               either its old or new path is accepted
     * @return the analysis of the diff: hashes, renames and API changes of every accepted file
     * @throws IOException     if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */
    public DiffAnalysis analyzeChanges(Predicate<String> filter) throws IOException, GitAPIException {
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
     * @return the analysis of the diff: hashes, renames and API changes of every changed file
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if the revision cannot be resolved to a commit
     */
    public DiffAnalysis analyzeCommit(String revision, Predicate<String> filter) throws IOException {
        GitInspector.Range range = inspector.resolveRange(revision);
        return analyze(inspector.getChanges(revision), filter, range);
    }

    private Repository repository() throws IOException {
        if (git == null) {
            git = Git.open(repositoryPath.toFile());
        }
        return git.getRepository();
    }

    /**
     * Analyzes the files accepted by the filter. All the changed Java files are analyzed with them, as a file can
     * refer to elements renamed in the others, but only the accepted ones are in the result.
     *
     * @param range the compared versions; a {@code null} new commit stands for the working copy
     */
    private DiffAnalysis analyze(List<DiffEntry> allDiffs, Predicate<String> filter,
                                 GitInspector.Range range) throws IOException {
        Predicate<DiffEntry> accepted = d -> filter.test(d.getOldPath()) || filter.test(d.getNewPath());
        List<DiffEntry> diffs = allDiffs.stream()
                .filter(d -> accepted.test(d) || d.getOldPath().endsWith(".java") || d.getNewPath().endsWith(".java"))
                .toList();
        DiffAnalysis all = analyzeAll(diffs, range);
        return new DiffAnalysis(all.files().stream().filter(f -> accepted.test(f.diff())).toList(), all.renames());
    }

    private DiffAnalysis analyzeAll(List<DiffEntry> diffs, GitInspector.Range range) throws IOException {
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

        return ApiDiffAnalyzer.analyze(diffs, oldInfos, newInfos);
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
