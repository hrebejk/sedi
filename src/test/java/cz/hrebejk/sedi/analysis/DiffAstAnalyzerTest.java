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
}
