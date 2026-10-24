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
import com.sun.source.util.Trees;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Analyzer that inspects a Git repository, finds changed files, parses and analyzes
 * both old and new versions into attributed ASTs (CompilationUnitTree, Trees, DocTrees).
 */
public class DiffAstAnalyzer implements AutoCloseable {

    private final Path repositoryPath;
    private final AstLoader astLoader;
    private final List<Path> tempDirs = new ArrayList<>();

    /**
     * Creates a new DiffAstAnalyzer for the given repository path.
     *
     * @param repositoryPath path to the Git repository
     */
    public DiffAstAnalyzer(Path repositoryPath) {
        this(repositoryPath, new AstLoader());
    }

    /**
     * Creates a new DiffAstAnalyzer which attributes the sources using the given loader.
     *
     * @param repositoryPath path to the Git repository
     * @param astLoader      loader used to parse and attribute the old and new versions of files
     */
    public DiffAstAnalyzer(Path repositoryPath, AstLoader astLoader) {
        this.repositoryPath = repositoryPath;
        this.astLoader = astLoader;
    }

    /**
     * Deletes the temporary copies of old file versions created by the analyses.
     * The ASTs returned earlier must not be used afterwards.
     */
    @Override
    public void close() throws IOException {
        for (Path dir : tempDirs) {
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        tempDirs.clear();
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
        return analyze(inspector, inspector.getChanges(), filter, true);
    }

    /**
     * Analyzes the Java files changed by a commit (compared with its first parent).
     * Both versions of every file are read from the git objects, never from the working tree.
     *
     * @param revision any revision git understands: SHA, abbreviated SHA, tag, branch, {@code HEAD~2}, ...
     * @param filter   accepts repository relative paths ({@code /} separated)
     * @return list of FileDiffAnalysis containing old/new files, diffs, and ASTs
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if the revision cannot be resolved to a commit
     */
    public List<FileDiffAnalysis> analyzeCommit(String revision, Predicate<String> filter) throws IOException {
        GitInspector inspector = new GitInspector(repositoryPath);
        return analyze(inspector, inspector.getChanges(revision), filter, false);
    }

    private List<FileDiffAnalysis> analyze(GitInspector inspector, List<DiffEntry> allDiffs,
                                           Predicate<String> filter, boolean preferWorkingTree) throws IOException {
        List<DiffEntry> diffs = allDiffs.stream()
                .filter(d -> filter.test(d.getOldPath()) || filter.test(d.getNewPath()))
                .toList();

        try (Repository repo = inspector.openRepository()) {
            Path oldTempDir = Files.createTempDirectory("sedi-old-ast");
            Path newTempDir = Files.createTempDirectory("sedi-new-ast");
            tempDirs.add(oldTempDir);
            tempDirs.add(newTempDir);
            List<FileDiffAnalysis> results = new ArrayList<>();

            for (DiffEntry diff : diffs) {
                Path oldFile = extractBlob(repo, diff.getOldId(), diff.getOldPath(), oldTempDir);
                Path newFile = resolveNewFile(diff, newTempDir, repo, preferWorkingTree);

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

    private Path resolveNewFile(DiffEntry diff, Path tempDir, Repository repo,
                                boolean preferWorkingTree) throws IOException {
        String newPath = diff.getNewPath();
        if ("/dev/null".equals(newPath)) {
            return null;
        }
        Path workingTreeFile = repositoryPath.resolve(newPath);
        if (preferWorkingTree && Files.exists(workingTreeFile)) {
            return workingTreeFile;
        }
        return extractBlob(repo, diff.getNewId(), newPath, tempDir);
    }

    private CompilationUnitResult analyzeFile(Path filePath) throws IOException {
        if (filePath == null || !Files.exists(filePath)) {
            return new CompilationUnitResult(null, null, null);
        }
        CompilationAnalysis analysis = astLoader.loadCompilationAnalyses(List.of(filePath)).getFirst();
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
