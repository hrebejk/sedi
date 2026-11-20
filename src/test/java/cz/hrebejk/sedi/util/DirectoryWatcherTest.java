/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.util;

import cz.hrebejk.sedi.GitTestHelper;
import junit.framework.TestCase;
import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Unit test for DirectoryWatcher.
 */
public class DirectoryWatcherTest extends TestCase {

    private GitTestHelper helper;
    private Path root;
    private DirectoryWatcher watcher;
    private final BlockingQueue<Set<Path>> batches = new LinkedBlockingQueue<>();

    @Override
    protected void setUp() throws Exception {
        helper = new GitTestHelper();
        root = helper.getRepositoryPath();
        helper.writeFile("src/p/A.java", "package p;\npublic class A {}\n");
        helper.writeFile("notes.txt", "notes\n");
        helper.commit("init");
    }

    @Override
    protected void tearDown() throws Exception {
        if (watcher != null) {
            watcher.close();
        }
        helper.close();
    }

    /** Starts watching the repository, collecting the batches in {@link #batches}. */
    private void startWatching() throws IOException {
        watcher = new DirectoryWatcher(root);
        Thread thread = new Thread(() -> {
            try {
                watcher.run(batches::add);
            } catch (ClosedWatchServiceException e) {
                // closed by tearDown
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Whether the batch contains a path in the git directory. */
    private boolean hasGitPath(Set<Path> batch) {
        return batch.stream().anyMatch(p -> p.startsWith(root.resolve(".git")));
    }

    /**
     * A commit is reported as a change of a ref, never of a lock file.
     */
    public void testCommitReportsRef() throws Exception {
        startWatching();
        helper.writeFile("notes.txt", "more notes\n");
        helper.commit("second");

        Set<Path> batch;
        do {
            batch = batches.poll(5, TimeUnit.SECONDS);
            assertNotNull("No batch with a git ref", batch);
        } while (!hasGitPath(batch));
        assertTrue(batch.toString(), batch.stream().anyMatch(
                p -> p.startsWith(root.resolve(".git/refs")) || p.equals(root.resolve(".git/HEAD"))));
        assertTrue(batch.toString(), batch.stream().noneMatch(p -> p.toString().endsWith(".lock")));
    }

    /**
     * Staging a file changes only the index and objects, which is not reported.
     */
    public void testAddIsNotReported() throws Exception {
        helper.writeFile("notes.txt", "staged notes\n");
        startWatching();
        try (Git git = Git.open(root.toFile())) {
            git.add().addFilepattern("notes.txt").call();
        }
        Set<Path> batch = batches.poll(1, TimeUnit.SECONDS);
        assertNull(String.valueOf(batch), batch);
    }

    /**
     * An edited Java file is reported.
     */
    public void testJavaFileEdit() throws Exception {
        startWatching();
        Path file = helper.writeFile("src/p/A.java", "package p;\npublic class A { int x; }\n");

        Set<Path> batch = batches.poll(5, TimeUnit.SECONDS);
        assertNotNull("No batch", batch);
        assertTrue(batch.toString(), batch.contains(file));
    }
    /**
     * A Java file written deep in a module, in directories which existed before the watcher started, is reported.
     */
    public void testJavaFileDeepInModule() throws Exception {
        helper.writeFile("core/src/main/java/p/Old.java", "package p;\npublic class Old {}\n");
        startWatching();
        Path file = helper.writeFile("core/src/main/java/p/X.java", "package p;\npublic class X {}\n");

        Set<Path> batch = batches.poll(5, TimeUnit.SECONDS);
        assertNotNull("No batch", batch);
        assertTrue(batch.toString(), batch.contains(file));
    }
}
