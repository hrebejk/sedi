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
import org.maraboo.fleck.console.Console;
import org.maraboo.fleck.console.ConsoleHtmlRenderer;
import org.maraboo.fleck.html.HTML;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Tests of the report shown on a console without colors: the layout of the lines, the empty lines and the marks
 * of the changes made by the {@link ReportStyles#plain() plain} style sheet.
 */
public class ConsoleOutputTest extends TestCase {

    /** The lines of the report on a console without colors. */
    private static List<String> plain(HTML.Tag report) {
        PrintStream ps = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new ConsoleHtmlRenderer(new Console(ps, ps, null, true), ReportStyles.plain()).lines(report);
    }

    public void testLayout() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "import java.util.List;\npublic class Foo {\n  int a;\n  void gone() { }\n"
                    + "  static class In { void x() {} }\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "import java.util.Set;\npublic class Foo {\n\n  long a;\n"
                    + "  static class In { void x() {}\n  void y() {} }\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ReportOptions options = new ReportOptions(ChangeLevel.IMPORTS,
                        ReportOptions.DEFAULT_BODY_CONTEXT, ReportOptions.NO_JAVADOC, true);
                assertEquals(List.of(
                                "# API changes",
                                "",
                                "~ Foo.java",
                                "2     public class Foo",
                                "4 ~   ├── ~~int~~ __long__ a",
                                "4 -   ├── ~~void gone()~~",
                                "5     └── static class In",
                                "6 +       └── void y()",
                                "",
                                "# Implementation changes",
                                "",
                                "~ Foo.java",
                                "2     public class Foo",
                                "5     └── static class In",
                                "6 +       └── void y() {}",
                                "",
                                "# Cosmetic changes",
                                "",
                                // Indented as the numbered lines, right of the column of the numbers.
                                "    NO CHANGES",
                                "",
                                "# Imports",
                                "",
                                "~ Foo.java",
                                "1 -   ~~import java.util.List~~",
                                "1 +   import java.util.Set"),
                        plain(new Reporter(options).report(analyzer.analyzeChanges())));
            }
        }
    }

    /** Without the numbers of the lines, the markers stay in their column. */
    public void testWithoutLineNumbers() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo {\n  int a;\n}\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo {\n  long a;\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertEquals(List.of(
                                "# API changes",
                                "",
                                "~ Foo.java",
                                "    public class Foo",
                                "~   └── ~~int~~ __long__ a"),
                        plain(new Reporter().report(analyzer.analyzeChanges())));
            }
        }
    }
}
