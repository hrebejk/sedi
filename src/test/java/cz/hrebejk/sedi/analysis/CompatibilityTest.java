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
import org.junit.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cz.hrebejk.sedi.analysis.Incompatibility.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests that the API changes are rated by the {@link Incompatibility incompatibilities} they bring to the clients.
 */
public class CompatibilityTest {

    // ---------------------------------------------------------------------------------------------- helpers

    /** Analyzes the change of the single file {@code p/Foo.java}. */
    private static DiffAnalysis analyze(String before, String after) throws Exception {
        return analyze(Map.of("p/Foo.java", before), Map.of("p/Foo.java", after));
    }

    /**
     * Commits the old files, then writes the new ones to the working copy and analyzes the difference. A file of
     * the old version missing in the new one is deleted.
     */
    private static DiffAnalysis analyze(Map<String, String> before, Map<String, String> after) throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            for (Map.Entry<String, String> e : before.entrySet()) {
                helper.writeFile(e.getKey(), e.getValue());
            }
            helper.commit("init");
            for (String path : before.keySet()) {
                if (!after.containsKey(path)) {
                    Files.delete(helper.getRepositoryPath().resolve(path));
                }
            }
            for (Map.Entry<String, String> e : after.entrySet()) {
                helper.writeFile(e.getKey(), e.getValue());
            }
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                return analyzer.analyzeChanges();
            }
        }
    }

    /** All the member changes of the analysis, at any depth, of the root and the primary changes. */
    private static List<MemberChange> allChanges(DiffAnalysis analysis) {
        List<MemberChange> result = new ArrayList<>();
        for (FileAnalysis file : analysis.files()) {
            collect(file.rootChanges(), result);
            if (file.primaryChanges() != null) {
                collect(file.primaryChanges(), result);
            }
        }
        return result;
    }

    private static void collect(ApiChanges changes, List<MemberChange> result) {
        result.addAll(changes.changes());
        for (ApiChanges nested : changes.nested()) {
            collect(nested, result);
        }
    }

    /** The name of the changed element: the new one, or the old one of a removed element. */
    private static String name(MemberChange change) {
        ElementPair pair = change.pair();
        return (pair.isRemoved() ? pair.oldElement() : pair.newElement()).getName();
    }

    /** The incompatibilities of the only change of the named member at the given level. */
    private static Set<Incompatibility> of(DiffAnalysis analysis, String name, ChangeLevel level) {
        return change(analysis, name, level).incompatibilities();
    }

    /** The only change of the named member at the given level. */
    private static MemberChange change(DiffAnalysis analysis, String name, ChangeLevel level) {
        List<MemberChange> found = allChanges(analysis).stream()
                .filter(c -> c.level() == level && name.equals(name(c)))
                .distinct().toList();
        assertEquals("changes of " + name + " at " + level + " in " + describe(analysis), 1, found.size());
        return found.getFirst();
    }

    /** The incompatibilities of the only API change of the named member. */
    private static Set<Incompatibility> of(DiffAnalysis analysis, String name) {
        return of(analysis, name, ChangeLevel.API);
    }

    /** Asserts there is some change, but no incompatibility anywhere in the analysis. */
    private static void assertCompatible(DiffAnalysis analysis) {
        List<MemberChange> changes = allChanges(analysis);
        assertFalse("no change found", changes.isEmpty());
        assertEquals(describe(analysis), List.of(), changes.stream()
                .filter(c -> !c.incompatibilities().isEmpty()).map(CompatibilityTest::describe).toList());
        for (FileAnalysis file : analysis.files()) {
            assertEquals(Set.of(), typeIncompatibilities(file.rootChanges()));
        }
    }

    private static Set<Incompatibility> typeIncompatibilities(ApiChanges changes) {
        Set<Incompatibility> result = EnumSet.noneOf(Incompatibility.class);
        result.addAll(changes.incompatibilities());
        for (ApiChanges nested : changes.nested()) {
            result.addAll(typeIncompatibilities(nested));
        }
        return result;
    }

    private static String describe(MemberChange change) {
        return change.level() + ":" + name(change) + change.incompatibilities();
    }

    private static String describe(DiffAnalysis analysis) {
        return allChanges(analysis).stream().map(CompatibilityTest::describe).toList().toString();
    }

    private static Set<Incompatibility> set(Incompatibility... incompatibilities) {
        return incompatibilities.length == 0 ? Set.of() : EnumSet.of(incompatibilities[0], incompatibilities);
    }

    // ---------------------------------------------------------------------------------------------- breaking

    @Test
    public void publicMethodRemoved() throws Exception {
        DiffAnalysis a = analyze("package p; public class Foo { public void a() { } public void b() { } }",
                "package p; public class Foo { public void a() { } }");
        assertEquals(set(REMOVED), of(a, "b"));
        assertTrue(change(a, "b", ChangeLevel.API).isBreaking());
    }

    @Test
    public void publicMethodRenamed() throws Exception {
        DiffAnalysis a = analyze("package p; public class Foo { public int b(int x) { return x; } }",
                "package p; public class Foo { public int c(int x) { return x; } }");
        assertEquals(set(REMOVED), of(a, "c"));
    }

    /** A type keeps its signature when only its package changes, but its old name is gone. */
    @Test
    public void publicTypeMovedToAnotherPackage() throws Exception {
        String body = "public class Foo {\n  public int f;\n  public void m() { }\n  public void n() { }\n}\n";
        DiffAnalysis a = analyze(Map.of("a/Foo.java", "package a;\n" + body),
                Map.of("b/Foo.java", "package b;\n" + body));
        assertEquals(1, a.files().size());
        assertEquals(set(REMOVED), of(a, "Foo"));
    }

    /** A type renamed together with its file carries the incompatibility in its primary changes. */
    @Test
    public void publicTypeRenamedWithFile() throws Exception {
        String body = " {\n  public int f;\n  public void m() { }\n  public void n() { }\n}\n";
        DiffAnalysis a = analyze(Map.of("p/Foo.java", "package p;\npublic class Foo" + body),
                Map.of("p/Bar.java", "package p;\npublic class Bar" + body));
        assertEquals(1, a.files().size());
        assertEquals(set(REMOVED), a.files().getFirst().primaryChanges().incompatibilities());
    }

    @Test
    public void publicMethodBecamePackagePrivate() throws Exception {
        assertEquals(set(VISIBILITY_REDUCED), of(analyze("package p; public class Foo { public void m() { } }",
                "package p; public class Foo { void m() { } }"), "m"));
    }

    @Test
    public void protectedMethodBecamePrivate() throws Exception {
        assertEquals(set(VISIBILITY_REDUCED), of(analyze("package p; public class Foo { protected void m() { } }",
                "package p; public class Foo { private void m() { } }"), "m"));
    }

    @Test
    public void staticAddedToMethod() throws Exception {
        assertEquals(set(STATIC_CHANGED), of(analyze("package p; public class Foo { public void m() { } }",
                "package p; public class Foo { public static void m() { } }"), "m"));
    }

    @Test
    public void parameterTypeChanged() throws Exception {
        assertEquals(set(PARAMETERS_CHANGED), of(analyze(
                "package p; public class Foo { public void m(int x) { } }",
                "package p; public class Foo { public void m(long x) { } }"), "m"));
    }

    @Test
    public void returnTypeChanged() throws Exception {
        assertEquals(set(TYPE_CHANGED), of(analyze(
                "package p; public class Foo { public int m() { return 1; } }",
                "package p; public class Foo { public long m() { return 1; } }"), "m"));
    }

    @Test
    public void fieldTypeChanged() throws Exception {
        assertEquals(set(TYPE_CHANGED), of(analyze("package p; public class Foo { public int f; }",
                "package p; public class Foo { public long f; }"), "f"));
    }

    @Test
    public void methodTypeParametersChanged() throws Exception {
        assertEquals(set(TYPE_PARAMS_CHANGED), of(analyze(
                "package p; public class Foo { public <T> void m(T t) { } }",
                "package p; public class Foo { public <T, U> void m(T t) { } }"), "m"));
    }

    @Test
    public void checkedExceptionAdded() throws Exception {
        assertEquals(set(EXCEPTION_ADDED), of(analyze("package p; public class Foo { public void m() { } }",
                "package p; public class Foo { public void m() throws java.io.IOException { } }"), "m"));
    }

    @Test
    public void finalAddedToMethodOfExtensibleClass() throws Exception {
        assertEquals(set(FINAL_ADDED), of(analyze("package p; public class Foo { public void m() { } }",
                "package p; public class Foo { public final void m() { } }"), "m"));
    }

    @Test
    public void finalAddedToMethodOfFinalClassIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public final class Foo { public void m() { } }",
                "package p; public final class Foo { public final void m() { } }"));
    }

    @Test
    public void finalAddedToPublicField() throws Exception {
        assertEquals(set(FINAL_ADDED), of(analyze("package p; public class Foo { public int f = 1; }",
                "package p; public class Foo { public final int f = 1; }"), "f"));
    }

    @Test
    public void abstractAddedToMethod() throws Exception {
        assertEquals(set(ABSTRACT_ADDED), of(analyze(
                "package p; public abstract class Foo { public void m() { } }",
                "package p; public abstract class Foo { public abstract void m(); }"), "m"));
    }

    @Test
    public void defaultMethodBecameAbstract() throws Exception {
        assertEquals(set(ABSTRACT_ADDED), of(analyze("package p; public interface Foo { default void m() { } }",
                "package p; public interface Foo { void m(); }"), "m"));
    }

    @Test
    public void abstractMethodAddedToInterface() throws Exception {
        assertEquals(set(ABSTRACT_METHOD_ADDED), of(analyze("package p; public interface Foo { void a(); }",
                "package p; public interface Foo { void a(); void b(); }"), "b"));
    }

    @Test
    public void abstractMethodAddedToExtensibleAbstractClass() throws Exception {
        assertEquals(set(ABSTRACT_METHOD_ADDED), of(analyze(
                "package p; public abstract class Foo { public void a() { } }",
                "package p; public abstract class Foo { public void a() { } public abstract void b(); }"), "b"));
    }

    @Test
    public void abstractMethodAddedToClassWithPrivateConstructorsIsCompatible() throws Exception {
        assertCompatible(analyze(
                "package p; public abstract class Foo { private Foo() { } public void a() { } }",
                "package p; public abstract class Foo { private Foo() { } public void a() { }"
                        + " public abstract void b(); }"));
    }

    @Test
    public void annotationElementWithoutDefaultAdded() throws Exception {
        assertEquals(set(ANNOTATION_ELEMENT_ADDED), of(analyze("package p; public @interface Foo { int a(); }",
                "package p; public @interface Foo { int a(); int b(); }"), "b"));
    }

    @Test
    public void annotationElementWithDefaultAddedIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public @interface Foo { int a(); }",
                "package p; public @interface Foo { int a(); int b() default 0; }"));
    }

    @Test
    public void firstConstructorWithParametersLosesDefaultConstructor() throws Exception {
        assertEquals(set(DEFAULT_CONSTRUCTOR_LOST), of(analyze("package p; public class Foo { public int f; }",
                "package p; public class Foo { public int f; public Foo(int f) { this.f = f; } }"), "<init>"));
    }

    @Test
    public void publicNoArgConstructorAddedKeepsDefaultConstructor() throws Exception {
        assertCompatible(analyze("package p; public class Foo { public int f; }",
                "package p; public class Foo { public int f; public Foo() { } public Foo(int f) { this.f = f; } }"));
    }

    @Test
    public void classBecameFinal() throws Exception {
        assertEquals(set(FINAL_ADDED), of(analyze("package p; public class Foo { public void m() { } }",
                "package p; public final class Foo { public void m() { } }"), "Foo"));
    }

    @Test
    public void classBecameAbstract() throws Exception {
        assertEquals(set(ABSTRACT_ADDED), of(analyze("package p; public class Foo { public void m() { } }",
                "package p; public abstract class Foo { public void m() { } }"), "Foo"));
    }

    @Test
    public void classBecameSealed() throws Exception {
        assertEquals(set(SEALED_ADDED), of(analyze("package p; public class Foo { public void m() { } }",
                "package p; public sealed class Foo { public void m() { } } final class Bar extends Foo { }"),
                "Foo"));
    }

    @Test
    public void classBecameInterface() throws Exception {
        assertEquals(set(KIND_CHANGED), of(analyze("package p; public class Foo { public void m() { } }",
                "package p; public interface Foo { void m(); }"), "Foo"));
    }

    @Test
    public void supertypeRemoved() throws Exception {
        assertEquals(set(SUPERTYPE_REMOVED), of(analyze(
                "package p; public class Foo implements Runnable { public void run() { } }",
                "package p; public class Foo { public void run() { } }"), "Foo"));
    }

    @Test
    public void supertypeAddedIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public class Foo { public void run() { } }",
                "package p; public class Foo implements Runnable { public void run() { } }"));
    }

    @Test
    public void recordComponentAdded() throws Exception {
        assertEquals(set(COMPONENTS_CHANGED), of(analyze("package p; public record Foo(int x) { }",
                "package p; public record Foo(int x, int y) { }"), "Foo"));
    }

    @Test
    public void nestedTypeMovedToTopLevel() throws Exception {
        DiffAnalysis a = analyze(
                Map.of("p/Outer.java", "package p;\npublic class Outer {\n  public void keep() { }\n"
                        + "  public static class Inner {\n    public int x;\n    public void y() { }\n  }\n}\n"),
                Map.of("p/Outer.java", "package p;\npublic class Outer {\n  public void keep() { }\n}\n",
                        "p/Inner.java", "package p;\npublic class Inner {\n  public int x;\n"
                                + "  public void y() { }\n}\n"));
        assertEquals(set(REMOVED), of(a, "Inner"));
    }

    // ---------------------------------------------------------------------------------------------- risky

    @Test
    public void methodAddedToExtensibleClass() throws Exception {
        DiffAnalysis a = analyze("package p; public class Foo { public void a() { } }",
                "package p; public class Foo { public void a() { } public void b() { } }");
        assertEquals(set(METHOD_ADDED), of(a, "b"));
        MemberChange change = change(a, "b", ChangeLevel.API);
        assertTrue(change.isRisky());
        assertFalse(change.isBreaking());
    }

    @Test
    public void defaultMethodAddedToInterface() throws Exception {
        assertEquals(set(DEFAULT_METHOD_ADDED), of(analyze("package p; public interface Foo { void a(); }",
                "package p; public interface Foo { void a(); default void b() { } }"), "b"));
    }

    @Test
    public void enumConstantAdded() throws Exception {
        assertEquals(set(ENUM_CONSTANT_ADDED), of(analyze("package p; public enum Foo { A }",
                "package p; public enum Foo { A, B }"), "B"));
    }

    @Test
    public void exceptionRemovedFromThrows() throws Exception {
        assertEquals(set(EXCEPTION_REMOVED), of(analyze(
                "package p; public class Foo { public void m() throws java.io.IOException { } }",
                "package p; public class Foo { public void m() { } }"), "m"));
    }

    @Test
    public void constantValueChanged() throws Exception {
        DiffAnalysis a = analyze("package p; public class Foo { public static final int C = 1; }",
                "package p; public class Foo { public static final int C = 2; }");
        assertEquals(set(CONSTANT_CHANGED), of(a, "C", ChangeLevel.IMPLEMENTATION));
    }

    /** A field of an interface is a constant without saying it is static and final. */
    @Test
    public void interfaceConstantValueChanged() throws Exception {
        DiffAnalysis a = analyze("package p; public interface Foo { String C = \"a\"; }",
                "package p; public interface Foo { String C = \"b\"; }");
        assertEquals(set(CONSTANT_CHANGED), of(a, "C", ChangeLevel.IMPLEMENTATION));
    }

    @Test
    public void nonConstantInitializerChangedIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public class Foo { public static int c = 1; }",
                "package p; public class Foo { public static int c = 2; }"));
    }

    // ---------------------------------------------------------------------------------------------- compatible

    @Test
    public void changesInPackagePrivateClassAreCompatible() throws Exception {
        assertCompatible(analyze("package p; class Foo { public void a() { } public int b() { return 1; } }",
                "package p; final class Foo { public long b() { return 1; } }"));
    }

    @Test
    public void privateMemberRemovedIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public class Foo { private int f; public void m() { } }",
                "package p; public class Foo { public void m() { } }"));
    }

    @Test
    public void wideningVisibilityIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public class Foo { protected void m() { } }",
                "package p; public class Foo { public void m() { } }"));
    }

    @Test
    public void removingFinalIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public final class Foo { public final void m() { } }",
                "package p; public class Foo { public void m() { } }"));
    }

    @Test
    public void addingStaticMethodIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public class Foo { public void a() { } }",
                "package p; public class Foo { public void a() { } public static void b() { } }"));
    }

    @Test
    public void addingFieldIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public class Foo { public void a() { } }",
                "package p; public class Foo { public void a() { } public int f; }"));
    }

    @Test
    public void javadocOnlyChangeIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public class Foo {\n  /** Old. */\n  public void m() { }\n}\n",
                "package p; public class Foo {\n  /** New. */\n  public void m() { }\n}\n"));
    }

    @Test
    public void bodyOnlyChangeIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public class Foo { public int m() { return 1; } }",
                "package p; public class Foo { public int m() { return 2; } }"));
    }

    @Test
    public void renamedParameterIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public class Foo { public int m(int a) { return a; } }",
                "package p; public class Foo { public int m(int b) { return b; } }"));
    }

    @Test
    public void removingExplicitNoArgConstructorIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public class Foo { public Foo() { } public void m() { } }",
                "package p; public class Foo { public void m() { } }"));
    }

    // ---------------------------------------------------------------------------------------------- inheritance

    private static final String BASE_RUN = "package p; public class Base { public void run() { } }";

    /** The only API change of the named member which was added ({@code true}) or removed ({@code false}). */
    private static MemberChange change(DiffAnalysis analysis, String name, boolean added) {
        List<MemberChange> found = allChanges(analysis).stream()
                .filter(c -> c.level() == ChangeLevel.API && name.equals(name(c))
                        && (added ? c.pair().isAdded() : c.pair().isRemoved()))
                .distinct().toList();
        assertEquals("changes of " + name + " in " + describe(analysis), 1, found.size());
        return found.getFirst();
    }

    @Test
    public void removedOverrideStillInheritedIsCompatible() throws Exception {
        assertCompatible(analyze(
                Map.of("p/Base.java", BASE_RUN,
                        "p/Foo.java", "package p; public class Foo extends Base { public void run() { }"
                                + " public void a() { } }"),
                Map.of("p/Base.java", BASE_RUN,
                        "p/Foo.java", "package p; public class Foo extends Base { public void a() { } }")));
    }

    @Test
    public void removedToStringOverrideIsCompatible() throws Exception {
        assertCompatible(analyze(
                "package p; public class Foo { public String toString() { return \"\"; } public void a() { } }",
                "package p; public class Foo { public void a() { } }"));
    }

    /**
     * The method is still there for the clients of {@code Foo}, inherited from {@code Base}. For {@code Base} it is
     * a new method of an extensible class, so a subclass a client wrote elsewhere may already have one like it.
     */
    @Test
    public void methodPulledUpToSuperclass() throws Exception {
        DiffAnalysis a = analyze(
                Map.of("p/Base.java", "package p; public class Base { public void b() { } }",
                        "p/Foo.java", "package p; public class Foo extends Base { public void m() { }"
                                + " public void a() { } }"),
                Map.of("p/Base.java", "package p; public class Base { public void b() { } public void m() { } }",
                        "p/Foo.java", "package p; public class Foo extends Base { public void a() { } }"));
        assertEquals(Set.of(), change(a, "m", false).incompatibilities());
        assertEquals(set(METHOD_ADDED), change(a, "m", true).incompatibilities());
    }

    @Test
    public void removedOverrideOfAbstractMethod() throws Exception {
        String base = "package p; public abstract class Base { public abstract void run(); }";
        assertEquals(set(ABSTRACT_ADDED), of(analyze(
                Map.of("p/Base.java", base,
                        "p/Foo.java", "package p; public abstract class Foo extends Base { public void run() { }"
                                + " public void a() { } }"),
                Map.of("p/Base.java", base,
                        "p/Foo.java", "package p; public abstract class Foo extends Base { public void a() { } }")),
                "run"));
    }

    @Test
    public void removedOverrideOfLessVisibleMethod() throws Exception {
        String base = "package p; public class Base { protected void run() { } }";
        assertEquals(set(VISIBILITY_REDUCED), of(analyze(
                Map.of("p/Base.java", base,
                        "p/Foo.java", "package p; public class Foo extends Base { public void run() { }"
                                + " public void a() { } }"),
                Map.of("p/Base.java", base,
                        "p/Foo.java", "package p; public class Foo extends Base { public void a() { } }")),
                "run"));
    }

    /** The clients call {@code Foo.get()} returning {@code String}, which the inherited one does not. */
    @Test
    public void removedCovariantOverride() throws Exception {
        String base = "package p; public class Base { public Object get() { return null; } }";
        assertEquals(set(REMOVED), of(analyze(
                Map.of("p/Base.java", base,
                        "p/Foo.java", "package p; public class Foo extends Base { public String get() { return \"\"; }"
                                + " public void a() { } }"),
                Map.of("p/Base.java", base,
                        "p/Foo.java", "package p; public class Foo extends Base { public void a() { } }")),
                "get"));
    }

    @Test
    public void removedInterfaceMethodStillInSuperinterfaceIsCompatible() throws Exception {
        String sup = "package p; public interface Sup { void run(); }";
        assertCompatible(analyze(
                Map.of("p/Sup.java", sup,
                        "p/Foo.java", "package p; public interface Foo extends Sup { void run(); void a(); }"),
                Map.of("p/Sup.java", sup,
                        "p/Foo.java", "package p; public interface Foo extends Sup { void a(); }")));
    }

    @Test
    public void redundantSupertypeRemovedIsCompatible() throws Exception {
        String base = "package p; public class Base implements Runnable { public void run() { } }";
        assertCompatible(analyze(
                Map.of("p/Base.java", base,
                        "p/Foo.java", "package p; public class Foo extends Base implements Runnable { }"),
                Map.of("p/Base.java", base,
                        "p/Foo.java", "package p; public class Foo extends Base { }")));
    }

    @Test
    public void supertypeNotInheritedOtherwiseRemoved() throws Exception {
        String base = "package p; public class Base { public void run() { } }";
        assertEquals(set(SUPERTYPE_REMOVED), of(analyze(
                Map.of("p/Base.java", base,
                        "p/Foo.java", "package p; public class Foo extends Base implements Runnable { }"),
                Map.of("p/Base.java", base,
                        "p/Foo.java", "package p; public class Foo extends Base { }")),
                "Foo"));
    }

    @Test
    public void addedOverridesAreNotNewMethods() throws Exception {
        DiffAnalysis a = analyze(
                Map.of("p/Base.java", BASE_RUN,
                        "p/Foo.java", "package p; public class Foo extends Base { public void a() { } }"),
                Map.of("p/Base.java", BASE_RUN,
                        "p/Foo.java", "package p; public class Foo extends Base { public void a() { }"
                                + " public void run() { } public String toString() { return \"\"; }"
                                + " public void b() { } }"));
        assertEquals(Set.of(), of(a, "run"));
        assertEquals(Set.of(), of(a, "toString"));
        assertEquals(set(METHOD_ADDED), of(a, "b"));
    }

    @Test
    public void interfaceRedeclaringToStringIsCompatible() throws Exception {
        assertCompatible(analyze("package p; public interface Foo { void a(); }",
                "package p; public interface Foo { void a(); String toString(); }"));
    }

    @Test
    public void interfaceRedeclaringDefaultMethodAsAbstract() throws Exception {
        String sup = "package p; public interface Sup { default void m() { } }";
        assertEquals(set(ABSTRACT_METHOD_ADDED), of(analyze(
                Map.of("p/Sup.java", sup, "p/Foo.java", "package p; public interface Foo extends Sup { void a(); }"),
                Map.of("p/Sup.java", sup,
                        "p/Foo.java", "package p; public interface Foo extends Sup { void a(); void m(); }")),
                "m"));
    }

    /** Analyzes a change of the package private {@code p/Base.java} next to the unchanged {@code p/Foo.java}. */
    private static DiffAnalysis analyzeBase(String foo, String before, String after) throws Exception {
        return analyze(Map.of("p/Foo.java", foo, "p/Base.java", before),
                Map.of("p/Foo.java", foo, "p/Base.java", after));
    }

    private static final String FOO_EXTENDS_BASE = "package p; public class Foo extends Base { }";

    @Test
    public void methodRemovedFromPackagePrivateBaseOfPublicClass() throws Exception {
        assertEquals(set(REMOVED), of(analyzeBase(FOO_EXTENDS_BASE,
                "package p; class Base { public void m() { } public void k() { } }",
                "package p; class Base { public void k() { } }"), "m"));
    }

    @Test
    public void returnTypeChangedInPackagePrivateBaseOfPublicClass() throws Exception {
        assertEquals(set(TYPE_CHANGED), of(analyzeBase(FOO_EXTENDS_BASE,
                "package p; class Base { public void m() { } }",
                "package p; class Base { public int m() { return 1; } }"), "m"));
    }

    @Test
    public void methodAddedToPackagePrivateBaseOfExtensiblePublicClass() throws Exception {
        assertEquals(set(METHOD_ADDED), of(analyzeBase(FOO_EXTENDS_BASE,
                "package p; class Base { public void k() { } }",
                "package p; class Base { public void k() { } public void m() { } }"), "m"));
    }

    @Test
    public void methodAddedToPackagePrivateBaseOfFinalPublicClassIsCompatible() throws Exception {
        assertCompatible(analyzeBase("package p; public final class Foo extends Base { }",
                "package p; class Base { public void k() { } }",
                "package p; class Base { public void k() { } public void m() { } }"));
    }

    @Test
    public void packagePrivateBaseWithoutPublicSubclassIsCompatible() throws Exception {
        assertCompatible(analyzeBase("package p; public class Foo { }",
                "package p; class Base { public void m() { } public void k() { } }",
                "package p; class Base { public int k() { return 1; } public void n() { } }"));
    }

    @Test
    public void methodRemovedFromPackagePrivateNestedBase() throws Exception {
        assertEquals(set(REMOVED), of(analyze(
                Map.of("p/Outer.java", "package p; public class Outer {"
                        + " static class Base { public void m() { } public void k() { } }"
                        + " public static class Sub extends Base { } }"),
                Map.of("p/Outer.java", "package p; public class Outer { static class Base { public void k() { } }"
                        + " public static class Sub extends Base { } }")), "m"));
    }
}
