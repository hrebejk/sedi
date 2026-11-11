/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.compilation;

import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import cz.hrebejk.sedi.GitTestHelper;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tests that only the loaded files are attributed, not the classes javac reads from the source path for them.
 */
public class AstLoaderAttributionTest extends TestCase {

    private static final List<String> ROOTS = List.of("src/main/java");

    public void testOnlyLoadedFilesAreAttributed() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/A.java", "package p; public class A { int a() { return new B().b(); } }");
            helper.writeFile("src/main/java/p/B.java", "package p; public class B { int b() { return new C().c(); } }");
            helper.writeFile("src/main/java/p/C.java", "package p; public class C { int c() { return 1; } }");
            Set<String> analyzed = new TreeSet<>();
            AstLoader loader = new AstLoader(List.of(), new TaskListener() {
                @Override
                public void finished(TaskEvent e) {
                    if (e.getKind() == TaskEvent.Kind.ANALYZE) {
                        analyzed.add(e.getTypeElement().getQualifiedName().toString());
                    }
                }
            });

            List<CompilationUnitInfo> units = loader.load(
                    List.of(SourceFileProvider.directories(helper.getRepositoryPath(), ROOTS)),
                    List.of(Path.of("src/main/java/p/A.java")));

            assertEquals(1, units.size());
            assertEquals(Set.of("p.A"), analyzed);
        }
    }
}
