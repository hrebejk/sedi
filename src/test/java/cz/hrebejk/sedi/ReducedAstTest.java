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
 * Unit test for ReducedAstNode and MemberShaCalculator.calculate, including digest cascading and imports.
 */
public class ReducedAstTest extends TestCase {

    /**
     * Tests building reduced AST dynamically with original tree pointers and message digests.
     */
    public void testBuildReducedAst() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path sourceFile = helper.writeFile("User.java", "package cz.hrebejk.sedi;\n\npublic class User {\n    private String username;\n    public String getUsername() { return username; }\n}\n");

            AstLoader loader = new AstLoader();
            List<CompilationAnalysis> analyses = loader.loadCompilationAnalyses(List.of(sourceFile));
            assertFalse(analyses.isEmpty());

            MemberShaCalculator calculator = new MemberShaCalculator(analyses.get(0).trees());
            ReducedAstNode root = calculator.calculate(analyses.get(0).compilationUnit());

            assertNotNull(root);
            assertEquals(ReducedAstNode.NodeType.COMPILATION_UNIT, root.getNodeType());
            assertNotNull(root.getOriginalTree());
            assertFalse(root.getMessageDigests().isEmpty());

            assertEquals(1, root.getChildren().size());
            ReducedAstNode typeNode = root.getChildren().get(0);
            assertEquals("User", typeNode.getName());
            assertEquals(ReducedAstNode.NodeType.TYPE, typeNode.getNodeType());
            assertNotNull(typeNode.getOriginalTree());
            assertFalse(typeNode.getMessageDigests().isEmpty());

            // Default constructor (<init>), field (username), method (getUsername) = 3 members
            assertEquals(3, typeNode.getChildren().size());
            ReducedAstNode fieldNode = typeNode.getChildren().get(1);
            assertEquals("username", fieldNode.getName());
            assertEquals(ReducedAstNode.NodeType.FIELD, fieldNode.getNodeType());
            assertNotNull(fieldNode.getOriginalTree());
            assertFalse(fieldNode.getMessageDigests().isEmpty());
            assertEquals(2, fieldNode.getMessageDigests().size());
            assertNotNull(fieldNode.getSha(0));
            assertEquals(64, fieldNode.getSha(0).length());
            assertNotNull(fieldNode.getSha(1));
            assertEquals(64, fieldNode.getSha(1).length());
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
            List<CompilationAnalysis> analysesV1 = loader.loadCompilationAnalyses(List.of(fileV1));
            List<CompilationAnalysis> analysesV2 = loader.loadCompilationAnalyses(List.of(fileV2));

            MemberShaCalculator calcV1 = new MemberShaCalculator(analysesV1.get(0).trees());
            ReducedAstNode rootV1 = calcV1.calculate(analysesV1.get(0).compilationUnit());
            String classShaV1 = rootV1.getChildren().get(0).getSha(0);

            MemberShaCalculator calcV2 = new MemberShaCalculator(analysesV2.get(0).trees());
            ReducedAstNode rootV2 = calcV2.calculate(analysesV2.get(0).compilationUnit());
            String classShaV2 = rootV2.getChildren().get(0).getSha(0);

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
            List<CompilationAnalysis> analysesV1 = loader.loadCompilationAnalyses(List.of(fileV1));
            List<CompilationAnalysis> analysesV2 = loader.loadCompilationAnalyses(List.of(fileV2));

            MemberShaCalculator calcV1 = new MemberShaCalculator(analysesV1.get(0).trees());
            ReducedAstNode rootV1 = calcV1.calculate(analysesV1.get(0).compilationUnit());

            MemberShaCalculator calcV2 = new MemberShaCalculator(analysesV2.get(0).trees());
            ReducedAstNode rootV2 = calcV2.calculate(analysesV2.get(0).compilationUnit());

            String shaV1 = rootV1.getSha(0);
            String shaV2 = rootV2.getSha(0);

            assertNotSame(shaV1, shaV2);
        }
    }
}
