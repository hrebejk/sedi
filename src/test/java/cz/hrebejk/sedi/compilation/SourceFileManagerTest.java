/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.compilation;

import cz.hrebejk.sedi.GitTestHelper;
import junit.framework.TestCase;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;

import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Tests that the binary names of the sources are inferred from their location.
 */
public class SourceFileManagerTest extends TestCase {

    private static final List<String> ROOTS = List.of("src/main/java");

    public void testBinaryNameOfDirectoryFileIsInferredFromUri() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/q/A.java", "package p.q; public class A {}");
            helper.writeFile("src/main/java/p/q/r/B.java", "package p.q.r; public class B {}");
            SourceFileProvider provider = SourceFileProvider.directories(helper.getRepositoryPath(), ROOTS);

            assertBinaryNames(provider, Set.of("p.q.A", "p.q.r.B"));
        }
    }

    public void testBinaryNameOfGitFileIsInferredFromUri() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/q/A.java", "package p.q; public class A {}");
            helper.writeFile("src/main/java/p/q/r/B.java", "package p.q.r; public class B {}");
            helper.writeFile("src/test/java/p/q/T.java", "package p.q; public class T {}");
            var commit = helper.commit("Initial commit");
            try (Git git = Git.open(helper.getRepositoryPath().toFile())) {
                Repository repo = git.getRepository();
                SourceFileProvider provider = SourceFileProvider.gitTree(repo, commit.getId(), ROOTS);

                assertBinaryNames(provider, Set.of("p.q.A", "p.q.r.B"));
                // Not below a source root of the provider.
                JavaFileObject test = provider.get(Path.of("src/test/java/p/q/T.java")).orElseThrow();
                assertNull(provider.inferBinaryName(test));
            }
        }
    }

    private static void assertBinaryNames(SourceFileProvider provider, Set<String> expected) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(null, null, null);
             SourceFileManager manager = new SourceFileManager(standard, List.of(provider))) {
            Iterable<JavaFileObject> files = manager.list(StandardLocation.SOURCE_PATH, "p",
                    Set.of(JavaFileObject.Kind.SOURCE), true);
            Set<String> names = new java.util.HashSet<>();
            for (JavaFileObject file : files) {
                names.add(manager.inferBinaryName(StandardLocation.SOURCE_PATH, file));
            }
            assertEquals(expected, names);
        }
    }
}
