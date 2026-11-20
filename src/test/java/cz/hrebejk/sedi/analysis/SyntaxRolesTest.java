/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import cz.hrebejk.sedi.GitTestHelper;
import cz.hrebejk.sedi.compilation.AstLoader;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Tests of {@link SyntaxRoles}.
 */
public class SyntaxRolesTest extends TestCase {

    private static final String SOURCE = """
            import java.util.List;
            class Foo<T> {
                static final int MAX = 1;
                int count;
                // a comment with int and Foo
                record P(int x) { }
                sealed interface S permits A { }
                final class A implements S { }
                enum E { RED, GREEN }
                @Deprecated
                int m(int p, List<T> list) {
                    var local = "a" + 'b' + true + null + 1.5;
                    int record = 0;
                    int r = Math.max(MAX, count + p);
                    Runnable run = this::m2;
                    return switch (p) { case 1 -> { yield E.RED.ordinal(); } default -> list.size(); };
                }
                void m2() { }
                Foo() { }
            }
            """;

    /**
     * The source with the runs of a role in braces, like {@code {keyword:int}}, a declaration marked by {@code *},
     * like {@code {*field:count}}.
     */
    private static String marked(String source) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path file = helper.writeFile("Foo.java", source);
            CompilationUnitInfo unit = TestSources.load(new AstLoader(), List.of(file)).getFirst();
            SyntaxRoles roles = SyntaxRoles.of(unit, source, unit.compilationUnit());
            StringBuilder out = new StringBuilder();
            for (SyntaxRoles.Run run : roles.runs(0, source.length())) {
                if (run.role() == null && !run.declaration()) {
                    out.append(run.text());
                } else {
                    out.append('{').append(run.declaration() ? "*" : "")
                            .append(run.role() == null ? "" : run.role().name().toLowerCase(Locale.ROOT))
                            .append(':').append(run.text()).append('}');
                }
            }
            return out.toString();
        }
    }

    private static void assertHas(String expected, String marked) {
        assertTrue("No\n" + expected + "\nin\n" + marked, marked.contains(expected));
    }

    public void testLiterals() throws Exception {
        assertHas("{keyword:var} local = {literal:\"a\"} + {literal:'b'} + {literal:true} + {literal:null} + "
                + "{literal:1.5};", marked(SOURCE));
    }

    /** The names are told by what they refer to, not by how they are written. */
    public void testNames() throws Exception {
        String marked = marked(SOURCE);
        assertHas("{keyword:int} r = {type:Math}.{method:max}({constant:MAX}, {field:count} + p);", marked);
        assertHas("{type:Runnable} run = {keyword:this}::{method:m2};", marked);
        assertHas("{keyword:int} {*method:m}({keyword:int} p, {type:List}<{type:T}> list)", marked);
        assertHas("{keyword:import} java.util.{type:List};", marked);
    }

    /** The names of declared types, methods and fields are declarations, the names of local variables are not. */
    public void testDeclarations() throws Exception {
        String marked = marked(SOURCE);
        assertHas("{keyword:class} {*type:Foo}<{type:T}>", marked);
        assertHas("{keyword:static} {keyword:final} {keyword:int} {*constant:MAX} = {literal:1};", marked);
        assertHas("{keyword:int} {*field:count};", marked);
        assertHas("{keyword:record} {*type:P}({keyword:int} {*field:x})", marked);
        assertHas("{keyword:enum} {*type:E} { {*constant:RED}, {*constant:GREEN} }", marked);
        assertHas("{*method:Foo}() { }", marked);
    }

    /** The contextual keywords are keywords only where they are used as such. */
    public void testContextualKeywords() throws Exception {
        String marked = marked(SOURCE);
        assertHas("{keyword:sealed} {keyword:interface} {*type:S} {keyword:permits} {type:A}", marked);
        assertHas("{keyword:int} record = {literal:0};", marked);
        assertHas("{keyword:yield} {type:E}.{constant:RED}.{method:ordinal}();", marked);
    }

    public void testCommentsAndAnnotations() throws Exception {
        String marked = marked(SOURCE);
        assertHas("{comment:// a comment with int and Foo}", marked);
        assertHas("{annotation:@Deprecated}", marked);
    }

    /** The roles of a part of the unit are those of its trees. */
    public void testPart() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            String source = "class Foo {\n    int f = 1;\n    int m() { return f; }\n}\n";
            Path file = helper.writeFile("Foo.java", source);
            CompilationUnitInfo unit = TestSources.load(new AstLoader(), List.of(file)).getFirst();
            ClassTree type = (ClassTree) unit.compilationUnit()
                    .getTypeDecls().getFirst();
            MethodTree method = type.getMembers().stream()
                    .filter(t -> t instanceof MethodTree m && m.getName().contentEquals("m"))
                    .map(MethodTree.class::cast).findFirst().orElseThrow();
            SyntaxRoles roles = SyntaxRoles.of(unit, method.getBody());
            int f = source.indexOf("f; }");
            assertEquals(SyntaxRoles.Role.FIELD, roles.role(f));
            assertEquals(SyntaxRoles.Role.KEYWORD, roles.role(source.indexOf("return")));
            assertNull("Out of the part", roles.role(source.indexOf("int f")));
        }
    }
}
