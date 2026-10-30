/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import cz.hrebejk.sedi.GitTestHelper;
import junit.framework.TestCase;

import java.util.List;

/**
 * Tests that the changes are classified by their {@link ChangeLevel}.
 */
public class ChangeClassificationTest extends TestCase {

    /** The changes of the top level type of the only changed file as {@code level:name}. */
    private static List<String> classify(String before, String after) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", before);
            helper.commit("init");
            helper.writeFile("Foo.java", after);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ApiChanges type = analyzer.analyzeChanges().files().get(0).rootChanges().nested().get(0);
                return type.changes().stream().map(c -> c.level() + ":"
                        + (c.pair().isRemoved() ? c.pair().oldElement() : c.pair().newElement()).getName()).toList();
            }
        }
    }

    public void testBodyChangeIsImplementation() throws Exception {
        assertEquals(List.of("IMPLEMENTATION:m"),
                classify("class Foo { int m() { return 1; } }", "class Foo { int m() { return 2; } }"));
    }

    public void testFieldInitializerIsImplementation() throws Exception {
        assertEquals(List.of("IMPLEMENTATION:f"), classify("class Foo { int f = 1; }", "class Foo { int f = 2; }"));
    }

    public void testLocalRenameIsCosmetic() throws Exception {
        assertEquals(List.of("COSMETIC:m"),
                classify("class Foo { int m() { int a = 1; return a; } }", "class Foo { int m() { int b = 1; return b; } }"));
    }

    public void testWhitespaceIsCosmetic() throws Exception {
        assertEquals(List.of("COSMETIC:m"),
                classify("class Foo { int m() { return 1; } }", "class Foo { int m() {\n    return 1;  // one\n  } }"));
    }

    public void testUnchangedIsNotReported() throws Exception {
        assertEquals(List.of("API:z"),
                classify("class Foo { int z; int m() { return 1; } }", "class Foo { long z; int m() { return 1; } }"));
    }

    public void testSignatureChangeIsApi() throws Exception {
        assertEquals(List.of("API:m"),
                classify("class Foo { int m() { return 1; } }", "class Foo { long m() { return 1; } }"));
    }

    /** A rename is an API change, the calls of the renamed method are cosmetic, never implementation. */
    public void testRenameIsApiAndItsUsesAreCosmetic() throws Exception {
        assertEquals(List.of("API:helper", "COSMETIC:use"),
                classify("class Foo { int help() { return 1; } int use() { return help() + 1; } }",
                        "class Foo { int helper() { return 1; } int use() { return helper() + 1; } }"));
    }

    public void testRenameWithRewrittenBodyIsApiAndImplementation() throws Exception {
        assertEquals(List.of("API:helper", "IMPLEMENTATION:helper"),
                classify("class Foo { int help() { return 1; } }", "class Foo { int helper() { return 2; } }"));
    }

    public void testVisibility() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "public class Foo { }\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "public class Foo { public void a() { } private void b() { } void c() { }"
                    + " protected void d() { } interface I { void e(); } private static class P { public void f() { } } }\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ApiChanges foo = analyzer.analyzeChanges().files().get(0).rootChanges().nested().get(0);
                assertTrue(foo.exposed());
                assertEquals(List.of("a", "d"), foo.changes().stream().filter(MemberChange::publicApi)
                        .map(c -> c.pair().newElement().getName()).filter(n -> n.length() == 1 && !n.equals("I")).toList());
            }
        }
    }

    public void testImports() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "import java.util.List;\nimport java.util.Map;\nclass Foo { }\n");
            helper.commit("init");
            helper.writeFile("Foo.java", "import java.util.Map;\nimport java.util.Set;\nimport java.util.List;\nimport static java.lang.Math.max;\nclass Foo { }\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ImportChanges imports = analyzer.analyzeChanges().files().get(0).importChanges();
                assertEquals(List.of("java.util.Set", "static java.lang.Math.max"), imports.added());
                assertEquals(List.of(), imports.removed());
            }
        }
    }
}
