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
import junit.framework.TestCase;
import org.maraboo.fleck.html.HTML;

import java.util.List;

import static cz.hrebejk.sedi.report.Expect.*;
import static cz.hrebejk.sedi.report.HtmlAssert.*;

/**
 * Tests of the section of the cosmetic changes, rendered like the implementation changes, and of the comments in the
 * section of the implementation changes.
 */
public class CosmeticReportTest extends TestCase {

    /** The section of the level for {@code Foo.java} changed as given, without the heading of the section and file. */
    private static List<HTML> section(String before, String after, ChangeLevel level) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", before);
            helper.commit("init");
            helper.writeFile("Foo.java", after);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                List<HTML> lines = lines(new Reporter(new ReportOptions(ChangeLevel.IMPORTS,
                        ReportOptions.Grouping.BY_CATEGORY)).reportOnly(analyzer.analyzeChanges(), level));
                // Without the heading of the section and the title of the file.
                return lines.subList(lines.size() > 4 ? 3 : 2, lines.size());
            }
        }
    }

    /** The example of renames in the notes of the project: the renamed locals and the uses of the renamed members. */
    public void testRenames() throws Exception {
        assertLines(List.of(
                line(' ', "  ", code("public class ", decl("Foo"))),
                line(' ', "  ", tree("├── "), code("static void ", decl("method"), "() {")),
                line('~', "  ", tree("│   "), "      ", code("int ", strike("l"), " ", added("sum"), " = 0;")),
                plain("    ", tree("│   "), "      "),
                line('~', "  ", tree("│   "), "      ", code("for(int ", strike("k"), " ", added("i"), " = 0; ",
                        strike("k"), " ", added("i"), " < ", strike("j"), " ", added("count"), "; ", strike("k"), " ",
                        added("i"), "++) {")),
                line('~', "  ", tree("│   "), "      ", code("    ", strike("l"), " ", added("sum"), " += ",
                        strike("k"), " ", added("i"), ";")),
                line(' ', "  ", tree("│   "), "      ", code("}")),
                line(' ', "  ", tree("│   "), code("}")),
                line(' ', "  ", tree("└── "), code("public static void ", decl("main"), "( String ... args ) {")),
                line('~', "            ", code(strike("m1"), " ", added("method"), "();")),
                line(' ', "      ", code("}"))),
                section("public class Foo {\n\n    static int j = 1;\n\n    static void m1() {\n\n        int l = 0;\n\n"
                                + "        for( int k = 0; k < j; k++) {\n            l += k;\n        }\n\n    }\n\n"
                                + "    public static void main(String ...args) {\n        m1();\n    }\n\n}\n",
                        "public class Foo {\n\n    static int count = 1;\n\n    static void method() {\n\n        int sum = 0;\n\n"
                                + "        for(int i = 0; i < count; i++) {\n            sum += i;\n        }\n\n\n    }\n\n"
                                + "    public static void main(String ...args) {\n        method();\n    }\n\n}\n",
                        ChangeLevel.COSMETIC));
    }

    /** A renamed parameter in the signature and the body; the rename of the method itself is not cosmetic. */
    public void testParameterRename() throws Exception {
        assertLines(List.of(
                line(' ', "  ", code("class ", decl("Foo"))),
                line('~', "  ", tree("└── "), code("void ", decl("method"), "( int ", strike("p"), " ", added("q"),
                        " ) {")),
                line('~', "            ", code("int l = j + ", strike("p"), " ", added("q"), ";")),
                line(' ', "      ", code("}"))),
                section("class Foo {\n  int j;\n  void m1(int p) { int l = j + p; }\n}\n",
                        "class Foo {\n  int j;\n  void method(int q) { int l = j + q; }\n}\n", ChangeLevel.COSMETIC));
    }

    /** Names qualified differently in a signature, a body and a field initializer. */
    public void testQualification() throws Exception {
        assertLines(List.of(
                line(' ', "  ", code("class ", decl("Foo"))),
                line('~', "  ", tree("├── "), code("int ", decl("m"), "( ", strike("java.util.List<String>"), " ",
                        added("List<String>"), " list ) {")),
                line('~', "  ", tree("│   "), "      ", code(strike("List"), " ", added("java.util.List"),
                        "<String> x = ", strike("java.util.List.of"), " ", added("List.of"), "();")),
                line(' ', "  ", tree("│   "), "      ", code("return list.size() + x.size();")),
                line(' ', "  ", tree("│   "), code("}")),
                line('~', "  ", tree("└── "), code("int ", decl("f"), " = ", strike("Math.max"), " ",
                        added("java.lang.Math.max"), "(1, 2)"))),
                section("import java.util.List;\nclass Foo {\n  int m(java.util.List<String> list) {\n"
                                + "    List<String> x = java.util.List.of();\n    return list.size() + x.size();\n  }\n"
                                + "  int f = Math.max(1, 2);\n}\n",
                        "import java.util.List;\nclass Foo {\n  int m(List<String> list) {\n"
                                + "    java.util.List<String> x = List.of();\n    return list.size() + x.size();\n  }\n"
                                + "  int f = java.lang.Math.max(1, 2);\n}\n", ChangeLevel.COSMETIC));
    }

    /** The references to a renamed type, in the signature and in the body. */
    public void testTypeRename() throws Exception {
        assertLines(List.of(
                line(' ', "  ", code("class ", decl("Foo"))),
                line('~', "  ", tree("└── "), code(strike("Old"), " ", added("Neu"), " ", decl("make"), "( ",
                        strike("Old"), " ", added("Neu"), " o ) {")),
                line('~', "            ", code(strike("Old"), " ", added("Neu"), " n = new ", strike("Old"), " ",
                        added("Neu"), "(); return n;")),
                line(' ', "      ", code("}"))),
                section("class Foo {\n  static class Old { int v; }\n  Old make(Old o) { Old n = new Old(); return n; }\n}\n",
                        "class Foo {\n  static class Neu { int v; }\n  Neu make(Neu o) { Neu n = new Neu(); return n; }\n}\n",
                        ChangeLevel.COSMETIC));
    }

    private static final String MIXED_OLD = "class Foo {\n  int m(int a) {\n    int t = a * 2;\n    if (t > 3) {\n"
            + "      return t + 1;\n    }\n    return 0;\n  }\n}\n";
    private static final String MIXED_NEW = "class Foo {\n  int m(int a) {\n    int total = a * 2;\n    if (total > 4) {\n"
            + "      return total + 1;\n    }\n    // nothing\n    return 0;\n  }\n}\n";

    /** A member with both kinds of changes: the cosmetic section shows only the renames, the code as it is now. */
    public void testMixedChangesInCosmeticSection() throws Exception {
        assertLines(List.of(
                line(' ', "  ", code("class ", decl("Foo"))),
                line(' ', "  ", tree("└── "), code("int ", decl("m"), "( int a ) {")),
                line('~', "            ", code("int ", strike("t"), " ", added("total"), " = a * 2;")),
                line('~', "            ", code("if (", strike("t"), " ", added("total"), " > 4) {")),
                line('~', "            ", code("  return ", strike("t"), " ", added("total"), " + 1;")),
                line(' ', "            ", code("}")),
                line(' ', "            ", code(comment("// nothing"))),
                line(' ', "            ", code("return 0;")),
                line(' ', "      ", code("}"))),
                section(MIXED_OLD, MIXED_NEW, ChangeLevel.COSMETIC));
    }

    /** A member with both kinds of changes: the implementation section shows the code and comment changes only. */
    public void testMixedChangesInImplementationSection() throws Exception {
        assertLines(List.of(
                line(' ', "  ", code("class ", decl("Foo"))),
                line(' ', "  ", tree("└── "), code("int ", decl("m"), "( int a ) {")),
                line(' ', "            ", code("int total = a * 2;")),
                line('~', "            ", code("if (total > ", strike("3"), " ", added("4"), ") {")),
                line(' ', "            ", code("  return total + 1;")),
                line(' ', "            ", code("}")),
                line('+', "            ", code(comment("// nothing"))),
                line(' ', "            ", code("return 0;")),
                line(' ', "      ", code("}"))),
                section(MIXED_OLD, MIXED_NEW, ChangeLevel.IMPLEMENTATION));
    }

    /** A removed comment: a line of its own if it was on its own line, else struck through where it was. */
    public void testRemovedComments() throws Exception {
        assertLines(List.of(
                line(' ', "  ", code("class ", decl("Foo"))),
                line(' ', "  ", tree("└── "), code("int ", decl("m"), "( int a ) {")),
                line('-', "            ", code(comment("// own line"))),
                line('~', "            ", code("int t = a; ", strike("// trailing"))),
                line(' ', "            ", code("return t;")),
                line(' ', "      ", code("}"))),
                section("class Foo {\n  int m(int a) {\n    // own line\n    int t = a; // trailing\n    return t;\n  }\n}\n",
                        "class Foo {\n  int m(int a) {\n    int t = a;\n    return t;\n  }\n}\n", ChangeLevel.IMPLEMENTATION));
    }

    /** Whitespace alone is no change at all. */
    public void testWhitespaceIsNoChange() throws Exception {
        String before = "class Foo {\n  int m(int a) { return a+1; }\n}\n";
        String after = "class Foo {\n  int m( int a )\n  {\n    return a + 1;\n  }\n}\n";
        assertLines(List.of(
                noChanges(),
                blank()),
                section(before, after, ChangeLevel.COSMETIC));
        assertLines(List.of(
                noChanges(),
                blank()),
                section(before, after, ChangeLevel.IMPLEMENTATION));
    }
}
