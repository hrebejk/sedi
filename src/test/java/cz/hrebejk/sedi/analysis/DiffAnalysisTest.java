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
import junit.framework.TestCase;

/**
 * Unit tests for the renames and hashes of a {@link DiffAnalysis}.
 */
public class DiffAnalysisTest extends TestCase {

    private static final String BEFORE = "public class Foo {\n  int count;\n  enum Color { RED, GREEN }\n"
            + "  static class Helper { void a() { } void b() { } }\n  String helper(String s) { return s; }\n"
            + "  int use(int p) { int local = p + count; helper(\"count\"); return local; }\n}\n";
    private static final String AFTER = "public class Foo {\n  int total;\n  enum Color { RED, GREEN }\n"
            + "  static class Assistant { void a() { } void b() { } }\n  String assist(String s) { return s; }\n"
            + "  int use(int q) { int sum = q + total; assist(\"count\"); return sum; }\n}\n";

    /**
     * Tests that renamed fields, methods and nested types are detected and reported.
     */
    public void testDetectsRenames() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", BEFORE);
            helper.commit("init");
            helper.writeFile("Foo.java", AFTER);

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                Renames renames = analyzer.analyzeChanges().renames();
                assertEquals("count", renames.oldName("Foo#total", "total"));
                assertEquals("Helper", renames.oldName("Foo.Assistant", "Assistant"));
                assertEquals("helper", renames.oldName("Foo#assist(java.lang.String)", "assist"));
                assertEquals("use", renames.oldName("Foo#use(int)", "use"));
            }
        }
    }

    /**
     * Tests that the WITHOUT_RENAMES hash of a method ignores renames of the elements it uses.
     */
    public void testWithoutRenamesHash() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", BEFORE);
            helper.commit("init");
            helper.writeFile("Foo.java", AFTER);

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                DiffAnalysis analysis = analyzer.analyzeChanges();
                FileAnalysis a = analysis.files().get(0);
                Renames renames = analysis.renames();
                ElementInfo oldUse = member(new ElementHashCalculator(a.oldUnit().trees()).calculate(a.oldUnit().compilationUnit()), "use");
                ElementInfo plain = member(new ElementHashCalculator(a.newUnit().trees()).calculate(a.newUnit().compilationUnit()), "use");
                ElementInfo mapped = member(new ElementHashCalculator(a.newUnit().trees(), renames).calculate(a.newUnit().compilationUnit()), "use");

                assertFalse(oldUse.getSha(ChangeCategory.WITHOUT_RENAMES).equals(plain.getSha(ChangeCategory.WITHOUT_RENAMES)));
                assertEquals(oldUse.getSha(ChangeCategory.WITHOUT_RENAMES), mapped.getSha(ChangeCategory.WITHOUT_RENAMES));
            }
        }
    }

    /**
     * Tests that a file analyzed alone, as when the watched file changes, knows the types renamed in the other
     * changed files: its references to them do not change its API.
     */
    public void testFilteredFileKnowsRenamesOfOtherFiles() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("ClassV1.java", "public class ClassV1 {\n    int value() { return 1; }\n}\n");
            helper.writeFile("Usages.java", "public class Usages {\n    ClassV1 obj = new ClassV1();\n"
                    + "    ClassV1 m(ClassV1 p, boolean b) { return b ? p : obj; }\n}\n");
            helper.commit("init");
            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("ClassV1.java"));
            helper.writeFile("ClassV2.java", "public class ClassV2 {\n    int value() { return 1; }\n}\n");
            helper.writeFile("Usages.java", "public class Usages {\n    ClassV2 obj = new ClassV2();\n"
                    + "    ClassV2 m(ClassV2 p, boolean b) { return !b ? p : obj; }\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                DiffAnalysis analysis = analyzer.analyzeChanges("Usages.java"::equals);
                assertEquals(1, analysis.files().size());
                ApiChanges usages = analysis.files().get(0).rootChanges().nested().get(0);
                assertEquals(java.util.List.of("COSMETIC:obj", "IMPLEMENTATION:m"), usages.changes().stream()
                        .map(c -> c.level() + ":" + c.pair().newElement().getName()).sorted().toList());
            }
        }
    }

    private static ElementInfo member(ElementInfo root, String name) {
        ElementInfo type = root.getChildren().get(0);
        return type.getChildren().stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }
}
