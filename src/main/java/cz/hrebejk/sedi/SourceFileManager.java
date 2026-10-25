/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A file manager which serves the {@link StandardLocation#SOURCE_PATH} from {@link Layer}s instead of
 * from directories. javac asks for the sources of a package only when it meets a class it does not know,
 * so the sources are read lazily, directly from where they live (for example a git tree).
 */
final class SourceFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    /** A source of Java files organized by packages. */
    interface Layer {
        /**
         * Lists the source files of a package.
         *
         * @param packageName dotted package name, empty for the default package
         * @param recurse     also list the files of the sub packages
         * @return the files, each with its binary name set
         */
        List<SourceFile> list(String packageName, boolean recurse) throws IOException;
    }

    private final List<Layer> layers;

    /**
     * Creates the manager.
     *
     * @param delegate manager serving everything but the source path
     * @param layers   sources of the source path; for a class present in several layers the first one wins
     */
    SourceFileManager(StandardJavaFileManager delegate, List<Layer> layers) {
        super(delegate);
        this.layers = List.copyOf(layers);
    }

    @Override
    public boolean hasLocation(Location location) {
        return location == StandardLocation.SOURCE_PATH || super.hasLocation(location);
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds,
                                         boolean recurse) throws IOException {
        if (location != StandardLocation.SOURCE_PATH) {
            return super.list(location, packageName, kinds, recurse);
        }
        List<JavaFileObject> result = new ArrayList<>();
        if (!kinds.contains(JavaFileObject.Kind.SOURCE)) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (Layer layer : layers) {
            for (SourceFile file : layer.list(packageName, recurse)) {
                if (seen.add(file.binaryName())) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        return file instanceof SourceFile source && source.binaryName() != null
                ? source.binaryName() : super.inferBinaryName(location, file);
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        return a instanceof SourceFile || b instanceof SourceFile ? a.equals(b) : super.isSameFile(a, b);
    }
}
