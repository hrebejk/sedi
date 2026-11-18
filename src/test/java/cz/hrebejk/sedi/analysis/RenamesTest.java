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
import cz.hrebejk.sedi.report.Reporter;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.util.List;

/**
 * Tests that renames are tracked by element, across files, in the working copy and in commits.
 */
public class RenamesTest extends TestCase {

    private static final String A_OLD = "package p;\npublic class A {\n  public int count;\n  public void helper() { }\n"
            + "  public void other() { }\n  public void more() { }\n}\n";
    private static final String A_NEW = "package p;\npublic class A {\n  public int total;\n  public void assist() { }\n"
            + "  public void other() { }\n  public void more() { }\n}\n";
    private static final String C = "package p;\npublic class C {\n  public void helper() { }\n  public void assist() { }\n}\n";

    private static String b(String call, String field) {
        return "package p;\npublic class B {\n  int use(A a) { a." + call + "(); return a." + field + "; }\n}\n";
    }

    private static ElementInfo use(FileAnalysis analysis, boolean old, Renames renames) {
        ElementInfo root = old
                ? new ElementHashCalculator(analysis.oldUnit().trees()).calculate(analysis.oldUnit().compilationUnit())
                : new ElementHashCalculator(analysis.newUnit().trees(), renames).calculate(analysis.newUnit().compilationUnit());
        return root.getChildren().get(0).getChildren().stream()
                .filter(c -> c.getName().equals("use")).findFirst().orElseThrow();
    }

    private static FileAnalysis fileNamed(List<FileAnalysis> analyses, String name) {
        return analyses.stream().filter(a -> a.diff().getNewPath().endsWith(name)).findFirst().orElseThrow();
    }

    private static String sha(ElementInfo element, ChangeCategory category) {
        return element.getSha(category);
    }

    /**
     * A reference from another file to a renamed method and field is not a change.
     */
    public void testRenameSeenFromAnotherFile() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/A.java", A_OLD);
            helper.writeFile("src/main/java/p/B.java", b("helper", "count"));
            helper.commit("init");
            helper.writeFile("src/main/java/p/A.java", A_NEW);
            helper.writeFile("src/main/java/p/B.java", b("assist", "total"));

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                DiffAnalysis analysis = analyzer.analyzeChanges();
                List<FileAnalysis> analyses = analysis.files();
                Renames renames = analysis.renames();
                FileAnalysis b = fileNamed(analyses, "B.java");
                ElementInfo oldUse = use(b, true, renames);
                ElementInfo newUse = use(b, false, renames);

