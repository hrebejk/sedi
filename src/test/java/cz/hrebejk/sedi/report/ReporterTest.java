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
                                "    public class Foo",
                                "    ~ ~~int~~ __long__ a",
                                "      static class In",
                                "      + void y()"),
                        new Reporter(Theme.PLAIN).report(analyses.get(0)));
            }
        }
    }

    /**
     * The lines of the source are shown in a column on the left, not indented with the code, followed by the
     * markers of the lines: of the new version, of the old one for removed members, the annotations on lines of
     * their own.
     */
    public void testLineNumbers() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  int a;\n  void gone() { }\n"
                    + "  static class In { void x() {} }\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n\n  long a;\n  static class In { void x() {}\n"
                    + "  @Deprecated /* new */\n  void y() {} }\n"
                    + "  void n1() { } void n2() { } void n3() { } void n4() { } void n5() { } void n6() { }\n"
                    + "  void n7() { } void n8() { } void n9() { }\n\n\n void ten() { }\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ReportOptions options = new ReportOptions(ChangeLevel.API, ReportOptions.Grouping.BY_CATEGORY,
                        ReportOptions.DEFAULT_BODY_LINES, ReportOptions.NO_JAVADOC, true);
                List<String> lines = new Reporter(Theme.PLAIN, options).report(analyzer.analyzeChanges());
                assertEquals(List.of(
                                "# API changes",
                                "",
                                "   ~ Foo.java",
                                " 1     public class Foo",
                                " 3 ~     ~~int~~ __long__ a",
                                " 3 -     ~~void gone()~~"),
                        lines.subList(0, 6));
                assertTrue(lines.toString(), lines.contains(" 5 +       @Deprecated"));
                assertTrue(lines.toString(), lines.contains(" 6 +       void y()"));
                assertTrue(lines.toString(), lines.contains("11 +     void ten()"));
            }
        }
    }

    /** Imports are numbered by their lines: of the old version if removed, else of the new one. */
    public void testImportLineNumbers() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "import java.util.List;\nimport static java.lang.Math.max;\n"
                    + "public class Foo {\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "\nimport static java.lang.Math.max;\n\nimport java.util.Set;\n"
                    + "public class Foo {\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ReportOptions options = new ReportOptions(ChangeLevel.IMPORTS, ReportOptions.Grouping.BY_TYPE,
                        ReportOptions.DEFAULT_BODY_LINES, ReportOptions.NO_JAVADOC, true);
                List<String> lines = new Reporter(Theme.PLAIN, options).reportOnly(analyzer.analyzeChanges(),
                        ChangeLevel.IMPORTS);
                assertTrue(lines.toString(), lines.contains("1 -   ~~import java.util.List~~"));
                assertTrue(lines.toString(), lines.contains("4 +   import java.util.Set"));
            }
        }
    }

    /** Javadoc lines are numbered by their lines: of the old version if removed, else of the new one. */
    public void testJavadocLineNumbers() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  /**\n   * Hello world.\n   * Second line.\n   */\n"
                    + "  public void m() { }\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n\n  /**\n   * Hello big world.\n   *\n   * Third.\n   */\n"
                    + "  public void m() { }\n  /** Added. */\n  public void n() { }\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ReportOptions options = new ReportOptions(ChangeLevel.API, ReportOptions.Grouping.BY_TYPE,
                        ReportOptions.DEFAULT_BODY_LINES, ReportOptions.WHOLE_JAVADOC, true);
                List<String> lines = new Reporter(Theme.PLAIN, options).report(analyzer.analyzeChanges());
                assertEquals(lines.toString(), List.of(
                                " 1     public class Foo",
                                " 9 +     * Added.",
                                "10 +     public void n()",
                                " 4 ~     * Hello __big__ world.",
                                " 4 -     * ~~Second line.~~",
                                " 5 +     *",
                                " 6 +     * Third.",
                                " 8 ~     public void m()"),
                        lines.subList(1, lines.size()));
            }
        }
    }

    /** The numbers have the colors of the markers of their lines. */
    public void testLineNumbersHaveColorsOfMarkers() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  int a;\n  String gone;\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  long a;\n  void added() { }\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                Theme theme = ThemeTest.only("added_mark", "#000001", "removed_mark", "#000002",
                        "changed_mark", "#000003", "line_number", "#000004");
                ReportOptions options = new ReportOptions(ChangeLevel.API, ReportOptions.Grouping.BY_CATEGORY,
                        ReportOptions.DEFAULT_BODY_LINES, ReportOptions.NO_JAVADOC, true);
                List<String> lines = new Reporter(theme, options).report(analyzer.analyzeChanges());
                String e = "\u001b[38;2;0;0;";
                assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.startsWith(e + "4m1\u001b[39m ")));
                assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.startsWith(e + "3m2\u001b[39m "
                        + e + "3m~\u001b[39m ")));
                assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.startsWith(e + "2m3\u001b[39m "
                        + e + "2m-\u001b[39m ")));
                assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.startsWith(e + "1m3\u001b[39m "
                        + e + "1m+\u001b[39m ")));
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
                assertTrue(new Reporter(Theme.PLAIN).report(analyzer.analyzeChanges().files().get(0)).isEmpty());
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
                                "  ~ public class ~~Foo~~ __Bar__",
                                "    ~ ~~int~~ __long__ a"),
                        new Reporter(Theme.PLAIN).report(analyses.get(0)));
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
                assertEquals(List.of("  - ~~class Gone~~", "  + class New"),
                        new Reporter(Theme.PLAIN).report(analyzer.analyzeChanges().files().get(0)));
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
                Reporter reporter = new Reporter(Theme.PLAIN);
                List<String> titles = analyzer.analyzeCommit("HEAD", p -> p.endsWith(".java")).files().stream()
                        .map(reporter::title).sorted().toList();
                assertEquals(List.of("+ New.java n", "- ~~Gone.java~~", "\u2192 ~~Foo.java~~ __Bar.java__ ~~a~~ __b__"), titles);
            }
        }
    }

    /**
     * Tests that the files grouped by type are separated by an empty line, as in the grouping by category, and that
     * {@link Reporter#withGrouping} switches the grouping.
     */
    public void testFilesGroupedByTypeAreSeparated() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("A.java", "public class A { public int a; }\n");
            helper.writeFile("B.java", "public class B { public int b; }\n");
            helper.commit("init");
            helper.writeFile("A.java", "public class A { public long a; }\n");
            helper.writeFile("B.java", "public class B { public long b; }\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                Reporter byType = new Reporter(Theme.PLAIN, ReportOptions.DEFAULT);
                assertEquals(List.of("~ A.java", "    public class A", "    ~ public ~~int~~ __long__ a", "",
                                "~ B.java", "    public class B", "    ~ public ~~int~~ __long__ b"),
                        byType.report(analyzer.analyzeChanges()));
                Reporter byCategory = byType.withGrouping(ReportOptions.Grouping.BY_CATEGORY);
                assertEquals(ReportOptions.Grouping.BY_CATEGORY, byCategory.grouping());
                assertEquals("# API changes", byCategory.report(analyzer.analyzeChanges()).get(0));
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
                return new Reporter(Theme.PLAIN, options).report(analyzer.analyzeChanges());
            }
        }
    }

    /**
     * Tests that the default options show the API changes only.
     */
    public void testDefaultShowsOnlyApi() throws Exception {
        assertEquals(List.of("~ Foo.java", "    public class Foo", "    ~ public ~~int~~ __long__ a", "    ~ private ~~int~~ __long__ b"), report(ReportOptions.DEFAULT));
    }

    /**
     * Tests that a deeper level adds the less important changes, tagged, and the imports last.
     */
    public void testLevelsByType() throws Exception {
        assertEquals(List.of("~ Foo.java", "    public class Foo",
                        "    ~ public ~~int~~ __long__ a", "    ~ private ~~int~~ __long__ b",
                        "    ~ public int m() [impl]", "          return ~~1~~ __2__;", "    ~ int o() [cosmetic]",
                        "  - ~~import java.util.List~~ [import]", "  + import java.util.Set [import]"),
                report(new ReportOptions(ChangeLevel.IMPORTS, ReportOptions.Grouping.BY_TYPE)));
        assertEquals(List.of("~ Foo.java", "    public class Foo",
                        "    ~ public ~~int~~ __long__ a", "    ~ private ~~int~~ __long__ b", "    ~ public int m() [impl]", "          return ~~1~~ __2__;"),
                report(new ReportOptions(ChangeLevel.IMPLEMENTATION, ReportOptions.Grouping.BY_TYPE)));
    }

    /**
     * Tests the grouping by category, with the API changes split by visibility.
     */
    public void testGroupByCategory() throws Exception {
        assertEquals(List.of(
                        "# API changes", "",
                        "~ Foo.java", "    public class Foo", "    ~ public ~~int~~ __long__ a", "    ~ private ~~int~~ __long__ b",
                        "",
                        "# Implementation changes", "",
                        "~ Foo.java", "    public class Foo", "    ~ public int m()", "          return ~~1~~ __2__;",
                        "",
                        "# Cosmetic changes", "",
                        "~ Foo.java", "    public class Foo", "    ~ int o()",
                        "",
                        "# Imports", "",
                        "~ Foo.java", "  - ~~import java.util.List~~", "  + import java.util.Set"),
                report(new ReportOptions(ChangeLevel.IMPORTS, ReportOptions.Grouping.BY_CATEGORY)));
    }

    /**
     * Tests that the API changes list the public API of the new types, the names of the deleted ones and then
     * the changes of the other types.
     */
    public void testApiStructure() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("a/Foo.java", "package a;\npublic class Foo {\n  int a;\n  class Gone { }\n}\n");
            helper.writeFile("a/Old.java", "package a;\nclass Old { }\n");
            helper.commit("init");
            helper.writeFile("a/Foo.java", "package a;\npublic class Foo {\n  long a;\n"
                    + "  public static class Fresh { public int x; private int y; protected void m() { } }\n}\n");
            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("a/Old.java"));
            helper.writeFile("b/New.java", "package b;\npublic class New {\n  public New(int i) { }\n"
                    + "  private int hidden;\n  public void m() { }\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertEquals(List.of(
                                "# API changes", "",
                                "+ New.java b",
                                "  public class New",
                                "    public New( int i )",
                                "    public void m()",
                                "",
                                "- ~~Old.java~~ ~~a~~",
                                "- Gone a.Foo",
                                "",
                                "~ Foo.java a",
                                "    public class Foo",
                                "    ~ ~~int~~ __long__ a",
                                "    + public static class Fresh",
                                "        public Fresh()",
                                "        public int x",
                                "        protected void m()"),
                        new Reporter(Theme.PLAIN, new ReportOptions(ChangeLevel.API, ReportOptions.Grouping.BY_CATEGORY))
                                .report(analyzer.analyzeChanges()));
            }
        }
    }

    /**
     * Tests that a changed type is shown with the changes of its whole signature, also if none of its members
     * changed, and that a nested type is shown the same way.
     */
    public void testChangedTypeSignature() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("p/Foo.java", "package p;\npublic class Foo<T> extends Object implements Runnable {\n"
                    + "  public void run() { }\n  static class In { }\n}\n");
            helper.commit("init");
            helper.writeFile("p/Foo.java", "package p;\npublic abstract class Foo<T, U> extends Number implements Runnable, Cloneable {\n"
                    + "  public void run() { }\n  static final class In implements Runnable { public void run() { } }\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertEquals(List.of(
                                "~ Foo.java p",
                                "  ~ public __abstract__ class Foo<T, __U__> extends ~~Object~~ __Number__ implements Runnable, __Cloneable__",
                                "    ~ static __final__ class In implements __Runnable__",
                                "      + public void run()"),
                        new Reporter(Theme.PLAIN).report(analyzer.analyzeChanges()));
            }
        }
    }

    /**
     * Tests that annotations are on lines of their own and shown only if added, removed or changed, or if the
     * element is new.
     */
    public void testAnnotations() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  @Deprecated int a;\n  @Deprecated int b;\n"
                    + "  @SuppressWarnings(\"x\") int c;\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "@SuppressWarnings(\"all\")\npublic class Foo {\n  @Deprecated long a;\n"
                    + "  int b;\n  @SuppressWarnings(\"y\") int c;\n  @Deprecated int d;\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertEquals(List.of(
                                "  ~ __@SuppressWarnings(value = \"all\")__",
                                "    public class Foo",
                                "    ~ ~~int~~ __long__ a",
                                "    ~ ~~@Deprecated~~",
                                "    ~ int b",
                                "    ~ ~~@SuppressWarnings(value = \"x\")~~ __@SuppressWarnings(value = \"y\")__",
                                "    ~ int c",
                                "    + @Deprecated",
                                "    + int d"),
                        new Reporter(Theme.PLAIN).report(analyzer.analyzeChanges().files().get(0)));
            }
        }
    }

    /** Tests that the changed files of the API changes are separated by an empty line. */
    public void testChangedFilesSeparated() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("A.java", "class A {\n  int a;\n}\n");
            helper.writeFile("B.java", "class B {\n  int b;\n}\n");
            helper.commit("init");
            helper.writeFile("A.java", "class A {\n  long a;\n}\n");
            helper.writeFile("B.java", "class B {\n  long b;\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertEquals(List.of("# API changes", "",
                                "~ A.java", "    class A", "    ~ ~~int~~ __long__ a",
                                "",
                                "~ B.java", "    class B", "    ~ ~~int~~ __long__ b"),
                        new Reporter(Theme.PLAIN, new ReportOptions(ChangeLevel.API, ReportOptions.Grouping.BY_CATEGORY))
                                .report(analyzer.analyzeChanges()));
            }
        }
    }

    /**
     * Tests that a file which git sees as deleted and added, but which holds a renamed type with the same members,
     * is listed in a section of renames after the added and the removed files.
     */
    public void testRenamedFileSection() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("a/Foo.java", "package a;\npublic class Foo {\n  public int x = 1111111111;\n"
                    + "  public int y = 2222222222;\n  public void run() { System.out.println(\"one\"); }\n}\n");
            helper.writeFile("Gone.java", "class Gone { }\n");
            helper.commit("init");
            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("a/Foo.java"));
            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("Gone.java"));
            helper.writeFile("b/Bar.java", "package b;\npublic class Bar {\n  public int x = 7;\n"
                    + "  public int y = 8;\n  public void run() { }\n}\n");
            helper.writeFile("Fresh.java", "interface Fresh { void m(); void n(); }\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertEquals(List.of("# API changes", "",
                                "+ Fresh.java",
                                "  interface Fresh",
                                "    void m()",
                                "    void n()",
                                "",
                                "- ~~Gone.java~~",
                                "",
                                "\u2192 ~~Foo.java~~ __Bar.java__ ~~a~~ __b__",
                                "  ~ public class ~~Foo~~ __Bar__"),
                        new Reporter(Theme.PLAIN, new ReportOptions(ChangeLevel.API, ReportOptions.Grouping.BY_CATEGORY))
                                .report(analyzer.analyzeChanges()));
            }
        }
    }

    /** Tests that a file moved to another package, which changes nothing else, is shown by its title only. */
    public void testMovedFileHasNoApiChange() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            String body = "public final class Foo {\n  private record Parts(int a) { static Parts of(int a) { return new Parts(a); } }\n"
                    + "  private int render(Parts p) { return p.a(); }\n  private Parts make() { return Parts.of(1); }\n"
                    + "  void other() { }\n  void more() { }\n}\n";
            helper.writeFile("a/Foo.java", "package a;\n" + body);
            helper.commit("init");
            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("a/Foo.java"));
            helper.writeFile("b/Foo.java", "package b;\n" + body);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertEquals(List.of("\u2192 Foo.java ~~a~~ __b__"),
                        new Reporter(Theme.PLAIN).report(analyzer.analyzeChanges()));
            }
        }
    }

    private static final String ONLY_OLD = "import java.util.List;\npublic class Foo {\n  int a;\n  void m() { int x = 1; }\n}\n";
    private static final String ONLY_NEW = "import java.util.Set;\npublic class Foo {\n  long a;\n  void m() { int y = 1; }\n}\n";

    /**
     * Tests that reportOnly shows just the section of one level, whatever the options are.
     */
    public void testReportOnly() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", ONLY_OLD);
            helper.commit("init");
            helper.writeFile("Foo.java", ONLY_NEW);

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                var analysis = analyzer.analyzeChanges();
                Reporter reporter = new Reporter(Theme.PLAIN,
                        new ReportOptions(ChangeLevel.IMPORTS, ReportOptions.Grouping.BY_TYPE));
                List<String> api = reporter.reportOnly(analysis, ChangeLevel.API);
                assertEquals("# API changes", api.get(0));
                assertTrue(api.toString(), api.stream().anyMatch(l -> l.contains("__long__ a")));
                assertFalse(api.toString(), api.stream().anyMatch(l -> l.contains("Cosmetic") || l.contains("Imports")));

                List<String> imports = reporter.reportOnly(analysis, ChangeLevel.IMPORTS);
                assertEquals("# Imports", imports.get(0));
                assertTrue(imports.toString(), imports.stream().anyMatch(l -> l.contains("java.util.Set")));
                assertFalse(imports.toString(), imports.stream().anyMatch(l -> l.contains("__long__ a")));

                // The API section is the same as in the report grouped by category.
                List<String> grouped = new Reporter(Theme.PLAIN,
                        new ReportOptions(ChangeLevel.API, ReportOptions.Grouping.BY_CATEGORY)).report(analysis);
                assertEquals(grouped, api);
            }
        }
    }

    /**
     * Tests that a level without changes gives a single note.
     */
    public void testReportOnlyEmptyLevel() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  int a;\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  long a;\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertEquals(List.of("# Imports", "", "  NO CHANGES", ""),
                        new Reporter(Theme.PLAIN).reportOnly(analyzer.analyzeChanges(), ChangeLevel.IMPORTS));
            }
        }
    }

    /**
     * Tests that a report by type with nothing to show lists every shown level with NO CHANGES.
     */
    public void testNothingToShowByType() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "import java.util.List;\npublic class Foo {\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "import java.util.Set;\npublic class Foo {\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertEquals(List.of("# API changes", "", "  NO CHANGES", "",
                                "# Implementation changes", "", "  NO CHANGES", ""),
                        new Reporter(Theme.PLAIN, new ReportOptions(ChangeLevel.IMPLEMENTATION,
                                ReportOptions.Grouping.BY_TYPE)).report(analyzer.analyzeChanges()));
            }
        }
    }

    /**
     * Tests that with only changed imports and the imports level, every level is listed.
     */
    public void testOnlyImportChangesShowAllLevels() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "import java.util.List;\npublic class Foo {\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "import java.util.Set;\npublic class Foo {\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                List<String> lines = new Reporter(Theme.PLAIN, new ReportOptions(ChangeLevel.IMPORTS,
                        ReportOptions.Grouping.BY_TYPE)).report(analyzer.analyzeChanges());
                assertEquals(List.of("# API changes", "", "  NO CHANGES", "",
                                "# Implementation changes", "", "  NO CHANGES", "",
                                "# Cosmetic changes", "", "  NO CHANGES", ""),
                        lines.subList(0, 12));
                assertEquals("# Imports", lines.get(12));
                assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.contains("java.util.Set")));
            }
        }
    }

    /** Reports the change of Foo.java from one content to the other, with the given javadoc context. */
    private static List<String> javadocReport(String oldSource, String newSource, int context) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", oldSource);
            helper.commit("init");
            helper.writeFile("Foo.java", newSource);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                return new Reporter(Theme.PLAIN, new ReportOptions(ChangeLevel.API, ReportOptions.Grouping.BY_TYPE,
                        ReportOptions.DEFAULT_BODY_LINES, context)).report(analyzer.analyzeChanges().files().get(0));
            }
        }
    }

    private static final String DOC_OLD = "public class Foo {\n"
            + "  /**\n   * Returns the first element.\n   *\n   * @param i the index\n"
            + "   * @return the element\n   * @throws IOException never\n   */\n"
            + "  public String get(int i) { return null; }\n}\n";

    /**
     * Tests that a javadoc-only change is an API change: a similar line shows the changed words, a line changed
     * completely is followed by the new one, and the unchanged lines around are limited by the context.
     */
    public void testJavadocChange() throws Exception {
        String changed = DOC_OLD.replace("first", "last").replace("@throws IOException never", "@since 2.0");
        assertEquals(List.of(
                        "    public class Foo",
                        "    ~ * Returns the ~~first~~ __last__ element.",
                        "      *",
                        "      …",
                        "      * @return the element",
                        "    - * ~~@throws IOException never~~",
                        "    + * @since 2.0",
                        "    ~ public String get( int i )"),
                javadocReport(DOC_OLD, changed, 1));
    }

    /** Tests that a change of the javadoc alone is not reported when the javadoc is not shown. */
    public void testJavadocOnlyChangeHidden() throws Exception {
        assertEquals(List.of(),
                javadocReport(DOC_OLD, DOC_OLD.replace("first", "last"), ReportOptions.NO_JAVADOC));
        String nested = "public class Foo {\n  /** Inner one. */\n  public static class In { }\n}\n";
        assertEquals(List.of(),
                javadocReport(nested, nested.replace("one.", "two."), ReportOptions.NO_JAVADOC));
    }

    /** Tests that a block of completely changed lines shows all the removed lines first, then the new ones. */
    public void testJavadocChangedBlock() throws Exception {
        String changed = DOC_OLD.replace("@return the element", "Nothing here matches")
                .replace("@throws IOException never", "@since 2.0");
        assertEquals(List.of(
                        "    public class Foo",
                        "      …",
                        "      * @param i the index",
                        "    - * ~~@return the element~~",
                        "    - * ~~@throws IOException never~~",
                        "    + * Nothing here matches",
                        "    + * @since 2.0",
                        "    ~ public String get( int i )"),
                javadocReport(DOC_OLD, changed, 1));
    }

    /** Tests the context of a javadoc change: none, the whole javadoc and no javadoc at all. */
    public void testJavadocContext() throws Exception {
        String changed = DOC_OLD.replace("   * @return the element\n", "");
        assertEquals(List.of(
                        "    public class Foo",
                        "      …",
                        "    - * ~~@return the element~~",
                        "      …",
                        "    ~ public String get( int i )"),
                javadocReport(DOC_OLD, changed, 0));
        assertEquals(List.of(
                        "    public class Foo",
                        "      * Returns the first element.",
                        "      *",
                        "      * @param i the index",
                        "    - * ~~@return the element~~",
                        "      * @throws IOException never",
                        "    ~ public String get( int i )"),
                javadocReport(DOC_OLD, changed, ReportOptions.WHOLE_JAVADOC));
        assertEquals(List.of(), javadocReport(DOC_OLD, changed, ReportOptions.NO_JAVADOC));
    }

    /** Tests that an added member shows its javadoc, a removed one does not. */
    public void testJavadocOfAddedAndRemoved() throws Exception {
        String old = "public class Foo {\n  /** Gone. */\n  public int gone(String s) { return 0; }\n}\n";
        String added = "public class Foo {\n  /**\n   * Fresh.\n   * Really.\n   */\n  public void fresh() { }\n}\n";
        assertEquals(List.of(
                        "    public class Foo",
                        "    - ~~public int gone( String s )~~",
                        "    + * Fresh.",
                        "    + * Really.",
                        "    + public void fresh()"),
                javadocReport(old, added, 1));
    }

    /** Tests that a renamed member shows the change of its javadoc too. */
    public void testJavadocOfRenamed() throws Exception {
        String old = "public class Foo {\n  /** Gone. */\n  public void gone() { }\n}\n";
        String added = "public class Foo {\n  /**\n   * Fresh.\n   * Really.\n   */\n  public void fresh() { }\n}\n";
        assertEquals(List.of(
                        "    public class Foo",
                        "    - * ~~Gone.~~",
                        "    + * Fresh.",
                        "    + * Really.",
                        "    ~ public void ~~gone~~ __fresh__()"),
                javadocReport(old, added, 1));
    }

    /** Tests that a changed javadoc of a nested type is shown above its heading. */
    public void testJavadocOfType() throws Exception {
        String old = "public class Foo {\n  /** Inner one. */\n  public static class In { }\n}\n";
        assertEquals(List.of(
                        "    public class Foo",
                        "    ~ * Inner ~~one.~~ __two.__",
                        "      public static class In"),
                javadocReport(old, old.replace("one.", "two."), 1));
    }
}
