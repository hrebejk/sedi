/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches a directory tree and reports batches of changed {@code .java} files. Events arriving
 * in quick succession are merged into one batch. Hidden directories and {@code target} are ignored,
 * except the refs of a {@code .git} directory in the root: a commit changes {@code .git/HEAD} or a
 * file under {@code .git/refs}, which are reported too.
 */
public final class DirectoryWatcher implements AutoCloseable {

    private static final long QUIET_PERIOD_MS = 200;

    private final WatchService service;
    private final Map<WatchKey, Path> directories = new HashMap<>();
    /** The git directory of the root, watched for changes of its refs. */
    private final Path gitDir;

    /**
     * Starts watching the tree below the root.
     *
     * @param root the directory to watch recursively
     * @throws IOException if the directories cannot be registered
     */
    public DirectoryWatcher(Path root) throws IOException {
        service = root.getFileSystem().newWatchService();
        gitDir = root.resolve(".git");
        register(root);
        if (Files.isDirectory(gitDir)) {
            directories.put(gitDir.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE), gitDir);
            Path refs = gitDir.resolve("refs");
            if (Files.isDirectory(refs)) {
                register(refs);
            }
        }
    }

    /**
     * Blocks until interrupted, passing every batch of changed Java files to the listener.
     *
     * @param listener receives absolute paths of created, modified or deleted {@code .java} files and git refs
     * @throws IOException if watching fails
     */
    public void run(Consumer<Set<Path>> listener) throws IOException {
        try {
            while (true) {
                Set<Path> changed = new LinkedHashSet<>();
                WatchKey key = service.take();
                do {
                    collect(key, changed);
                    key = service.poll(QUIET_PERIOD_MS, TimeUnit.MILLISECONDS);
                } while (key != null);
                if (!changed.isEmpty()) {
                    listener.accept(changed);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void collect(WatchKey key, Set<Path> changed) throws IOException {
        Path dir = directories.get(key);
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.context() instanceof Path name && dir != null) {
                Path path = dir.resolve(name);
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path)) {
                    // Of the git directory only the refs are watched, not its objects.
                    if (!path.startsWith(gitDir) || path.startsWith(gitDir.resolve("refs"))) {
                        register(path);
                    }
                } else if (path.toString().endsWith(".java") || isRef(path)) {
                    changed.add(path);
                }
            }
        }
        if (!key.reset()) {
            directories.remove(key);
        }
    }

    /**
     * Whether the path is {@code .git/HEAD} or a ref under {@code .git/refs}; a ref is written to a
     * {@code .lock} file first, which is left out.
     */
    private boolean isRef(Path path) {
        return path.equals(gitDir.resolve("HEAD"))
                || path.startsWith(gitDir.resolve("refs")) && !path.toString().endsWith(".lock");
    }

    private void register(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(root) && (name.startsWith(".") || name.equals("target"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                WatchKey key = dir.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                directories.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void close() throws IOException {
        service.close();
    }
}
