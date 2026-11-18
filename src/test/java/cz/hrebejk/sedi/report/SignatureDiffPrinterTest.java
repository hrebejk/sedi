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
import cz.hrebejk.sedi.analysis.DiffAstAnalyzer;
import cz.hrebejk.sedi.analysis.ElementPair;
import junit.framework.TestCase;
import org.maraboo.fleck.html.HTML;

import java.util.List;

import static cz.hrebejk.sedi.report.Expect.*;
import static cz.hrebejk.sedi.report.HtmlAssert.*;

/**
 * Unit tests for {@link SignatureDiffPrinter}.
 */
public class SignatureDiffPrinterTest extends TestCase {

    /** The element of the line without its line number, see {@link LineNumbers}. */
    static HTML.Tag unnumbered(Line line) {
        return LineNumbers.render(List.of(line), false).getFirst();
    }


    private List<ElementPair> pairs(String oldMembers, String newMembers) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("T.java", source(oldMembers));
            helper.commit("init");
            helper.writeFile("T.java", source(newMembers));
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                return analyzer.analyzeChanges().files().get(0).rootChanges().nested().get(0).pairs();
            }
        }
    }

    private static String source(String members) {
        return "public class T {\n" + members + "\n}\n";
    }

    private List<HTML> print(String oldMembers, String newMembers) throws Exception {
        SignatureDiffPrinter printer = new SignatureDiffPrinter();
        return pairs(oldMembers, newMembers).stream()
                .flatMap(p -> java.util.stream.Stream.concat(printer.annotations(p).stream(),
                        java.util.stream.Stream.of(printer.format(p, "T"))))
                .<HTML>map(SignatureDiffPrinterTest::unnumbered)
                .toList();
    }

    /** The signature is printed as it is in the source: varargs, comments and spaces left out, modifiers in order. */
    public void testSignatureAsInSource() throws Exception {
        assertLines(List.of(
                line('~', code("final static void ", decl("main"), "( String... args, ", added("int x"), " )"))),
                print("final static void main(String... args) { }", "final static void main(String...  args, int x) { }"));
        // The comment is left out, the type of a C style array printed by javac.
        assertLines(List.of(
                line('~', code("void ", decl("p"), "( java.util.List< String> l, ", added("int[] x"), " )"))),
                print("void p(java.util.List< /* c */ String> l) { }",
                        "void p(java.util.List< /* c */ String> l, int x[]) { }"));
        assertLines(List.of(
                line('~', code("void ", decl("q"), "( final @Deprecated String s, ", added("int i"), " )"))),
                print("void q(final @Deprecated String s) { }", "void q(final @Deprecated String s, int i) { }"));
    }

    /** An added parameter is marked as added, a removed one struck through, also between the kept ones. */
    public void testAddedAndRemovedParameters() throws Exception {
        assertLines(List.of(
                line('~', code("void ", decl("a"), "( String text, ", added("long line"), " )"))),
                print("void a(String line) { }", "void a(String text, long line) { }"));
        assertLines(List.of(
                line('~', code("void ", decl("b"), "( ", added("String s"), ", int a, ", added("boolean f"), " )"))),
                print("void b(int a) { }", "void b(String s, int a, boolean f) { }"));
        assertLines(List.of(
                line('~', code("void ", decl("c"), "( int a, ", strike("String s"), " )"))),
                print("void c(int a, String s) { }", "void c(int a) { }"));
        assertLines(List.of(
                line('~', code("void ", decl("d"), "( ", strike("int"), " ", added("long"), " a, String s )"))),
                print("void d(int a, String s) { }", "void d(long a, String s) { }"));
    }

    /** A changed annotation shows only its changed arguments as changed, paired by their names. */
    public void testAnnotationArguments() throws Exception {
        String type = "@interface A { int x() default 0; String y() default \"\"; int z() default 0; }\n";
        assertLines(List.of(
                line('~', code("@A(x = 1, y = ", strike("\"old\""), " ", added("\"new\""), ")")),
                line(' ', code("void ", decl("m"), "()"))),
                print(type + "@A(x = 1, y = \"old\") void m() {}", type + "@A(x = 1, y = \"new\") void m() {}"));
        // A string which did not change too much shows the changed words.
        assertLines(List.of(
                line('~', code("@A(y = \"Show the ", strike("old"), " ", added("new"), " diff and exit\")")),
                line(' ', code("void ", decl("m"), "()"))),
                print(type + "@A(y = \"Show the old diff and exit\") void m() {}",
                        type + "@A(y = \"Show the new diff and exit\") void m() {}"));
        assertLines(List.of(
                line('~', code("@A(x = 1, y = ", strike("\"old\""), " ", added("\"new\""), ")")),
                line(' ', code("void ", decl("m"), "()"))),
                print(type + "@A(x = 1, y = \"old\") void m() {}", type + "@A(x = 1, y = \"new\") void m() {}"));
        assertLines(List.of(
                line('~', code("@A(", strike("x = 1"), ", y = \"s\", ", added("z = 2"), ")")),
                line(' ', code("void ", decl("m"), "()"))),
                print(type + "@A(x = 1, y = \"s\") void m() {}", type + "@A(y = \"s\", z = 2) void m() {}"));
        assertLines(List.of(
                line('~', code("@SuppressWarnings(", strike("\"a\""), " ", added("\"b\""), ")")),
                line(' ', code("void ", decl("m"), "()"))),
                print("@SuppressWarnings(\"a\") void m() {}", "@SuppressWarnings(\"b\") void m() {}"));
    }

    /**
     * Tests that the types renamed in another file are not shown as changes of the signature: only the added
     * modifier is struck through or added, the renamed types are printed once.
     */
    public void testRenamedTypesAreNotChanges() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("E1.java", "public enum E1 {\n    YES, NO\n}\n");
            helper.writeFile("I1.java", "public interface I1 {\n    void run();\n}\n");
            helper.writeFile("T.java", "public class T implements I1 {\n"
                    + "    E1 yesno(boolean p, java.util.List<E1> all) throws Exception { return p ? E1.YES : E1.NO; }\n"
                    + "    public void run() { }\n}\n");
            helper.commit("init");
            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("E1.java"));
            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("I1.java"));
            helper.writeFile("E2.java", "public enum E2 {\n    YES, NO\n}\n");
            helper.writeFile("I2.java", "public interface I2 {\n    void run();\n}\n");
            helper.writeFile("T.java", "public class T implements I2 {\n"
                    + "    private E2 yesno(boolean p, java.util.List<E2> all) throws Exception { return p ? E2.YES : E2.NO; }\n"
                    + "    public void run() { }\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                var t = analyzer.analyzeChanges().files().stream()
                        .filter(f -> f.diff().getNewPath().equals("T.java")).findFirst().orElseThrow()
                        .rootChanges().nested().get(0);
                SignatureDiffPrinter printer = new SignatureDiffPrinter();
                assertHtml(
                        line(' ', code("public class ", decl("T"), " implements I2")),
                        unnumbered(printer.formatType(t.type(), "")));
                assertLines(List.of(
                        line('~', code(added("private"), " E2 ", decl("yesno"),
                                "( boolean p, java.util.List<E2> all ) throws Exception"))),
                        t.pairs().stream().<HTML>map(p -> unnumbered(printer.format(p, "T"))).toList());
            }
        }
    }

    public void testParameterTypeAndNameChange() throws Exception {
        assertLines(List.of(
                line('~', code("public String ", decl("method"), "( ", strike("int"), " ", added("float"), " f )"))),
                print("public String method(int i) { return null; }", "public String method(float f) { return null; }"));
    }

    public void testModifierAndReturnTypeChange() throws Exception {
        assertLines(List.of(
                line('~', code(strike("private"), " ", added("public"), " ", added("static"), " ", strike("int"), " ",
                        added("long"), " ", decl("m"), "()"))),
                print("private int m() { return 0; }", "public static long m() { return 0; }"));
    }

    public void testThrowsAndAnnotation() throws Exception {
        assertLines(List.of(
                line('~', code(added("@Deprecated"))),
                line('~', code("void ", decl("m"), "() throws ", added("Exception")))),
                print("void m() {}", "@Deprecated void m() throws Exception {}"));
    }

    public void testRename() throws Exception {
        assertLines(List.of(
                line('~', code("int ", strike("a"), " ", added(decl("b")), "( String s )"))),
                print("int a(String s) { return 0; }", "int b(String s) { return 0; }"));
    }

    public void testFieldTypeChange() throws Exception {
        assertLines(List.of(
                line('~', code("private ", strike("int"), " ", added("long"), " ", decl("f")))),
                print("private int f;", "private long f;"));
    }

    public void testAddedAndRemoved() throws Exception {
        List<HTML> lines = print("int gone;", "String fresh;");
        assertContains(line('-', code(strike("int ", decl("gone")))), lines);
        assertContains(line('+', code("String ", decl("fresh"))), lines);
    }

    /** The old text is a span struck through, the new one a span marked as added; the code in it is highlighted. */
    public void testStrike() throws Exception {
        ElementPair pair = pairs("int f;", "long f;").get(0);
        assertHtmlExact(line('~', code(strike("int"), " ", added(span(Css.KEYWORD, "long")), " ", decl("f"))),
                unnumbered(new SignatureDiffPrinter().format(pair, "T")));
    }

    /** The marker of an added line is in its column, out of the code of the line. */
    public void testAddedLineAndKeywords() throws Exception {
        List<HTML> lines = pairs("", "int fresh;").stream()
                .<HTML>map(p -> unnumbered(new SignatureDiffPrinter().format(p, "T"))).toList();
        assertEquals(1, lines.size());
        assertHtmlExact(line('+', code(span(Css.KEYWORD, "int"), " ", decl("fresh"))), lines.getFirst());
    }
}
