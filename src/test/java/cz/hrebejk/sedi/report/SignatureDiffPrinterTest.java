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
                .flatMap(p -> java.util.stream.Stream.concat(printer.annotations(p).stream(), java.util.stream.Stream.of(printer.format(p, "T"))))
                .toList();
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
                assertEquals("  public class T implements I2", printer.formatType(t.type(), ""));
                assertEquals(List.of("~ private E2 yesno( boolean p, java.util.List<E2> all ) throws Exception"),
                        t.pairs().stream().map(p -> printer.format(p, "T")).toList());
            }
        }
    }

    public void testParameterTypeAndNameChange() throws Exception {
        assertEquals(List.of("~ public String method( ~~int~~ float f )"),
                print("public String method(int i) { return null; }", "public String method(float f) { return null; }"));
    }

    public void testModifierAndReturnTypeChange() throws Exception {
        assertEquals(List.of("~ ~~private~~ public static ~~int~~ long m()"),
                print("private int m() { return 0; }", "public static long m() { return 0; }"));
    }

    public void testThrowsAndAnnotation() throws Exception {
        assertEquals(List.of("~ @Deprecated", "~ void m() throws Exception"),
                print("void m() {}", "@Deprecated void m() throws Exception {}"));
    }

    public void testRename() throws Exception {
        assertEquals(List.of("~ int ~~a~~ b( String s )"), print("int a(String s) { return 0; }", "int b(String s) { return 0; }"));
    }

    public void testFieldTypeChange() throws Exception {
        assertEquals(List.of("~ private ~~int~~ long f"), print("private int f;", "private long f;"));
    }

    public void testAddedAndRemoved() throws Exception {
        List<String> lines = print("int gone;", "String fresh;");
        assertTrue(lines.contains("- ~~int gone~~"));
        assertTrue(lines.contains("+ String fresh"));
    }

    public void testAnsiStrike() throws Exception {
        ElementPair pair = pairs("int f;", "long f;").get(0);
        assertEquals("~ \u001b[9mint\u001b[29m long f", new SignatureDiffPrinter(ThemeTest.only("strike", "strike"))
                .format(pair, "T"));
    }

    public void testAnsiAddedLineAndKeywords() throws Exception {
        Theme theme = ThemeTest.only("added", "bg:#010203", "added_mark", "underline", "keyword", "bold",
                "declaration", "strike");
        List<String> lines = pairs("", "int fresh;").stream().map(p -> new SignatureDiffPrinter(theme).format(p, "T"))
                .toList();
        assertEquals(List.of("\u001b[48;2;1;2;3m\u001b[4m+\u001b[24m \u001b[1mint\u001b[22m \u001b[9mfresh\u001b[29m"
                + "\u001b[49m"), lines);
    }
}
