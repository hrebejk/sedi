/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.util;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.diff.ContentSource;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Inspector for Git repositories to detect changed files and compute diffs using JGit.
 */
public class GitInspector {

    /** The least similarity, in percent, of an added and a deleted file to be a rename; as in the git command line. */
    public static final int DEFAULT_RENAME_SCORE = 50;

    private final Path repositoryPath;
    private final int renameScore;

    /**
     * Creates a new GitInspector for the given repository path, with the {@link #DEFAULT_RENAME_SCORE}.
     *
     * @param repositoryPath the path to the git repository
     */
    public GitInspector(Path repositoryPath) {
        this(repositoryPath, DEFAULT_RENAME_SCORE);
    }

    /**
     * Creates a new GitInspector for the given repository path.
     *
     * @param repositoryPath the path to the git repository
     * @param renameScore    the least similarity, in percent (0 - 100), of an added and a deleted file to be a rename
     * @throws IllegalArgumentException if the score is not between 0 and 100
     */
    public GitInspector(Path repositoryPath, int renameScore) {
        if (renameScore < 0 || renameScore > 100) {
            throw new IllegalArgumentException("Rename score must be between 0 and 100: " + renameScore);
        }
        this.repositoryPath = repositoryPath;
        this.renameScore = renameScore;
    }

    /**
     * Computes the changes of the working copy: what is on disk compared with HEAD, whether staged or not.
     * If the working copy is clean, the changes of HEAD against its parent.
     *
     * @return list of DiffEntry representing changes
     * @throws IOException     if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */
    public List<DiffEntry> getChanges() throws IOException, GitAPIException {
        return getChanges(resolveWorkingCopy());
    }

