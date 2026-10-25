/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import junit.framework.TestCase;
import java.nio.file.Path;
import java.util.List;

/**
 * Unit test for DiffAstAnalyzer.
 */
public class DiffAstAnalyzerTest extends TestCase {

    /**
     * Tests analyzing changes between old and new versions of changed Java files.
     */
    public void testAnalyzeChanges() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Calculator.java", "package cz.hrebejk.sedi;\n\npublic class Calculator {\n    public int add(int a, int b) { return a + b; }\n}\n");
            helper.commit("Initial commit");

            // Modify file
            helper.writeFile("Calculator.java", "package cz.hrebejk.sedi;\n\npublic class Calculator {\n    public int add(int a, int b) { return a + b + 1; }\n}\n");

            DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath());
            List<FileDiffAnalysis> analyses = analyzer.analyzeChanges();

            assertNotNull(analyses);
            assertEquals(1, analyses.size());

            FileDiffAnalysis analysis = analyses.get(0);
            assertNotNull(analysis.oldFile());
            assertNotNull(analysis.newFile());
            assertNotNull(analysis.diff());
            assertNotNull(analysis.oldCompilationUnit());
            assertNotNull(analysis.newCompilationUnit());
            assertNotNull(analysis.oldTrees());
            assertNotNull(analysis.newTrees());
            assertNotNull(analysis.oldDocTrees());
            assertNotNull(analysis.newDocTrees());
        }
    }
}
