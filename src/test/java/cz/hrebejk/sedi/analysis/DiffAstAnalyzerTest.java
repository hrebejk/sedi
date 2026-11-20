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

import cz.hrebejk.sedi.compilation.AstLoader;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;
import cz.hrebejk.sedi.compilation.SourceFileProvider;
import cz.hrebejk.sedi.compilation.SourceModule;
import cz.hrebejk.sedi.util.GitInspector;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit test for DiffAstAnalyzer.
 */
public class DiffAstAnalyzerTest extends TestCase {

    /**
     * Tests analyzing changes between old and new versions of changed Java files.
     */
    public void testAnalyzeChanges() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("Calculator.java", "package cz.hrebejk.sedi;\n\npublic class Calculator {\n    public int add(int a, int b) { return a + b; }\n}\n");
            helper.commit("Initial commit");

            // Modify file
            helper.writeFile("Calculator.java", "package cz.hrebejk.sedi;\n\npublic class Calculator {\n    public int add(int a, int b) { return a + b + 1; }\n}\n");

            DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath());
            List<FileAnalysis> analyses = analyzer.analyzeChanges().files();

            assertNotNull(analyses);
            assertEquals(1, analyses.size());

            FileAnalysis analysis = analyses.get(0);
            assertNotNull(analysis.diff());
            assertNotNull(analysis.oldUnit().compilationUnit());
            assertNotNull(analysis.newUnit().compilationUnit());
            assertNotNull(analysis.oldUnit().trees());
            assertNotNull(analysis.newUnit().trees());
            assertNotNull(analysis.oldUnit().docTrees());
            assertNotNull(analysis.newUnit().docTrees());
        }
    }

    /** A loader which remembers the paths it was asked to load, one list per javac task. */
    private static final class RecordingLoader extends AstLoader {
        final List<List<String>> loads = new ArrayList<>();

        @Override
        public List<CompilationUnitInfo> load(List<SourceFileProvider> providers, List<Path> paths) throws IOException {
            loads.add(paths.stream().map(p -> p.toString().replace('\\', '/')).toList());
            return super.load(providers, paths);
        }
    }

    private static final String A_OLD = "package p;\npublic class A {\n  public int helper() { return 1; }\n}\n";
    private static final String A_NEW = "package p;\npublic class A {\n  public int assist() { return 1; }\n}\n";

    private static String b(String call, int plus) {
        return "package p;\npublic class B {\n  int use(A a) { return a." + call + "() + " + plus + "; }\n}\n";
    }

    private static FileAnalysis fileNamed(DiffAnalysis analysis, String name) {
        return analysis.files().stream().filter(f -> f.diff().getNewPath().endsWith(name)).findFirst().orElseThrow();
    }

    /** The levels of the changes of the members of a file, as {@code LEVEL:name}, sorted. */
    private static List<String> levels(FileAnalysis file) {
        List<String> result = new ArrayList<>();
        collect(file.rootChanges(), result);
        return result.stream().sorted().toList();
    }

    private static void collect(ApiChanges changes, List<String> result) {
        changes.changes().forEach(c -> result.add(c.level() + ":" + c.pair().newElement().getName()));
        changes.nested().forEach(n -> collect(n, result));
    }

    /**
     * A file changed in a later analysis has only its old version compiled; the old versions compiled before
     * are reused.
     */
    public void testOldVersionsAreCompiledOnce() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/A.java", A_OLD);
            helper.writeFile("src/main/java/p/B.java", b("helper", 0));
            helper.commit("init");
            RecordingLoader loader = new RecordingLoader();
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath(), loader)) {
                helper.writeFile("src/main/java/p/A.java", A_OLD.replace("return 1", "return 2"));
                DiffAnalysis first = analyzer.analyzeChanges();
                helper.writeFile("src/main/java/p/B.java", b("helper", 1));
                DiffAnalysis second = analyzer.analyzeChanges();

                assertEquals(List.of(
                        List.of("src/main/java/p/A.java"),
                        List.of("src/main/java/p/A.java"),
                        List.of("src/main/java/p/B.java"),
                        List.of("src/main/java/p/A.java", "src/main/java/p/B.java")), loader.loads);
                assertSame(fileNamed(first, "A.java").oldUnit(), fileNamed(second, "A.java").oldUnit());
                assertSame(fileNamed(first, "A.java").oldRoot(), fileNamed(second, "A.java").oldRoot());
                assertEquals(List.of("IMPLEMENTATION:helper"), levels(fileNamed(second, "A.java")));
                assertEquals(List.of("IMPLEMENTATION:use"), levels(fileNamed(second, "B.java")));
            }
        }
    }

    /**
     * The classes of the project are found in the given source roots, so a call of a renamed method from another
     * file is resolved and is only a cosmetic change.
     */
    public void testGivenSourceRoots() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/p/A.java", A_OLD);
            helper.writeFile("src/p/B.java", b("helper", 0));
            helper.commit("init");
            helper.writeFile("src/p/A.java", A_NEW);
            helper.writeFile("src/p/B.java", b("assist", 0));
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath(), new AstLoader(),
                    GitInspector.DEFAULT_RENAME_SCORE, List.of("src"))) {
                assertEquals(List.of("COSMETIC:use"), levels(fileNamed(analyzer.analyzeChanges(), "B.java")));
            }
        }
    }

    /** When the old commit changes, the old versions are compiled again, from the new old commit. */
    public void testNewCommitRecompilesOldVersions() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/A.java", A_OLD);
            helper.commit("init");
            RecordingLoader loader = new RecordingLoader();
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath(), loader)) {
                helper.writeFile("src/main/java/p/A.java", A_NEW);
                DiffAnalysis first = analyzer.analyzeChanges();
                helper.commit("rename");
                helper.writeFile("src/main/java/p/A.java", A_NEW.replace("return 1", "return 2"));
                DiffAnalysis second = analyzer.analyzeChanges();

                assertEquals(4, loader.loads.size());
                assertNotSame(fileNamed(first, "A.java").oldUnit(), fileNamed(second, "A.java").oldUnit());
                // Compared with the second commit, so the method is no longer renamed, only changed.
                assertEquals(List.of("IMPLEMENTATION:assist"), levels(fileNamed(second, "A.java")));
            }
        }
    }

    /**
     * A rename found in an old version compiled by an earlier analysis still makes a reference to it in an old
     * version compiled later only a cosmetic change.
     */
    public void testRenameAcrossOldVersionsCompiledSeparately() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/A.java", A_OLD);
            helper.writeFile("src/main/java/p/B.java", b("helper", 0));
            helper.commit("init");
            RecordingLoader loader = new RecordingLoader();
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath(), loader)) {
                helper.writeFile("src/main/java/p/A.java", A_NEW);
                analyzer.analyzeChanges();
                helper.writeFile("src/main/java/p/B.java", b("assist", 0));
                DiffAnalysis second = analyzer.analyzeChanges();

                assertEquals(List.of("src/main/java/p/B.java"), loader.loads.get(2));
                assertEquals(List.of("COSMETIC:use"), levels(fileNamed(second, "B.java")));
            }
        }
    }

    /**
     * The range of a dirty working copy ends with the working copy; once everything is committed, it is the last
     * commit.
     */
    public void testRangeOfChanges() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("src/main/java/p/A.java", A_OLD);
            helper.commit("init");
            helper.writeFile("src/main/java/p/A.java", A_NEW);
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath())) {
                assertNull(analyzer.analyzeChanges().range().newCommit());
                RevCommit head = helper.commit("rename");
                assertEquals(head.getId(), analyzer.analyzeChanges().range().newCommit());
            }
        }
    }
    private static final String CORE = "core/src/main/java";
    private static final String APP = "app/src/main/java";

    /** The core module, compiled only against itself, and the app module, compiled against itself and core. */
    private static List<SourceModule> modules(AstLoader coreLoader, AstLoader appLoader) {
        return List.of(new SourceModule(List.of(CORE), List.of(CORE), coreLoader),
                new SourceModule(List.of(APP), List.of(APP, CORE), appLoader));
    }

    /** The number of names javac could not resolve in the new version of the file. */
    private static int unresolved(FileAnalysis file) {
        return unresolved(file.newRoot());
    }

    private static int unresolved(ElementInfo element) {
        int count = element.getUnresolved().size();
        for (ElementInfo child : element.getChildren()) {
            count += unresolved(child);
        }
        return count;
    }

    /** Writes core's {@code A} and app's {@code B} calling it, and commits them. */
    private static void writeModules(GitTestHelper helper) throws Exception {
        helper.writeFile(CORE + "/p/A.java", A_OLD);
        helper.writeFile(APP + "/q/B.java", appB("helper", 0));
        helper.commit("init");
    }

    private static String appB(String call, int plus) {
        return "package q;\nimport p.A;\npublic class B {\n  int use(A a) { return a." + call + "() + " + plus
                + "; }\n}\n";
    }

    /** A file of a module refers to a class of a module it depends on, which javac finds and resolves. */
    public void testModuleResolvesDependency() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            writeModules(helper);
            helper.writeFile(APP + "/q/B.java", appB("helper", 1));
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath(),
                    GitInspector.DEFAULT_RENAME_SCORE, modules(new AstLoader(), new AstLoader()))) {
                FileAnalysis b = fileNamed(analyzer.analyzeChanges(), "B.java");
                assertEquals(0, unresolved(b));
                assertEquals(List.of("IMPLEMENTATION:use"), levels(b));
            }
        }
    }

    /**
     * A method renamed in one module and its caller in another: the rename is an API change of the first, the
     * call only a cosmetic change of the second.
     */
    public void testRenameAcrossModules() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            writeModules(helper);
            helper.writeFile(CORE + "/p/A.java", A_NEW);
            helper.writeFile(APP + "/q/B.java", appB("assist", 0));
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath(),
                    GitInspector.DEFAULT_RENAME_SCORE, modules(new AstLoader(), new AstLoader()))) {
                DiffAnalysis analysis = analyzer.analyzeChanges();
                assertEquals(List.of("API:assist"), levels(fileNamed(analysis, "A.java")));
                assertEquals(List.of("COSMETIC:use"), levels(fileNamed(analysis, "B.java")));
            }
        }
    }

    /** A module does not see the classes of a module depending on it: a reference to one is unresolved. */
    public void testModulesAreIsolated() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            writeModules(helper);
            helper.writeFile(CORE + "/p/A.java",
                    "package p;\npublic class A {\n  public int helper() { return 1; }\n"
                            + "  public Object app() { return new q.B(); }\n}\n");
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath(),
                    GitInspector.DEFAULT_RENAME_SCORE, modules(new AstLoader(), new AstLoader()))) {
                FileAnalysis a = fileNamed(analyzer.analyzeChanges(), "A.java");
                assertTrue(unresolved(a) > 0);
            }
        }
    }

    /** The files of each module are compiled in a javac task of their own, by the loader of their module. */
    public void testEachModuleIsCompiledSeparately() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            writeModules(helper);
            helper.writeFile(CORE + "/p/A.java", A_NEW);
            helper.writeFile(APP + "/q/B.java", appB("assist", 0));
            RecordingLoader core = new RecordingLoader();
            RecordingLoader app = new RecordingLoader();
            try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(helper.getRepositoryPath(),
                    GitInspector.DEFAULT_RENAME_SCORE, modules(core, app))) {
                analyzer.analyzeChanges();
                // The old versions, then the new ones.
                assertEquals(List.of(List.of(CORE + "/p/A.java"), List.of(CORE + "/p/A.java")), core.loads);
                assertEquals(List.of(List.of(APP + "/q/B.java"), List.of(APP + "/q/B.java")), app.loads);
            }
        }
    }
}
