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
    static List<String> body(String oldMembers, String newMembers, int bodyContext) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "class Foo {\n" + oldMembers + "\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "class Foo {\n" + newMembers + "\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ReportOptions options = new ReportOptions(ChangeLevel.IMPLEMENTATION,
                        ReportOptions.Grouping.BY_TYPE, bodyContext);
                List<String> lines = new Reporter(Theme.PLAIN, options).report(analyzer.analyzeChanges());
                // Keeps the column of the markers, leaves out the indentation of the top level type.
                return lines.subList(2, lines.size()).stream().map(l -> l.substring(0, 2) + l.substring(4)).toList();
            }
        }
    }

    static List<String> body(String oldMembers, String newMembers) throws Exception {
        return body(oldMembers, newMembers, ReportOptions.DEFAULT_BODY_CONTEXT);
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
                                .replaceAll("\u001b\\[[0-9;]*m", "")
                                // The marker of the line, then its code without the lines of the tree.
                                .replaceAll("[\u2502\u251c\u2514\u2500]", "").replaceAll("^([-+~])\\s+", "$1 ").strip())
                        .filter(l -> l.startsWith("~ return")).toList();
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

    /** Lines of a method, two spaces indented, joined into its source. */
    private static String method(String declaration, String... body) {
        return declaration + " {\n" + java.util.Arrays.stream(body).map(l -> "  " + l + "\n")
                .collect(java.util.stream.Collectors.joining()) + "}";
    }

    /** Code added around an expression is marked alone, the expression kept as it is, renamed locals too. */
    public void testWrappedExpressionMarksOnlyTheAddedCode() throws Exception {
        assertEquals(List.of("~ return _!_bool ? 1 : 0;", "~ return _f(_f(a) _+ b)_;", "~ return a + b _+ 1_;",
                        "~ return s.trim()_.toLowerCase()_;", "~ return a[i _+ 1_];"),
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
        assertEquals(List.of(
                        "  ├── int m( java.util.List<String> list ) { [impl]",
                        "- │         return 1;",
                        "+ │         return list.size() > 1 ? list.size() * 2 : 0;",
                        "  │   }",
                        "  └── int n( int x ) { [impl]",
                        "~           return __Math.max(__x__, 0)__;",
                        "      }"),
                body(method("int m(java.util.List<String> list)", "return 1;") + "\n" + method("int n(int x)", "return x;"),
                        method("int m(java.util.List<String> list)", "return list.size() > 1 ? list.size() * 2 : 0;")
                                + "\n" + method("int n(int x)", "return Math.max(x, 0);")));
    }

    /** A statement added around old statements shows only its own lines as added, the old statements as they are. */
    public void testWrappedStatements() throws Exception {
        assertEquals(List.of(
                        "  ├── void a( boolean c ) { [impl]",
                        "  │         f(1);",
                        "+ │         if (c) {",
                        "  │           f(2);",
                        "+ │         }",
                        "  │         f(3);",
                        "  │   }",
                        "  └── void b() { [impl]",
                        "+           try {",
                        "              f(1);",
                        "              f(2);",
                        "+           } catch (RuntimeException e) {",
                        "+             f(9);",
                        "+           }",
                        "      }"),
                body("void f(int x) { }\n" + method("void a(boolean c)", "f(1);", "f(2);", "f(3);") + "\n"
                                + method("void b()", "f(1);", "f(2);"),
                        "void f(int x) { }\n" + method("void a(boolean c)", "f(1);", "if (c) {", "  f(2);", "}", "f(3);")
                                + "\n" + method("void b()", "try {", "  f(1);", "  f(2);", "} catch (RuntimeException e) {",
                                "  f(9);", "}")));
    }

    /** Statements on one line are shown as that line, the added ones marked in it. */
    public void testWrappedStatementsOnOneLine() throws Exception {
        assertEquals(List.of(
                        "  └── void c( int n ) { [impl]",
                        "~           __for (int i = 0; i < n; i++)__ f(1);",
                        "      }"),
                body("void f(int x) { }\nvoid c(int n) { f(1); }",
                        "void f(int x) { }\nvoid c(int n) { for (int i = 0; i < n; i++) f(1); }"));
    }

    /** A removed statement around statements kept shows only its own lines as removed, the kept ones as they are now. */
    public void testUnwrappedStatements() throws Exception {
        assertEquals(List.of(
                        "  └── void a( boolean c, int q ) { [impl]",
                        "            f(0);",
                        "-           if (c) {",
                        "            f(q);",
                        "            f(2);",
                        "-           }",
                        "            f(3);",
                        "      }"),
                body("void f(int x) { }\n" + method("void a(boolean c, int p)", "f(0);", "if (c) {", "  f(p);", "  f(2);", "}", "f(3);"),
                        "void f(int x) { }\n" + method("void a(boolean c, int q)", "f(0);", "f(q);", "f(2);", "f(3);")));
    }

    /** Code removed around an expression is struck through alone, the rest of the line in one run. */
    public void testUnwrappedExpressionStrikesOnlyTheRemovedCode() throws Exception {
        assertEquals(List.of(
                        "  ├── boolean neg( boolean q ) { [impl]",
                        "~ │         return ~~!~~q;",
                        "  │   }",
                        "  └── int paren( int a, int b ) { [impl]",
                        "~           return ~~(~~a + b~~) * 2~~;",
                        "      }"),
                body("boolean neg(boolean p) { return !p; }\nint paren(int a, int b) { return (a + b) * 2; }",
                        "boolean neg(boolean q) { return q; }\nint paren(int a, int b) { return a + b; }"));
    }

    public void testEditedLiteral() throws Exception {
        assertEquals(List.of(
                        "  └── int m() { [impl]",
                        "            int a = 0;",
                        "~           return ~~1~~ __2__;",
                        "      }"),
                body(method("int m()", "int a = 0;", "return 1;"), method("int m()", "int a = 0;", "return 2;")));
    }

    /** The lines of the statements holding a change are shown, the skipped lines as a remark with their number. */
    public void testChangeInIfKeepsContext() throws Exception {
        assertEquals(List.of(
                        "  └── int m( int x ) { [impl]",
                        "            … (1 line)",
                        "            if (x > 0) {",
                        "            … (1 line)",
                        "~             return ~~1~~ __2__;",
                        "            }",
                        "            … (1 line)",
                        "      }"),
                body(method("int m(int x)", "foo();", "if (x > 0) {", "  bar();", "  return 1;", "}", "return 0;")
                                + "\nvoid foo() {}\nvoid bar() {}",
                        method("int m(int x)", "foo();", "if (x > 0) {", "  bar();", "  return 2;", "}", "return 0;")
                                + "\nvoid foo() {}\nvoid bar() {}", 0));
    }

    public void testAddedAndRemovedStatements() throws Exception {
        assertEquals(List.of(
                        "  └── void m() { [impl]",
                        "            c();",
                        "-           a();",
                        "+           b(\"done\");",
                        "      }"),
                body(method("void m()", "c();", "a();") + "\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}",
                        method("void m()", "c();", "b(\"done\");") + "\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}"));
    }

    /** Statements on one line are shown as that line, the removed ones struck through, the added marked. */
    public void testAddedAndRemovedStatementsOnOneLine() throws Exception {
        assertEquals(List.of(
                        "  └── void m() { [impl]",
                        "~           c(); ~~a();~~ __b(\"done\");__",
                        "      }"),
                body("void m() { c(); a(); }\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}",
                        "void m() { c(); b(\"done\"); }\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}"));
    }

    /** A statement on several lines is shown on its lines, with the comments, the tabs expanded. */
    public void testMultiLineStatement() throws Exception {
        assertEquals(List.of(
                        "  └── int m( int a, int b ) { [impl]",
                        "            return Math.max( // the bigger",
                        "                    a,",
                        "~                   ~~b~~ __3__);",
                        "      }"),
                body("int m(int a, int b) { return Math.max(a, b); }",
                        "int m(int a, int b) {\n\treturn Math.max( // the bigger\n\t\t\ta,\n\t\t\t3);\n}"));
    }

    /** A member select whose qualifier and name both changed is struck through as a whole. */
    public void testOwnChangeOfNode() throws Exception {
        assertEquals(List.of(
                        "  └── int m( String s ) { [impl]",
                        "~           return ~~s.length~~ __t.hashCode__();",
                        "      }"),
                body("String t;\nint m(String s) { return s.length(); }", "String t;\nint m(String s) { return t.hashCode(); }"));
    }

    /** An added argument is marked inline with its comma, the other lines of the call shown unchanged. */
    public void testAddedArgument() throws Exception {
        assertEquals(List.of(
                        "  └── void m() { [impl]",
                        "~           f(1__, 2__, 3,",
                        "                    4, 5);",
                        "      }"),
                body(method("void m()", "f(1, 3,", "        4, 5);") + "\nvoid f(int... a) {}",
                        method("void m()", "f(1, 2, 3,", "        4, 5);") + "\nvoid f(int... a) {}"));
    }

    /** An argument added in front of one which differs only in the name of a local is the one marked. */
    public void testAddedArgumentLikeAnother() throws Exception {
        assertEquals(List.of(
                        "  └── void m( String a, String b ) { [impl]",
                        "~           f(1__, a.length()__, b.length());",
                        "      }"),
                body(method("void m(String a, String b)", "f(1, b.length());") + "\nvoid f(int... x) {}",
                        method("void m(String a, String b)", "f(1, a.length(), b.length());") + "\nvoid f(int... x) {}"));
    }

    /** A removed argument is struck through with its comma. */
    public void testRemovedArguments() throws Exception {
        assertEquals(List.of(
                        "  └── void m() { [impl]",
                        "~           f(1, ~~2,~~ 3);",
                        "~           f(1, 2~~, 3~~);",
                        "      }"),
                body(method("void m()", "f(1, 2, 3);", "f(1, 2, 3);") + "\nvoid f(int... a) {}",
                        method("void m()", "f(1, 3);", "f(1, 2);") + "\nvoid f(int... a) {}"));
    }

    /** Arguments replaced by expressions of another kind are struck through in place. */
    public void testArgumentsOfOtherKind() throws Exception {
        assertEquals(List.of(
                        "  └── boolean m() { [impl]",
                        "~           return new java.util.ArrayList<String>(~~5~~ __Integer.MAX_VALUE__).add(~~\"a\"~~ __String.valueOf(1)__);",
                        "      }"),
                body("boolean m() { return new java.util.ArrayList<String>(5).add(\"a\"); }",
                        "boolean m() { return new java.util.ArrayList<String>(Integer.MAX_VALUE).add(String.valueOf(1)); }"));
    }

    /** A big expression replaced by one of another kind replaces the statement. */
    public void testBigExpressionOfOtherKind() throws Exception {
        assertEquals(List.of(
                        "  └── int m( boolean b, int x ) { [impl]",
                        "-           return b ? x + 1 : x - 1;",
                        "+           return Math.abs(x);",
                        "      }"),
                body(method("int m(boolean b, int x)", "return b ? x + 1 : x - 1;"),
                        method("int m(boolean b, int x)", "return Math.abs(x);")));
    }

    public void testRenamedLocalsAndMethodsHaveNoDetails() throws Exception {
        assertTrue(body("int m() { int a = h(); return a; }\nint h() { return 1; }",
                "int m() { int b = g(); return b; }\nint g() { return 1; }").stream().noneMatch(l -> l.contains("[impl]")));
    }

    /** Context of lambda blocks, else branches and switch cases; a changed comment is not shown. */
    public void testNestedContexts() throws Exception {
        assertEquals(List.of(
                        "  └── void m( java.util.List<String> l, int x ) { [impl]",
                        "            l.forEach(s -> {",
                        "~             ~~System.out~~ __System.err__.println(s);",
                        "            });",
                        "            if (x > 0) {",
                        "            … (1 line)",
                        "            } else {",
                        "            … (1 line)",
                        "+             f(6);",
                        "            }",
                        "            switch (x) {",
                        "            … (1 line)",
                        "~             default: f(~~4~~ __7__);",
                        "            }",
                        "            … (1 line)",
                        "      }"),
                body(
                "void m(java.util.List<String> l, int x) {\n  l.forEach(s -> {\n    System.out.println(s);\n  });\n  if (x > 0) {\n    f(1);\n  } else {\n    f(2);\n  }\n  switch (x) {\n    case 1: f(3); break;\n    default: f(4);\n  }\n  f(5); // five\n}\nvoid f(int i) {}",
                "void m(java.util.List<String> l, int x) {\n  l.forEach(s -> {\n    System.err.println(s);\n  });\n  if (x > 0) {\n    f(1);\n  } else {\n    f(2);\n    f(6);\n  }\n  switch (x) {\n    case 1: f(3); break;\n    default: f(7);\n  }\n  f(5); // FIVE\n}\nvoid f(int i) {}", 0));
    }

    public void testBigAddedBlockIsShown() throws Exception {
        assertEquals(List.of(
                        "  └── void m( int[] xs ) { [impl]",
                        "+           for (int x : xs) {",
                        "+             f(x);",
                        "+             f(x + 1);",
                        "+             f(x + 2);",
                        "+           }",
                        "      }"),
                body("void m(int[] xs) { }\nvoid f(int x) {}",
                        method("void m(int[] xs)", "for (int x : xs) {", "  f(x);", "  f(x + 1);", "  f(x + 2);", "}")
                                + "\nvoid f(int x) {}"));
    }

    /**
     * A member whose signature did not change has no marker; a field initializer shown on the line of its declaration
     * marks the line with its change.
     */
    public void testMarkerOfDeclaration() throws Exception {
        assertEquals(List.of(
                        "~ ├── int f = ~~1~~ __2__ [impl]",
                        "  └── int m() { [impl]",
                        "~           return ~~1~~ __2__;",
                        "      }"),
                body("int f = 1;\nint m() { return 1; }", "int f = 2;\nint m() { return 2; }"));
    }

    public void testConstructorSkipsImplicitSuper() throws Exception {
        assertEquals(List.of(
                        "  └── Foo() { [impl]",
                        "~           x = ~~1~~ __2__;",
                        "      }"),
                body("int x;\nFoo() { x = 1; }", "int x;\nFoo() { x = 2; }"));
    }

    public void testRewrittenBodyIsShown() throws Exception {
        assertEquals(List.of(
                        "  └── void m() { [impl]",
                        "-           a(1);",
                        "-           a(2);",
                        "+           int x = 1;",
                        "+           b(\"x\" + x);",
                        "      }"),
                body("void m() {\n  a(1);\n  a(2);\n}\nvoid a(int i) {}\nvoid b(String s) {}",
                        method("void m()", "int x = 1;", "b(\"x\" + x);") + "\nvoid a(int i) {}\nvoid b(String s) {}"));
    }

    public void testUnrelatedStatementIsReplaced() throws Exception {
        assertEquals(List.of(
                        "  └── int m( String s ) { [impl]",
                        "-           int n = s.length();",
                        "+           StringBuilder n = new StringBuilder();",
                        "~           return n__.length()__;",
                        "      }"),
                body(method("int m(String s)", "int n = s.length();", "return n;"),
                        method("int m(String s)", "StringBuilder n = new StringBuilder();", "return n.length();")));
    }

    /** A run of unchanged lines longer than the context is a remark with their number, a shorter one is shown. */
    public void testLongUnchangedRunIsCollapsed() throws Exception {
        String old = method("void m()", "a(1);", "a(2);", "a(3);", "a(4);", "a(5);", "a(6);", "a(7);", "a(8);")
                + "\nvoid a(int i) {}";
        String now = method("void m()", "a(1);", "a(2);", "a(3);", "a(4);", "a(5);", "a(60);", "a(7);", "a(8);")
                + "\nvoid a(int i) {}";
        assertEquals(List.of(
                        "  └── void m() { [impl]",
                        "            … (5 lines)",
                        "~           a(~~6~~ __60__);",
                        "            a(7);",
                        "            a(8);",
                        "      }"),
                body(old, now));
    }

    /** A negative context shows the whole body. */
    public void testWholeBody() throws Exception {
        assertEquals(List.of(
                        "  └── void m() { [impl]",
                        "            a(1);",
                        "            a(2);",
                        "            a(3);",
                        "            a(4);",
                        "            a(5);",
                        "~           a(~~6~~ __60__);",
                        "      }"),
                body(method("void m()", "a(1);", "a(2);", "a(3);", "a(4);", "a(5);", "a(6);") + "\nvoid a(int i) {}",
                        method("void m()", "a(1);", "a(2);", "a(3);", "a(4);", "a(5);", "a(60);") + "\nvoid a(int i) {}",
                        ReportOptions.WHOLE_BODY));
    }

    /** The lines of the body are numbered with the lines of the source: the removed ones with the old lines. */
    public void testBodyLineNumbers() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "class Foo {\n" + method("  void m()", "  a(1);", "  a(2);", "  a(3);")
                    + "\n  void a(int i) {}\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "class Foo {\n\n" + method("  void m()", "  a(1);", "  a(3);", "  a(4);", "  a(5);")
                    + "\n  void a(int i) {}\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ReportOptions options = new ReportOptions(ChangeLevel.IMPLEMENTATION, ReportOptions.Grouping.BY_TYPE,
                        ReportOptions.DEFAULT_BODY_CONTEXT, ReportOptions.NO_JAVADOC, true);
                assertEquals(List.of(
                        "~ Foo.java",
                        "1     class Foo",
                        "3     └── void m() { [impl]",
                        "4               a(1);",
                        "4 -             a(2);",
                        "5               a(3);",
                        "6 +             a(4);",
                        "7 +             a(5);",
                        "8         }"), new Reporter(Theme.PLAIN, options).report(analyzer.analyzeChanges()));
            }
        }
    }
}
