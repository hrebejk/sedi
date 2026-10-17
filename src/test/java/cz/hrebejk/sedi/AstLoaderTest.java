/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import com.sun.source.tree.CompilationUnitTree;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;

/**
 * Unit test for AstLoader.
 */
public class AstLoaderTest extends TestCase {

    /**
     * Tests loading attributed AST of a Java source file.
     */
    public void testLoadAst() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path sourceFile = helper.writeFile("Sample.java", "package cz.hrebejk.sedi;\n\npublic class Sample {\n    private int value = 42;\n}\n");

            AstLoader loader = new AstLoader();
            List<CompilationUnitTree> asts = loader.loadAst(List.of(sourceFile));

            assertNotNull(asts);
            assertEquals(1, asts.size());
            CompilationUnitTree cut = asts.get(0);
            assertNotNull(cut);
            assertEquals("cz.hrebejk.sedi", cut.getPackageName().toString());
            assertFalse(cut.getTypeDecls().isEmpty());
        }
    }
}
