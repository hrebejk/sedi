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
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
 * <p>
 * One analyzer is meant to be reused for the repeated analyses of a watched directory. The old versions do not
 * change as long as the old commit stays the same, so each is compiled and hashed only once: a later analysis
 * compiles only the old versions of the files which were not changed before.
 */
public class DiffAstAnalyzer implements AutoCloseable {

    private final Path repositoryPath;
    private final GitInspector inspector;
    /** The parts of the project compiled separately; never empty. */
    private final List<SourceModule> modules;
    private Git git;
    /** The old commit the cached old versions are from; {@code null} also when nothing is cached. */
    private ObjectId cachedOldCommit;
    /** The compiled old versions of the files, by their repository relative paths. */
    private final Map<String, CompilationUnitInfo> oldUnits = new HashMap<>();
    /** The element trees of the old versions, hashed without renames. */
    private final Map<CompilationUnitInfo, ElementInfo> oldRoots = new IdentityHashMap<>();

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
        this(repositoryPath, astLoader, renameScore, List.of());
    }

    /**
     * Creates a new DiffAstAnalyzer which attributes the sources using the given loader and finds the classes of
     * the project in the given source roots.
     *
     * @param repositoryPath path to the Git repository
     * @param astLoader      loader used to parse and attribute the old and new versions of files
     * @param renameScore    the least similarity, in percent (0 - 100), of an added and a deleted file for git to
     *                       report them as a rename
     * @param sourceRoots    repository relative ({@code /} separated) source roots, {@code ""} stands for the
     *                       repository root; when empty, the roots are found as by
     *                       {@link #DiffAstAnalyzer(Path, AstLoader)}
     * @throws IllegalArgumentException if the score is not between 0 and 100
     */
    public DiffAstAnalyzer(Path repositoryPath, AstLoader astLoader, int renameScore, List<String> sourceRoots) {
        this(repositoryPath, renameScore, List.of(SourceModule.of(repositoryPath, sourceRoots, astLoader)));
    }

    /**
     * Creates a new DiffAstAnalyzer for a project made of modules compiled separately, such as the modules of a
     * multi-module Maven project. Each changed file is compiled with the module whose own roots contain it most
     * specifically, or with the first module if none does. Renames are still found across all the modules.
     *
     * @param repositoryPath path to the Git repository
     * @param renameScore    the least similarity, in percent (0 - 100), of an added and a deleted file for git to
     *                       report them as a rename
     * @param modules        the modules of the project, at least one
     * @throws IllegalArgumentException if the score is not between 0 and 100 or there are no modules
     */
    public DiffAstAnalyzer(Path repositoryPath, int renameScore, List<SourceModule> modules) {
        if (modules.isEmpty()) {
            throw new IllegalArgumentException("No modules");
        }
        this.inspector = new GitInspector(repositoryPath, renameScore);
        this.repositoryPath = repositoryPath;
        this.modules = List.copyOf(modules);
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
     * Both versions of every file, and the classes they refer to, are read from the git objects, except for
     * a range with no right side ({@code a..}), whose new versions are read from the working tree.
     *
     * @param revision any revision git understands: SHA, abbreviated SHA, tag, branch, {@code HEAD~2}, ...;
     *                 or a range, see {@link GitInspector#getChanges(String)}
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
        return new DiffAnalysis(all.files().stream().filter(f -> accepted.test(f.diff())).toList(), all.renames(),
                range);
    }

    private DiffAnalysis analyzeAll(List<DiffEntry> diffs, GitInspector.Range range) throws IOException {
        Repository repo = repository();

        // The providers of each module, by the index of the module.
        List<List<SourceFileProvider>> oldProviders = new ArrayList<>();
        List<List<SourceFileProvider>> newProviders = new ArrayList<>();
        for (SourceModule module : modules) {
            oldProviders.add(range.oldCommit() == null ? List.of()
                    : List.of(SourceFileProvider.gitTree(repo, range.oldCommit(), module.sourceRoots())));
            newProviders.add(List.of(range.newCommit() == null
                    ? SourceFileProvider.directories(repositoryPath, module.sourceRoots())
                    : SourceFileProvider.gitTree(repo, range.newCommit(), module.sourceRoots())));
        }

        List<String> oldPaths = new ArrayList<>();
        List<String> newPaths = new ArrayList<>();
        for (DiffEntry diff : diffs) {
            oldPaths.add(DiffEntry.DEV_NULL.equals(diff.getOldPath()) ? null : diff.getOldPath());
            newPaths.add(DiffEntry.DEV_NULL.equals(diff.getNewPath()) ? null : diff.getNewPath());
        }
        List<CompilationUnitInfo> oldInfos = loadOld(range.oldCommit(), oldProviders, oldPaths);
        List<CompilationUnitInfo> newInfos = load(newProviders, newPaths);

        return ApiDiffAnalyzer.analyze(diffs, oldInfos, newInfos,
                unit -> oldRoots.computeIfAbsent(unit, ApiDiffAnalyzer::root));
    }

    /**
     * Loads the old versions, compiling only those not compiled by an earlier analysis of the same old commit.
     * The result has a {@code null} for every {@code null} path.
     */
    private List<CompilationUnitInfo> loadOld(ObjectId oldCommit, List<List<SourceFileProvider>> providers,
                                              List<String> paths) throws IOException {
        if (!Objects.equals(oldCommit, cachedOldCommit)) {
            oldUnits.clear();
            oldRoots.clear();
            cachedOldCommit = oldCommit;
        }
        List<String> missing = paths.stream().filter(Objects::nonNull).filter(p -> !oldUnits.containsKey(p))
                .distinct().toList();
        if (!missing.isEmpty()) {
            List<CompilationUnitInfo> loaded = load(providers, missing);
            for (int i = 0; i < missing.size(); i++) {
                oldUnits.put(missing.get(i), loaded.get(i));
            }
        }
        List<CompilationUnitInfo> result = new ArrayList<>();
        for (String path : paths) {
            result.add(path == null ? null : oldUnits.get(path));
        }
        return result;
    }

    /**
     * Loads the files in one compilation per module, each with the providers of its module (by the index of the
     * module); the result has a {@code null} for every {@code null} path.
     */
    private List<CompilationUnitInfo> load(List<List<SourceFileProvider>> providers, List<String> paths)
            throws IOException {
        // The indexes of the paths of each module, by the index of the module.
        List<List<Integer>> byModule = new ArrayList<>();
        modules.forEach(m -> byModule.add(new ArrayList<>()));
        for (int i = 0; i < paths.size(); i++) {
            if (paths.get(i) != null) {
                byModule.get(moduleOf(paths.get(i))).add(i);
            }
        }
        CompilationUnitInfo[] result = new CompilationUnitInfo[paths.size()];
        for (int m = 0; m < modules.size(); m++) {
            List<Integer> indexes = byModule.get(m);
            if (indexes.isEmpty()) {
                continue;
            }
            List<Path> present = indexes.stream().map(i -> Path.of(paths.get(i))).toList();
            Iterator<CompilationUnitInfo> loaded = modules.get(m).loader().load(providers.get(m), present).iterator();
            for (int i : indexes) {
                result[i] = loaded.next();
            }
        }
        return Arrays.asList(result);
    }

    /** The index of the module of the file: the one whose own roots contain it most specifically, else the first. */
    private int moduleOf(String path) {
        int best = 0;
        int bestMatch = -1;
        for (int m = 0; m < modules.size(); m++) {
            int match = modules.get(m).match(path);
            if (match > bestMatch) {
                best = m;
                bestMatch = match;
            }
        }
        return best;
    }
}
