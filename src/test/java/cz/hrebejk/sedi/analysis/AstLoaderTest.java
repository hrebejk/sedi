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

            CompilationUnitInfo analysis = new AstLoader(List.of(jar)).loadCompilationAnalyses(List.of(sourceFile)).get(0);
            var method = ((com.sun.source.tree.ClassTree) analysis.compilationUnit().getTypeDecls().get(0)).getMembers().get(1);
            var path = analysis.trees().getPath(analysis.compilationUnit(), method);
            assertNotNull(analysis.trees().getElement(path));
        }
    }
}
