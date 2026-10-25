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
public class ElementHashCalculatorTest extends TestCase {

    /**
     * Tests calculating reduced AST and member SHAs via ShaContext without toString().
     */
    public void testMemberShaCalculation() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path sourceFile = helper.writeFile("Data.java", "package cz.hrebejk.sedi;\n\npublic class Data {\n    private int x = 10;\n    public int getX() { return x; }\n}\n");

            AstLoader loader = new AstLoader();
            List<CompilationAnalysis> analyses = loader.loadCompilationAnalyses(List.of(sourceFile));
            assertFalse(analyses.isEmpty());

            ElementHashCalculator calculator = new ElementHashCalculator(analyses.get(0).trees());
            ElementInfo root = calculator.calculate(analyses.get(0).compilationUnit());

            assertNotNull(root);
            assertEquals(1, root.getChildren().size());
            ElementInfo typeNode = root.getChildren().get(0);
            assertEquals("Data", typeNode.getName());
            assertFalse(typeNode.getChildren().isEmpty());

            for (ElementInfo child : typeNode.getChildren()) {
                assertNotNull(child.getOriginalTree());
                assertFalse(child.getMessageDigests().isEmpty());
                String sha = child.getSha(ChangeCategory.FULL);
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
            ElementHashCalculator calc1 = new ElementHashCalculator(analyses1.get(0).trees());
            ElementInfo root1 = calc1.calculate(analyses1.get(0).compilationUnit());
            String sha1Cat0 = root1.getSha(ChangeCategory.FULL);
            String sha1Cat1 = root1.getSha(ChangeCategory.IGNORE_LOCAL_NAMES);

            // Change parameter 'a' to 'x', local variable 'res' to 'total', and their usages
            String code2 = "package cz.hrebejk.sedi;\n\npublic class Calc {\n    public int compute(int x) {\n        int total = x * 2;\n        return total;\n    }\n}\n";
            Path file2 = helper.writeFile("Calc.java", code2);

            List<CompilationAnalysis> analyses2 = loader.loadCompilationAnalyses(List.of(file2));
            ElementHashCalculator calc2 = new ElementHashCalculator(analyses2.get(0).trees());
            ElementInfo root2 = calc2.calculate(analyses2.get(0).compilationUnit());
            String sha2Cat0 = root2.getSha(ChangeCategory.FULL);
            String sha2Cat1 = root2.getSha(ChangeCategory.IGNORE_LOCAL_NAMES);

            // Category 0 SHA should change because parameter and local variable names changed
            assertFalse("Category 0 SHA should change when local/param names change", sha1Cat0.equals(sha2Cat0));
            // Category 1 SHA should REMAIN UNCHANGED because category 1 ignores local/param names and usages
            assertEquals("Category 1 SHA must remain unchanged when local/param names and usages change", sha1Cat1, sha2Cat1);
        }
    }

    /**
     * Tests that the SIGNATURE SHA ignores method bodies, field initializers and local names,
     * but changes when a signature changes.
     */
    public void testSignatureCategory() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            AstLoader loader = new AstLoader();
            String tpl = "package cz.hrebejk.sedi;\n\npublic class S {\n    private int f = %s;\n    @Deprecated\n    public %s run(int p) throws Exception {\n        int l = %s;\n        return l;\n    }\n    static { System.out.println(%s); }\n}\n";
            ElementInfo base = calc(loader, helper.writeFile("S.java", String.format(tpl, "1", "int", "1", "1")));
            ElementInfo bodyChange = calc(loader, helper.writeFile("S.java", String.format(tpl, "2", "int", "2", "2")));
            ElementInfo sigChange = calc(loader, helper.writeFile("S.java", String.format(tpl, "1", "long", "1", "1")));

            ChangeCategory sig = ChangeCategory.SIGNATURE;
            assertEquals("Bodies and initializers must not affect SIGNATURE", base.getSha(sig), bodyChange.getSha(sig));
            assertFalse("FULL must see body changes", base.getSha(ChangeCategory.FULL).equals(bodyChange.getSha(ChangeCategory.FULL)));
            assertFalse("Return type change must change SIGNATURE of the CU member",
                    base.getChildren().get(0).getChildren().get(2).getSha(sig)
                            .equals(sigChange.getChildren().get(0).getChildren().get(2).getSha(sig)));
            // Members do not cascade into the type
            assertEquals(base.getChildren().get(0).getSha(sig), sigChange.getChildren().get(0).getSha(sig));
        }
    }

    private ElementInfo calc(AstLoader loader, Path file) throws Exception {
        List<CompilationAnalysis> analyses = loader.loadCompilationAnalyses(List.of(file));
        return new ElementHashCalculator(analyses.get(0).trees()).calculate(analyses.get(0).compilationUnit());
    }

    private String calculateCompilationUnitSha(AstLoader loader, Path file) throws Exception {
        List<CompilationAnalysis> analyses = loader.loadCompilationAnalyses(List.of(file));
        assertFalse(analyses.isEmpty());
        ElementHashCalculator calculator = new ElementHashCalculator(analyses.get(0).trees());
        ElementInfo root = calculator.calculate(analyses.get(0).compilationUnit());
        return root.getSha(ChangeCategory.FULL);
    }

    /**
     * Tests that bodies which differ only in the kinds of their trees, not in any name or literal, hash differently.
     */
    public void testEveryTreeKindIsHashed() throws Exception {
        String[][] pairs = {
                {"return a + b;", "return a - b;"},
                {"return a + b * 1;", "return (a + b) * 1;"},
                {"a += b; return a;", "a -= b; return a;"},
                {"a++; return a;", "a--; return a;"},
                {"return -a;", "return ~a;"},
                {"if (c) return a; return b;", "while (c) return a; return b;"},
                {"for (;;) { break; }", "for (;;) { continue; }"},
                {"return a;", "throw new RuntimeException();"},
                {"int x = 1; return x;", "long x = 1; return (int) x;"},
                {"int x = 1; return x;", "short x = 1; return x;"},
                {"return c ? a : b;", "return c ? b : a;"},
                {"{ { a++; } b++; } return a;", "{ { a++; b++; } } return a;"},
                {"l.forEach(x -> x.length()); return a;", "l.forEach(String::length); return a;"},
                {"Object o = l; return o instanceof String ? a : b;", "Object o = l; return o != null ? a : b;"},
                {"int[] x = new int[1]; return x[0];", "int[] x = {1}; return x[0];"},
                {"outer: for (;;) { break outer; }", "other: for (;;) { break other; }"},
                {"switch (a) { case 1: return a; default: return b; }", "switch (a) { case 1 -> { return a; } default -> { return b; } }"},
                {"try { return a; } catch (RuntimeException e) { return b; }", "try { return a; } finally { b++; }"},
                {"synchronized (this) { return a; }", "{ return a; }"},
                {"assert c; return a;", "return a;"},
                {"int[] x = new int[]{1}; return x[0];", "int[] x = new int[1]; return x[0];"},
                {"int[][] x = new int[1][]; return a;", "int[][] x = new int[][]{}; return a;"},
                {"return a;", "do { a++; } while (c); return a;"},
                {"while (c) { a++; }", "do { a++; } while (c);"},
                {"for (;;a++) { }", "for (;a++;) { }"},
                {"for (String s : l) { }", "for (String s = null; ; ) { }"},
                {"l.forEach(x -> { });", "l.forEach((String x) -> { });"},
                {"l.forEach(x -> x.length());", "l.forEach(x -> { x.length(); });"},
                {"Runnable r = String::new; return a;", "Runnable r = String::valueOf; return a;"},
                {"java.util.function.Supplier<Object> s = Object::new; return a;",
                        "java.util.function.Function<Object, Object> s = Object::toString; return a;"},
                {"this.<String>g(); return a;", "this.g(); return a;"},
                {"Object o = new Object(); return a;", "Object o = new Object() { }; return a;"},
                {"java.util.List<String> x = new java.util.ArrayList<>(); return a;",
                        "java.util.List<String> x = new java.util.ArrayList<String>(); return a;"},
                {"Object o = l; if (o instanceof String s) { return a; } return b;",
                        "Object o = l; if (o instanceof String) { return a; } return b;"},
                {"Object o = l; return switch (o) { case String s -> a; default -> b; };",
                        "Object o = l; return switch (o) { case Integer s -> a; default -> b; };"},
                {"return switch (a) { case 1 -> { yield a; } default -> b; };",
                        "return switch (a) { case 1 -> { yield b; } default -> b; };"},
                {"try (java.io.StringReader r = new java.io.StringReader(\"\")) { } return a;", "try { } finally { } return a;"},
                {"try { } catch (IllegalStateException | IllegalArgumentException e) { } return a;",
                        "try { } catch (IllegalStateException e) { } return a;"},
                {"int x = 1; return x;", "final int x = 1; return x;"},
                {"return;", "return a;"},
                {"return (a);", "return a;"},
                {"return a < b ? 1 : 2;", "return a > b ? 1 : 2;"},
                {"return a == b ? 1 : 2;", "return a != b ? 1 : 2;"},
                {"return a << b;", "return a >> b;"},
                {"return a >> b;", "return a >>> b;"},
                {"return a & b;", "return a | b;"},
                {"return c && c;", "return c || c;"},
                {"Object o = (Object) l; return a;", "Object o = l; return a;"},
                {"java.util.List<? extends Number> x = null; return a;", "java.util.List<? super Number> x = null; return a;"},
                {"java.util.List<?> x = null; return a;", "java.util.List<Object> x = null; return a;"},
                {"@SuppressWarnings(\"a\") int x = 1; return x;", "int x = 1; return x;"},
                {"@SuppressWarnings(\"a\") int x = 1; return x;", "@SuppressWarnings(\"b\") int x = 1; return x;"},
                {"class L { } return a;", "interface L { } return a;"},
                {"l.size(); return a;", "l.isEmpty(); return a;"},
                {"return l.size();", "return l.size() + 0;"},
                {"return a;", "; return a;"},
        };
        try (GitTestHelper helper = new GitTestHelper()) {
            AstLoader loader = new AstLoader();
            StringBuilder failures = new StringBuilder();
            for (String[] pair : pairs) {
                String first = methodSha(loader, helper, pair[0]);
                String second = methodSha(loader, helper, pair[1]);
                if (first.equals(second)) {
                    failures.append("\n  ").append(pair[0]).append("  ==  ").append(pair[1]);
                }
            }
            assertEquals("Equal hashes of different code:", "", failures.toString());
        }
    }

    /**
     * Tests that members of a type which differ in trees of their own, or in initializer blocks, hash differently.
     */
    public void testMemberLevelTreesAreHashed() throws Exception {
        String[][] pairs = {
                {"{ a++; }", "static { a++; }"},
                {"int a; { a = 1; }", "int a; { a = 2; }"},
                {"int f = 1;", "int f;"},
                {"int f = 1 + 2;", "int f = 1 - 2;"},
                {"Runnable r = () -> { };", "Runnable r = () -> { ; };"},
                {"int[] f = new int[]{1};", "int[] f = new int[1];"},
                {"int m() { return 1; }", "int m() { return -1; }"},
                {"abstract int m();", "int m() { return 0; }"},
                {"<T> void m(T t) { }", "<U> void m(U t) { }"},
        };
        try (GitTestHelper helper = new GitTestHelper()) {
            AstLoader loader = new AstLoader();
            StringBuilder failures = new StringBuilder();
            for (String[] pair : pairs) {
                if (typeSha(loader, helper, pair[0]).equals(typeSha(loader, helper, pair[1]))) {
                    failures.append("\n  ").append(pair[0]).append("  ==  ").append(pair[1]);
                }
            }
            assertEquals("Equal hashes of different code:", "", failures.toString());
        }
    }

    /**
     * Tests that module declarations and their directives are hashed.
     */
    public void testModuleDeclarationIsHashed() throws Exception {
        String[][] pairs = {
                {"module m { requires java.sql; }", "module m { requires static java.sql; }"},
                {"module m { requires static java.sql; }", "module m { requires transitive java.sql; }"},
                {"module m { requires java.sql; }", "module m { requires java.xml; }"},
                {"module m { requires java.sql; }", "open module m { requires java.sql; }"},
                {"module m { exports p; }", "module m { opens p; }"},
                {"module m { exports p; }", "module m { exports q; }"},
                {"module m { exports p to a; }", "module m { exports p to a, b; }"},
                {"module m { uses p.S; }", "module m { provides p.S with p.I; }"},
        };
        try (GitTestHelper helper = new GitTestHelper()) {
            AstLoader loader = new AstLoader();
            StringBuilder failures = new StringBuilder();
            for (String[] pair : pairs) {
                String first = calc(loader, helper.writeFile("module-info.java", pair[0])).getSha(ChangeCategory.FULL);
                String second = calc(loader, helper.writeFile("module-info.java", pair[1])).getSha(ChangeCategory.FULL);
                if (first.equals(second)) {
                    failures.append("\n  ").append(pair[0]).append("  ==  ").append(pair[1]);
                }
            }
            assertEquals("Equal hashes of different code:", "", failures.toString());
        }
    }

    /**
     * Tests that the members of a type, initializer blocks included, are not part of its signature hash.
     */
    public void testMembersAreNotPartOfTypeSignature() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            AstLoader loader = new AstLoader();
            String base = signatureOfType(loader, helper, "int a;");
            assertEquals(base, signatureOfType(loader, helper, "int a; void m() { a++; }"));
            assertEquals(base, signatureOfType(loader, helper, "int a; { a = 1; } static { }"));
            assertEquals(base, signatureOfType(loader, helper, ""));
        }
    }

    private String signatureOfType(AstLoader loader, GitTestHelper helper, String members) throws Exception {
        Path file = helper.writeFile("T.java", "abstract class T {\n  " + members + "\n}\n");
        return calc(loader, file).getChildren().get(0).getSha(ChangeCategory.SIGNATURE);
    }

    private String typeSha(AstLoader loader, GitTestHelper helper, String members) throws Exception {
        Path file = helper.writeFile("T.java", "abstract class T {\n  " + members + "\n}\n");
        return calc(loader, file).getChildren().get(0).getSha(ChangeCategory.FULL);
    }

    private String methodSha(AstLoader loader, GitTestHelper helper, String body) throws Exception {
        Path file = helper.writeFile("T.java", "class T {\n  void g() { }\n  int m(int a, int b, boolean c, java.util.List<String> l) { "
                + body + " }\n}\n");
        ElementInfo root = calc(loader, file);
        return root.getChildren().get(0).getChildren().stream()
                .filter(c -> c.getName().equals("m")).findFirst().orElseThrow().getSha(ChangeCategory.FULL);
    }
}
