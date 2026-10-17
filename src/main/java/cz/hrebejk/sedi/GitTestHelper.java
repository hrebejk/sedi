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
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Helper utility to create temporary Git repositories and manipulate files for testing.
 */
public class GitTestHelper implements AutoCloseable {

    private final Path tempDir;
    private final Git git;

    /**
     * Initializes a new Git repository in a temporary directory.
     *
     * @throws IOException     if temp directory cannot be created
     * @throws GitAPIException if git init fails
     */
    public GitTestHelper() throws IOException, GitAPIException {
        this.tempDir = Files.createTempDirectory("sedi-test-repo");
        this.git = Git.init().setDirectory(tempDir.toFile()).call();
    }

    /**
     * Returns the path to the repository working directory.
     *
     * @return the repository Path
     */
    public Path getRepositoryPath() {
        return tempDir;
    }

    /**
     * Creates or updates a file with the given content in the repository.
     *
     * @param relativePath relative path of the file within repository
     * @param content      file content
     * @return path to the written file
     * @throws IOException if writing fails
     */
    public Path writeFile(String relativePath, String content) throws IOException {
        Path filePath = tempDir.resolve(relativePath);
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content);
        return filePath;
    }

    /**
     * Stages and commits all changes with the given message.
     *
     * @param message commit message
     * @return the commit reference
     * @throws GitAPIException if commit fails
     */
    public RevCommit commit(String message) throws GitAPIException {
        try {
            git.add().addFilepattern(".").call();
        } catch (Exception e) {
            // Might be nothing to add if no tracked changes, ignore or let commit handle
        }
        return git.commit().setMessage(message).setSign(false).call();
    }

    @Override
    public void close() {
        try {
            git.close();
        } catch (Exception ignored) {
        }
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ignored) {
        }
    }
}
