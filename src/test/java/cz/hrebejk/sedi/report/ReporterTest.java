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
import org.maraboo.fleck.css.Palette;
import org.maraboo.fleck.css.StyleSheet;
import org.maraboo.fleck.html.HTML;

import java.util.Comparator;
import java.util.List;

import static cz.hrebejk.sedi.report.Expect.*;
import static cz.hrebejk.sedi.report.HtmlAssert.*;

/**
 * Unit tests for {@link Reporter}.
 */
public class ReporterTest extends TestCase {

    /** The line between the files. */
    private static final String SEPARATOR = "\u2500".repeat(Reporter.DEFAULT_WIDTH);

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
                assertLines(List.of(
                        line(' ', "  ", code("public class ", decl("Foo"))),
                        line('~', "  ", tree("├── "), code(strike("int"), " ", added("long"), " ", decl("a"))),
                        line(' ', "  ", tree("└── "), code("static class ", decl("In"))),
                        line('+', "      ", tree("└── "), code("void ", decl("y"), "()"))),
                        new Reporter().report(analyses.get(0)));
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
                ReportOptions options = new ReportOptions(ChangeLevel.API,
                        ReportOptions.DEFAULT_BODY_CONTEXT, ReportOptions.NO_JAVADOC, true);
                List<HTML> lines = lines(new Reporter(options).report(analyzer.analyzeChanges()));
                assertLines(List.of(
                        heading("API changes"),
                        blank(),
                        fileTitle('~', name("Foo.java")),
                        line(" 1", ' ', "  ", code("public class ", decl("Foo"))),
                        line(" 3", '~', "  ", tree("├── "), code(strike("int"), " ", added("long"), " ", decl("a"))),
                        line(" 3", '-', "  ", tree("├── "), code(strike("void ", decl("gone"), "()")))),
                        lines.subList(0, 6));
                assertContains(line(" 5", '+', "  ", tree("│   "), tree("│   "), code("@Deprecated")), lines);
                assertContains(
                        line(" 6", '+', "  ", tree("│   "), tree("└── "), code("void ", decl("y"), "()")),
                        lines);
                assertContains(line("11", '+', "  ", tree("└── "), code("void ", decl("ten"), "()")), lines);
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
                ReportOptions options = new ReportOptions(ChangeLevel.IMPORTS,
                        ReportOptions.DEFAULT_BODY_CONTEXT, ReportOptions.NO_JAVADOC, true);
                List<HTML> lines = lines(new Reporter(options).reportOnly(analyzer.analyzeChanges(),
                        ChangeLevel.IMPORTS));
                assertContains(line("1", '-', "  ", code(strike("import java.util.List"))), lines);
                assertContains(line("4", '+', "  ", code("import java.util.Set")), lines);
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
                ReportOptions options = new ReportOptions(ChangeLevel.API,
                        ReportOptions.DEFAULT_BODY_CONTEXT, ReportOptions.WHOLE_JAVADOC, true);
                List<HTML> lines = lines(new Reporter(options).report(analyzer.analyzeChanges().files().getFirst()));
                assertLines(List.of(
                        line(" 1", ' ', "  ", code("public class ", decl("Foo"))),
                        line(" 4", '~', "  ", tree("│   "), code(comment("* Hello ", added("big"), " world."))),
                        line(" 4", '-', "  ", tree("│   "), code(comment("* ", strike("Second line.")))),
                        line(" 5", '+', "  ", tree("│   "), code(comment("*"))),
                        line(" 6", '+', "  ", tree("│   "), code(comment("* Third."))),
                        line(" 8", ' ', "  ", tree("├── "), code("public void ", decl("m"), "()")),
                        line(" 9", '+', "  ", tree("│   "), code(comment("* Added."))),
                        line("10", '+', "  ", tree("└── "), code("public void ", decl("n"), "()"))),
                        lines);
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
                ReportOptions options = new ReportOptions(ChangeLevel.API,
                        ReportOptions.DEFAULT_BODY_CONTEXT, ReportOptions.NO_JAVADOC, true);
                HTML.Tag report = new Reporter(options).report(analyzer.analyzeChanges());
                Palette palette = ReportStyles.palette();
                StyleSheet styleSheet = ReportStyles.styleSheet(palette);
                assertEquals(palette.get("line-number"), numberColor(report, styleSheet, "1", ""));
                assertEquals(palette.get("changed-mark"), numberColor(report, styleSheet, "2", "~"));
                assertEquals(palette.get("removed-mark"), numberColor(report, styleSheet, "3", "-"));
                assertEquals(palette.get("added-mark"), numberColor(report, styleSheet, "3", "+"));
            }
        }
    }

    /**
     * The report by file shows every level, whatever the options say: under each file the levels with changes, each
     * under its name. The file and the levels are folds, all but the API collapsed at first.
     */
    public void testReportByFile() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "import java.util.List;\npublic class Foo {\n  int a;\n"
                    + "  int b() { return 1; }\n}\n");
            helper.writeFile("Bar.java", "import java.util.List;\npublic class Bar {\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "import java.util.Set;\npublic class Foo {\n  long a;\n"
                    + "  int b() { return 2; }\n}\n");
            helper.writeFile("Bar.java", "import java.util.Set;\npublic class Bar {\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                HTML.Tag report = new Reporter(new ReportOptions(ChangeLevel.API))
                        .reportByFile(analyzer.analyzeChanges());
                assertEquals(List.of(
                                "~ Bar.java",
                                "  # Imports",
                                "-   import java.util.List",
                                "+   import java.util.Set",
                                "\u2500".repeat(Reporter.DEFAULT_WIDTH),
                                "~ Foo.java",
                                "  # API",
                                "    public class Foo",
                                "~   \u2514\u2500\u2500 int long a",
                                "  # Implementation",
                                "    public class Foo",
                                "    \u2514\u2500\u2500 int b() {",
                                "~             return 1 2;",
                                "        }",
                                "  # Imports",
                                "-   import java.util.List",
                                "+   import java.util.Set"),
                        texts(lines(report)));
                HTML.Tag foo = (HTML.Tag) report.children.get(2);
                assertEquals("Foo.java", foo.attrs.get("data-name"));
                List<HTML.Tag> levels = foo.children.stream().skip(1).map(HTML.Tag.class::cast).toList();
                assertEquals(List.of("API", "Implementation", "Imports"),
                        levels.stream().map(l -> l.attrs.get("data-name")).toList());
                assertEquals(java.util.Arrays.asList(null, "false", "false"),
                        levels.stream().map(l -> l.attrs.get("data-open")).toList());
            }
        }
    }

    /** The lines of a file are in a fold, its title first, so the file can be collapsed to its title. */
    public void testFileIsFolded() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  int a;\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  long a;\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                HTML.Tag report = new Reporter().report(analyzer.analyzeChanges());
                // The heading of the API changes, an empty line and the file.
                assertEquals(markup(report.children), 3, report.children.size());
                HTML.Tag fold = (HTML.Tag) report.children.getLast();
                assertTrue(markup(fold), Css.FOLD.on(fold));
                assertEquals("Foo.java", fold.attrs.get("data-name"));
                assertNull(markup(fold), fold.attrs.get("data-kind"));
                assertEquals(List.of("~ Foo.java", "    public class Foo", "~   \u2514\u2500\u2500 int long a"),
                        texts(lines(fold)));
                // The type with its changed members is folded too, under its signature.
                HTML.Tag type = (HTML.Tag) fold.children.getLast();
                assertTrue(markup(type), Css.FOLD.on(type));
                assertEquals("public class Foo", type.attrs.get("data-name"));
                assertEquals("type", type.attrs.get("data-kind"));
            }
        }
    }

    /** The fold of a member with its body under its signature says it is a member and carries its javadoc. */
    public void testMemberFoldIsMarked() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  /**\n   * One.\n   * Really.\n   */\n"
                    + "  int m() {\n    return 1;\n  }\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  /**\n   * One.\n   * Really.\n   */\n"
                    + "  int m() {\n    return 2;\n  }\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                HTML.Tag file = (HTML.Tag) new Reporter(new ReportOptions(ChangeLevel.IMPLEMENTATION))
                        .report(analyzer.analyzeChanges()).children.getLast();
                HTML.Tag type = (HTML.Tag) file.children.getLast();
                assertEquals(markup(type), "type", type.attrs.get("data-kind"));
                HTML.Tag member = (HTML.Tag) type.children.getLast();
                assertTrue(markup(member), Css.FOLD.on(member));
                assertEquals(markup(member), "member", member.attrs.get("data-kind"));
                assertEquals(markup(member), "One.\nReally.", member.attrs.get("data-javadoc"));
                // The type has no javadoc.
                assertNull(markup(type), type.attrs.get("data-javadoc"));
            }
        }
    }

    /** An element with nothing under its signature is no fold. */
    public void testLeafIsNotFolded() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  int a;\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  long a;\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                HTML.Tag type = (HTML.Tag) ((HTML.Tag) new Reporter().report(analyzer.analyzeChanges())
                        .children.getLast()).children.getLast();
                assertEquals(markup(type), 2, type.children.size());
                assertFalse(markup(type), Css.FOLD.on((HTML.Tag) type.children.getLast()));
            }
        }
    }

    /** The color of the number of the line with the number and the mark, as the style sheet computes it. */
    private static org.maraboo.fleck.css.CssValues.Color numberColor(HTML.Tag report, StyleSheet styleSheet,
                                                                      String number, String mark) {
        org.maraboo.fleck.css.Style reportStyle = styleSheet.computeStyle(report, null);
        for (HTML node : lines(report)) {
            if (node instanceof HTML.Tag line && line.children.size() > 2
                    && line.children.get(0) instanceof HTML.Tag ln && Css.LN.on(ln)
                    && ln.getInnerText().equals(number) && line.children.get(2).getInnerText().strip().equals(mark)) {
                org.maraboo.fleck.css.Style lineStyle = styleSheet.computeStyle(line, reportStyle);
                return styleSheet.computeStyle(ln, lineStyle).foreground();
            }
        }
        throw new AssertionError("No line " + number + " " + mark + " in\n" + markup(lines(report)));
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
                assertTrue(new Reporter().report(analyzer.analyzeChanges().files().get(0)).children.isEmpty());
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
                assertLines(List.of(
                        line('~', "  ", code("public class ", strike("Foo"), " ", added(decl("Bar")))),
                        line('~', "  ", tree("└── "), code(strike("int"), " ", added("long"), " ", decl("a")))),
                        new Reporter().report(analyses.get(0)));
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
                assertLines(List.of(
                        line('-', "  ", code(strike("class ", decl("Gone")))),
                        line('+', "  ", code("class ", decl("New")))),
                        new Reporter().report(analyzer.analyzeChanges().files().get(0)));
            }
        }
    }

    /** A file moved to another package of the same source root: only the package is shown changed. */
    public void testTitleOfMovedPackageKeepsSourceRoot() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            java.nio.file.Path old = helper.writeFile("src/main/java/a/Foo.java",
                    "package a;\npublic class Foo {\n  void m() { }\n}\n");
            helper.commit("init");
            java.nio.file.Files.delete(old);
            helper.writeFile("src/main/java/b/Foo.java", "package b;\npublic class Foo {\n  void m() { }\n}\n");
            helper.commit("change");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                FileAnalysis file = analyzer.analyzeCommit("HEAD", p -> true).files().getFirst();
                assertLines(List.of(fileTitle('→', name("Foo.java"), " ", pkg(strike("a"), " ", added("b")), " ",
                                pkg("src/main/java"))),
                        LineNumbers.render(List.of(Line.outside(new Reporter().title(file))), false));
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
                Reporter reporter = new Reporter();
                List<HTML> titles = analyzer.analyzeCommit("HEAD", p -> p.endsWith(".java")).files().stream()
                        .<HTML>map(f -> LineNumbers.render(List.of(Line.outside(reporter.title(f))), false).getFirst())
                        .sorted(Comparator.comparing(HTML::getInnerText)).toList();
                assertLines(List.of(
                        fileTitle('+', name("New.java"), " ", pkg("n")),
                        fileTitle('-', strike("Gone.java")),
                        fileTitle('→', name(strike("Foo.java"), " ", added("Bar.java")), " ", pkg(strike("a"), " ",
                                added("b")))),
                        titles);
            }
        }
    }

    private static final String BEFORE = "import java.util.List;\npublic class Foo {\n  public int a;\n  private int b;\n"
            + "  public int m() { return 1; }\n  private int n() { return 1; }\n  int o() { int x = 1; return x; }\n}\n";
    private static final String AFTER = "import java.util.Set;\npublic class Foo {\n  public long a;\n  private long b;\n"
            + "  public int m() { return 2; }\n  private int n() { return 1; }\n  int o() { int y = 1; return y; }\n}\n";

    /** The report of the changes of {@code BEFORE} to {@code AFTER} in {@code Foo.java}. */
    private HTML.Tag fileReport(ReportOptions options) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", BEFORE);
            helper.commit("init");
            helper.writeFile("Foo.java", AFTER);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                return new Reporter(options).report(analyzer.analyzeChanges().files().getFirst());
            }
        }
    }

    private HTML.Tag report(ReportOptions options) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", BEFORE);
            helper.commit("init");
            helper.writeFile("Foo.java", AFTER);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                return new Reporter(options).report(analyzer.analyzeChanges());
            }
        }
    }

    /**
     * Tests that the default options show the API changes only.
     */
    public void testDefaultShowsOnlyApi() throws Exception {
        assertLines(List.of(
                heading("API changes"),
                blank(),
                fileTitle('~', name("Foo.java")),
                line(' ', "  ", code("public class ", decl("Foo"))),
                line('~', "  ", tree("├── "), code("public ", strike("int"), " ", added("long"), " ", decl("a"))),
                line('~', "  ", tree("└── "), code("private ", strike("int"), " ", added("long"), " ", decl("b")))),
                report(ReportOptions.DEFAULT));
    }

    /**
     * Tests that in the report of a file a deeper level adds the less important changes, tagged, and the imports
     * last.
     */
    public void testLevelsOfFile() throws Exception {
        assertLines(List.of(
                line(' ', "  ", code("public class ", decl("Foo"))),
                line('~', "  ", tree("├── "), code("public ", strike("int"), " ", added("long"), " ", decl("a"))),
                line('~', "  ", tree("├── "), code("private ", strike("int"), " ", added("long"), " ", decl("b"))),
                line(' ', "  ", tree("├── "), code("public int ", decl("m"), "() { ", tag("impl"))),
                line('~', "  ", tree("│   "), "      ", code("return ", strike("1"), " ", added("2"), ";")),
                line(' ', "  ", tree("│   "), code("}")),
                line(' ', "  ", tree("└── "), code("int ", decl("o"), "() { ", tag("cosmetic"))),
                line('~', "            ", code("int ", strike("x"), " ", added("y"), " = 1; return ", strike("x"), " ",
                        added("y"), ";")),
                line(' ', "      ", code("}")),
                line('-', "  ", code(strike("import java.util.List"), " ", tag("import"))),
                line('+', "  ", code("import java.util.Set ", tag("import")))),
                fileReport(new ReportOptions(ChangeLevel.IMPORTS)));
        assertLines(List.of(
                line(' ', "  ", code("public class ", decl("Foo"))),
                line('~', "  ", tree("├── "), code("public ", strike("int"), " ", added("long"), " ", decl("a"))),
                line('~', "  ", tree("├── "), code("private ", strike("int"), " ", added("long"), " ", decl("b"))),
                line(' ', "  ", tree("└── "), code("public int ", decl("m"), "() { ", tag("impl"))),
                line('~', "            ", code("return ", strike("1"), " ", added("2"), ";")),
                line(' ', "      ", code("}"))),
                fileReport(new ReportOptions(ChangeLevel.IMPLEMENTATION)));
    }

    /**
     * Tests the grouping by category, with the API changes split by visibility.
     */
    public void testGroupByCategory() throws Exception {
        assertLines(List.of(
                heading("API changes"),
                blank(),
                fileTitle('~', name("Foo.java")),
                line(' ', "  ", code("public class ", decl("Foo"))),
                line('~', "  ", tree("├── "), code("public ", strike("int"), " ", added("long"), " ", decl("a"))),
                line('~', "  ", tree("└── "), code("private ", strike("int"), " ", added("long"), " ", decl("b"))),
                blank(),
                heading("Implementation changes"),
                blank(),
                fileTitle('~', name("Foo.java")),
                line(' ', "  ", code("public class ", decl("Foo"))),
                line(' ', "  ", tree("└── "), code("public int ", decl("m"), "() {")),
                line('~', "            ", code("return ", strike("1"), " ", added("2"), ";")),
                line(' ', "      ", code("}")),
                blank(),
                heading("Cosmetic changes"),
                blank(),
                fileTitle('~', name("Foo.java")),
                line(' ', "  ", code("public class ", decl("Foo"))),
                line(' ', "  ", tree("└── "), code("int ", decl("o"), "() {")),
                line('~', "            ", code("int ", strike("x"), " ", added("y"), " = 1; return ", strike("x"), " ",
                        added("y"), ";")),
                line(' ', "      ", code("}")),
                blank(),
                heading("Imports"),
                blank(),
                fileTitle('~', name("Foo.java")),
                line('-', "  ", code(strike("import java.util.List"))),
                line('+', "  ", code("import java.util.Set"))),
                report(new ReportOptions(ChangeLevel.IMPORTS)));
    }

    /** The implementation changes are in the order of the new source, nested types among the members. */
    public void testImplementationInSourceOrder() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "class Foo {\n  int a() { return 1; }\n"
                    + "  static class In {\n    int f() { return 1; }\n  }\n"
                    + "  int m() { return 1; }\n  int z(int x) { return x; }\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "class Foo {\n  int a() { return 2; }\n"
                    + "  static class In {\n    int f() { return 2; }\n  }\n"
                    + "  int m() { return 2; }\n  int z(long x) { return (int) x; }\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                List<HTML> lines = lines(new Reporter(new ReportOptions(ChangeLevel.IMPLEMENTATION)).report(analyzer.analyzeChanges()));
                List<HTML> impl = lines.subList(texts(lines).indexOf("# Implementation changes"), lines.size()).stream()
                        .filter(l -> l.getInnerText().contains("(") && !l.getInnerText().contains("return")).toList();
                assertLines(List.of(
                        line(' ', "  ", tree("├── "), code("int ", decl("a"), "() {")),
                        line(' ', "  ", tree("│   "), tree("└── "), code("int ", decl("f"), "() {")),
                        line(' ', "  ", tree("├── "), code("int ", decl("m"), "() {")),
                        line(' ', "  ", tree("└── "), code("int ", decl("z"), "( long x ) {"))),
                        impl);
            }
        }
    }

    /**
     * The arguments of an enum constant follow its name: removed ones, leaving nothing of the new version in the
     * source, are shown struck through, added ones to a constant without them as added, changed ones replaced.
     */
    public void testEnumConstantArguments() throws Exception {
        assertLines(List.of(
                line('~', "  ", tree("├── "), code("public static final E ", decl("A"), strike("(\"a\")"))),
                line('~', "  ", tree("└── "), code("public static final E ", decl("B"), strike("(\"b\")")))),
                enumImplementation("public enum E {\n  A(\"a\"),\n  B(\"b\");\n  E(String s) { }\n}\n",
                        "public enum E {\n  A,\n  B;\n  E(String s) { }\n}\n"));
        assertLines(List.of(
                line('~', "  ", tree("├── "), code("public static final E ", decl("A"), added("(\"a\")"))),
                line('~', "  ", tree("└── "), code("public static final E ", decl("B"), added("{ }")))),
                enumImplementation("public enum E {\n  A,\n  B;\n  E() { }\n}\n",
                        "public enum E {\n  A(\"a\"),\n  B { };\n  E() { }\n}\n"));
        assertLines(List.of(
                line('~', "  ", tree("└── "), code("public static final E ", decl("A"), strike("(\"a\")"), " ",
                        added("(\"x\")")))),
                enumImplementation("public enum E {\n  A(\"a\"),\n  B(\"b\");\n  E(String s) { }\n}\n",
                        "public enum E {\n  A(\"x\"),\n  B(\"b\");\n  E(String s) { }\n}\n"));
    }

    /** The lines of the constants of the enum in the implementation changes. */
    private static List<HTML> enumImplementation(String oldSource, String newSource) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("E.java", oldSource);
            helper.commit("init");
            helper.writeFile("E.java", newSource);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                List<HTML> lines = lines(new Reporter(new ReportOptions(ChangeLevel.IMPLEMENTATION)).report(analyzer.analyzeChanges()));
                return lines.subList(texts(lines).indexOf("# Implementation changes"), lines.size()).stream()
                        .filter(l -> l.getInnerText().contains("static final E")).toList();
            }
        }
    }

    /**
     * Tests that the members javac adds are not reported: the default constructor and the fields of the components
     * of a record. A constructor written in place of the default one is reported as added, and a change of the
     * components shows in the header of the record.
     */
    public void testGeneratedMembersLeftOut() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  public int a;\n}\n");
            helper.writeFile("Bar.java", "public class Bar {\n  public int a;\n}\n");
            helper.writeFile("R.java", "public record R(int a, String b) { }\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  public long a;\n}\n");
            helper.writeFile("Bar.java", "public class Bar {\n  public int a;\n  public Bar() { }\n}\n");
            helper.writeFile("R.java", "public record R(int a, long c, String b) { }\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertLines(List.of(
                        heading("API changes"),
                        blank(),
                        fileTitle('~', name("Bar.java")),
                        line(' ', "  ", code("public class ", decl("Bar"))),
                        line('+', "  ", tree("└── "), code("public ", decl("Bar"), "()")),
                        separator(),
                        fileTitle('~', name("Foo.java")),
                        line(' ', "  ", code("public class ", decl("Foo"))),
                        line('~', "  ", tree("└── "), code("public ", strike("int"), " ", added("long"), " ",
                                decl("a"))),
                        separator(),
                        fileTitle('~', name("R.java")),
                        line('~', "  ", code("public record ", decl("R"), "( int a, ", added("long c"), ", String b )"))),
                        new Reporter(new ReportOptions(ChangeLevel.API)).report(analyzer.analyzeChanges()));
            }
        }
    }

    /**
     * Tests that the API changes list the public API of the new types, the names of the deleted ones and then
     * the changes of the other types, a removed nested type among the members of its outer type.
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
                assertLines(List.of(
                        heading("API changes"),
                        blank(),
                        fileTitle('+', name("New.java"), " ", pkg("b")),
                        line(' ', "  ", code("public class ", decl("New"))),
                        line(' ', "  ", tree("├── "), code("public ", decl("New"), "( int i )")),
                        line(' ', "  ", tree("└── "), code("public void ", decl("m"), "()")),
                        separator(),
                        fileTitle('-', strike("Old.java"), " ", strike("a")),
                        separator(),
                        fileTitle('~', name("Foo.java"), " ", pkg("a")),
                        line(' ', "  ", code("public class ", decl("Foo"))),
                        line('~', "  ", tree("├── "), code(strike("int"), " ", added("long"), " ", decl("a"))),
                        line('-', "  ", tree("├── "), code(strike("class ", decl("Gone")))),
                        line('+', "  ", tree("└── "), code("public static class ", decl("Fresh"))),
                        line(' ', "      ", tree("├── "), code("public int ", decl("x"))),
                        line(' ', "      ", tree("└── "), code("protected void ", decl("m"), "()"))),
                        new Reporter(new ReportOptions(ChangeLevel.API))
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
                assertLines(List.of(
                        heading("API changes"),
                        blank(),
                        fileTitle('~', name("Foo.java"), " ", pkg("p")),
                        line('~', "  ", code("public ", added("abstract"), " class ", decl("Foo"), "<T, ", added("U"),
                                "> extends ", strike("Object"), " ", added("Number"), " implements Runnable, ",
                                added("Cloneable"))),
                        line('~', "  ", tree("└── "), code("static ", added("final"), " class ", decl("In"),
                                " implements ", added("Runnable"))),
                        line('+', "      ", tree("└── "), code("public void ", decl("run"), "()"))),
                        new Reporter().report(analyzer.analyzeChanges()));
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
                assertLines(List.of(
                        line('~', "  ", code(added("@SuppressWarnings(\"all\")"))),
                        line(' ', "  ", code("public class ", decl("Foo"))),
                        line('~', "  ", tree("├── "), code(strike("int"), " ", added("long"), " ", decl("a"))),
                        line('~', "  ", tree("│   "), code(strike("@Deprecated"))),
                        line(' ', "  ", tree("├── "), code("int ", decl("b"))),
                        line('~', "  ", tree("│   "), code("@SuppressWarnings(", strike("\"x\""), " ", added("\"y\""),
                                ")")),
                        line(' ', "  ", tree("├── "), code("int ", decl("c"))),
                        line('+', "  ", tree("│   "), code("@Deprecated")),
                        line('+', "  ", tree("└── "), code("int ", decl("d")))),
                        new Reporter().report(analyzer.analyzeChanges().files().get(0)));
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
                assertLines(List.of(
                        heading("API changes"),
                        blank(),
                        fileTitle('~', name("A.java")),
                        line(' ', "  ", code("class ", decl("A"))),
                        line('~', "  ", tree("└── "), code(strike("int"), " ", added("long"), " ", decl("a"))),
                        separator(),
                        fileTitle('~', name("B.java")),
                        line(' ', "  ", code("class ", decl("B"))),
                        line('~', "  ", tree("└── "), code(strike("int"), " ", added("long"), " ", decl("b")))),
                        new Reporter(new ReportOptions(ChangeLevel.API))
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
                assertLines(List.of(
                        heading("API changes"),
                        blank(),
                        fileTitle('+', name("Fresh.java")),
                        line(' ', "  ", code("interface ", decl("Fresh"))),
                        line(' ', "  ", tree("├── "), code("void ", decl("m"), "()")),
                        line(' ', "  ", tree("└── "), code("void ", decl("n"), "()")),
                        separator(),
                        fileTitle('-', strike("Gone.java")),
                        separator(),
                        fileTitle('→', name(strike("Foo.java"), " ", added("Bar.java")), " ", pkg(strike("a"), " ",
                                added("b"))),
                        line('~', "  ", code("public class ", strike("Foo"), " ", added(decl("Bar"))))),
                        new Reporter(new ReportOptions(ChangeLevel.API))
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
                assertLines(List.of(
                        heading("API changes"),
                        blank(),
                        fileTitle('→', name("Foo.java"), " ", pkg(strike("a"), " ", added("b")))),
                        new Reporter().report(analyzer.analyzeChanges()));
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
                Reporter reporter = new Reporter(new ReportOptions(ChangeLevel.IMPORTS));
                List<HTML> api = lines(reporter.reportOnly(analysis, ChangeLevel.API));
                assertHtml(heading("API changes"), api.getFirst());
                assertTrue(markup(api), hasSpan(api, Css.ADDED, "long"));
                assertFalse(markup(api), texts(api).stream().anyMatch(l -> l.contains("Cosmetic") || l.contains("Imports")));

                List<HTML> imports = lines(reporter.reportOnly(analysis, ChangeLevel.IMPORTS));
                assertHtml(heading("Imports"), imports.getFirst());
                assertTrue(markup(imports), texts(imports).stream().anyMatch(l -> l.contains("java.util.Set")));
                assertFalse(markup(imports), hasSpan(imports, Css.ADDED, "long"));

                // The API section is the same as in the report of all the levels.
                HTML.Tag grouped = new Reporter(new ReportOptions(ChangeLevel.API))
                        .report(analysis);
                assertLines(lines(grouped), api);
            }
        }
    }

    /**
     * Tests that the changes of the files under {@code src/test/} are left out of every view when the tests are
     * hidden, and shown by default.
     */
    public void testHideTests() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/Foo.java", "package p;\nimport java.util.List;\npublic class Foo {\n  int a;\n}\n");
            helper.writeFile("src/test/java/p/FooTest.java", "package p;\nimport java.util.List;\npublic class FooTest {\n  int b;\n}\n");
            helper.commit("init");
            helper.writeFile("src/main/java/p/Foo.java", "package p;\nimport java.util.Set;\npublic class Foo {\n  long a;\n}\n");
            helper.writeFile("src/test/java/p/FooTest.java", "package p;\nimport java.util.Set;\npublic class FooTest {\n  long b;\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                var analysis = analyzer.analyzeChanges();
                Reporter shown = new Reporter(new ReportOptions(ChangeLevel.IMPORTS));
                assertTrue(shown.options().showTests());
                assertTrue(text(shown.report(analysis)).contains("FooTest"));

                Reporter hidden = shown.withTests(false);
                for (HTML.Tag tag : List.of(hidden.report(analysis), hidden.reportByFile(analysis))) {
                    String report = text(tag);
                    assertTrue(report, report.contains("Foo.java"));
                    assertFalse(report, report.contains("FooTest"));
                }
                for (ChangeLevel level : ChangeLevel.values()) {
                    assertFalse(level.toString(), text(hidden.reportOnly(analysis, level)).contains("FooTest"));
                }
                assertTrue(text(hidden.withTests(true).report(analysis)).contains("FooTest"));
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
                assertLines(List.of(
                        heading("Imports"),
                        blank(),
                        noChanges(),
                        blank()),
                        new Reporter().reportOnly(analyzer.analyzeChanges(), ChangeLevel.IMPORTS));
            }
        }
    }

    /**
     * Tests that a report with nothing to show but imports lists every shown level, the empty ones with NO CHANGES.
     */
    public void testNothingToShowButImports() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "import java.util.List;\npublic class Foo {\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "import java.util.Set;\npublic class Foo {\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertLines(List.of(
                        heading("API changes"),
                        blank(),
                        noChanges(),
                        blank(),
                        heading("Implementation changes"),
                        blank(),
                        noChanges(),
                        blank()),
                        new Reporter(new ReportOptions(ChangeLevel.IMPLEMENTATION)).report(analyzer.analyzeChanges()));
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
                List<HTML> lines = lines(new Reporter(new ReportOptions(ChangeLevel.IMPORTS)).report(analyzer.analyzeChanges()));
                assertLines(List.of(
                        heading("API changes"),
                        blank(),
                        noChanges(),
                        blank(),
                        heading("Implementation changes"),
                        blank(),
                        noChanges(),
                        blank(),
                        heading("Cosmetic changes"),
                        blank(),
                        noChanges(),
                        blank()),
                        lines.subList(0, 12));
                assertHtml(heading("Imports"), lines.get(12));
                assertTrue(markup(lines), texts(lines).stream().anyMatch(l -> l.contains("java.util.Set")));
            }
        }
    }

    /** Reports the change of Foo.java from one content to the other, with the given javadoc context. */
    private static HTML.Tag javadocReport(String oldSource, String newSource, int context) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", oldSource);
            helper.commit("init");
            helper.writeFile("Foo.java", newSource);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                return new Reporter(new ReportOptions(ChangeLevel.API,
                        ReportOptions.DEFAULT_BODY_CONTEXT, context)).report(analyzer.analyzeChanges().files().get(0));
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
        assertLines(List.of(
                line(' ', "  ", code("public class ", decl("Foo"))),
                line('~', "  ", tree("│   "), code(comment("* Returns the ", strike("first"), " ", added("last"),
                        " element."))),
                line(' ', "  ", tree("│   "), code(comment("*"))),
                line(' ', "  ", tree("│   "), code(note("…"))),
                line(' ', "  ", tree("│   "), code(comment("* @return the element"))),
                line('-', "  ", tree("│   "), code(comment("* ", strike("@throws IOException never")))),
                line('+', "  ", tree("│   "), code(comment("* @since 2.0"))),
                line(' ', "  ", tree("└── "), code("public String ", decl("get"), "( int i )"))),
                javadocReport(DOC_OLD, changed, 1));
    }

    /** Tests that a change of the javadoc alone is not reported when the javadoc is not shown. */
    public void testJavadocOnlyChangeHidden() throws Exception {
        assertLines(List.of(), javadocReport(DOC_OLD, DOC_OLD.replace("first", "last"), ReportOptions.NO_JAVADOC));
        String nested = "public class Foo {\n  /** Inner one. */\n  public static class In { }\n}\n";
        assertLines(List.of(), javadocReport(nested, nested.replace("one.", "two."), ReportOptions.NO_JAVADOC));
    }

    /** Tests that a block of completely changed lines shows all the removed lines first, then the new ones. */
    public void testJavadocChangedBlock() throws Exception {
        String changed = DOC_OLD.replace("@return the element", "Nothing here matches")
                .replace("@throws IOException never", "@since 2.0");
        assertLines(List.of(
                line(' ', "  ", code("public class ", decl("Foo"))),
                line(' ', "  ", tree("│   "), code(note("…"))),
                line(' ', "  ", tree("│   "), code(comment("* @param i the index"))),
                line('-', "  ", tree("│   "), code(comment("* ", strike("@return the element")))),
                line('-', "  ", tree("│   "), code(comment("* ", strike("@throws IOException never")))),
                line('+', "  ", tree("│   "), code(comment("* Nothing here matches"))),
                line('+', "  ", tree("│   "), code(comment("* @since 2.0"))),
                line(' ', "  ", tree("└── "), code("public String ", decl("get"), "( int i )"))),
                javadocReport(DOC_OLD, changed, 1));
    }

    /** Tests the context of a javadoc change: none, the whole javadoc and no javadoc at all. */
    public void testJavadocContext() throws Exception {
        String changed = DOC_OLD.replace("   * @return the element\n", "");
        assertLines(List.of(
                line(' ', "  ", code("public class ", decl("Foo"))),
                line(' ', "  ", tree("│   "), code(note("…"))),
                line('-', "  ", tree("│   "), code(comment("* ", strike("@return the element")))),
                line(' ', "  ", tree("│   "), code(note("…"))),
                line(' ', "  ", tree("└── "), code("public String ", decl("get"), "( int i )"))),
                javadocReport(DOC_OLD, changed, 0));
        assertLines(List.of(
                line(' ', "  ", code("public class ", decl("Foo"))),
                line(' ', "  ", tree("│   "), code(comment("* Returns the first element."))),
                line(' ', "  ", tree("│   "), code(comment("*"))),
                line(' ', "  ", tree("│   "), code(comment("* @param i the index"))),
                line('-', "  ", tree("│   "), code(comment("* ", strike("@return the element")))),
                line(' ', "  ", tree("│   "), code(comment("* @throws IOException never"))),
                line(' ', "  ", tree("└── "), code("public String ", decl("get"), "( int i )"))),
                javadocReport(DOC_OLD, changed, ReportOptions.WHOLE_JAVADOC));
        assertLines(List.of(), javadocReport(DOC_OLD, changed, ReportOptions.NO_JAVADOC));
    }

    /** Tests that an added member shows its javadoc, a removed one does not. */
    public void testJavadocOfAddedAndRemoved() throws Exception {
        String old = "public class Foo {\n  /** Gone. */\n  public int gone(String s) { return 0; }\n}\n";
        String added = "public class Foo {\n  /**\n   * Fresh.\n   * Really.\n   */\n  public void fresh() { }\n}\n";
        assertLines(List.of(
                line(' ', "  ", code("public class ", decl("Foo"))),
                line('-', "  ", tree("├── "), code(strike("public int ", decl("gone"), "( String s )"))),
                line('+', "  ", tree("│   "), code(comment("* Fresh."))),
                line('+', "  ", tree("│   "), code(comment("* Really."))),
                line('+', "  ", tree("└── "), code("public void ", decl("fresh"), "()"))),
                javadocReport(old, added, 1));
    }

    /** Tests that a renamed member shows the change of its javadoc too. */
    public void testJavadocOfRenamed() throws Exception {
        String old = "public class Foo {\n  /** Gone. */\n  public void gone() { }\n}\n";
        String added = "public class Foo {\n  /**\n   * Fresh.\n   * Really.\n   */\n  public void fresh() { }\n}\n";
        assertLines(List.of(
                line(' ', "  ", code("public class ", decl("Foo"))),
                line('-', "  ", tree("│   "), code(comment("* ", strike("Gone.")))),
                line('+', "  ", tree("│   "), code(comment("* Fresh."))),
                line('+', "  ", tree("│   "), code(comment("* Really."))),
                line('~', "  ", tree("└── "), code("public void ", strike("gone"), " ", added(decl("fresh")), "()"))),
                javadocReport(old, added, 1));
    }

    /** Tests that a changed javadoc of a nested type is shown above its heading. */
    public void testJavadocOfType() throws Exception {
        String old = "public class Foo {\n  /** Inner one. */\n  public static class In { }\n}\n";
        assertLines(List.of(
                line(' ', "  ", code("public class ", decl("Foo"))),
                line('~', "  ", tree("│   "), code(comment("* Inner ", strike("one"), " ", added("two"), "."))),
                line(' ', "  ", tree("└── "), code("public static class ", decl("In")))),
                javadocReport(old, old.replace("one.", "two."), 1));
    }

    /** Reports only the implementation changes of Foo.java from one content to the other, with the javadoc context. */
    private static List<String> implReport(String oldSource, String newSource, int context) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", oldSource);
            helper.commit("init");
            helper.writeFile("Foo.java", newSource);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                return texts(lines(new Reporter(new ReportOptions(ChangeLevel.API,
                        ReportOptions.DEFAULT_BODY_CONTEXT, context))
                        .reportOnly(analyzer.analyzeChanges(), ChangeLevel.IMPLEMENTATION)));
            }
        }
    }

    /** Tests that the implementation section shows the unchanged javadoc as the javadoc context says. */
    public void testJavadocInImplementation() throws Exception {
        String changed = DOC_OLD.replace("return null;", "return \"x\";");
        List<String> none = implReport(DOC_OLD, changed, ReportOptions.NO_JAVADOC);
        assertFalse(none.toString(), none.stream().anyMatch(l -> l.contains("* ")));
        List<String> some = implReport(DOC_OLD, changed, 1);
        assertTrue(some.toString(), some.stream().anyMatch(l -> l.contains("* Returns the first element.")));
        assertFalse(some.toString(), some.stream().anyMatch(l -> l.contains("@param")));
        assertTrue(some.toString(), some.stream().anyMatch(l -> l.replace("│", "").strip().equals("…")));
        List<String> whole = implReport(DOC_OLD, changed, ReportOptions.WHOLE_JAVADOC);
        assertTrue(whole.toString(), whole.stream().anyMatch(l -> l.contains("* @throws IOException never")));
    }

    private static final String WHOLE_OLD = "public class Foo {\n  void keep() { }\n  int gone() {\n    return 1;\n  }\n}\n";
    private static final String WHOLE_NEW = "public class Foo {\n  void keep() { }\n  void added() {\n"
            + "    /* two\n       lines */\n    run();\n  }\n  static class In {\n\tint f;\n  }\n}\n";

    /** Reports the changes of Foo.java from {@link #WHOLE_OLD} to {@link #WHOLE_NEW} with the options. */
    private static HTML.Tag wholeReport(ReportOptions options, boolean implOnly) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", WHOLE_OLD);
            helper.commit("init");
            helper.writeFile("Foo.java", WHOLE_NEW);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                Reporter reporter = new Reporter(options);
                return implOnly ? reporter.reportOnly(analyzer.analyzeChanges(), ChangeLevel.IMPLEMENTATION)
                        : reporter.report(analyzer.analyzeChanges().files().getFirst());
            }
        }
    }

    /**
     * Tests that the implementation changes show the whole source of the added members and types, their
     * indentation and comments kept and tabs expanded, and not that of the removed ones by default.
     */
    public void testAddedShownWhole() throws Exception {
        assertLines(List.of(
                heading("Implementation changes"),
                blank(),
                fileTitle('~', name("Foo.java")),
                line(' ', "  ", code("public class ", decl("Foo"))),
                line('+', "  ", tree("├── "), code("void added() {")),
                line('+', "  ", tree("│   "), code("  ", comment("/* two"))),
                line('+', "  ", tree("│   "), code(comment("     lines */"))),
                line('+', "  ", tree("│   "), code("  run();")),
                line('+', "  ", tree("│   "), code("}")),
                line('+', "  ", tree("└── "), code("static class In {")),
                line('+', "      ", code("  int f;")),
                line('+', "      ", code("}"))),
                wholeReport(new ReportOptions(ChangeLevel.API), true));
    }

    /** Tests that the removed members are shown whole with the deletions on, and the added ones not with them off. */
    public void testDeletionsShownWhole() throws Exception {
        ReportOptions options = new ReportOptions(ChangeLevel.API)
                .withAdditions(false).withDeletions(true);
        assertLines(List.of(
                heading("Implementation changes"),
                blank(),
                fileTitle('~', name("Foo.java")),
                line(' ', "  ", code("public class ", decl("Foo"))),
                line('-', "  ", tree("└── "), code("int gone() {")),
                line('-', "      ", code("  return 1;")),
                line('-', "      ", code("}"))),
                wholeReport(options, true));
    }

    /** Tests that in the report of a file the whole source replaces the signature of an added member. */
    public void testWholeInFileReport() throws Exception {
        List<HTML> lines = lines(wholeReport(new ReportOptions(ChangeLevel.IMPLEMENTATION), false));
        assertContains(line('+', "  ", tree("├── "), code("void added() {")), lines);
        assertContains(line('+', "  ", tree("│   "), code("  run();")), lines);
        assertContains(line('-', "  ", tree("├── "), code(strike("int ", decl("gone"), "()"))), lines);
        assertEquals(markup(lines), 1, texts(lines).stream().filter(l -> l.contains("added()")).count());
        List<String> api = texts(lines(wholeReport(new ReportOptions(ChangeLevel.API),
                false)));
        assertFalse(api.toString(), api.stream().anyMatch(l -> l.contains("run();")));
    }
    /** Reports one level of the move of the top level {@code a.Color} into the new {@code b.Values}, which adds a member. */
    private static HTML.Tag movedTypeReport(ChangeLevel level) throws Exception {
        return movedTypeReport(level, new ReportOptions(ChangeLevel.IMPORTS));
    }

    private static HTML.Tag movedTypeReport(ChangeLevel level, ReportOptions options) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("a/Color.java", "package a;\npublic class Color {\n  public int r;\n"
                    + "  public int g() { return r; }\n}\n");
            helper.commit("init");
            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("a/Color.java"));
            helper.writeFile("b/Values.java", "package b;\npublic class Values {\n  public static class Color {\n"
                    + "    public int r;\n    public int g() { return r; }\n    public void b() { }\n  }\n"
                    + "  public int v;\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                return new Reporter(options).reportOnly(analyzer.analyzeChanges(), level);
            }
        }
    }

    /**
     * Tests that a type moved into an added type is shown in it by where it was and its changes, not as added, and
     * that the deleted file it was in is not listed.
     */
    public void testMovedTypeInApi() throws Exception {
        List<HTML> lines = lines(movedTypeReport(ChangeLevel.API));
        assertLines(List.of(
                heading("API changes"),
                blank(),
                fileTitle('+', name("Values.java"), " ", pkg("b")),
                line(' ', "  ", code("public class ", decl("Values"))),
                line('~', "  ", tree("├── "), code("public ", added("static"), " class ", strike("a.Color"), " ",
                        added(decl("Color")))),
                line('+', "  ", tree("│   "), tree("└── "), code("public void ", decl("b"), "()")),
                line(' ', "  ", tree("└── "), code("public int ", decl("v")))),
                lines);
        assertFalse(texts(lines).toString(), texts(lines).stream().anyMatch(l -> l.contains("Color.java")));
    }

    /** Tests that the whole source of an added type shows a type moved into it collapsed, with where it was. */
    public void testMovedTypeInImplementation() throws Exception {
        assertLines(List.of(
                heading("Implementation changes"),
                blank(),
                fileTitle('+', name("Values.java"), " ", pkg("b")),
                line('+', "  ", code("public class Values {")),
                line('+', "  ", code("  public static class Color {")),
                line(' ', "  ", code(note("\u2026 moved from a.Color (4 lines)"))),
                line('+', "  ", code("  public int v;")),
                line('+', "  ", code("}"))),
                movedTypeReport(ChangeLevel.IMPLEMENTATION));
    }

    /** For the page the source of a moved type is kept behind the note of the move, to be expanded there. */
    public void testMovedTypeExpandableInImplementation() throws Exception {
        assertLines(List.of(
                heading("Implementation changes"),
                blank(),
                fileTitle('+', name("Values.java"), " ", pkg("b")),
                line('+', "  ", code("public class Values {")),
                line('+', "  ", code("  public static class Color {")),
                HTML.DIV(
                        line(' ', "  ", code(note("\u2026 moved from a.Color (4 lines)"))),
                        line(' ', "  ", code("    public int r;")),
                        line(' ', "  ", code("    public int g() { return r; }")),
                        line(' ', "  ", code("    public void b() { }")),
                        line(' ', "  ", code("  }"))).clazz(Css.ELIDED.cssClass()),
                line('+', "  ", code("  public int v;")),
                line('+', "  ", code("}"))),
                movedTypeReport(ChangeLevel.IMPLEMENTATION,
                        new ReportOptions(ChangeLevel.IMPORTS).withFoldContext(true)));
    }
}
