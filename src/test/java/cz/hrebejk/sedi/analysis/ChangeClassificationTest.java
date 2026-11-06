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

    /** A renamed parameter is cosmetic, like a renamed local, also in a public method. */
    public void testParameterRenameIsCosmetic() throws Exception {
        assertEquals(List.of("COSMETIC:yesno"),
                classify("public class Foo { public int yesno(boolean p) { return p ? 1 : 0; } }",
                        "public class Foo { public int yesno(boolean bool) { return bool ? 1 : 0; } }"));
    }

    /** A type referred to by its qualified name instead of the simple one, or the other way around, is cosmetic. */
    public void testQualifiedTypeNameIsCosmetic() throws Exception {
        assertEquals(List.of("COSMETIC:m"),
                classify("import java.util.List; class Foo { int m() { List<String> l = List.of(); return l.size(); } }",
                        "import java.util.List; class Foo { int m() { java.util.List<String> l = java.util.List.of();"
                                + " return l.size(); } }"));
        assertEquals(List.of("COSMETIC:m"),
                classify("class Foo { int m(java.util.List<String> l) { return l.size(); } }",
                        "import java.util.List; class Foo { int m(List<String> l) { return l.size(); } }"));
    }

    /** A static member qualified by its type instead of imported statically, or the other way around, is cosmetic. */
    public void testQualifiedStaticMemberIsCosmetic() throws Exception {
        assertEquals(List.of("COSMETIC:m", "COSMETIC:f"),
                classify("import static java.lang.Math.max; class Foo { static final int C = 1;"
                                + " int m(int a) { return max(a, C); } int f = C; }",
                        "class Foo { static final int C = 1;"
                                + " int m(int a) { return Math.max(a, Foo.C); } int f = Foo.C; }"));
    }

    /** An enum constant qualified by its enum or not is cosmetic: in expressions, case labels and annotations. */
    public void testQualifiedEnumConstantIsCosmetic() throws Exception {
        assertEquals(List.of("COSMETIC:m", "COSMETIC:f"),
                classify("import java.lang.annotation.*; import static java.lang.annotation.RetentionPolicy.SOURCE;"
                                + " class Foo { enum Color { RED, GREEN }"
                                + " @Retention(SOURCE) @interface A { }"
                                + " int m(Color c) { switch (c) { case RED: return 1; default: return c == Color.GREEN ? 2 : 0; } }"
                                + " Color f = Color.RED; }",
                        "import java.lang.annotation.*;"
                                + " class Foo { enum Color { RED, GREEN }"
                                + " @Retention(RetentionPolicy.SOURCE) @interface A { }"
                                + " int m(Color c) { switch (c) { case Color.RED: return 1; default: return c == Foo.Color.GREEN ? 2 : 0; } }"
                                + " Color f = Foo.Color.RED; }"));
    }

    /** Inside the enum its constants may be referred to with or without the name of the enum. */
    public void testEnumConstantInsideEnumIsCosmetic() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "enum Foo { A, B; Foo next() { return this == A ? B : A; } }");
            helper.commit("init");
            helper.writeFile("Foo.java", "enum Foo { A, B; Foo next() { return this == Foo.A ? Foo.B : A; } }");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                ApiChanges type = analyzer.analyzeChanges().files().get(0).rootChanges().nested().get(0);
                assertEquals(List.of("COSMETIC:next"), type.changes().stream()
                        .map(c -> c.level() + ":" + c.pair().newElement().getName()).toList());
            }
        }
    }

    /** In a body changed otherwise, a name changed from a qualified to a simple one is not marked. */
    public void testQualifiedNameIsNotMarkedInChangedBody() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Foo.java", "class Foo { int m(int a) { return Math.max(a, 1); } }");
            helper.commit("init");
            helper.writeFile("Foo.java", "import static java.lang.Math.max; class Foo { int m(int a) { return max(a, 2); } }");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                MemberChange change = analyzer.analyzeChanges().files().get(0).rootChanges().nested().get(0)
                        .changes().get(0);
                assertEquals(ChangeLevel.IMPLEMENTATION, change.level());
                List<String> edited = change.body().marks().entrySet().stream()
                        .filter(e -> !(e.getValue() instanceof BodyChanges.Changed))
                        .map(e -> e.getKey().toString()).toList();
                assertEquals(List.of("2"), edited);
            }
        }
    }

    /** A static member of another type with the same name is a different element. */
    public void testOtherStaticMemberIsImplementation() throws Exception {
        assertEquals(List.of("IMPLEMENTATION:m"),
                classify("class Foo { int m(int a) { return Math.max(a, 1); } }",
                        "class Foo { int m(int a) { return StrictMath.max(a, 1); } }"));
    }

    /** A field qualified by an expression rather than a type is not just another name of the element. */
    public void testFieldQualifiedByThisIsNotIgnored() throws Exception {
        assertEquals(List.of("IMPLEMENTATION:m"),
                classify("class Foo { int x; Foo o; int m() { return x; } }",
                        "class Foo { int x; Foo o; int m() { return o.x; } }"));
    }

    /** Changes of whitespace alone are not reported at all. */
    public void testWhitespaceIsNotReported() throws Exception {
        assertEquals(List.of(),
                classify("class Foo { int m() { return 1; } }", "class Foo { int m() {\n    return  1;\n  } }"));
    }

    /** A comment in a body is a part of the implementation; a reformatted one is not a change. */
    public void testCommentInBodyIsImplementation() throws Exception {
        assertEquals(List.of("IMPLEMENTATION:m"),
                classify("class Foo { int m() { return 1; } }", "class Foo { int m() {\n    return 1;  // one\n  } }"));
        assertEquals(List.of(),
                classify("class Foo { int m() { /* a  b */ return 1; } }", "class Foo { int m() {\n /* a\n b */\n return 1; } }"));
    }

    /** A member with both kinds of changes has an implementation and a cosmetic change, each with its own body. */
    public void testImplementationAndCosmeticChangesTogether() throws Exception {
        assertEquals(List.of("IMPLEMENTATION:m", "COSMETIC:m"),
                classify("class Foo { int m() { int a = 1; return a + 1; } }",
                        "class Foo { int m() { int b = 1; return b + 2; } }"));
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

    /**
     * When a class is renamed, its constructors and the members referring to it change only by the rename, which
     * is cosmetic; a constructor whose signature changed otherwise is an API change.
     */
    public void testTypeRenameChangesReferencesOnlyCosmetically() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("A.java", "public class A {\n    public A() { }\n    public A(A other) { }\n"
                    + "    public A(int x) { }\n    public A copy() { return new A(this); }\n}\n");
            helper.commit("init");
            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("A.java"));
            helper.writeFile("B.java", "public class B {\n    public B() { }\n    public B(B other) { }\n"
                    + "    public B(long x) { }\n    public B copy() { return new B(this); }\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                FileAnalysis file = analyzer.analyzeChanges().files().get(0);
                assertEquals("B", file.newPrimary().getName());
                List<String> changes = file.primaryChanges().changes().stream()
                        .map(c -> c.level() + ":" + c.pair().newElement().getName() + "/"
                                + c.pair().newElement().getParamCount()
                                + (c.pair().isAdded() || c.pair().isRemoved() ? "!" : ""))
                        .sorted().toList();
                assertEquals(List.of("API:<init>/1", "COSMETIC:<init>/0", "COSMETIC:<init>/1", "COSMETIC:copy/0"),
                        changes);
            }
        }
    }

    /**
     * The constants of a renamed enum do not change, even though javac gives them the type of the enum; a changed
     * argument of a constant is an implementation change.
     */
    public void testEnumRenameDoesNotChangeConstants() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("E1.java", "public enum E1 {\n    YES(1),\n    NO(2),\n    MAYBE { },\n    ;\n"
                    + "    E1() { this(0); }\n    E1(int v) { }\n}\n");
            helper.commit("init");
            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("E1.java"));
            helper.writeFile("E2.java", "public enum E2 {\n    YES(1),\n    NO(3),\n    MAYBE { },\n    ;\n"
                    + "    E2() { this(0); }\n    E2(int v) { }\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                FileAnalysis file = analyzer.analyzeChanges().files().get(0);
                assertEquals("E2", file.newPrimary().getName());
                List<String> changes = file.primaryChanges().changes().stream()
                        .map(c -> c.level() + ":" + c.pair().newElement().getName()).sorted().toList();
                assertEquals(List.of("COSMETIC:<init>", "COSMETIC:<init>", "IMPLEMENTATION:NO"), changes);
            }
        }
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