    /**
     * The two versions compared by {@link #getChanges()}: HEAD and the working copy, or, if the working copy
     * is clean, the parent of HEAD and HEAD.
     *
     * @return the compared versions; the new commit is {@code null} for the working copy
     * @throws IOException     if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */
    public Range resolveWorkingCopy() throws IOException, GitAPIException {
        try (Git git = Git.open(repositoryPath.toFile())) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            if (head == null) {
                return new Range(null, null);
            }
            Range workingCopy = new Range(head, null);
            if (!getChanges(git, workingCopy).isEmpty()) {
                return workingCopy;
            }
            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit commit = revWalk.parseCommit(head);
                return commit.getParentCount() == 0 ? workingCopy : new Range(commit.getParent(0).getId(), head);
            }
        }
    }

    /**
     * Computes the changes between two versions.
     *
     * @param range the compared versions, see {@link Range}
     * @return list of DiffEntry representing changes
     * @throws IOException     if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */
    public List<DiffEntry> getChanges(Range range) throws IOException, GitAPIException {
        try (Git git = Git.open(repositoryPath.toFile())) {
            return getChanges(git, range);
        }
    }

    private List<DiffEntry> getChanges(Git git, Range range) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        if (range.newCommit() != null) {
            return diff(repo, range.oldCommit(), range.newCommit());
        }
        if (range.oldCommit() == null) {
            return List.of();
        }
        try (RevWalk revWalk = new RevWalk(repo); ObjectReader reader = repo.newObjectReader()) {
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            oldTree.reset(reader, revWalk.parseCommit(range.oldCommit()).getTree());
            // No new tree, so the working tree is compared. Its files are read from the disk, as their
            // content is usually not in the object database.
            ContentSource.Pair contents = new ContentSource.Pair(ContentSource.create(reader),
                    ContentSource.create(new FileTreeIterator(repo)));
            return detectRenames(repo, git.diff().setOldTree(oldTree).call(), contents);
        }
    }

    /**
     * Computes the changes selected by a revision expression:
     * <ul>
     *   <li>{@code rev} - changes introduced by the commit, i.e. the diff against its first parent
     *       (or against an empty tree for a root commit)</li>
     *   <li>{@code a..b} - the diff between commits {@code a} and {@code b}</li>
     *   <li>{@code a...b} - the diff between the merge base of {@code a} and {@code b} and commit {@code b}</li>
     * </ul>
     * An omitted side of a range means {@code HEAD}. Each revision can be a SHA, abbreviated SHA, tag,
     * branch, {@code HEAD~2}, ...
     *
     * @param revision the revision expression
     * @return list of DiffEntry representing changes
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if a revision cannot be resolved to a commit
     */
    public List<DiffEntry> getChanges(String revision) throws IOException {
        Range range = resolveRange(revision);
        try (Git git = Git.open(repositoryPath.toFile())) {
            return diff(git.getRepository(), range.oldCommit(), range.newCommit());
        }
    }

    /**
     * The two versions compared: commits, or a commit and the working copy.
     *
     * @param oldCommit the old commit, {@code null} stands for the empty tree
     * @param newCommit the new commit, {@code null} stands for the working copy (what is on disk)
     */
    public record Range(ObjectId oldCommit, ObjectId newCommit) {
    }

    /**
     * Resolves a revision expression, see {@link #getChanges(String)}, to the commits it compares.
     *
     * @param revision the revision expression
     * @return the old and the new commit
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if a revision cannot be resolved to a commit
     */
    public Range resolveRange(String revision) throws IOException {
        try (Git git = Git.open(repositoryPath.toFile())) {
            Repository repo = git.getRepository();
            int threeDots = revision.indexOf("...");
            int twoDots = revision.indexOf("..");
            if (threeDots < 0 && twoDots < 0) {
                ObjectId commit = resolveCommit(repo, revision);
                try (RevWalk revWalk = new RevWalk(repo)) {
                    RevCommit parsed = revWalk.parseCommit(commit);
                    return new Range(parsed.getParentCount() > 0 ? parsed.getParent(0).getId() : null, commit);
                }
            }
            boolean mergeBase = threeDots >= 0;
            int at = mergeBase ? threeDots : twoDots;
            ObjectId left = resolveCommit(repo, revision.substring(0, at));
            ObjectId right = resolveCommit(repo, revision.substring(at + (mergeBase ? 3 : 2)));
            if (mergeBase) {
                left = findMergeBase(repo, left, right);
            }
            return new Range(left, right);
        }
    }

    private static ObjectId resolveCommit(Repository repo, String revision) throws IOException {
        String rev = revision.isEmpty() ? "HEAD" : revision;
        ObjectId id;
        try {
            id = repo.resolve(rev + "^{commit}");
        } catch (RevisionSyntaxException | AmbiguousObjectException | IncorrectObjectTypeException e) {
            throw new IllegalArgumentException("Cannot resolve commit '" + rev + "': " + e.getMessage(), e);
        }
        if (id == null) {
            throw new IllegalArgumentException("Cannot resolve commit '" + rev + "'");
        }
        return id;
    }

    /** Returns the merge base of the two commits, or {@code null} if they have no common ancestor. */
    private static ObjectId findMergeBase(Repository repo, ObjectId a, ObjectId b) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(a));
            walk.markStart(walk.parseCommit(b));
            RevCommit base = walk.next();
            return base == null ? null : base.getId();
        }
    }

    /** Diffs two commits; a {@code null} old commit stands for the empty tree. */
    private List<DiffEntry> diff(Repository repo, ObjectId oldCommit, ObjectId newCommit) throws IOException {
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
             RevWalk revWalk = new RevWalk(repo);
             ObjectReader reader = repo.newObjectReader()) {
            diffFormatter.setRepository(repo);
            AbstractTreeIterator oldTree = new EmptyTreeIterator();
            if (oldCommit != null) {
                CanonicalTreeParser oldParser = new CanonicalTreeParser();
                oldParser.reset(reader, revWalk.parseCommit(oldCommit).getTree());
                oldTree = oldParser;
            }
            CanonicalTreeParser newParser = new CanonicalTreeParser();
            newParser.reset(reader, revWalk.parseCommit(newCommit).getTree());
            ContentSource source = ContentSource.create(reader);
            return detectRenames(repo, diffFormatter.scan(oldTree, newParser), new ContentSource.Pair(source, source));
        }
    }

    /**
     * Pairs added and deleted files with similar content into {@code RENAME} (or {@code COPY}) entries.
     *
     * @param contents reads the old and the new content of the files
     */
    private List<DiffEntry> detectRenames(Repository repo, List<DiffEntry> entries,
                                          ContentSource.Pair contents) throws IOException {
        RenameDetector detector = new RenameDetector(repo);
        detector.setRenameScore(renameScore);
        detector.addAll(entries);
        try {
            return detector.compute(contents, NullProgressMonitor.INSTANCE);
        } catch (CanceledException e) {
            // Not possible, nothing cancels the null monitor.
            throw new IOException(e);
        }
    }
}
