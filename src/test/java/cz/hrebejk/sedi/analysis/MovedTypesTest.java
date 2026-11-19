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

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests that types moved into another type or out of one are paired as moves.
 */
public class MovedTypesTest extends TestCase {

    private static FileAnalysis file(List<FileAnalysis> files, String path) {
        return files.stream().filter(f -> f.diff().getNewPath().equals(path) || f.diff().getOldPath().equals(path))
                .findFirst().orElseThrow();
    }

    /** All the type pairs in the changes as {@code old->new}, {@code -} for a missing version, at any depth. */
    private static List<String> typePairs(ApiChanges changes) {
        List<String> result = new ArrayList<>();
        for (MemberChange change : changes.changes()) {
            ElementPair pair = change.pair();
            ElementInfo shown = pair.isRemoved() ? pair.oldElement() : pair.newElement();
            if (shown.getNodeType() == ElementInfo.NodeType.TYPE) {
                result.add(name(pair.oldElement()) + "->" + name(pair.newElement()));
            }
        }
        for (ApiChanges nested : changes.nested()) {
            result.addAll(typePairs(nested));
        }
        return result;
    }

    private static String name(ElementInfo element) {
        return element == null ? "-" : element.getName();
    }

    /** The analyzed files after the given files are written, committed and then the changes made. */
    private static List<FileAnalysis> analyze(GitTestHelper helper) throws Exception {
        try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
            return analyzer.analyzeChanges().files();
        }
    }

    /**
     * A top level type which became a nested type of a new type in another package is a move, and the references to
     * it by its new name are cosmetic.
     */
    public void testTopLevelTypeMovedIntoNewType() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("a/Color.java", "package a;\npublic class Color {\n  public int r;\n"
                    + "  public int g() { return r; }\n}\n");
            helper.writeFile("c/User.java", "package c;\nimport a.Color;\npublic class User {\n"
                    + "  int use(Color c) { return c.g(); }\n}\n");
            helper.commit("init");
            Files.delete(helper.getRepositoryPath().resolve("a/Color.java"));
            helper.writeFile("b/Values.java", "package b;\npublic class Values {\n  public static class Color {\n"
                    + "    public int r;\n    public int g() { return r; }\n  }\n}\n");
            helper.writeFile("c/User.java", "package c;\nimport b.Values;\npublic class User {\n"
                    + "  int use(Values.Color c) { return c.g(); }\n}\n");
            List<FileAnalysis> files = analyze(helper);

            // The old Color is not removed: it is in the move pair.
            assertEquals(List.of(), typePairs(file(files, "a/Color.java").rootChanges()));
            ApiChanges root = file(files, "b/Values.java").rootChanges();
            assertEquals(List.of("-->Values", "Color->Color"), typePairs(root));
            ApiChanges values = root.nested().getFirst();
            assertNull(values.type().oldElement());
            assertEquals("Values", values.type().newElement().getName());
            assertEquals(1, values.changes().size());
            MemberChange move = values.changes().getFirst();
            assertEquals(ChangeLevel.API, move.level());
            assertEquals("a.Color", move.pair().newElement().getMovedFrom());
            assertEquals("b.Values.Color", move.pair().newElement().getMovedTo());
            assertEquals("Color", move.pair().oldElement().getName());
            assertSame(move.pair().newElement(), values.nested().getFirst().type().newElement());

            List<ChangeLevel> user = file(files, "c/User.java").rootChanges().nested().getFirst().changes().stream()
                    .map(MemberChange::level).toList();
            assertEquals(List.of(ChangeLevel.COSMETIC), user);
        }
    }

    /** A nested type which became a top level type is a move, not removed from its old container. */
    public void testNestedTypeMovedToTopLevel() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("p/Outer.java", "package p;\npublic class Outer {\n  public void keep() { }\n"
                    + "  public static class Inner {\n    public int x;\n    public void y() { }\n  }\n}\n");
            helper.commit("init");
            helper.writeFile("p/Outer.java", "package p;\npublic class Outer {\n  public void keep() { }\n}\n");
            helper.writeFile("p/Inner.java", "package p;\npublic class Inner {\n  public int x;\n"
                    + "  public void y() { }\n}\n");
            List<FileAnalysis> files = analyze(helper);

            assertEquals(List.of(), typePairs(file(files, "p/Outer.java").rootChanges()));
            ApiChanges root = file(files, "p/Inner.java").rootChanges();
            assertEquals(List.of("Inner->Inner"), typePairs(root));
            ElementInfo inner = root.changes().getFirst().pair().newElement();
            assertEquals("p.Outer.Inner", inner.getMovedFrom());
            assertEquals("p.Inner", inner.getMovedTo());
        }
    }

    /** Types of the same name with mostly different members did not move: one is removed, the other added. */
    public void testDifferentMembersAreNoMove() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("a/Color.java", "package a;\npublic class Color {\n  public int r;\n"
                    + "  public void g() { }\n  public void h() { }\n}\n");
            helper.commit("init");
            Files.delete(helper.getRepositoryPath().resolve("a/Color.java"));
            helper.writeFile("b/Values.java", "package b;\npublic class Values {\n  public static class Color {\n"
                    + "    public int r;\n    public void x() { }\n    public void y() { }\n  }\n}\n");
            List<FileAnalysis> files = analyze(helper);

            assertEquals(List.of("Color->-"), typePairs(file(files, "a/Color.java").rootChanges()));
            ApiChanges root = file(files, "b/Values.java").rootChanges();
            assertEquals(List.of("-->Values"), typePairs(root));
            assertNull(root.changes().getFirst().pair().newElement().getChildren().stream()
                    .filter(c -> c.getName().equals("Color")).findFirst().orElseThrow().getMovedFrom());
        }
    }

    /** Types without members move by their names and kinds alone. */
    public void testEmptyTypesMove() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("p/Outer.java", "package p;\npublic class Outer {\n  public void keep() { }\n"
                    + "  public interface Marker { }\n}\n");
            helper.commit("init");
            helper.writeFile("p/Outer.java", "package p;\npublic class Outer {\n  public void keep() { }\n}\n");
            helper.writeFile("p/Marker.java", "package p;\npublic interface Marker { }\n");
            List<FileAnalysis> files = analyze(helper);

            assertEquals(List.of(), typePairs(file(files, "p/Outer.java").rootChanges()));
            ApiChanges root = file(files, "p/Marker.java").rootChanges();
            assertEquals(List.of("Marker->Marker"), typePairs(root));
            assertEquals("p.Outer.Marker", root.changes().getFirst().pair().newElement().getMovedFrom());
        }
    }
}
