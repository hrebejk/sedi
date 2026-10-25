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

import java.util.List;

/**
 * Unit tests for {@link ApiDiffReporter}.
 */
public class ApiDiffReporterTest extends TestCase {

    /**
     * Tests that member and nested type changes are reported and that body changes are not.
     */
    public void testReport() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  int a;\n  void body() { }\n  static class In { void x() {} }\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  long a;\n  void body() { int z = 1; }\n  static class In { void x() {} void y() {} }\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                List<FileDiffAnalysis> analyses = analyzer.analyzeChanges();
                assertEquals(1, analyses.size());
                assertEquals(List.of(
                                "  Foo",
                                "      ~~int~~ long a",
                                "    In",
                                "      + void y()"),
                        new ApiDiffReporter(false).report(analyses.get(0)));
            }
        }
    }

    /**
     * Tests that a body-only change reports nothing.
     */
    public void testBodyOnlyChangeIsEmpty() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  void body() { }\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  void body() { int z = 1; }\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertTrue(new ApiDiffReporter(false).report(analyzer.analyzeChanges().get(0)).isEmpty());
            }
        }
    }

    /**
     * Tests that a file renamed in git is reported as a renamed top level type, with its member changes.
     */
    public void testRenamedFile() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            java.nio.file.Path old = helper.writeFile("Foo.java",
                    "public class Foo {\n  int a;\n  void m() { }\n  void n() { }\n  void o() { }\n}\n");
            helper.commit("init");
            java.nio.file.Files.delete(old);
            helper.writeFile("Bar.java",
                    "public class Bar {\n  long a;\n  void m() { }\n  void n() { }\n  void o() { }\n}\n");
            helper.commit("rename");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                List<FileDiffAnalysis> analyses = analyzer.analyzeCommit("HEAD", p -> p.endsWith(".java"));
                assertEquals(1, analyses.size());
                assertEquals(org.eclipse.jgit.diff.DiffEntry.ChangeType.RENAME, analyses.get(0).diff().getChangeType());
                assertEquals(List.of(
                                "  public class ~~Foo~~ Bar",
                                "      ~~int~~ long a"),
                        new ApiDiffReporter(false).report(analyses.get(0)));
            }
        }
    }

    /**
     * Tests that added and removed top level types are reported.
     */
    public void testAddedAndRemovedTypes() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n}\nclass Gone { }\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n}\nclass New { }\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertEquals(List.of("  - class Gone", "  + class New"),
                        new ApiDiffReporter(false).report(analyzer.analyzeChanges().get(0)));
            }
        }
    }

    /**
     * Tests the file headings for renamed, added and deleted files.
     */
    public void testTitles() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            java.nio.file.Path old = helper.writeFile("a/Foo.java",
                    "package a;\npublic class Foo {\n  int a;\n  void m() { }\n  void n() { }\n  void o() { }\n  void p() { }\n  void q() { }\n}\n");
            helper.writeFile("Gone.java", "class Gone { }\n");
            helper.commit("init");
            java.nio.file.Files.delete(old);
            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("Gone.java"));
            helper.writeFile("b/Bar.java",
                    "package b;\npublic class Bar {\n  int a;\n  void m() { }\n  void n() { }\n  void o() { }\n  void p() { }\n  void q() { }\n}\n");
            helper.writeFile("New.java", "package n;\nclass New { }\n");
            helper.commit("change");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ApiDiffReporter reporter = new ApiDiffReporter(false);
                List<String> titles = analyzer.analyzeCommit("HEAD", p -> p.endsWith(".java")).stream()
                        .map(reporter::title).sorted().toList();
                assertEquals(List.of("+ New n", "- Gone", "~~Foo~~ Bar ~~a~~ b"), titles);
            }
        }
    }

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
                List<FileDiffAnalysis> analyses = analyzer.analyzeChanges();
                ApiDiffReporter reporter = new ApiDiffReporter(false);
                Renames renames = reporter.detectRenames(analyses);
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
                FileDiffAnalysis a = analyzer.analyzeChanges().get(0);
                Renames renames = new ApiDiffReporter(false).detectRenames(List.of(a));
                ElementInfo oldUse = member(new ElementHashCalculator(a.oldTrees()).calculate(a.oldCompilationUnit()), "use");
                ElementInfo plain = member(new ElementHashCalculator(a.newTrees()).calculate(a.newCompilationUnit()), "use");
                ElementInfo mapped = member(new ElementHashCalculator(a.newTrees(), renames).calculate(a.newCompilationUnit()), "use");

                assertFalse(oldUse.getSha(ChangeCategory.WITHOUT_RENAMES).equals(plain.getSha(ChangeCategory.WITHOUT_RENAMES)));
                assertEquals(oldUse.getSha(ChangeCategory.WITHOUT_RENAMES), mapped.getSha(ChangeCategory.WITHOUT_RENAMES));
            }
        }
    }

    private static ElementInfo member(ElementInfo root, String name) {
        ElementInfo type = root.getChildren().get(0);
        return type.getChildren().stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }
}
