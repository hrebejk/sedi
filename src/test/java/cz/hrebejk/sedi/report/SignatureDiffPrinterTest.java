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
        SignatureDiffPrinter printer = new SignatureDiffPrinter(false);
        return pairs(oldMembers, newMembers).stream().map(p -> printer.format(p, "T")).toList();
    }

    public void testParameterTypeAndNameChange() throws Exception {
        assertEquals(List.of("  public String method( ~~int~~ float ~~i~~ f )"),
                print("public String method(int i) { return null; }", "public String method(float f) { return null; }"));
    }

    public void testModifierAndReturnTypeChange() throws Exception {
        assertEquals(List.of("  ~~private~~ public static ~~int~~ long m()"),
                print("private int m() { return 0; }", "public static long m() { return 0; }"));
    }

    public void testThrowsAndAnnotation() throws Exception {
        assertEquals(List.of("  @Deprecated void m() throws Exception"),
                print("void m() {}", "@Deprecated void m() throws Exception {}"));
    }

    public void testRename() throws Exception {
        assertEquals(List.of("  int ~~a~~ b( String s )"), print("int a(String s) { return 0; }", "int b(String s) { return 0; }"));
    }

    public void testFieldTypeChange() throws Exception {
        assertEquals(List.of("  private ~~int~~ long f"), print("private int f;", "private long f;"));
    }

    public void testAddedAndRemoved() throws Exception {
        List<String> lines = print("int gone;", "String fresh;");
        assertTrue(lines.contains("- int gone"));
        assertTrue(lines.contains("+ String fresh"));
    }

    public void testAnsiStrike() throws Exception {
        ElementPair pair = pairs("int f;", "long f;").get(0);
        assertEquals("  \u001b[9mint\u001b[29m long f", new SignatureDiffPrinter(true).format(pair, "T"));
    }
}
