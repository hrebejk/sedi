/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import cz.hrebejk.sedi.GitTestHelper;
import cz.hrebejk.sedi.analysis.ChangeLevel;
import cz.hrebejk.sedi.analysis.DiffAstAnalyzer;
import cz.hrebejk.sedi.analysis.FileAnalysis;
import junit.framework.TestCase;

import java.util.List;

/**
 * Unit tests for {@link Reporter}.
 */
public class ReporterTest extends TestCase {

    /**
     * Tests that member and nested type changes are reported and that body changes are not.
     */
    public void testReport() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  int a;\n  void body() { }\n  static class In { void x() {} }\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  long a;\n  void body() { int z = 1; }\n  static class In { void x() {} void y() {} }\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                List<FileAnalysis> analyses = analyzer.analyzeChanges().files();
                assertEquals(1, analyses.size());
                assertEquals(List.of(
                                "  Foo",
                                "      ~~int~~ long a",
                                "    In",
                                "      + void y()"),
                        new Reporter(false).report(analyses.get(0)));
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
                assertTrue(new Reporter(false).report(analyzer.analyzeChanges().files().get(0)).isEmpty());
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
                List<FileAnalysis> analyses = analyzer.analyzeCommit("HEAD", p -> p.endsWith(".java")).files();
                assertEquals(1, analyses.size());
                assertEquals(org.eclipse.jgit.diff.DiffEntry.ChangeType.RENAME, analyses.get(0).diff().getChangeType());
                assertEquals(List.of(
                                "  public class ~~Foo~~ Bar",
                                "      ~~int~~ long a"),
                        new Reporter(false).report(analyses.get(0)));
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
                        new Reporter(false).report(analyzer.analyzeChanges().files().get(0)));
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
                Reporter reporter = new Reporter(false);
                List<String> titles = analyzer.analyzeCommit("HEAD", p -> p.endsWith(".java")).files().stream()
                        .map(reporter::title).sorted().toList();
                assertEquals(List.of("+ New n", "- Gone", "~~Foo~~ Bar ~~a~~ b"), titles);
            }
        }
    }

    private static final String BEFORE = "import java.util.List;\npublic class Foo {\n  public int a;\n  private int b;\n"
            + "  public int m() { return 1; }\n  private int n() { return 1; }\n  int o() { int x = 1; return x; }\n}\n";
    private static final String AFTER = "import java.util.Set;\npublic class Foo {\n  public long a;\n  private long b;\n"
            + "  public int m() { return 2; }\n  private int n() { return 1; }\n  int o() { int y = 1; return y; }\n}\n";

    private List<String> report(ReportOptions options) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", BEFORE);
            helper.commit("init");
            helper.writeFile("Foo.java", AFTER);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                return new Reporter(false, options).report(analyzer.analyzeChanges());
            }
        }
    }

    /**
     * Tests that the default options show the API changes only.
     */
    public void testDefaultShowsOnlyApi() throws Exception {
        assertEquals(List.of("Foo", "  Foo", "      public ~~int~~ long a", "      private ~~int~~ long b"), report(ReportOptions.DEFAULT));
    }

    /**
     * Tests that a deeper level adds the less important changes, tagged, and the imports last.
     */
    public void testLevelsByType() throws Exception {
        assertEquals(List.of("Foo", "  Foo",
                        "      public ~~int~~ long a", "      private ~~int~~ long b",
                        "      public int m() [impl]", "          return ~~1~~ 2;", "      int o() [cosmetic]",
                        "  - import java.util.List [import]", "  + import java.util.Set [import]"),
                report(new ReportOptions(ChangeLevel.IMPORTS, false, ReportOptions.Grouping.BY_TYPE)));
        assertEquals(List.of("Foo", "  Foo",
                        "      public ~~int~~ long a", "      private ~~int~~ long b", "      public int m() [impl]", "          return ~~1~~ 2;"),
                report(new ReportOptions(ChangeLevel.IMPLEMENTATION, false, ReportOptions.Grouping.BY_TYPE)));
    }

    /**
     * Tests that public and private API changes can be told apart.
     */
    public void testSplitVisibility() throws Exception {
        assertEquals(List.of("Foo", "  Foo", "      public ~~int~~ long a [public]", "      private ~~int~~ long b [private]"),
                report(new ReportOptions(ChangeLevel.API, true, ReportOptions.Grouping.BY_TYPE)));
    }

    /**
     * Tests the grouping by category, with the API changes split by visibility.
     */
    public void testGroupByCategory() throws Exception {
        assertEquals(List.of(
                        "=== Public API changes ===", "Foo", "  Foo", "      public ~~int~~ long a",
                        "=== Private API changes ===", "Foo", "  Foo", "      private ~~int~~ long b",
                        "=== Implementation changes ===", "Foo", "  Foo", "      public int m()", "          return ~~1~~ 2;",
                        "=== Cosmetic changes ===", "Foo", "  Foo", "      int o()",
                        "=== Imports ===", "Foo", "  - import java.util.List", "  + import java.util.Set"),
                report(new ReportOptions(ChangeLevel.IMPORTS, true, ReportOptions.Grouping.BY_CATEGORY)));
    }
}
