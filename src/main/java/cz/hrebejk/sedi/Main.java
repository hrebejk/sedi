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
import cz.hrebejk.sedi.analysis.DiffAnalysis;
import cz.hrebejk.sedi.analysis.DiffAstAnalyzer;
import cz.hrebejk.sedi.compilation.AstLoader;
import cz.hrebejk.sedi.report.ReportOptions;
import cz.hrebejk.sedi.report.Reporter;
import cz.hrebejk.sedi.report.Theme;
import cz.hrebejk.sedi.util.DirectoryWatcher;
import cz.hrebejk.sedi.util.MavenClasspath;
import cz.hrebejk.sedi.util.Terminal;
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
            description = "Show the API diff of the given commit (SHA, abbreviated SHA, tag, branch, HEAD~2, ...) or range (a..b, a...b) and exit, unless --interactive is given.")
    private String commit;

    @Option(names = "--info", description = "Print the resolved class path.")
    private boolean info;

    @Option(names = {"-i", "--interactive"},
            description = "Keep running after the diff of <rev> is shown, reacting to keys (h shows the help).")
    private boolean interactive;

    @Option(names = {"-l", "--level"}, paramLabel = "<level>", converter = LevelConverter.class,
            description = "Least important changes to show: api, impl, cosmetic or imports (default: api).")
    private ChangeLevel level = ChangeLevel.API;

    @Option(names = {"-g", "--group"}, paramLabel = "<grouping>", converter = GroupingConverter.class,
            description = "Group the changes by type or by category (default: type).")
    private ReportOptions.Grouping grouping = ReportOptions.Grouping.BY_TYPE;

    @Option(names = {"-b", "--body-lines"}, paramLabel = "<n>",
            description = "Most lines shown of the changes of one method body, 0 for none (default: "
                    + ReportOptions.DEFAULT_BODY_LINES + ").")
    private int bodyLines = ReportOptions.DEFAULT_BODY_LINES;

    @Option(names = "--javadoc-context", paramLabel = "<n>",
            description = "Unchanged javadoc lines shown around a javadoc change, -1 for the whole javadoc, "
                    + "-2 for no javadoc (default: " + ReportOptions.DEFAULT_JAVADOC_CONTEXT + ").")
    private int javadocContext = ReportOptions.DEFAULT_JAVADOC_CONTEXT;

    @Option(names = "--color", paramLabel = "<when>", converter = ColorConverter.class,
            description = "Colors: auto (when the output is a terminal), always or never (default: auto).")
    private ColorMode color = ColorMode.AUTO;

    /** When to color the output. */
    private enum ColorMode { AUTO, ALWAYS, NEVER }

    @Option(names = "--theme", paramLabel = "<file>",
            description = "Properties file with the colors, keyed by role (keyword, type, method, string, number, "
                    + "constant, annotation, comment, strike, replacement, added, removed, added_mark, removed_mark, declaration, heading, heading_mark, changed_mark, "
                    + "title, package, tag, note, no_changes), "
                    + "e.g. keyword=#82aaff bold, added=bg:#1e3a2a. Attributes: #rrggbb, bg:#rrggbb, bold, "
                    + "underline, strike, none (default: ~/.config/sedi/theme.properties if it exists).")
    private Path themeFile;

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

    /** Reads the color modes: {@code auto}, {@code always} and {@code never}. */
    private static final class ColorConverter implements CommandLine.ITypeConverter<ColorMode> {
        @Override
        public ColorMode convert(String value) {
            return switch (value.toLowerCase()) {
                case "auto" -> ColorMode.AUTO;
                case "always" -> ColorMode.ALWAYS;
                case "never" -> ColorMode.NEVER;
                default -> throw new CommandLine.TypeConversionException("expected auto, always or never");
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
        boolean terminal = console != null && console.isTerminal();
        Theme theme;
        try {
            boolean colored = color == ColorMode.ALWAYS || color == ColorMode.AUTO && terminal;
            theme = colored ? theme() : Theme.PLAIN;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Cannot read the theme: " + e.getMessage());
            return 1;
        }
        Reporter reporter = new Reporter(theme, new ReportOptions(level, grouping, bodyLines, javadocContext));
        boolean keepRunning = commit == null || interactive;
        if (!keepRunning || !terminal && commit != null) {
            return reportCommit(reporter);
        }
        boolean colored = theme != Theme.PLAIN;
        Session session = new Session(reporter, javadocContext, System.out, terminal, colored, "Watching " + projectDir + " ...");
        Terminal raw = null;
        if (terminal) {
            try {
                raw = Terminal.enterRaw();
                session.listenToKeys();
            } catch (IOException e) {
                System.err.println("Cannot read keys: " + e.getMessage());
                return 1;
            }
        }
        try {
            if (commit != null) {
                session.run(filter -> analyzeCommit(filter), path -> path.endsWith(".java"));
                return 0;
            }
            try (DirectoryWatcher watcher = new DirectoryWatcher(projectDir)) {
                session.watch(watcher, projectDir);
                session.run(this::analyzeChanges, path -> path.endsWith(".java"));
            }
            return 0;
        } finally {
            if (raw != null) {
                raw.close();
                System.out.println();
            }
        }
    }

    private DiffAnalysis analyzeChanges(Predicate<String> filter) throws IOException, GitAPIException {
        try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(projectDir, astLoader)) {
            return analyzer.analyzeChanges(filter);
        }
    }

    private DiffAnalysis analyzeCommit(Predicate<String> filter) throws IOException {
        try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(projectDir, astLoader)) {
            return analyzer.analyzeCommit(commit, filter);
        }
    }

    /** The theme of {@code --theme}, or of the default file if there is one, or the default colors. */
    private Theme theme() throws IOException {
        Path file = themeFile;
        if (file == null) {
            Path home = Path.of(System.getProperty("user.home"), ".config", "sedi", "theme.properties");
            file = Files.isRegularFile(home) ? home : null;
        }
        return file == null ? Theme.defaults() : Theme.load(file);
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
