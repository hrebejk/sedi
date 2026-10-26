/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.compilation;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Implementations of {@link SourceFileProvider}: a directory tree, and a git tree.
 */
final class SourceLayers {

    private static final String JAVA = ".java";

    private SourceLayers() {
    }

    static SourceFileProvider directories(Path base, List<String> roots) {
        Path absoluteBase = base.toAbsolutePath().normalize();
        return new SourceFileProvider() {
            @Override
            public List<JavaFileObject> list(String packageName, boolean recurse) throws IOException {
                List<JavaFileObject> files = new ArrayList<>();
                for (String root : roots) {
                    Path rootDir = root.isEmpty() ? base : base.resolve(root);
                    Path dir = rootDir.resolve(packageName.replace('.', '/'));
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try (Stream<Path> paths = recurse ? Files.walk(dir) : Files.list(dir)) {
                        for (Path p : paths.filter(f -> f.toString().endsWith(JAVA) && Files.isRegularFile(f)).toList()) {
                            files.add(SourceFile.ofPath(p));
                        }
                    }
                }
                return files;
            }

            @Override
            public Optional<JavaFileObject> get(Path path) {
                Path file = absoluteBase.resolve(path).normalize();
                if (!Files.isRegularFile(file)) {
                    return Optional.empty();
                }
                return Optional.of(SourceFile.ofPath(file));
            }

            @Override
            public String inferBinaryName(JavaFileObject file) {
                URI uri = file.toUri();
                if (!"file".equals(uri.getScheme())) {
                    return null;
                }
                String relative = absoluteBase.relativize(Path.of(uri).toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                return relative.startsWith("../") ? null : binaryName(relative, roots);
            }
        };
    }

    static SourceFileProvider gitTree(Repository repo, ObjectId commit, List<String> roots) throws IOException {
        ObjectId tree;
        try (RevWalk walk = new RevWalk(repo)) {
            tree = walk.parseCommit(commit).getTree();
        }
        return new SourceFileProvider() {
            @Override
            public List<JavaFileObject> list(String packageName, boolean recurse) throws IOException {
                List<JavaFileObject> files = new ArrayList<>();
                for (String root : roots) {
                    String prefix = root.isEmpty() ? "" : root + "/";
                    String dir = prefix + packageName.replace('.', '/');
                    if (dir.endsWith("/")) {
                        dir = dir.substring(0, dir.length() - 1);
                    }
                    try (TreeWalk walk = new TreeWalk(repo)) {
                        walk.addTree(tree);
                        walk.setRecursive(recurse);
                        if (!dir.isEmpty()) {
                            walk.setFilter(PathFilter.create(dir));
                        }
                        while (walk.next()) {
                            String path = walk.getPathString();
                            if (walk.isSubtree()) {
                                // Only the directories leading to the package, never its sub packages.
                                if (path.length() <= dir.length()) {
                                    walk.enterSubtree();
                                }
                                continue;
                            }
                            if (path.endsWith(JAVA) && (recurse || parent(path).equals(dir))) {
                                files.add(SourceFile.ofBlob(repo, walk.getObjectId(0), path));
                            }
                        }
                    }
                }
                return files;
            }

            @Override
            public Optional<JavaFileObject> get(Path path) throws IOException {
                String repoPath = path.toString().replace('\\', '/');
                try (TreeWalk walk = TreeWalk.forPath(repo, repoPath, tree)) {
                    if (walk == null || walk.isSubtree()) {
                        return Optional.empty();
                    }
                    return Optional.of(SourceFile.ofBlob(repo, walk.getObjectId(0), repoPath));
                }
            }

            @Override
            public String inferBinaryName(JavaFileObject file) {
                URI uri = file.toUri();
                if (!SourceFile.GIT_SCHEME.equals(uri.getScheme())) {
                    return null;
                }
                // The path of the URI is /<blob id>/<repository relative path>.
                String path = uri.getPath().substring(1);
                return binaryName(path.substring(path.indexOf('/') + 1), roots);
            }
        };
    }

    /**
     * Binary name of the class declared in the file, by its place in the source root.
     *
     * @param repoPath repository relative path of the file
     * @param roots    repository relative source roots
     * @return the name, {@code null} if the file is not below any root
     */
    static String binaryName(String repoPath, List<String> roots) {
        for (String root : roots) {
            String prefix = root.isEmpty() ? "" : root + "/";
            if (repoPath.startsWith(prefix) && repoPath.endsWith(JAVA)) {
                return binaryName(repoPath.substring(prefix.length()));
            }
        }
        return null;
    }

    private static String binaryName(String relativePath) {
        return relativePath.substring(0, relativePath.length() - JAVA.length()).replace('/', '.');
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
