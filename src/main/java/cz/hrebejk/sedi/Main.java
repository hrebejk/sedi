/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import org.eclipse.jgit.api.errors.GitAPIException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Entry point of the application.
 */
@Command(name = "sedi", mixinStandardHelpOptions = true, description = "Semantic diff of Java sources.")
public class Main implements Callable<Integer> {

    @Option(names = {"-d", "--dir"}, description = "Project directory containing pom.xml (default: current directory).")
    private Path projectDir = Path.of("").toAbsolutePath();

    @Option(names = {"-c", "--commit"}, paramLabel = "<rev>",
            description = "Show the API diff of the given commit (SHA, abbreviated SHA, tag, branch, HEAD~2, ...) or range (a..b, a...b) and exit.")
    private String commit;

    private List<Path> classpath = List.of();

    private AstLoader astLoader = new AstLoader();

    /**
     * Starts the application.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() throws IOException {
        projectDir = projectDir.toAbsolutePath().normalize();
        Path pom = projectDir.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            classpath = MavenClasspath.resolve(projectDir);
            astLoader = new AstLoader(classpath);
        } else {
            System.err.println("No pom.xml found in " + projectDir + ", using JDK classpath only.");
        }
        System.out.println(classpath.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.joining(File.pathSeparator)));

        Console console = System.console();
        ApiDiffReporter reporter = new ApiDiffReporter(console != null && console.isTerminal());
        if (commit != null) {
            return reportCommit(reporter);
        }
        report(reporter, path -> path.endsWith(".java"));
        System.out.println("Watching " + projectDir + " ...");
        try (DirectoryWatcher watcher = new DirectoryWatcher(projectDir)) {
            watcher.run(changed -> {
                Set<String> relative = changed.stream()
                        .map(p -> projectDir.relativize(p).toString().replace(File.separatorChar, '/'))
                        .collect(Collectors.toSet());
                report(reporter, relative::contains);
            });
        }
        return 0;
    }

    /** Prints the API changes of the changed files accepted by the filter. */
    private void report(ApiDiffReporter reporter, Predicate<String> filter) {
        try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(projectDir, astLoader)) {
            print(reporter, analyzer.analyzeChanges(filter));
        } catch (IOException | GitAPIException e) {
            System.err.println("Cannot analyze changes: " + e.getMessage());
        }
    }

    /** Prints the API changes of the selected commit and returns the process exit code. */
    private int reportCommit(ApiDiffReporter reporter) {
        try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(projectDir, astLoader)) {
            print(reporter, analyzer.analyzeCommit(commit, path -> path.endsWith(".java")));
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Cannot analyze commit: " + e.getMessage());
            return 1;
        }
    }

    private static void print(ApiDiffReporter reporter, List<FileDiffAnalysis> analyses) {
        for (FileDiffAnalysis analysis : analyses) {
            List<String> lines = reporter.report(analysis);
            if (!lines.isEmpty()) {
                System.out.println(analysis.diff().getNewPath().equals("/dev/null")
                        ? analysis.diff().getOldPath() : analysis.diff().getNewPath());
                lines.forEach(System.out::println);
            }
        }
    }
}
