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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Factories of {@link SourceFileManager.Layer}s: a directory tree, a git tree, and a fixed set of files.
 */
public final class SourceLayers {

    private static final String JAVA = ".java";

    private SourceLayers() {
    }

    /** The source roots of a directory tree, e.g. {@code src/main/java}. Nothing is read until asked for. */
    public static SourceFileManager.Layer directories(List<Path> roots) {
        return (packageName, recurse) -> {
            List<SourceFile> files = new ArrayList<>();
            for (Path root : roots) {
                Path dir = root.resolve(packageName.replace('.', '/'));
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> paths = recurse ? Files.walk(dir) : Files.list(dir)) {
                    for (Path p : paths.filter(f -> f.toString().endsWith(JAVA) && Files.isRegularFile(f)).toList()) {
                        files.add(SourceFile.ofPath(p, binaryName(root.relativize(p).toString().replace('\\', '/'))));
                    }
                }
            }
            return files;
        };
    }

    /**
     * The source roots of a commit of a git repository; only the directories javac asks for are read.
     *
     * @param repo   repository, must stay open as long as the layer is used
     * @param commit the commit
     * @param roots  repository relative source roots, {@code ""} stands for the repository root
     */
    public static SourceFileManager.Layer gitTree(Repository repo, ObjectId commit, List<String> roots) throws IOException {
        ObjectId tree;
        try (RevWalk walk = new RevWalk(repo)) {
            tree = walk.parseCommit(commit).getTree();
        }
        return (packageName, recurse) -> {
            List<SourceFile> files = new ArrayList<>();
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
                            files.add(SourceFile.ofBlob(repo, walk.getObjectId(0), path,
                                    binaryName(path.substring(prefix.length()))));
                        }
                    }
                }
            }
            return files;
        };
    }

    /** A fixed set of files, each with its binary name set. */
    public static SourceFileManager.Layer fixed(List<SourceFile> files) {
        return (packageName, recurse) -> files.stream().filter(f -> {
            String pkg = packageOf(f.binaryName());
            return recurse ? pkg.equals(packageName) || pkg.startsWith(packageName + ".") || packageName.isEmpty()
                    : pkg.equals(packageName);
        }).toList();
    }

    /**
     * Binary name of the class declared in the file, by its place in the source root.
     *
     * @param repoPath repository relative path of the file
     * @param roots    repository relative source roots
     * @return the name, {@code null} if the file is not below any root
     */
    public static String binaryName(String repoPath, List<String> roots) {
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

    private static String packageOf(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
