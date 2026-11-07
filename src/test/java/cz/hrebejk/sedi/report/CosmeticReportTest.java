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

import java.util.List;

/**
 * Tests of the section of the cosmetic changes, rendered like the implementation changes, and of the comments in the
 * section of the implementation changes.
 */
public class CosmeticReportTest extends TestCase {

    /** The section of the level for {@code Foo.java} changed as given, without the heading of the section and file. */
    private static List<String> section(String before, String after, ChangeLevel level) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", before);
            helper.commit("init");
            helper.writeFile("Foo.java", after);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                List<String> lines = new Reporter(Theme.PLAIN, new ReportOptions(ChangeLevel.IMPORTS,
                        ReportOptions.Grouping.BY_CATEGORY)).reportOnly(analyzer.analyzeChanges(), level);
                // Without the heading of the section and the title of the file.
                return lines.subList(lines.size() > 4 ? 3 : 2, lines.size()).stream().map(String::stripTrailing).toList();
            }
        }
    }

    /** The example of renames in the notes of the project: the renamed locals and the uses of the renamed members. */
    public void testRenames() throws Exception {
        assertEquals(List.of(
                        "    public class Foo",
                        "    ├── static void method() {",
                        "~   │         int ~~l~~ __sum__ = 0;",
                        "    │",
                        "~   │         for(int ~~k~~ __i__ = 0; ~~k~~ __i__ < ~~j~~ __count__; ~~k~~ __i__++) {",
                        "~   │             ~~l~~ __sum__ += ~~k~~ __i__;",
                        "    │         }",
                        "    │   }",
                        "    └── public static void main( String ... args ) {",
                        "~             ~~m1~~ __method__();",
                        "        }"),
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
        assertEquals(List.of(
                        "    class Foo",
                        "~   └── void method( int ~~p~~ __q__ ) {",
                        "~             int l = j + ~~p~~ __q__;",
                        "        }"),
                section("class Foo {\n  int j;\n  void m1(int p) { int l = j + p; }\n}\n",
                        "class Foo {\n  int j;\n  void method(int q) { int l = j + q; }\n}\n", ChangeLevel.COSMETIC));
    }

    /** Names qualified differently in a signature, a body and a field initializer. */
    public void testQualification() throws Exception {
        assertEquals(List.of(
                        "    class Foo",
                        "~   ├── int m( ~~java.util.List<String>~~ __List<String>__ list ) {",
                        "~   │         ~~List~~ __java.util.List__<String> x = ~~java.util.List.of~~ __List.of__();",
                        "    │         return list.size() + x.size();",
                        "    │   }",
                        "~   └── int f = ~~Math.max~~ __java.lang.Math.max__(1, 2)"),
                section("import java.util.List;\nclass Foo {\n  int m(java.util.List<String> list) {\n"
                                + "    List<String> x = java.util.List.of();\n    return list.size() + x.size();\n  }\n"
                                + "  int f = Math.max(1, 2);\n}\n",
                        "import java.util.List;\nclass Foo {\n  int m(List<String> list) {\n"
                                + "    java.util.List<String> x = List.of();\n    return list.size() + x.size();\n  }\n"
                                + "  int f = java.lang.Math.max(1, 2);\n}\n", ChangeLevel.COSMETIC));
    }

    /** The references to a renamed type, in the signature and in the body. */
    public void testTypeRename() throws Exception {
        assertEquals(List.of(
                        "    class Foo",
                        "~   └── ~~Old~~ __Neu__ make( ~~Old~~ __Neu__ o ) {",
                        "~             ~~Old~~ __Neu__ n = new ~~Old~~ __Neu__(); return n;",
                        "        }"),
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
        assertEquals(List.of(
                        "    class Foo",
                        "    └── int m( int a ) {",
                        "~             int ~~t~~ __total__ = a * 2;",
                        "~             if (~~t~~ __total__ > 4) {",
                        "~               return ~~t~~ __total__ + 1;",
                        "              }",
                        "              // nothing",
                        "              return 0;",
                        "        }"),
                section(MIXED_OLD, MIXED_NEW, ChangeLevel.COSMETIC));
    }

    /** A member with both kinds of changes: the implementation section shows the code and comment changes only. */
    public void testMixedChangesInImplementationSection() throws Exception {
        assertEquals(List.of(
                        "    class Foo",
                        "    └── int m( int a ) {",
                        "              int total = a * 2;",
                        "~             if (total > ~~3~~ __4__) {",
                        "                return total + 1;",
                        "              }",
                        "+             // nothing",
                        "              return 0;",
                        "        }"),
                section(MIXED_OLD, MIXED_NEW, ChangeLevel.IMPLEMENTATION));
    }

    /** A removed comment: a line of its own if it was on its own line, else struck through where it was. */
    public void testRemovedComments() throws Exception {
        assertEquals(List.of(
                        "    class Foo",
                        "    └── int m( int a ) {",
                        "-             // own line",
                        "~             int t = a; ~~// trailing~~",
                        "              return t;",
                        "        }"),
                section("class Foo {\n  int m(int a) {\n    // own line\n    int t = a; // trailing\n    return t;\n  }\n}\n",
                        "class Foo {\n  int m(int a) {\n    int t = a;\n    return t;\n  }\n}\n", ChangeLevel.IMPLEMENTATION));
    }

    /** Whitespace alone is no change at all. */
    public void testWhitespaceIsNoChange() throws Exception {
        String before = "class Foo {\n  int m(int a) { return a+1; }\n}\n";
        String after = "class Foo {\n  int m( int a )\n  {\n    return a + 1;\n  }\n}\n";
        assertEquals(List.of("  NO CHANGES", ""), section(before, after, ChangeLevel.COSMETIC));
        assertEquals(List.of("  NO CHANGES", ""), section(before, after, ChangeLevel.IMPLEMENTATION));
    }
}
