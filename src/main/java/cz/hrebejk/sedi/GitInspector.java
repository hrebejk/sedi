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
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
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
                return unstaged;
            }

            // 2. Check staged changes (index vs HEAD)
            List<DiffEntry> staged = git.diff().setCached(true).call();
            if (!staged.isEmpty()) {
                return staged;
            }

            // 3. If no working copy changes, compare latest commit (HEAD) against its parent (HEAD^)
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            if (head == null) {
                return List.of();
            }

            try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                diffFormatter.setRepository(repo);

                try (RevWalk revWalk = new RevWalk(repo)) {
                    RevCommit headCommit = revWalk.parseCommit(head);
                    if (headCommit.getParentCount() > 0) {
                        RevCommit parentCommit = revWalk.parseCommit(headCommit.getParent(0));
                        CanonicalTreeParser parentParser = new CanonicalTreeParser();
                        parentParser.reset(repo.newObjectReader(), parentCommit.getTree());

                        CanonicalTreeParser headParser = new CanonicalTreeParser();
                        headParser.reset(repo.newObjectReader(), headCommit.getTree());

                        return diffFormatter.scan(parentParser, headParser);
                    }
                }
            }

            return List.of();
        }
    }
}
