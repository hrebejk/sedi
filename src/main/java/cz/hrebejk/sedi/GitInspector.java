/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Inspector for Git repositories to detect changed files and compute diffs using JGit.
 */
public class GitInspector {

    private final Path repositoryPath;

    /**
     * Creates a new GitInspector for the given repository path.
     *
     * @param repositoryPath the path to the git repository
     */
    public GitInspector(Path repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

    /**
     * Opens the Git repository.
     *
     * @return the JGit Repository instance
     * @throws IOException if repository cannot be opened
     */
    public Repository openRepository() throws IOException {
        File workTree = repositoryPath.toFile();
        try (Git git = Git.open(workTree)) {
            return git.getRepository();
        }
    }

    /**
     * Computes the list of diff entries. If there are changes in the working copy (unstaged or staged),
     * returns those changes. If there are no working copy changes, compares HEAD against its parent.
     *
     * @return list of DiffEntry representing changes
     * @throws IOException     if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */
    public List<DiffEntry> getChanges() throws IOException, GitAPIException {
        try (Git git = Git.open(repositoryPath.toFile())) {
            // 1. Check unstaged working tree changes (working tree vs index)
            List<DiffEntry> unstaged = git.diff().call();
            if (!unstaged.isEmpty()) {
                return detectRenames(git.getRepository(), unstaged);
            }

            // 2. Check staged changes (index vs HEAD)
            List<DiffEntry> staged = git.diff().setCached(true).call();
            if (!staged.isEmpty()) {
                return detectRenames(git.getRepository(), staged);
            }

            // 3. If no working copy changes, compare latest commit (HEAD) against its parent (HEAD^)
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            if (head == null) {
                return List.of();
            }

            try (RevWalk revWalk = new RevWalk(repo)) {
                if (revWalk.parseCommit(head).getParentCount() == 0) {
                    return List.of();
                }
            }
            return diffAgainstParent(repo, head);
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
     * The two commits compared by a revision expression.
     *
     * @param oldCommit the old commit, {@code null} stands for the empty tree
     * @param newCommit the new commit
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
    private static List<DiffEntry> diff(Repository repo, ObjectId oldCommit, ObjectId newCommit) throws IOException {
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
            return detectRenames(repo, diffFormatter.scan(oldTree, newParser));
        }
    }

    /** Pairs added and deleted files with similar content into {@code RENAME} (or {@code COPY}) entries. */
    private static List<DiffEntry> detectRenames(Repository repo, List<DiffEntry> entries) throws IOException {
        RenameDetector detector = new RenameDetector(repo);
        detector.addAll(entries);
        return detector.compute();
    }

    private static List<DiffEntry> diffAgainstParent(Repository repo, ObjectId commitId) throws IOException {
        try (RevWalk revWalk = new RevWalk(repo)) {
            RevCommit commit = revWalk.parseCommit(commitId);
            return diff(repo, commit.getParentCount() > 0 ? commit.getParent(0) : null, commitId);
        }
    }
}
