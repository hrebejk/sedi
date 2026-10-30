/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import cz.hrebejk.sedi.analysis.ChangeLevel;
import cz.hrebejk.sedi.analysis.DiffAstAnalyzer;
import cz.hrebejk.sedi.compilation.AstLoader;
import cz.hrebejk.sedi.report.ReportOptions;
import cz.hrebejk.sedi.report.Reporter;
import cz.hrebejk.sedi.util.DirectoryWatcher;
import cz.hrebejk.sedi.util.MavenClasspath;
import cz.hrebejk.sedi.util.Pager;
import org.eclipse.jgit.api.errors.GitAPIException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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

    @Parameters(arity = "0..1", paramLabel = "<rev>",
            description = "Show the API diff of the given commit (SHA, abbreviated SHA, tag, branch, HEAD~2, ...) or range (a..b, a...b) and exit.")
    private String commit;

    @Option(names = {"-i", "--info"}, description = "Print the resolved class path.")
    private boolean info;

    @Option(names = {"-l", "--level"}, paramLabel = "<level>", converter = LevelConverter.class,
            description = "Least important changes to show: api, impl, cosmetic or imports (default: api).")
    private ChangeLevel level = ChangeLevel.API;

    @Option(names = {"-s", "--split-visibility"}, description = "Tell the changes of the public API from the private ones.")
    private boolean splitVisibility;

    @Option(names = {"-g", "--group"}, paramLabel = "<grouping>", converter = GroupingConverter.class,
            description = "Group the changes by type or by category (default: type).")
    private ReportOptions.Grouping grouping = ReportOptions.Grouping.BY_TYPE;

    @Option(names = {"-b", "--body-lines"}, paramLabel = "<n>",
            description = "Most lines shown of the changes of one method body, 0 for none (default: "
                    + ReportOptions.DEFAULT_BODY_LINES + ").")
    private int bodyLines = ReportOptions.DEFAULT_BODY_LINES;

    private List<Path> classpath = List.of();

    private AstLoader astLoader = new AstLoader();

    /** Reads the short names of the levels: {@code api}, {@code impl}, {@code cosmetic} and {@code imports}. */
    private static final class LevelConverter implements CommandLine.ITypeConverter<ChangeLevel> {
        @Override
        public ChangeLevel convert(String value) {
            return switch (value.toLowerCase()) {
                case "api" -> ChangeLevel.API;
                case "impl", "implementation" -> ChangeLevel.IMPLEMENTATION;
                case "cosmetic" -> ChangeLevel.COSMETIC;
                case "imports" -> ChangeLevel.IMPORTS;
                default -> throw new CommandLine.TypeConversionException("expected api, impl, cosmetic or imports");
            };
        }
    }

    /** Reads the groupings: {@code type} and {@code category}. */
    private static final class GroupingConverter implements CommandLine.ITypeConverter<ReportOptions.Grouping> {
        @Override
        public ReportOptions.Grouping convert(String value) {
            return switch (value.toLowerCase()) {
                case "type" -> ReportOptions.Grouping.BY_TYPE;
                case "category" -> ReportOptions.Grouping.BY_CATEGORY;
                default -> throw new CommandLine.TypeConversionException("expected type or category");
            };
        }
    }

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
        if (info) {
            System.out.println(classpath.stream()
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.joining(File.pathSeparator)));
        }

        Console console = System.console();
        boolean interactive = console != null && console.isTerminal();
        Reporter reporter = new Reporter(interactive, new ReportOptions(level, splitVisibility, grouping, bodyLines));
        if (commit != null) {
            return reportCommit(reporter);
        }
        Pager pager = new Pager(System.out, interactive);
        if (!report(reporter, pager, path -> path.endsWith(".java"))) {
            return 0;
        }
        try (DirectoryWatcher watcher = new DirectoryWatcher(projectDir)) {
            watcher.run(changed -> {
                Set<String> relative = changed.stream()
                        .map(p -> projectDir.relativize(p).toString().replace(File.separatorChar, '/'))
                        .collect(Collectors.toSet());
                if (!report(reporter, pager, relative::contains)) {
                    // Makes the watcher's next wait for events end the run.
                    Thread.currentThread().interrupt();
                }
            });
        }
        return 0;
    }

    /**
     * Shows the API changes of the changed files accepted by the filter.
     *
     * @return {@code false} if the user asked to quit
     */
    private boolean report(Reporter reporter, Pager pager, Predicate<String> filter) {
        List<String> lines;
        try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(projectDir, astLoader)) {
            lines = reporter.report(analyzer.analyzeChanges(filter));
        } catch (IOException | GitAPIException e) {
            System.err.println("Cannot analyze changes: " + e.getMessage());
            return true;
        }
        if (!lines.isEmpty() && !pager.show(lines)) {
            return false;
        }
        System.out.println("Watching " + projectDir + " ...");
        return true;
    }

    /** Prints the API changes of the selected commit and returns the process exit code. */
    private int reportCommit(Reporter reporter) {
        try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(projectDir, astLoader)) {
            reporter.report(analyzer.analyzeCommit(commit, path -> path.endsWith(".java"))).forEach(System.out::println);
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Cannot analyze commit: " + e.getMessage());
            return 1;
        }
    }
}
