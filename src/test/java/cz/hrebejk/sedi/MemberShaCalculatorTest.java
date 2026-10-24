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
 * Unit test for MemberShaCalculator and ShaContext.
 */
public class MemberShaCalculatorTest extends TestCase {

    /**
     * Tests calculating reduced AST and member SHAs via ShaContext without toString().
     */
    public void testMemberShaCalculation() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path sourceFile = helper.writeFile("Data.java", "package cz.hrebejk.sedi;\n\npublic class Data {\n    private int x = 10;\n    public int getX() { return x; }\n}\n");

            AstLoader loader = new AstLoader();
            List<CompilationAnalysis> analyses = loader.loadCompilationAnalyses(List.of(sourceFile));
            assertFalse(analyses.isEmpty());

            MemberShaCalculator calculator = new MemberShaCalculator(analyses.get(0).trees());
            ReducedAstNode root = calculator.calculate(analyses.get(0).compilationUnit());

            assertNotNull(root);
            assertEquals(1, root.getChildren().size());
            ReducedAstNode typeNode = root.getChildren().get(0);
            assertEquals("Data", typeNode.getName());
            assertFalse(typeNode.getChildren().isEmpty());

            for (ReducedAstNode child : typeNode.getChildren()) {
                assertNotNull(child.getOriginalTree());
                assertFalse(child.getMessageDigests().isEmpty());
                String sha = child.getSha(ShaCategory.FULL);
                assertNotNull(sha);
                assertEquals(64, sha.length()); // SHA-256 hex string length
            }
        }
    }

    /**
     * Tests that any change other than whitespace leads to the change of the CompilationUnit's SHA,
     * and whitespace changes do not affect the SHA.
     */
    public void testCompilationUnitShaSensitivity() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            String baseCode = "package cz.hrebejk.sedi;\n\npublic class Data {\n    private int x = 10;\n    public int getX() { return x; }\n}\n";
            Path file1 = helper.writeFile("Data.java", baseCode);
            AstLoader loader = new AstLoader();
            String shaBase = calculateCompilationUnitSha(loader, file1);

            // 1. Whitespace change should NOT change CompilationUnit SHA
            String whitespaceCode = "package cz.hrebejk.sedi;\n\n   public   class   Data   {\n    private   int   x   =   10;\n    public   int   getX()   {\n        return   x;\n    }\n}\n";
            Path fileWhitespace = helper.writeFile("Data.java", whitespaceCode);
            String shaWhitespace = calculateCompilationUnitSha(loader, fileWhitespace);
            assertEquals("Whitespace changes must not alter CompilationUnit SHA", shaBase, shaWhitespace);

            // 2. Non-whitespace change: altering literal value (10 -> 20) MUST change SHA
            String modifiedLiteral = "package cz.hrebejk.sedi;\n\npublic class Data {\n    private int x = 20;\n    public int getX() { return x; }\n}\n";
            Path fileLiteral = helper.writeFile("Data.java", modifiedLiteral);
            String shaLiteral = calculateCompilationUnitSha(loader, fileLiteral);
            assertFalse("Altering literal value must change CompilationUnit SHA", shaBase.equals(shaLiteral));

            // 3. Non-whitespace change: altering method logic (return x -> return x + 1) MUST change SHA
            String modifiedLogic = "package cz.hrebejk.sedi;\n\npublic class Data {\n    private int x = 10;\n    public int getX() { return x + 1; }\n}\n";
            Path fileLogic = helper.writeFile("Data.java", modifiedLogic);
            String shaLogic = calculateCompilationUnitSha(loader, fileLogic);
            assertFalse("Altering method logic must change CompilationUnit SHA", shaBase.equals(shaLogic));

            // 4. Non-whitespace change: renaming method (getX -> getY) MUST change SHA
            String modifiedName = "package cz.hrebejk.sedi;\n\npublic class Data {\n    private int x = 10;\n    public int getY() { return x; }\n}\n";
            Path fileMethodName = helper.writeFile("Data.java", modifiedName);
            String shaMethodName = calculateCompilationUnitSha(loader, fileMethodName);
            assertFalse("Renaming method must change CompilationUnit SHA", shaBase.equals(shaMethodName));

            // 5. Non-whitespace change: adding an import MUST change SHA
            String modifiedImport = "package cz.hrebejk.sedi;\n\nimport java.util.List;\n\npublic class Data {\n    private int x = 10;\n    public int getX() { return x; }\n}\n";
            Path fileImport = helper.writeFile("Data.java", modifiedImport);
            String shaImport = calculateCompilationUnitSha(loader, fileImport);
            assertFalse("Adding import must change CompilationUnit SHA", shaBase.equals(shaImport));
        }
    }

    /**
     * Tests type changes across fields, method return types, and method parameters (e.g. int -> float, LinkedList -> ArrayList).
     */
    public void testTypeChangesAffectCompilationUnitSha() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            String baseCode = "package cz.hrebejk.sedi;\n\nimport java.util.List;\nimport java.util.LinkedList;\n\npublic class TypeData {\n    private List<String> items = new LinkedList<>();\n    public int count() { return 0; }\n    public void process(int val) {}\n}\n";
            Path file1 = helper.writeFile("TypeData.java", baseCode);
            AstLoader loader = new AstLoader();
            String shaBase = calculateCompilationUnitSha(loader, file1);

            // 1. Field type change: LinkedList -> ArrayList (and update import)
            String fieldTypeChanged = "package cz.hrebejk.sedi;\n\nimport java.util.List;\nimport java.util.ArrayList;\n\npublic class TypeData {\n    private List<String> items = new ArrayList<>();\n    public int count() { return 0; }\n    public void process(int val) {}\n}\n";
            Path fileField = helper.writeFile("TypeData.java", fieldTypeChanged);
            String shaField = calculateCompilationUnitSha(loader, fileField);
            assertFalse("Field type change (LinkedList -> ArrayList) must change CompilationUnit SHA", shaBase.equals(shaField));

            // 2. Return type change: int -> double
            String returnTypeChanged = "package cz.hrebejk.sedi;\n\nimport java.util.List;\nimport java.util.LinkedList;\n\npublic class TypeData {\n    private List<String> items = new LinkedList<>();\n    public double count() { return 0.0; }\n    public void process(int val) {}\n}\n";
            Path fileReturn = helper.writeFile("TypeData.java", returnTypeChanged);
            String shaReturn = calculateCompilationUnitSha(loader, fileReturn);
            assertFalse("Return type change (int -> double) must change CompilationUnit SHA", shaBase.equals(shaReturn));

            // 3. Parameter type change: int -> long
            String paramTypeChanged = "package cz.hrebejk.sedi;\n\nimport java.util.List;\nimport java.util.LinkedList;\n\npublic class TypeData {\n    private List<String> items = new LinkedList<>();\n    public int count() { return 0; }\n    public void process(long val) {}\n}\n";
            Path fileParam = helper.writeFile("TypeData.java", paramTypeChanged);
            String shaParam = calculateCompilationUnitSha(loader, fileParam);
            assertFalse("Parameter type change (int -> long) must change CompilationUnit SHA", shaBase.equals(shaParam));
        }
    }

    /**
     * Tests enum definition changes (e.g. adding enum constants, changing enum constant names).
     */
    public void testEnumChangesAffectCompilationUnitSha() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            String baseCode = "package cz.hrebejk.sedi;\n\npublic enum Direction {\n    NORTH, SOUTH\n}\n";
            Path file1 = helper.writeFile("Direction.java", baseCode);
            AstLoader loader = new AstLoader();
            String shaBase = calculateCompilationUnitSha(loader, file1);

            // 1. Add enum constant: EAST
            String addedConstant = "package cz.hrebejk.sedi;\n\npublic enum Direction {\n    NORTH, SOUTH, EAST\n}\n";
            Path fileAdded = helper.writeFile("Direction.java", addedConstant);
            String shaAdded = calculateCompilationUnitSha(loader, fileAdded);
            assertFalse("Adding enum constant must change CompilationUnit SHA", shaBase.equals(shaAdded));

            // 2. Change enum constant name: NORTH -> UP
            String renamedConstant = "package cz.hrebejk.sedi;\n\npublic enum Direction {\n    UP, SOUTH\n}\n";
            Path fileRenamed = helper.writeFile("Direction.java", renamedConstant);
            String shaRenamed = calculateCompilationUnitSha(loader, fileRenamed);
            assertFalse("Renaming enum constant must change CompilationUnit SHA", shaBase.equals(shaRenamed));
        }
    }

    /**
     * Tests annotation changes on classes, methods, and fields.
     */
    public void testAnnotationChangesAffectCompilationUnitSha() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            String baseCode = "package cz.hrebejk.sedi;\n\npublic class AnnotatedClass {\n    private int id;\n    public void execute() {}\n}\n";
            Path file1 = helper.writeFile("AnnotatedClass.java", baseCode);
            AstLoader loader = new AstLoader();
            String shaBase = calculateCompilationUnitSha(loader, file1);

            // 1. Add class-level annotation (@Deprecated)
            String classAnnotated = "package cz.hrebejk.sedi;\n\n@Deprecated\npublic class AnnotatedClass {\n    private int id;\n    public void execute() {}\n}\n";
            Path fileClassAnn = helper.writeFile("AnnotatedClass.java", classAnnotated);
            String shaClassAnn = calculateCompilationUnitSha(loader, fileClassAnn);
            assertFalse("Adding class-level annotation must change CompilationUnit SHA", shaBase.equals(shaClassAnn));

            // 2. Add method-level annotation (@Override or custom)
            String methodAnnotated = "package cz.hrebejk.sedi;\n\npublic class AnnotatedClass {\n    private int id;\n    @Deprecated\n    public void execute() {}\n}\n";
            Path fileMethodAnn = helper.writeFile("AnnotatedClass.java", methodAnnotated);
            String shaMethodAnn = calculateCompilationUnitSha(loader, fileMethodAnn);
            assertFalse("Adding method-level annotation must change CompilationUnit SHA", shaBase.equals(shaMethodAnn));
        }
    }

    /**
     * Tests that changing the name of a local variable or parameter and its usages
     * does not change the category 1 SHA (SHA without local variable/parameter names and usages),
     * while category 0 SHA reflects changes.
     */
    public void testCategory1IgnoresLocalAndParamNamesAndUsages() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            String code1 = "package cz.hrebejk.sedi;\n\npublic class Calc {\n    public int compute(int a) {\n        int res = a * 2;\n        return res;\n    }\n}\n";
            Path file1 = helper.writeFile("Calc.java", code1);
            AstLoader loader = new AstLoader();

            List<CompilationAnalysis> analyses1 = loader.loadCompilationAnalyses(List.of(file1));
            MemberShaCalculator calc1 = new MemberShaCalculator(analyses1.get(0).trees());
            ReducedAstNode root1 = calc1.calculate(analyses1.get(0).compilationUnit());
            String sha1Cat0 = root1.getSha(ShaCategory.FULL);
            String sha1Cat1 = root1.getSha(ShaCategory.IGNORE_LOCAL_NAMES);

            // Change parameter 'a' to 'x', local variable 'res' to 'total', and their usages
            String code2 = "package cz.hrebejk.sedi;\n\npublic class Calc {\n    public int compute(int x) {\n        int total = x * 2;\n        return total;\n    }\n}\n";
            Path file2 = helper.writeFile("Calc.java", code2);

            List<CompilationAnalysis> analyses2 = loader.loadCompilationAnalyses(List.of(file2));
            MemberShaCalculator calc2 = new MemberShaCalculator(analyses2.get(0).trees());
            ReducedAstNode root2 = calc2.calculate(analyses2.get(0).compilationUnit());
            String sha2Cat0 = root2.getSha(ShaCategory.FULL);
            String sha2Cat1 = root2.getSha(ShaCategory.IGNORE_LOCAL_NAMES);

            // Category 0 SHA should change because parameter and local variable names changed
            assertFalse("Category 0 SHA should change when local/param names change", sha1Cat0.equals(sha2Cat0));
            // Category 1 SHA should REMAIN UNCHANGED because category 1 ignores local/param names and usages
            assertEquals("Category 1 SHA must remain unchanged when local/param names and usages change", sha1Cat1, sha2Cat1);
        }
    }

    private String calculateCompilationUnitSha(AstLoader loader, Path file) throws Exception {
        List<CompilationAnalysis> analyses = loader.loadCompilationAnalyses(List.of(file));
        assertFalse(analyses.isEmpty());
        MemberShaCalculator calculator = new MemberShaCalculator(analyses.get(0).trees());
        ReducedAstNode root = calculator.calculate(analyses.get(0).compilationUnit());
        return root.getSha(ShaCategory.FULL);
    }
}
