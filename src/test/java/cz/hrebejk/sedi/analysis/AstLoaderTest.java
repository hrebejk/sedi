/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import cz.hrebejk.sedi.GitTestHelper;
import cz.hrebejk.sedi.compilation.AstLoader;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;

/**
 * Unit test for AstLoader.
 */
public class AstLoaderTest extends TestCase {

    /**
     * Tests that types from the classpath resolve, i.e. attribution reports no errors.
     */
    public void testClasspathResolvesLibraryTypes() throws Exception {
        Path jar = Path.of(org.junit.Assert.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (GitTestHelper helper = new GitTestHelper()) {
            Path sourceFile = helper.writeFile("Lib.java", "public class Lib {\n    void m() { org.junit.Assert.assertTrue(true); }\n}\n");

            CompilationUnitInfo analysis = TestSources.load(new AstLoader(List.of(jar)), List.of(sourceFile)).get(0);
            var method = ((com.sun.source.tree.ClassTree) analysis.compilationUnit().getTypeDecls().get(0)).getMembers().get(1);
            var path = analysis.trees().getPath(analysis.compilationUnit(), method);
            assertNotNull(analysis.trees().getElement(path));
        }
    }

    /**
     * Tests that several files are loaded in one task: the infos come in the order of the paths and share Trees.
     */
    public void testSeveralFilesShareOneTask() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path b = helper.writeFile("B.java", "public class B {}\n");
            Path a = helper.writeFile("A.java", "public class A { B b; }\n");

            List<CompilationUnitInfo> infos = TestSources.load(new AstLoader(), List.of(a, b));

            assertEquals(2, infos.size());
            assertTrue(infos.get(0).compilationUnit().getSourceFile().getName().endsWith("A.java"));
            assertTrue(infos.get(1).compilationUnit().getSourceFile().getName().endsWith("B.java"));
            assertSame(infos.get(0).trees(), infos.get(1).trees());
        }
    }

    public void testMissingPathFails() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path missing = helper.writeFile("A.java", "class A {}\n").resolveSibling("Missing.java");
            try {
                TestSources.load(new AstLoader(), List.of(missing));
                fail("Expected IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }
}
