/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.compilation;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A file manager which serves the {@link StandardLocation#SOURCE_PATH} from {@link SourceFileProvider}s instead of
 * from directories. javac asks for the sources of a package only when it meets a class it does not know,
 * so the sources are read lazily, directly from where they live (for example a git tree).
 */
final class SourceFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final List<SourceFileProvider> providers;

    /**
     * Creates the manager.
     *
     * @param delegate  manager serving everything but the source path
     * @param providers sources of the source path; for a class present in several providers the first one wins
     */
    SourceFileManager(StandardJavaFileManager delegate, List<SourceFileProvider> providers) {
        super(delegate);
        this.providers = List.copyOf(providers);
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
        for (SourceFileProvider provider : providers) {
            for (JavaFileObject file : provider.list(packageName, recurse)) {
                if (seen.add(file.getName())) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    @Override
    public boolean contains(Location location, FileObject file) throws IOException {
        // The files of the providers are not path based, the standard manager cannot tell where they are.
        return file instanceof SourceFile ? location == StandardLocation.SOURCE_PATH : super.contains(location, file);
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (!(file instanceof SourceFile)) {
            return super.inferBinaryName(location, file);
        }
        for (SourceFileProvider provider : providers) {
            String binaryName = provider.inferBinaryName(file);
            if (binaryName != null) {
                return binaryName;
            }
        }
        return null;
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        return a instanceof SourceFile || b instanceof SourceFile ? a.equals(b) : super.isSameFile(a, b);
    }
}