                assertEquals(sha(oldUse, ChangeCategory.WITHOUT_RENAMES), sha(newUse, ChangeCategory.WITHOUT_RENAMES));
                // Without the renames, or in the other categories, the names are different.
                assertFalse(sha(oldUse, ChangeCategory.IGNORE_LOCAL_NAMES).equals(sha(newUse, ChangeCategory.IGNORE_LOCAL_NAMES)));
                assertFalse(sha(oldUse, ChangeCategory.WITHOUT_RENAMES).equals(
                        sha(use(b, false, Renames.EMPTY), ChangeCategory.WITHOUT_RENAMES)));
                // Nothing to report in B: the API did not change.
                assertTrue(new Reporter().report(b).children.isEmpty());
            }
        }
    }

    /**
     * Another element which merely has the new name of the renamed one is not treated as renamed.
     */
    public void testSameNameOfAnotherElementIsNotARename() throws Exception {
        String oldB = "package p;\npublic class B {\n  void use(C c) { c.helper(); }\n}\n";
        String newB = "package p;\npublic class B {\n  void use(C c) { c.assist(); }\n}\n";
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/A.java", A_OLD);
            helper.writeFile("src/main/java/p/B.java", oldB);
            helper.writeFile("src/main/java/p/C.java", C);
            helper.commit("init");
            helper.writeFile("src/main/java/p/A.java", A_NEW);
            helper.writeFile("src/main/java/p/B.java", newB);

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                DiffAnalysis analysis = analyzer.analyzeChanges();
                List<FileAnalysis> analyses = analysis.files();
                Renames renames = analysis.renames();
                FileAnalysis b = fileNamed(analyses, "B.java");
                assertEquals("helper", renames.oldName("p.A#assist()", "assist"));
                assertEquals("assist", renames.oldName("p.C#assist()", "assist"));
                assertFalse(sha(use(b, true, renames), ChangeCategory.WITHOUT_RENAMES).equals(
                        sha(use(b, false, renames), ChangeCategory.WITHOUT_RENAMES)));
            }
        }
    }

    /**
     * Changing the name after the dot of a member select changes the hash.
     */
    public void testMemberSelectIsHashed() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/A.java", A_OLD);
            helper.writeFile("src/main/java/p/B.java", b("helper", "count"));
            helper.commit("init");
            helper.writeFile("src/main/java/p/B.java", b("other", "count"));

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                FileAnalysis b = analyzer.analyzeChanges().files().get(0);
                for (ChangeCategory category : List.of(ChangeCategory.FULL, ChangeCategory.IGNORE_LOCAL_NAMES,
                        ChangeCategory.WITHOUT_RENAMES)) {
                    assertFalse(category.name(), sha(use(b, true, Renames.EMPTY), category)
                            .equals(sha(use(b, false, Renames.EMPTY), category)));
                }
            }
        }
    }

    /**
     * In a commit the classes referred to are read from git, not from the working tree.
     */
    public void testCommitReadsSourcesFromGit() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/A.java", A_OLD);
            helper.writeFile("src/main/java/p/B.java", b("helper", "count"));
            helper.commit("init");
            helper.writeFile("src/main/java/p/A.java", A_NEW);
            helper.writeFile("src/main/java/p/B.java", b("assist", "total"));
            helper.commit("rename");
            // The working tree no longer has anything the commit refers to.
            Files.writeString(helper.getRepositoryPath().resolve("src/main/java/p/A.java"), "package p;\npublic class A { }\n");

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                DiffAnalysis analysis = analyzer.analyzeCommit("HEAD", p -> p.endsWith(".java"));
                List<FileAnalysis> analyses = analysis.files();
                Renames renames = analysis.renames();
                FileAnalysis b = fileNamed(analyses, "B.java");
                assertEquals(sha(use(b, true, renames), ChangeCategory.WITHOUT_RENAMES),
                        sha(use(b, false, renames), ChangeCategory.WITHOUT_RENAMES));
            }
        }
    }

    /**
     * A renamed type, also when it is nested in a renamed type, is replaced in the qualified names.
     */
    public void testRenamedTypeReference() throws Exception {
        String oldA = "package p;\npublic class A {\n  public static class In { public void m() { } public void n() { } }\n  public void x() { }\n}\n";
        String newA = "package p;\npublic class A {\n  public static class Out { public void m() { } public void n() { } }\n  public void x() { }\n}\n";
        String oldB = "package p;\npublic class B {\n  int use(A.In in) { in.m(); return 0; }\n}\n";
        String newB = "package p;\npublic class B {\n  int use(A.Out in) { in.m(); return 0; }\n}\n";
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/A.java", oldA);
            helper.writeFile("src/main/java/p/B.java", oldB);
            helper.commit("init");
            helper.writeFile("src/main/java/p/A.java", newA);
            helper.writeFile("src/main/java/p/B.java", newB);

            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                DiffAnalysis analysis = analyzer.analyzeChanges();
                List<FileAnalysis> analyses = analysis.files();
                Renames renames = analysis.renames();
                assertEquals("In", renames.oldName("p.A.Out", "Out"));
                FileAnalysis b = fileNamed(analyses, "B.java");
                assertEquals(sha(use(b, true, renames), ChangeCategory.WITHOUT_RENAMES),
                        sha(use(b, false, renames), ChangeCategory.WITHOUT_RENAMES));
            }
        }
    }
}
