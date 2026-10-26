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

import javax.tools.SimpleJavaFileObject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A Java source file whose content is read only when javac asks for it: from the working tree or
 * straight from a git blob, so nothing needs to be copied to a temporary location.
 */
public final class SourceFile extends SimpleJavaFileObject {

    /** Supplies the bytes of the file. */
    @FunctionalInterface
    interface Content {
        byte[] read() throws IOException;
    }

    private final String binaryName;
    private final Content content;

    SourceFile(URI uri, String binaryName, Content content) {
        super(uri, Kind.SOURCE);
        this.binaryName = binaryName;
        this.content = content;
    }

    /** A file of the working tree. */
    public static SourceFile ofPath(Path path, String binaryName) {
        return new SourceFile(path.toUri(), binaryName, () -> Files.readAllBytes(path));
    }

    /**
     * A blob of a git repository.
     *
     * @param repo       repository, must stay open as long as the file may be read
     * @param blob       id of the blob
     * @param repoPath   repository relative path of the file ({@code /} separated)
     * @param binaryName binary name of the class the file declares, {@code null} if unknown
     */
    public static SourceFile ofBlob(Repository repo, ObjectId blob, String repoPath, String binaryName) {
        try {
            URI uri = new URI("git", null, "/" + blob.name() + "/" + repoPath, null);
            return new SourceFile(uri, binaryName, () -> repo.open(blob).getBytes());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Binary name of the class this file is the source of, {@code null} if unknown. */
    public String binaryName() {
        return binaryName;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
        return new String(content.read(), StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SourceFile other && toUri().equals(other.toUri());
    }

    @Override
    public int hashCode() {
        return toUri().hashCode();
    }
}
