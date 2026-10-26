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
 * Unit test for ReducedAstNode and MemberShaCalculator.calculate, including digest cascading and imports.
 */
public class ElementInfoTest extends TestCase {

    /**
     * Tests building reduced AST dynamically with original tree pointers and message digests.
     */
    public void testBuildReducedAst() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path sourceFile = helper.writeFile("User.java", "package cz.hrebejk.sedi;\n\npublic class User {\n    private String username;\n    public String getUsername() { return username; }\n}\n");

            AstLoader loader = new AstLoader();
            List<CompilationUnitInfo> analyses = TestSources.load(loader, List.of(sourceFile));
            assertFalse(analyses.isEmpty());

            ElementHashCalculator calculator = new ElementHashCalculator(analyses.get(0).trees());
            ElementInfo root = calculator.calculate(analyses.get(0).compilationUnit());

            assertNotNull(root);
            assertEquals(ElementInfo.NodeType.COMPILATION_UNIT, root.getNodeType());
            assertNotNull(root.getOriginalTree());
            assertFalse(root.getMessageDigests().isEmpty());

            assertEquals(1, root.getChildren().size());
            ElementInfo typeNode = root.getChildren().get(0);
            assertEquals("User", typeNode.getName());
            assertEquals(ElementInfo.NodeType.TYPE, typeNode.getNodeType());
            assertNotNull(typeNode.getOriginalTree());
            assertFalse(typeNode.getMessageDigests().isEmpty());

            // Default constructor (<init>), field (username), method (getUsername) = 3 members
            assertEquals(3, typeNode.getChildren().size());
            ElementInfo fieldNode = typeNode.getChildren().get(1);
            assertEquals("username", fieldNode.getName());
            assertEquals(ElementInfo.NodeType.FIELD, fieldNode.getNodeType());
            assertNotNull(fieldNode.getOriginalTree());
            assertFalse(fieldNode.getMessageDigests().isEmpty());
            assertEquals(ChangeCategory.values().length, fieldNode.getMessageDigests().size());
            assertNotNull(fieldNode.getSha(ChangeCategory.FULL));
            assertEquals(64, fieldNode.getSha(ChangeCategory.FULL).length());
            assertNotNull(fieldNode.getSha(ChangeCategory.IGNORE_LOCAL_NAMES));
            assertEquals(64, fieldNode.getSha(ChangeCategory.IGNORE_LOCAL_NAMES).length());
        }
    }

    /**
     * Tests that changing an enclosed element (method) cascades its SHA into the enclosing type (class).
     */
    public void testDigestCascading() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path fileV1 = helper.writeFile("Service.java", "package cz.hrebejk.sedi;\n\npublic class Service {\n    public int compute() { return 1; }\n}\n");
            Path fileV2 = helper.writeFile("Service.java", "package cz.hrebejk.sedi;\n\npublic class Service {\n    public int compute() { return 2; }\n}\n");

            AstLoader loader = new AstLoader();
            List<CompilationUnitInfo> analysesV1 = TestSources.load(loader, List.of(fileV1));
            List<CompilationUnitInfo> analysesV2 = TestSources.load(loader, List.of(fileV2));

            ElementHashCalculator calcV1 = new ElementHashCalculator(analysesV1.get(0).trees());
            ElementInfo rootV1 = calcV1.calculate(analysesV1.get(0).compilationUnit());
            String classShaV1 = rootV1.getChildren().get(0).getSha(ChangeCategory.FULL);

            ElementHashCalculator calcV2 = new ElementHashCalculator(analysesV2.get(0).trees());
            ElementInfo rootV2 = calcV2.calculate(analysesV2.get(0).compilationUnit());
            String classShaV2 = rootV2.getChildren().get(0).getSha(ChangeCategory.FULL);

            // Enclosing class SHA must change because the method SHA cascaded into it
            assertNotSame(classShaV1, classShaV2);
        }
    }

    /**
     * Tests that adding or changing an import updates the compilation unit SHA.
     */
    public void testImportShaChange() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path fileV1 = helper.writeFile("App.java", "package cz.hrebejk.sedi;\n\npublic class App {\n}\n");
            Path fileV2 = helper.writeFile("App.java", "package cz.hrebejk.sedi;\n\nimport java.util.List;\n\npublic class App {\n}\n");

            AstLoader loader = new AstLoader();
            List<CompilationUnitInfo> analysesV1 = TestSources.load(loader, List.of(fileV1));
            List<CompilationUnitInfo> analysesV2 = TestSources.load(loader, List.of(fileV2));

            ElementHashCalculator calcV1 = new ElementHashCalculator(analysesV1.get(0).trees());
            ElementInfo rootV1 = calcV1.calculate(analysesV1.get(0).compilationUnit());

            ElementHashCalculator calcV2 = new ElementHashCalculator(analysesV2.get(0).trees());
            ElementInfo rootV2 = calcV2.calculate(analysesV2.get(0).compilationUnit());

            String shaV1 = rootV1.getSha(ChangeCategory.FULL);
            String shaV2 = rootV2.getSha(ChangeCategory.FULL);

            assertNotSame(shaV1, shaV2);
        }
    }
}
