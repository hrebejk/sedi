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

import java.util.List;

/**
 * Unit tests for {@link SignatureDiffPrinter}.
 */
public class SignatureDiffPrinterTest extends TestCase {

    /** The line without its line number, see {@link LineNumbers}. */
    static String unnumbered(String line) {
        return unnumbered(line, Theme.PLAIN);
    }

    /** The line without its line number, the marker in front of it in the colors of the theme. */
    static String unnumbered(String line, Theme theme) {
        return LineNumbers.render(java.util.List.of(line), false, theme).getFirst();
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

    private List<String> print(String oldMembers, String newMembers) throws Exception {
        SignatureDiffPrinter printer = new SignatureDiffPrinter(Theme.PLAIN);
        return pairs(oldMembers, newMembers).stream()
                .flatMap(p -> java.util.stream.Stream.concat(printer.annotations(p).stream(), java.util.stream.Stream.of(printer.format(p, "T"))).map(SignatureDiffPrinterTest::unnumbered))
                .toList();
    }

    /** The signature is printed as it is in the source: varargs, comments and spaces left out, modifiers in order. */
    public void testSignatureAsInSource() throws Exception {
        assertEquals(List.of("~ final static void main( String... args, __int x__ )"),
                print("final static void main(String... args) { }", "final static void main(String...  args, int x) { }"));
        // The comment is left out, the type of a C style array printed by javac.
        assertEquals(List.of("~ void p( java.util.List< String> l, __int[] x__ )"),
                print("void p(java.util.List< /* c */ String> l) { }",
                        "void p(java.util.List< /* c */ String> l, int x[]) { }"));
        assertEquals(List.of("~ void q( final @Deprecated String s, __int i__ )"),
                print("void q(final @Deprecated String s) { }", "void q(final @Deprecated String s, int i) { }"));
    }

    /** An added parameter is marked as added, a removed one struck through, also between the kept ones. */
    public void testAddedAndRemovedParameters() throws Exception {
        assertEquals(List.of("~ void a( String text, __long line__ )"),
                print("void a(String line) { }", "void a(String text, long line) { }"));
        assertEquals(List.of("~ void b( __String s__, int a, __boolean f__ )"),
                print("void b(int a) { }", "void b(String s, int a, boolean f) { }"));
        assertEquals(List.of("~ void c( int a, ~~String s~~ )"),
                print("void c(int a, String s) { }", "void c(int a) { }"));
        assertEquals(List.of("~ void d( ~~int~~ __long__ a, String s )"),
                print("void d(int a, String s) { }", "void d(long a, String s) { }"));
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
                SignatureDiffPrinter printer = new SignatureDiffPrinter(Theme.PLAIN);
                assertEquals("  public class T implements I2", unnumbered(printer.formatType(t.type(), "")));
                assertEquals(List.of("~ __private__ E2 yesno( boolean p, java.util.List<E2> all ) throws Exception"),
                        t.pairs().stream().map(p -> unnumbered(printer.format(p, "T"))).toList());
            }
        }
    }

    public void testParameterTypeAndNameChange() throws Exception {
        assertEquals(List.of("~ public String method( ~~int~~ __float__ f )"),
                print("public String method(int i) { return null; }", "public String method(float f) { return null; }"));
    }

    public void testModifierAndReturnTypeChange() throws Exception {
        assertEquals(List.of("~ ~~private~~ __public__ __static__ ~~int~~ __long__ m()"),
                print("private int m() { return 0; }", "public static long m() { return 0; }"));
    }

    public void testThrowsAndAnnotation() throws Exception {
        assertEquals(List.of("~ __@Deprecated__", "~ void m() throws __Exception__"),
                print("void m() {}", "@Deprecated void m() throws Exception {}"));
    }

    public void testRename() throws Exception {
        assertEquals(List.of("~ int ~~a~~ __b__( String s )"), print("int a(String s) { return 0; }", "int b(String s) { return 0; }"));
    }

    public void testFieldTypeChange() throws Exception {
        assertEquals(List.of("~ private ~~int~~ __long__ f"), print("private int f;", "private long f;"));
    }

    public void testAddedAndRemoved() throws Exception {
        List<String> lines = print("int gone;", "String fresh;");
        assertTrue(lines.contains("- ~~int gone~~"));
        assertTrue(lines.contains("+ String fresh"));
    }

    public void testAnsiStrike() throws Exception {
        ElementPair pair = pairs("int f;", "long f;").get(0);
        assertEquals("~ \u001b[9mint\u001b[29m long f", unnumbered(new SignatureDiffPrinter(ThemeTest.only("strike", "strike"))
                .format(pair, "T")));
    }

    public void testAnsiAddedLineAndKeywords() throws Exception {
        Theme theme = ThemeTest.only("added", "bg:#010203", "added_mark", "underline", "keyword", "bold",
                "declaration", "strike");
        List<String> lines = pairs("", "int fresh;").stream().map(p -> unnumbered(new SignatureDiffPrinter(theme).format(p, "T"), theme))
                .toList();
        // The marker is in its column, out of the style of the added code.
        assertEquals(List.of("\u001b[4m+\u001b[24m \u001b[48;2;1;2;3m\u001b[1mint\u001b[22m \u001b[9mfresh\u001b[29m"
                + "\u001b[49m"), lines);
    }
}
