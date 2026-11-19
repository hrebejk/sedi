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
 * Tests of the lines showing the changes of a method body, printed by {@link AstPrinter}.
 */
public class AstPrinterTest extends TestCase {

    /** The report of the implementation changes of the methods of {@code Foo}. */
    static List<HTML> body(String oldMembers, String newMembers, int bodyContext) throws Exception {
        return body(oldMembers, newMembers, new ReportOptions(ChangeLevel.IMPLEMENTATION, bodyContext));
    }

    private static List<HTML> body(String oldMembers, String newMembers, ReportOptions options) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "class Foo {\n" + oldMembers + "\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "class Foo {\n" + newMembers + "\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                List<HTML> lines = lines(new Reporter(options).report(analyzer.analyzeChanges().files().getFirst()));
                // Without the line of the top level type.
                return lines.subList(1, lines.size());
            }
        }
    }

    static List<HTML> body(String oldMembers, String newMembers) throws Exception {
        return body(oldMembers, newMembers, ReportOptions.DEFAULT_BODY_CONTEXT);
    }

    /** The changed lines of the bodies starting with {@code return}. */
    private static List<HTML> marked(String oldMembers, String newMembers) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "class Foo {\n" + oldMembers + "\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "class Foo {\n" + newMembers + "\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ReportOptions options = new ReportOptions(ChangeLevel.IMPLEMENTATION);
                return lines(new Reporter(options).report(analyzer.analyzeChanges().files().getFirst())).stream()
                        .filter(l -> l instanceof HTML.Tag t && Css.CHANGE_LINE.on(t)
                                && t.getInnerText().substring(1).strip().replaceAll("^[\u2502\u251c\u2514\u2500 ]+", "")
                                .startsWith("return"))
                        .toList();
            }
        }
    }

    /** The lines of the report of the file. */
    private static List<HTML> errors(String oldMembers, String newMembers) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "import java.util.*;\nclass Foo {\n" + oldMembers + "\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "import java.util.*;\nclass Foo {\n" + newMembers + "\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ReportOptions options = new ReportOptions(ChangeLevel.IMPLEMENTATION);
                return lines(new Reporter(options).report(analyzer.analyzeChanges().files().getFirst()));
            }
        }
    }

    /** Names javac could not resolve are marked as errors as a whole, in bodies and in signatures. */
    public void testUnresolvedNamesAreErrors() throws Exception {
        List<HTML> lines = errors("int m(int p) { return p; }\nint f = 1;",
                "int m(Missing p) { return Other.X + 2; }\nint f = undefined(1);");
        assertContains(line(' ', "  ", tree("\u251c\u2500\u2500 "), code("int ", decl("m"), "( ", error("Missing"),
                " p ) { ", tag("impl"))), lines);
        assertContains(line('~', "  ", tree("\u2502   "), "      ", code("return ", strike("p"), " ",
                added(error("Other.X")), " ", added("+ 2"), ";")), lines);
        assertContains(line('~', "  ", tree("\u2514\u2500\u2500 "), code("int ", decl("f"), " = ",
                added(error("undefined")), added("("), "1", added(")"), " ", tag("impl"))), lines);
    }

    /** Code which compiles has no errors, whatever kinds of names it uses. */
    public void testResolvedNamesAreNotErrors() throws Exception {
        List<HTML> lines = errors("int m(int[] a, Thread.State s) { return 0; }",
                "@SuppressWarnings(value = \"x\") int m(int[] a, Thread.State s) {\n"
                        + "  Class<?> c = Foo.class; int n = a.length + this.hashCode() + super.hashCode();\n"
                        + "  java.util.function.IntUnaryOperator op = x -> x + n;\n"
                        + "  outer: for (int i : a) { if (i > 0) break outer; }\n"
                        + "  switch (s) { case NEW: return List.of(1).size(); default: return Math.max(op.applyAsInt(1), 0); }\n"
                        + "}");
        assertFalse(markup(lines), hasClass(lines, Css.ERROR));
    }

    /** Lines of a method, two spaces indented, joined into its source. */
    private static String method(String declaration, String... body) {
        return declaration + " {\n" + java.util.Arrays.stream(body).map(l -> "  " + l + "\n")
                .collect(java.util.stream.Collectors.joining()) + "}";
    }

    /** Code added around an expression is marked alone, the expression kept as it is, renamed locals too. */
    public void testWrappedExpressionMarksOnlyTheAddedCode() throws Exception {
        assertLines(List.of(
                line('~', "  ", tree("│   "), "      ", code("return ", added("!"), renamed("p", "bool", "bool"), " ? 1 : 0;")),
                line('~', "  ", tree("│   "), "      ", code("return ", added("f("), "f(a) ", added("+ b)"), ";")),
                line('~', "  ", tree("│   "), "      ", code("return a + b ", added("+ 1"), ";")),
                line('~', "  ", tree("│   "), "      ", code("return s.trim()", added(".toLowerCase()"), ";")),
                line('~', "            ", code("return a[i ", added("+ 1"), "];"))),
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
        assertLines(List.of(
                line(' ', "  ", tree("├── "), code("int ", decl("m"), "( java.util.List<String> list ) { ",
                        tag("impl"))),
                line('-', "  ", tree("│   "), "      ", code("return 1;")),
                line('+', "  ", tree("│   "), "      ", code("return list.size() > 1 ? list.size() * 2 : 0;")),
                line(' ', "  ", tree("│   "), code("}")),
                line(' ', "  ", tree("└── "), code("int ", decl("n"), "( int x ) { ", tag("impl"))),
                line('~', "            ", code("return ", added("Math.max("), "x", added(", 0)"), ";")),
                line(' ', "      ", code("}"))),
                body(method("int m(java.util.List<String> list)", "return 1;") + "\n" + method("int n(int x)", "return x;"),
                        method("int m(java.util.List<String> list)", "return list.size() > 1 ? list.size() * 2 : 0;")
                                + "\n" + method("int n(int x)", "return Math.max(x, 0);")));
    }

    /** A statement added around old statements shows only its own lines as added, the old statements as they are. */
    public void testWrappedStatements() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("├── "), code("void ", decl("a"), "( boolean c ) { ", tag("impl"))),
                line(' ', "  ", tree("│   "), "      ", code("f(1);")),
                line('+', "  ", tree("│   "), "      ", code("if (c) {")),
                line(' ', "  ", tree("│   "), "      ", code("  f(2);")),
                line('+', "  ", tree("│   "), "      ", code("}")),
                line(' ', "  ", tree("│   "), "      ", code("f(3);")),
                line(' ', "  ", tree("│   "), code("}")),
                line(' ', "  ", tree("└── "), code("void ", decl("b"), "() { ", tag("impl"))),
                line('+', "            ", code("try {")),
                line(' ', "            ", code("  f(1);")),
                line(' ', "            ", code("  f(2);")),
                line('+', "            ", code("} catch (RuntimeException e) {")),
                line('+', "            ", code("  f(9);")),
                line('+', "            ", code("}")),
                line(' ', "      ", code("}"))),
                body("void f(int x) { }\n" + method("void a(boolean c)", "f(1);", "f(2);", "f(3);") + "\n"
                                + method("void b()", "f(1);", "f(2);"),
                        "void f(int x) { }\n" + method("void a(boolean c)", "f(1);", "if (c) {", "  f(2);", "}", "f(3);")
                                + "\n" + method("void b()", "try {", "  f(1);", "  f(2);", "} catch (RuntimeException e) {",
                                "  f(9);", "}")));
    }

    /** Statements on one line are shown as that line, the added ones marked in it. */
    public void testWrappedStatementsOnOneLine() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("c"), "( int n ) { ", tag("impl"))),
                line('~', "            ", code(added("for (int i = 0; i < n; i++)"), " f(1);")),
                line(' ', "      ", code("}"))),
                body("void f(int x) { }\nvoid c(int n) { f(1); }",
                        "void f(int x) { }\nvoid c(int n) { for (int i = 0; i < n; i++) f(1); }"));
    }

    /** A removed statement around statements kept shows only its own lines as removed, the kept ones as they are now. */
    public void testUnwrappedStatements() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("a"), "( boolean c, int ", renamed("p", "q", "q"), " ) { ", tag("impl"))),
                line(' ', "            ", code("f(0);")),
                line('-', "            ", code("if (c) {")),
                line(' ', "            ", code("f(", renamed("p", "q", "q"), ");")),
                line(' ', "            ", code("f(2);")),
                line('-', "            ", code("}")),
                line(' ', "            ", code("f(3);")),
                line(' ', "      ", code("}"))),
                body("void f(int x) { }\n" + method("void a(boolean c, int p)", "f(0);", "if (c) {", "  f(p);", "  f(2);", "}", "f(3);"),
                        "void f(int x) { }\n" + method("void a(boolean c, int q)", "f(0);", "f(q);", "f(2);", "f(3);")));
    }

    /** Code removed around an expression is struck through alone, the rest of the line in one run. */
    public void testUnwrappedExpressionStrikesOnlyTheRemovedCode() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("├── "), code("boolean ", decl("neg"), "( boolean ", renamed("p", "q", "q"), " ) { ", tag("impl"))),
                line('~', "  ", tree("│   "), "      ", code("return ", strike("!"), renamed("p", "q", "q"), ";")),
                line(' ', "  ", tree("│   "), code("}")),
                line(' ', "  ", tree("└── "), code("int ", decl("paren"), "( int a, int b ) { ", tag("impl"))),
                line('~', "            ", code("return ", strike("("), "a + b", strike(") * 2"), ";")),
                line(' ', "      ", code("}"))),
                body("boolean neg(boolean p) { return !p; }\nint paren(int a, int b) { return (a + b) * 2; }",
                        "boolean neg(boolean q) { return q; }\nint paren(int a, int b) { return a + b; }"));
    }

    public void testEditedLiteral() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("int ", decl("m"), "() { ", tag("impl"))),
                line(' ', "            ", code("int a = 0;")),
                line('~', "            ", code("return ", strike("1"), " ", added("2"), ";")),
                line(' ', "      ", code("}"))),
                body(method("int m()", "int a = 0;", "return 1;"), method("int m()", "int a = 0;", "return 2;")));
    }

    /** The lines of the statements holding a change are shown, the skipped lines as a remark with their number. */
    public void testChangeInIfKeepsContext() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("int ", decl("m"), "( int x ) { ", tag("impl"))),
                line(' ', "            ", code(note("… (1 line)"))),
                line(' ', "            ", code("if (x > 0) {")),
                line(' ', "            ", code(note("… (1 line)"))),
                line('~', "            ", code("  return ", strike("1"), " ", added("2"), ";")),
                line(' ', "            ", code("}")),
                line(' ', "            ", code(note("… (1 line)"))),
                line(' ', "      ", code("}"))),
                body(method("int m(int x)", "foo();", "if (x > 0) {", "  bar();", "  return 1;", "}", "return 0;")
                                + "\nvoid foo() {}\nvoid bar() {}",
                        method("int m(int x)", "foo();", "if (x > 0) {", "  bar();", "  return 2;", "}", "return 0;")
                                + "\nvoid foo() {}\nvoid bar() {}", 0));
    }

    public void testAddedAndRemovedStatements() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("m"), "() { ", tag("impl"))),
                line(' ', "            ", code("c();")),
                line('-', "            ", code("a();")),
                line('+', "            ", code("b(\"done\");")),
                line(' ', "      ", code("}"))),
                body(method("void m()", "c();", "a();") + "\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}",
                        method("void m()", "c();", "b(\"done\");") + "\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}"));
    }

    /** Statements on one line are shown as that line, the removed ones struck through, the added marked. */
    public void testAddedAndRemovedStatementsOnOneLine() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("m"), "() { ", tag("impl"))),
                line('~', "            ", code("c(); ", strike("a();"), " ", added("b(\"done\");"))),
                line(' ', "      ", code("}"))),
                body("void m() { c(); a(); }\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}",
                        "void m() { c(); b(\"done\"); }\nvoid a() {}\nvoid b(String s) {}\nvoid c() {}"));
    }

    /** A statement on several lines is shown on its lines, with the comments, the tabs expanded; an added comment is a change. */
    public void testMultiLineStatement() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("int ", decl("m"), "( int a, int b ) { ", tag("impl"))),
                line('~', "            ", code("return Math.max( ", added(comment("// the bigger")))),
                line(' ', "            ", code("        a,")),
                line('~', "            ", code("        ", strike("b"), " ", added("3"), ");")),
                line(' ', "      ", code("}"))),
                body("int m(int a, int b) { return Math.max(a, b); }",
                        "int m(int a, int b) {\n\treturn Math.max( // the bigger\n\t\t\ta,\n\t\t\t3);\n}"));
    }

    /** A member select whose qualifier and name both changed is struck through as a whole. */
    public void testOwnChangeOfNode() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("int ", decl("m"), "( String s ) { ", tag("impl"))),
                line('~', "            ", code("return ", strike("s.length"), " ", added("t.hashCode"), "();")),
                line(' ', "      ", code("}"))),
                body("String t;\nint m(String s) { return s.length(); }", "String t;\nint m(String s) { return t.hashCode(); }"));
    }

    /** An added argument is marked inline with its comma, the other lines of the call shown unchanged. */
    public void testAddedArgument() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("m"), "() { ", tag("impl"))),
                line('~', "            ", code("f(1", added(", 2"), ", 3,")),
                line(' ', "            ", code("        4, 5);")),
                line(' ', "      ", code("}"))),
                body(method("void m()", "f(1, 3,", "        4, 5);") + "\nvoid f(int... a) {}",
                        method("void m()", "f(1, 2, 3,", "        4, 5);") + "\nvoid f(int... a) {}"));
    }

    /** An argument added in front of one which differs only in the name of a local is the one marked. */
    public void testAddedArgumentLikeAnother() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("m"), "( String a, String b ) { ", tag("impl"))),
                line('~', "            ", code("f(1", added(", a.length()"), ", b.length());")),
                line(' ', "      ", code("}"))),
                body(method("void m(String a, String b)", "f(1, b.length());") + "\nvoid f(int... x) {}",
                        method("void m(String a, String b)", "f(1, a.length(), b.length());") + "\nvoid f(int... x) {}"));
    }

    /** A removed argument is struck through with its comma. */
    public void testRemovedArguments() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("m"), "() { ", tag("impl"))),
                line('~', "            ", code("f(1, ", strike("2,"), " 3);")),
                line('~', "            ", code("f(1, 2", strike(", 3"), ");")),
                line(' ', "      ", code("}"))),
                body(method("void m()", "f(1, 2, 3);", "f(1, 2, 3);") + "\nvoid f(int... a) {}",
                        method("void m()", "f(1, 3);", "f(1, 2);") + "\nvoid f(int... a) {}"));
    }

    /** Arguments replaced by expressions of another kind are struck through in place. */
    public void testArgumentsOfOtherKind() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("boolean ", decl("m"), "() { ", tag("impl"))),
                line('~', "            ", code("return new java.util.ArrayList<String>(", strike("5"), " ",
                        added("Integer.MAX_VALUE"), ").add(", strike("\"a\""), " ", added("String.valueOf(1)"), ");")),
                line(' ', "      ", code("}"))),
                body("boolean m() { return new java.util.ArrayList<String>(5).add(\"a\"); }",
                        "boolean m() { return new java.util.ArrayList<String>(Integer.MAX_VALUE).add(String.valueOf(1)); }"));
    }

    /** A changed string literal and a changed comment show only the changed words, if they did not change too much. */
    public void testWordDiffOfLiteralsAndComments() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("String ", decl("m"), "() { ", tag("impl"))),
                line('~', "            ", code(comment("// returns the "), strike("old"), " ", added(comment("new")),
                        comment(" greeting"))),
                line('~', "            ", code("return \"Hello ", strike("big"), " ", added("small"),
                        " world, nice day\";")),
                line(' ', "      ", code("}"))),
                body("String m() {\n// returns the old greeting\nreturn \"Hello big world, nice day\"; }",
                        "String m() {\n// returns the new greeting\nreturn \"Hello small world, nice day\"; }"));
        // Too different for a word diff: the statement is mostly changed, so it is shown removed and added.
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("String ", decl("m"), "() { ", tag("impl"))),
                line('-', "            ", code("return \"one two\";")),
                line('+', "            ", code("return \"three four\";")),
                line(' ', "      ", code("}"))),
                body("String m() { return \"one two\"; }", "String m() { return \"three four\"; }"));
    }

    /** A big expression replaced by one of another kind replaces the statement. */
    public void testBigExpressionOfOtherKind() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("int ", decl("m"), "( boolean b, int x ) { ", tag("impl"))),
                line('-', "            ", code("return b ? x + 1 : x - 1;")),
                line('+', "            ", code("return Math.abs(x);")),
                line(' ', "      ", code("}"))),
                body(method("int m(boolean b, int x)", "return b ? x + 1 : x - 1;"),
                        method("int m(boolean b, int x)", "return Math.abs(x);")));
    }

    public void testRenamedLocalsAndMethodsHaveNoDetails() throws Exception {
        assertTrue(body("int m() { int a = h(); return a; }\nint h() { return 1; }",
                "int m() { int b = g(); return b; }\nint g() { return 1; }").stream()
                .noneMatch(l -> hasSpan(List.of(l), Css.TAG, "[impl]")));
    }

    /** Context of lambda blocks, else branches and switch cases; a changed comment is an implementation change. */
    public void testNestedContexts() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("m"), "( java.util.List<String> l, int x ) { ",
                        tag("impl"))),
                line(' ', "            ", code("l.forEach(s -> {")),
                line('~', "            ", code("  ", strike("System.out"), " ", added("System.err"), ".println(s);")),
                line(' ', "            ", code("});")),
                line(' ', "            ", code("if (x > 0) {")),
                line(' ', "            ", code(note("… (1 line)"))),
                line(' ', "            ", code("} else {")),
                line(' ', "            ", code(note("… (1 line)"))),
                line('+', "            ", code("  f(6);")),
                line(' ', "            ", code("}")),
                line(' ', "            ", code("switch (x) {")),
                line(' ', "            ", code(note("… (1 line)"))),
                line('~', "            ", code("  default: f(", strike("4"), " ", added("7"), ");")),
                line(' ', "            ", code("}")),
                line('~', "            ", code("f(5); ", strike("// five"), " ", added(comment("// FIVE")))),
                line(' ', "      ", code("}"))),
                body(
                "void m(java.util.List<String> l, int x) {\n  l.forEach(s -> {\n    System.out.println(s);\n  });\n  if (x > 0) {\n    f(1);\n  } else {\n    f(2);\n  }\n  switch (x) {\n    case 1: f(3); break;\n    default: f(4);\n  }\n  f(5); // five\n}\nvoid f(int i) {}",
                "void m(java.util.List<String> l, int x) {\n  l.forEach(s -> {\n    System.err.println(s);\n  });\n  if (x > 0) {\n    f(1);\n  } else {\n    f(2);\n    f(6);\n  }\n  switch (x) {\n    case 1: f(3); break;\n    default: f(7);\n  }\n  f(5); // FIVE\n}\nvoid f(int i) {}", 0));
    }

    public void testBigAddedBlockIsShown() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("m"), "( int[] xs ) { ", tag("impl"))),
                line('+', "            ", code("for (int x : xs) {")),
                line('+', "            ", code("  f(x);")),
                line('+', "            ", code("  f(x + 1);")),
                line('+', "            ", code("  f(x + 2);")),
                line('+', "            ", code("}")),
                line(' ', "      ", code("}"))),
                body("void m(int[] xs) { }\nvoid f(int x) {}",
                        method("void m(int[] xs)", "for (int x : xs) {", "  f(x);", "  f(x + 1);", "  f(x + 2);", "}")
                                + "\nvoid f(int x) {}"));
    }

    /**
     * A member whose signature did not change has no marker; a field initializer shown on the line of its declaration
     * marks the line with its change.
     */
    public void testMarkerOfDeclaration() throws Exception {
        assertLines(List.of(
                line('~', "  ", tree("├── "), code("int ", decl("f"), " = ", strike("1"), " ", added("2"), " ",
                        tag("impl"))),
                line(' ', "  ", tree("└── "), code("int ", decl("m"), "() { ", tag("impl"))),
                line('~', "            ", code("return ", strike("1"), " ", added("2"), ";")),
                line(' ', "      ", code("}"))),
                body("int f = 1;\nint m() { return 1; }", "int f = 2;\nint m() { return 2; }"));
    }

    public void testConstructorSkipsImplicitSuper() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code(decl("Foo"), "() { ", tag("impl"))),
                line('~', "            ", code("x = ", strike("1"), " ", added("2"), ";")),
                line(' ', "      ", code("}"))),
                body("int x;\nFoo() { x = 1; }", "int x;\nFoo() { x = 2; }"));
    }

    public void testRewrittenBodyIsShown() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("m"), "() { ", tag("impl"))),
                line('-', "            ", code("a(1);")),
                line('-', "            ", code("a(2);")),
                line('+', "            ", code("int x = 1;")),
                line('+', "            ", code("b(\"x\" + x);")),
                line(' ', "      ", code("}"))),
                body("void m() {\n  a(1);\n  a(2);\n}\nvoid a(int i) {}\nvoid b(String s) {}",
                        method("void m()", "int x = 1;", "b(\"x\" + x);") + "\nvoid a(int i) {}\nvoid b(String s) {}"));
    }

    public void testUnrelatedStatementIsReplaced() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("int ", decl("m"), "( String s ) { ", tag("impl"))),
                line('-', "            ", code("int n = s.length();")),
                line('+', "            ", code("StringBuilder n = new StringBuilder();")),
                line('~', "            ", code("return n", added(".length()"), ";")),
                line(' ', "      ", code("}"))),
                body(method("int m(String s)", "int n = s.length();", "return n;"),
                        method("int m(String s)", "StringBuilder n = new StringBuilder();", "return n.length();")));
    }

    /** A run of unchanged lines longer than the context is a remark with their number, a shorter one is shown. */
    public void testLongUnchangedRunIsCollapsed() throws Exception {
        String old = method("void m()", "a(1);", "a(2);", "a(3);", "a(4);", "a(5);", "a(6);", "a(7);", "a(8);")
                + "\nvoid a(int i) {}";
        String now = method("void m()", "a(1);", "a(2);", "a(3);", "a(4);", "a(5);", "a(60);", "a(7);", "a(8);")
                + "\nvoid a(int i) {}";
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("m"), "() { ", tag("impl"))),
                line(' ', "            ", code(note("… (5 lines)"))),
                line('~', "            ", code("a(", strike("6"), " ", added("60"), ");")),
                line(' ', "            ", code("a(7);")),
                line(' ', "            ", code("a(8);")),
                line(' ', "      ", code("}"))),
                body(old, now));
    }

    /** For the page the collapsed lines are kept, after the note of them, to be expanded there. */
    public void testCollapsedRunIsKeptForThePage() throws Exception {
        String old = method("void m()", "a(1);", "a(2);", "a(3);", "a(4);", "a(5);", "a(6);", "a(7);")
                + "\nvoid a(int i) {}";
        String now = method("void m()", "a(1);", "a(2);", "a(3);", "a(4);", "a(5);", "a(60);", "a(7);")
                + "\nvoid a(int i) {}";
        ReportOptions options = new ReportOptions(ChangeLevel.IMPLEMENTATION).withFoldContext(true);
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("m"), "() { ", tag("impl"))),
                HTML.DIV(
                        line(' ', "            ", code(note("… 5 lines"))),
                        line(' ', "            ", code("a(1);")),
                        line(' ', "            ", code("a(2);")),
                        line(' ', "            ", code("a(3);")),
                        line(' ', "            ", code("a(4);")),
                        line(' ', "            ", code("a(5);"))).clazz(Css.ELIDED.cssClass()),
                line('~', "            ", code("a(", strike("6"), " ", added("60"), ");")),
                line(' ', "            ", code("a(7);")),
                line(' ', "      ", code("}"))),
                body(old, now, options));
    }

    /** A negative context shows the whole body. */
    public void testWholeBody() throws Exception {
        assertLines(List.of(
                line(' ', "  ", tree("└── "), code("void ", decl("m"), "() { ", tag("impl"))),
                line(' ', "            ", code("a(1);")),
                line(' ', "            ", code("a(2);")),
                line(' ', "            ", code("a(3);")),
                line(' ', "            ", code("a(4);")),
                line(' ', "            ", code("a(5);")),
                line('~', "            ", code("a(", strike("6"), " ", added("60"), ");")),
                line(' ', "      ", code("}"))),
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
                ReportOptions options = new ReportOptions(ChangeLevel.IMPLEMENTATION,
                        ReportOptions.DEFAULT_BODY_CONTEXT, ReportOptions.NO_JAVADOC, true);
                assertLines(List.of(
                        line("1", ' ', "  ", code("class ", decl("Foo"))),
                        line("3", ' ', "  ", tree("└── "), code("void ", decl("m"), "() { ", tag("impl"))),
                        line("4", ' ', "            ", code("a(1);")),
                        line("4", '-', "            ", code("a(2);")),
                        line("5", ' ', "            ", code("a(3);")),
                        line("6", '+', "            ", code("a(4);")),
                        line("7", '+', "            ", code("a(5);")),
                        line("8", ' ', "      ", code("}"))),
                        new Reporter(options).report(analyzer.analyzeChanges().files().getFirst()));
            }
        }
    }
}
