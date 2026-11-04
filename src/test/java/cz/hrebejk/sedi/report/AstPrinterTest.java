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
                ReportOptions options = new ReportOptions(ChangeLevel.IMPLEMENTATION,
                        ReportOptions.Grouping.BY_TYPE, bodyLines);
                List<String> lines = new Reporter(Theme.PLAIN, options).report(analyzer.analyzeChanges());
                return lines.subList(2, lines.size()).stream().map(l -> l.substring(4)).toList();
            }
        }
    }

    static List<String> body(String oldMembers, String newMembers) throws Exception {
        return body(oldMembers, newMembers, ReportOptions.DEFAULT_BODY_LINES);
    }

    /**
     * The changed lines of the bodies printed in the default colors, the added text shown as {@code _text_}, the
     * struck through as {@code ~text~} and the other colors left out.
     */
    private static List<String> marked(String oldMembers, String newMembers) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "class Foo {\n" + oldMembers + "\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "class Foo {\n" + newMembers + "\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ReportOptions options = new ReportOptions(ChangeLevel.IMPLEMENTATION, ReportOptions.Grouping.BY_TYPE);
                return new Reporter(Theme.defaults(), options).report(analyzer.analyzeChanges()).stream()
                        .map(l -> l.replace("\u001b[4m", "_").replace("\u001b[24m", "_")
                                .replace("\u001b[9m", "~").replace("\u001b[29m", "~")
                                .replaceAll("\u001b\\[[0-9;]*m", "").strip())
                        .filter(l -> l.startsWith("return")).toList();
            }
        }
    }

    /** The whole report with only the unresolved names styled, shown as {@code !name!}. */
    private static List<String> errors(String oldMembers, String newMembers) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "import java.util.*;\nclass Foo {\n" + oldMembers + "\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "import java.util.*;\nclass Foo {\n" + newMembers + "\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                java.util.Map<String, String> specs = new java.util.HashMap<>();
                for (Theme.Role role : Theme.Role.values()) {
                    specs.put(role.name().toLowerCase(), "none");
                }
                specs.put("error", "bold");
                ReportOptions options = new ReportOptions(ChangeLevel.IMPLEMENTATION, ReportOptions.Grouping.BY_TYPE);
                return new Reporter(Theme.of(specs), options).report(analyzer.analyzeChanges()).stream()
                        .map(l -> l.replace("\u001b[1m", "!").replace("\u001b[22m", "!").strip()).toList();
            }
        }
    }

    /** Names javac could not resolve are marked as errors as a whole, in bodies and in signatures. */
    public void testUnresolvedNamesAreErrors() throws Exception {
        List<String> lines = errors("int m(int p) { return p; }\nint f = 1;",
                "int m(Missing p) { return Other.X + 2; }\nint f = undefined(1);");
        assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.contains("m( !Missing! p )")));
        assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.contains("!Other.X! + 2")));
        assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.contains("!undefined!(1)")));
    }

    /** Code which compiles has no errors, whatever kinds of names it uses. */
    public void testResolvedNamesAreNotErrors() throws Exception {
        List<String> lines = errors("int m(int[] a, Thread.State s) { return 0; }",
                "@SuppressWarnings(value = \"x\") int m(int[] a, Thread.State s) {\n"
                        + "  Class<?> c = Foo.class; int n = a.length + this.hashCode() + super.hashCode();\n"
                        + "  java.util.function.IntUnaryOperator op = x -> x + n;\n"
                        + "  outer: for (int i : a) { if (i > 0) break outer; }\n"
                        + "  switch (s) { case NEW: return List.of(1).size(); default: return Math.max(op.applyAsInt(1), 0); }\n"
                        + "}");
        assertTrue(lines.toString(), lines.stream().noneMatch(l -> l.contains("!")));
    }

    /** Code added around an expression is marked alone, the expression kept as it is, renamed locals too. */
    public void testWrappedExpressionMarksOnlyTheAddedCode() throws Exception {
        assertEquals(List.of("return _!_bool ? 1 : 0;", "return _f(_f(a) _+ b)_;", "return a + b _+ 1_;",
                        "return s.trim()_.toLowerCase()_;", "return a[i _+ 1_];"),
                marked("int yesno(boolean p) { return p ? 1 : 0; }\n"
                                + "int f(int x) { return x; }\n"
                                + "int call(int a, int b) { return f(a); }\n"
                                + "int sum(int a, int b) { return a + b; }\n"
                                + "String chain(String s) { return s.trim(); }\n"
                                + "int arr(int[] a, int i) { return a[i]; }",
                        "int yesno(boolean bool) { return !bool ? 1 : 0; }\n"
                                + "int f(int x) { return x; }\n"
                                + "int call(int a, int b) { return f(f(a) + b); }\n"
                                + "int sum(int a, int b) { return a + b + 1; }\n"
                                + "String chain(String s) { return s.trim().toLowerCase(); }\n"
                                + "int arr(int[] a, int i) { return a[i + 1]; }"));
    }

    /** A name or literal found by chance in a much bigger new expression is not kept: the whole is replaced. */
    public void testLeafInBigExpressionIsReplaced() throws Exception {
        assertEquals(List.of("~ int m( java.util.List<String> list ) [impl]",
                        "      - return 1;", "      + return list.size() > 1 ? list.size() * 2 : 0;",
                        "~ int n( int x ) [impl]", "      return __Math.max(__x__, 0)__;"),
                body("int m(java.util.List<String> list) { return 1; }\nint n(int x) { return x; }",
                        "int m(java.util.List<String> list) { return list.size() > 1 ? list.size() * 2 : 0; }\n"
                                + "int n(int x) { return Math.max(x, 0); }"));
    }

    /** A statement added around old statements shows only its own lines as added, the old statements as they are. */
    public void testWrappedStatements() throws Exception {
        assertEquals(List.of("~ void a( boolean c ) [impl]", "      + if (c) {", "        f(2);", "      + }",
                        "~ void b() [impl]", "      + try {", "        f(1);", "        f(2);",
                        "      + } catch (RuntimeException e) {", "        + f(9);", "      + }",
                        "~ void c( int n ) [impl]", "      + for (int i = 0; i < n; i++)", "        f(1);"),
                body("void f(int x) { }\nvoid a(boolean c) { f(1); f(2); f(3); }\nvoid b() { f(1); f(2); }\n"
                                + "void c(int n) { f(1); }",
                        "void f(int x) { }\nvoid a(boolean c) { f(1); if (c) { f(2); } f(3); }\n"
                                + "void b() { try { f(1); f(2); } catch (RuntimeException e) { f(9); } }\n"
                                + "void c(int n) { for (int i = 0; i < n; i++) f(1); }"));
    }

    /** A removed statement around statements kept shows only its own lines as removed, the kept ones as they are now. */
    public void testUnwrappedStatements() throws Exception {
        assertEquals(List.of("~ void a( boolean c, int q ) [impl]", "      - if (c) {", "        f(q);", "        f(2);",
                        "      - }"),
                body("void f(int x) { }\nvoid a(boolean c, int p) { f(0); if (c) { f(p); f(2); } f(3); }",
                        "void f(int x) { }\nvoid a(boolean c, int q) { f(0); f(q); f(2); f(3); }"));
    }

    /** Code removed around an expression is struck through alone, the rest of the line in one run. */
    public void testUnwrappedExpressionStrikesOnlyTheRemovedCode() throws Exception {
        assertEquals(List.of("~ boolean neg( boolean q ) [impl]", "      return ~~!~~q;",
                        "~ int paren( int a, int b ) [impl]", "      return ~~(~~a + b~~) * 2~~;"),
                body("boolean neg(boolean p) { return !p; }\nint paren(int a, int b) { return (a + b) * 2; }",
                        "boolean neg(boolean q) { return q; }\nint paren(int a, int b) { return a + b; }"));
    }

    public void testEditedLiteral() throws Exception {
        assertEquals(List.of("~ int m() [impl]", "      return ~~1~~ __2__;"),
                body("int m() { int a = 0; return 1; }", "int m() { int a = 0; return 2; }"));
    }

    public void testChangeInIfKeepsContext() throws Exception {
        assertEquals(List.of("~ int m( int x ) [impl]", "      if (x > 0) {", "        return ~~1~~ __2__;", "      }"),
                body("int m(int x) {\n  foo();\n  if (x > 0) {\n    bar();\n    return 1;\n  }\n  return 0;\n}\nvoid foo() {}\nvoid bar() {}",
                        "int m(int x) {\n  foo();\n  if (x > 0) {\n    bar();\n    return 2;\n  }\n  return 0;\n}\nvoid foo() {}\nvoid bar() {}"));
    }

    public void testAddedAndRemovedStatements() throws Exception {
        assertEquals(List.of("~ void m() [impl]", "      - a();", "      + b(\"done\");"),
                body("void m() { c(); a(); }\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}",
                        "void m() { c(); b(\"done\"); }\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}"));
    }

    public void testMultiLineAndCommentsCollapse() throws Exception {
        assertEquals(List.of("~ int m( int a, int b ) [impl]", "      return Math.max(a, ~~b~~ __3__);"),
                body("int m(int a, int b) { return Math.max(a, b); }",
                        "int m(int a, int b) {\n  return Math.max( // the bigger\n      a,\n      3);\n}"));
    }

    /** A member select whose qualifier and name both changed is struck through as a whole. */
    public void testOwnChangeOfNode() throws Exception {
        assertEquals(List.of("~ int m( String s ) [impl]", "      return ~~s.length~~ __t.hashCode__();"),
                body("String t;\nint m(String s) { return s.length(); }", "String t;\nint m(String s) { return t.hashCode(); }"));
    }

    public void testExtraArgumentReplacesStatement() throws Exception {
        assertEquals(List.of("~ void m() [impl]", "      - f(1);", "      + f(1, 2);"),
                body("void m() { f(1); }\nvoid f(int... a) {}", "void m() { f(1, 2); }\nvoid f(int... a) {}"));
    }

    /** Arguments replaced by expressions of another kind are struck through in place. */
    public void testArgumentsOfOtherKind() throws Exception {
        assertEquals(List.of("~ boolean m() [impl]",
                        "      return new java.util.ArrayList<String>(~~5~~ __Integer.MAX_VALUE__).add(~~\"a\"~~ __String.valueOf(1)__);"),
                body("boolean m() { return new java.util.ArrayList<String>(5).add(\"a\"); }",
                        "boolean m() { return new java.util.ArrayList<String>(Integer.MAX_VALUE).add(String.valueOf(1)); }"));
    }

    /** A big expression replaced by one of another kind replaces the statement. */
    public void testBigExpressionOfOtherKind() throws Exception {
        assertEquals(List.of("~ int m( boolean b, int x ) [impl]", "      - return b ? x + 1 : x - 1;",
                        "      + return Math.abs(x);"),
                body("int m(boolean b, int x) { return b ? x + 1 : x - 1; }",
                        "int m(boolean b, int x) { return Math.abs(x); }"));
    }

    public void testRenamedLocalsAndMethodsHaveNoDetails() throws Exception {
        assertTrue(body("int m() { int a = h(); return a; }\nint h() { return 1; }",
                "int m() { int b = g(); return b; }\nint g() { return 1; }").stream().noneMatch(l -> l.contains("[impl]")));
    }

    /** Context of lambda blocks, else branches and switch cases; a changed comment is not shown. */
    public void testNestedContexts() throws Exception {
        assertEquals(List.of("~ void m( java.util.List<String> l, int x ) [impl]",
                        "      l.forEach(s -> {", "        ~~System.out~~ __System.err__.println(s);", "      });",
                        "      if (x > 0) { … } else {", "        + f(6);", "      }",
                        "      switch (x) {", "        default:", "          f(~~4~~ __7__);", "      }"),
                body(
                "void m(java.util.List<String> l, int x) {\n  l.forEach(s -> {\n    System.out.println(s);\n  });\n  if (x > 0) {\n    f(1);\n  } else {\n    f(2);\n  }\n  switch (x) {\n    case 1: f(3); break;\n    default: f(4);\n  }\n  f(5); // five\n}\nvoid f(int i) {}",
                "void m(java.util.List<String> l, int x) {\n  l.forEach(s -> {\n    System.err.println(s);\n  });\n  if (x > 0) {\n    f(1);\n  } else {\n    f(2);\n    f(6);\n  }\n  switch (x) {\n    case 1: f(3); break;\n    default: f(7);\n  }\n  f(5); // FIVE\n}\nvoid f(int i) {}"));
    }

    public void testBigAddedBlockCollapses() throws Exception {
        assertEquals(List.of("~ void m( int[] xs ) [impl]", "      + for (int x : xs) { … 5 lines }"),
                body("void m(int[] xs) { }\nvoid f(int x) {}",
                        "void m(int[] xs) {\n  for (int x : xs) {\n    f(x);\n    f(x + 1);\n    f(x + 2);\n  }\n}\nvoid f(int x) {}"));
    }

    public void testConstructorSkipsImplicitSuper() throws Exception {
        assertEquals(List.of("~ Foo() [impl]", "      x = ~~1~~ __2__;"),
                body("int x;\nFoo() { x = 1; }", "int x;\nFoo() { x = 2; }"));
    }

    public void testRewrittenBody() throws Exception {
        assertEquals(List.of("~ void m() [impl]", "      body rewritten (-4 +4 statements)"),
                body("void m() { a(1); a(2); a(3); a(4); }\nvoid a(int i) {}\nvoid b(String s) {}",
                        "void m() { b(\"1\"); b(\"2\"); b(\"3\"); b(\"4\"); }\nvoid a(int i) {}\nvoid b(String s) {}"));
    }

    public void testCap() throws Exception {
        assertEquals(List.of("~ void m() [impl]", "      + a(1);", "      … 2 more lines"),
                body("void m() { }\nvoid a(int i) {}", "void m() { a(1); a(2); a(3); }\nvoid a(int i) {}", 1));
    }

    public void testNoBodyLines() throws Exception {
        assertEquals(List.of("~ int m() [impl]"), body("int m() { return 1; }", "int m() { return 2; }", 0));
    }
}
