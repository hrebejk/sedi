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
 * Tests of the lines showing the changes of a method body, printed by {@link AstPrinter}.
 */
public class AstPrinterTest extends TestCase {

    /** The report of the implementation changes of the methods of {@code Foo}, without the headings. */
    static List<String> body(String oldMembers, String newMembers, int bodyLines) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "class Foo {\n" + oldMembers + "\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "class Foo {\n" + newMembers + "\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ReportOptions options = new ReportOptions(ChangeLevel.IMPLEMENTATION, false,
                        ReportOptions.Grouping.BY_TYPE, bodyLines);
                List<String> lines = new Reporter(Theme.PLAIN, options).report(analyzer.analyzeChanges());
                return lines.subList(2, lines.size()).stream().map(l -> l.substring(4)).toList();
            }
        }
    }

    static List<String> body(String oldMembers, String newMembers) throws Exception {
        return body(oldMembers, newMembers, ReportOptions.DEFAULT_BODY_LINES);
    }

    public void testEditedLiteral() throws Exception {
        assertEquals(List.of("  int m() [impl]", "      return ~~1~~ 2;"),
                body("int m() { int a = 0; return 1; }", "int m() { int a = 0; return 2; }"));
    }

    public void testChangeInIfKeepsContext() throws Exception {
        assertEquals(List.of("  int m( int x ) [impl]", "      if (x > 0) {", "        return ~~1~~ 2;", "      }"),
                body("int m(int x) {\n  foo();\n  if (x > 0) {\n    bar();\n    return 1;\n  }\n  return 0;\n}\nvoid foo() {}\nvoid bar() {}",
                        "int m(int x) {\n  foo();\n  if (x > 0) {\n    bar();\n    return 2;\n  }\n  return 0;\n}\nvoid foo() {}\nvoid bar() {}"));
    }

    public void testAddedAndRemovedStatements() throws Exception {
        assertEquals(List.of("  void m() [impl]", "      - a();", "      + b(\"done\");"),
                body("void m() { c(); a(); }\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}",
                        "void m() { c(); b(\"done\"); }\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}"));
    }

    public void testMultiLineAndCommentsCollapse() throws Exception {
        assertEquals(List.of("  int m( int a, int b ) [impl]", "      return Math.max(a, ~~b~~ 3);"),
                body("int m(int a, int b) { return Math.max(a, b); }",
                        "int m(int a, int b) {\n  return Math.max( // the bigger\n      a,\n      3);\n}"));
    }

    /** A member select whose qualifier and name both changed is struck through as a whole. */
    public void testOwnChangeOfNode() throws Exception {
        assertEquals(List.of("  int m( String s ) [impl]", "      return ~~s.length~~ t.hashCode();"),
                body("String t;\nint m(String s) { return s.length(); }", "String t;\nint m(String s) { return t.hashCode(); }"));
    }

    public void testExtraArgumentReplacesStatement() throws Exception {
        assertEquals(List.of("  void m() [impl]", "      - f(1);", "      + f(1, 2);"),
                body("void m() { f(1); }\nvoid f(int... a) {}", "void m() { f(1, 2); }\nvoid f(int... a) {}"));
    }

    public void testRenamedLocalsAndMethodsHaveNoDetails() throws Exception {
        assertTrue(body("int m() { int a = h(); return a; }\nint h() { return 1; }",
                "int m() { int b = g(); return b; }\nint g() { return 1; }").stream().noneMatch(l -> l.contains("[impl]")));
    }

    /** Context of lambda blocks, else branches and switch cases; a changed comment is not shown. */
    public void testNestedContexts() throws Exception {
        assertEquals(List.of("  void m( java.util.List<String> l, int x ) [impl]",
                        "      l.forEach(s -> {", "        ~~System.out~~ System.err.println(s);", "      });",
                        "      if (x > 0) { … } else {", "        + f(6);", "      }",
                        "      switch (x) {", "        default:", "          f(~~4~~ 7);", "      }"),
                body(
                "void m(java.util.List<String> l, int x) {\n  l.forEach(s -> {\n    System.out.println(s);\n  });\n  if (x > 0) {\n    f(1);\n  } else {\n    f(2);\n  }\n  switch (x) {\n    case 1: f(3); break;\n    default: f(4);\n  }\n  f(5); // five\n}\nvoid f(int i) {}",
                "void m(java.util.List<String> l, int x) {\n  l.forEach(s -> {\n    System.err.println(s);\n  });\n  if (x > 0) {\n    f(1);\n  } else {\n    f(2);\n    f(6);\n  }\n  switch (x) {\n    case 1: f(3); break;\n    default: f(7);\n  }\n  f(5); // FIVE\n}\nvoid f(int i) {}"));
    }

    public void testBigAddedBlockCollapses() throws Exception {
        assertEquals(List.of("  void m( int[] xs ) [impl]", "      + for (int x : xs) { … 5 lines }"),
                body("void m(int[] xs) { }\nvoid f(int x) {}",
                        "void m(int[] xs) {\n  for (int x : xs) {\n    f(x);\n    f(x + 1);\n    f(x + 2);\n  }\n}\nvoid f(int x) {}"));
    }

    public void testConstructorSkipsImplicitSuper() throws Exception {
        assertEquals(List.of("  Foo() [impl]", "      x = ~~1~~ 2;"),
                body("int x;\nFoo() { x = 1; }", "int x;\nFoo() { x = 2; }"));
    }

    public void testRewrittenBody() throws Exception {
        assertEquals(List.of("  void m() [impl]", "      body rewritten (-4 +4 statements)"),
                body("void m() { a(1); a(2); a(3); a(4); }\nvoid a(int i) {}\nvoid b(String s) {}",
                        "void m() { b(\"1\"); b(\"2\"); b(\"3\"); b(\"4\"); }\nvoid a(int i) {}\nvoid b(String s) {}"));
    }

    public void testCap() throws Exception {
        assertEquals(List.of("  void m() [impl]", "      + a(1);", "      … 2 more lines"),
                body("void m() { }\nvoid a(int i) {}", "void m() { a(1); a(2); a(3); }\nvoid a(int i) {}", 1));
    }

    public void testNoBodyLines() throws Exception {
        assertEquals(List.of("  int m() [impl]"), body("int m() { return 1; }", "int m() { return 2; }", 0));
    }
}
