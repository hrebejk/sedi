/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import junit.framework.TestCase;

import java.util.List;

/**
 * Unit tests for {@link ApiDiffReporter}.
 */
public class ApiDiffReporterTest extends TestCase {

    /**
     * Tests that member and nested type changes are reported and that body changes are not.
     */
    public void testReport() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  int a;\n  void body() { }\n  static class In { void x() {} }\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  long a;\n  void body() { int z = 1; }\n  static class In { void x() {} void y() {} }\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                List<FileDiffAnalysis> analyses = analyzer.analyzeChanges();
                assertEquals(1, analyses.size());
                assertEquals(List.of(
                                "  Foo",
                                "      ~~int~~ long a",
                                "    In",
                                "      + void y()"),
                        new ApiDiffReporter(false).report(analyses.get(0)));
            }
        }
    }

    /**
     * Tests that a body-only change reports nothing.
     */
    public void testBodyOnlyChangeIsEmpty() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  void body() { }\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  void body() { int z = 1; }\n}\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertTrue(new ApiDiffReporter(false).report(analyzer.analyzeChanges().get(0)).isEmpty());
            }
        }
    }
}
