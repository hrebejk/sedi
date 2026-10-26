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
import cz.hrebejk.sedi.compilation.AstLoader;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;
import junit.framework.TestCase;

import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link MemberMatcher} and the facet hashes it relies on.
 */
public class MemberMatcherTest extends TestCase {

    private final AstLoader loader = new AstLoader();

    private ElementInfo type(GitTestHelper helper, String members) throws Exception {
        String src = "package p;\n\npublic class T {\n" + members + "\n}\n";
        List<CompilationUnitInfo> analyses = TestSources.load(loader, List.of(helper.writeFile("T.java", src)));
        ElementInfo root = new ElementHashCalculator(analyses.get(0).trees()).calculate(analyses.get(0).compilationUnit());
        return root.getChildren().get(0);
    }

    private List<ElementPair> match(String oldMembers, String newMembers) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            ElementInfo oldType = type(helper, oldMembers);
            ElementInfo newType = type(helper, newMembers);
            return MemberMatcher.match(oldType, newType);
        }
    }

    public void testUnchangedMembersAreDropped() throws Exception {
        String m = "int f; public void a() {} public T() {}";
        assertTrue(match(m, m).isEmpty());
    }

    public void testBodyChangeIsUnchanged() throws Exception {
        assertTrue(match("void a() { int x = 1; }", "void a() { int x = 2; }").isEmpty());
    }

    public void testModifierChange() throws Exception {
        List<ElementPair> pairs = match("void a() {}", "public void a() {}");
        assertEquals(1, pairs.size());
        assertEquals(Set.of(Facet.MODIFIERS), pairs.get(0).changedFacets());
    }

    public void testAnnotationThrowsAndReturnTypeChange() throws Exception {
        List<ElementPair> pairs = match("int a() { return 0; }",
                "@Deprecated long a() throws Exception { return 0; }");
        assertEquals(1, pairs.size());
        assertEquals(Set.of(Facet.ANNOTATIONS, Facet.THROWS, Facet.RETURN_TYPE), pairs.get(0).changedFacets());
    }

    public void testParameterRenameKeepsParamTypesFacet() throws Exception {
        List<ElementPair> pairs = match("void a(int x) {}", "void a(int y) {}");
        assertEquals(1, pairs.size());
        assertTrue(pairs.get(0).changedFacets().isEmpty());
    }

    public void testEqualIgnoreLocalNamesHashPairsMembers() throws Exception {
        List<ElementPair> pairs = match("void a(int x) { int i = x; } void a(String s) { String t = s; }",
                "void a(String q) { String r = q; } void a(int y) { int j = y; }");
        assertEquals(2, pairs.size());
        for (ElementPair pair : pairs) {
            assertNotNull(pair.oldElement());
            assertNotNull(pair.newElement());
            assertEquals(pair.oldElement().getSha(ChangeCategory.IGNORE_LOCAL_NAMES),
                    pair.newElement().getSha(ChangeCategory.IGNORE_LOCAL_NAMES));
        }
    }

    public void testChangedOverloadPairsByName() throws Exception {
        List<ElementPair> pairs = match("void a(int x) {}", "void a(String x) {}");
        assertEquals(1, pairs.size());
        assertFalse(pairs.get(0).isAdded() || pairs.get(0).isRemoved());
        assertEquals(Set.of(Facet.PARAM_TYPES), pairs.get(0).changedFacets());
    }

    public void testOneOfTwoOverloadsChanged() throws Exception {
        List<ElementPair> pairs = match("void a(int x) {} void a(String s) {}",
                "void a(int x) {} void a(String s, int n) {}");
        assertEquals(1, pairs.size());
        assertEquals("a", pairs.get(0).oldElement().getName());
        assertEquals(1, pairs.get(0).oldElement().getParamCount());
        assertEquals(2, pairs.get(0).newElement().getParamCount());
    }

    public void testMethodRename() throws Exception {
        List<ElementPair> pairs = match("int a(String s) { return 0; }", "int b(String s) { return 0; }");
        assertEquals(1, pairs.size());
        assertEquals(Set.of(Facet.NAME), pairs.get(0).changedFacets());
    }

    public void testAmbiguousRenameIsNotPaired() throws Exception {
        List<ElementPair> pairs = match("void a() {} void b() {}", "void c() {} void d() {}");
        assertEquals(4, pairs.size());
        for (ElementPair pair : pairs) {
            assertTrue(pair.isAdded() || pair.isRemoved());
        }
    }

    public void testKindsNeverPair() throws Exception {
        List<ElementPair> pairs = match("int a;", "int a() { return 0; }");
        assertEquals(2, pairs.size());
        for (ElementPair pair : pairs) {
            assertTrue(pair.isAdded() || pair.isRemoved());
        }
    }

    public void testConstructorNeverPairsWithMethod() throws Exception {
        // Note: a class without an explicit constructor still has a (synthetic) <init> child.
        List<ElementPair> pairs = match("T(int x) {}", "T() {} void m(int x) {}");
        assertEquals(2, pairs.size());
        ElementPair ctor = pairs.stream().filter(p -> !p.isAdded() && !p.isRemoved()).findFirst().orElseThrow();
        assertEquals("<init>", ctor.oldElement().getName());
        assertEquals("<init>", ctor.newElement().getName());
        assertTrue(pairs.stream().anyMatch(p -> p.isAdded() && p.newElement().getName().equals("m")));
    }

    public void testAddedAndRemoved() throws Exception {
        List<ElementPair> pairs = match("int keep; int gone;", "int keep; String fresh;");
        assertEquals(2, pairs.size());
        assertTrue(pairs.stream().anyMatch(p -> p.isRemoved() && p.oldElement().getName().equals("gone")));
        assertTrue(pairs.stream().anyMatch(p -> p.isAdded() && p.newElement().getName().equals("fresh")));
    }

    public void testFieldTypeChange() throws Exception {
        List<ElementPair> pairs = match("int f;", "long f;");
        assertEquals(1, pairs.size());
        assertEquals(Set.of(Facet.RETURN_TYPE), pairs.get(0).changedFacets());
    }
}
