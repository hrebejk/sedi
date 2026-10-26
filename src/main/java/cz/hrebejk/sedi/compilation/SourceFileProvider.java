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

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A source of Java files organized by packages. javac asks for the sources of a package only when it meets a
 * class it does not know, so the sources are read lazily, directly from where they live (for example a git tree).
 */
public interface SourceFileProvider {

    /**
     * Lists the source files of a package.
     *
     * @param packageName dotted package name, empty for the default package
     * @param recurse     also list the files of the sub packages
     * @return the files
     */
    List<JavaFileObject> list(String packageName, boolean recurse) throws IOException;

    /**
     * Finds a source file by its path.
     *
     * @param path path of the file as understood by the provider (see the factory methods)
     * @return the file, empty if the provider does not have it
     */
    Optional<JavaFileObject> get(Path path) throws IOException;

    /**
     * Infers the binary name of the class a file declares from the location of the file (its URI) in the source
     * roots of the provider.
     *
     * @param file a file, not necessarily one of this provider
     * @return the binary name, {@code null} if the file does not belong to the source roots of this provider
     */
    String inferBinaryName(JavaFileObject file);

    /**
     * The source roots of a directory tree. Nothing is read until asked for.
     *
     * @param base  directory the roots and paths are relative to
     * @param roots source roots relative to {@code base}, {@code ""} stands for {@code base} itself
     * @return provider whose {@link #get} accepts paths relative to {@code base} or absolute paths
     */
    static SourceFileProvider directories(Path base, List<String> roots) {
        return SourceLayers.directories(base, roots);
    }

    /**
     * The source roots of a commit of a git repository; only the directories javac asks for are read.
     *
     * @param repo   repository, must stay open as long as the provider is used
     * @param commit the commit
     * @param roots  repository relative source roots, {@code ""} stands for the repository root
     * @return provider whose {@link #get} accepts repository relative paths
     */
    static SourceFileProvider gitTree(Repository repo, ObjectId commit, List<String> roots) throws IOException {
        return SourceLayers.gitTree(repo, commit, roots);
    }
}
