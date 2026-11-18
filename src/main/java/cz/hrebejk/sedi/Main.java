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
import cz.hrebejk.sedi.report.ReportStyles;
import org.maraboo.fleck.console.ConsoleHtmlRenderer;
import org.maraboo.fleck.css.StyleSheet;
import cz.hrebejk.sedi.util.DirectoryWatcher;
import cz.hrebejk.sedi.util.GitInspector;
import cz.hrebejk.sedi.util.MavenClasspath;
import cz.hrebejk.sedi.util.Terminal;
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
import java.util.stream.Collectors;

/**
 * Entry point of the application.
 */
@Command(name = "sedi", mixinStandardHelpOptions = true, description = "Semantic diff of Java sources.")
public class Main implements Callable<Integer> {

    @Option(names = {"-d", "--dir"}, description = "Project directory containing pom.xml (default: current directory).")
    private Path projectDir = Path.of("").toAbsolutePath();

    @Parameters(arity = "0..1", paramLabel = "<rev>",
            description = "Show the API diff of the given commit (SHA, abbreviated SHA, tag, branch, HEAD~2, ...) or range (a..b, a...b; a.. compares a with the working copy) and exit, unless --interactive is given.")
    private String commit;

    @Option(names = "--info", description = "Print the resolved class path.")
    private boolean info;

    @Option(names = {"-i", "--interactive"},
            description = "Keep running after the diff of <rev> is shown, reacting to keys (h shows the help). "
                    + "With <rev> a.. the working copy is watched and the diff is shown again when it changes.")
    private boolean interactive;

    @Option(names = {"-l", "--level"}, paramLabel = "<level>", converter = LevelConverter.class,
            description = "Least important changes to show: api, impl, cosmetic or imports (default: api).")
    private ChangeLevel level = ChangeLevel.API;

    @Option(names = {"-g", "--group"}, paramLabel = "<grouping>", converter = GroupingConverter.class,
            description = "Group the changes by category or by type (default: category).")
    private ReportOptions.Grouping grouping = ReportOptions.Grouping.BY_CATEGORY;

    @Option(names = {"-b", "--body-context"}, paramLabel = "<n>",
            description = "Most unchanged lines of a method body shown in a row between its changes; a longer run is "
                    + "shown as … (N lines). -1 shows the whole body (default: " + ReportOptions.DEFAULT_BODY_CONTEXT + ").")
    private int bodyContext = ReportOptions.DEFAULT_BODY_CONTEXT;

    @Option(names = "--javadoc-context", paramLabel = "<n>",
            description = "Unchanged javadoc lines shown around a javadoc change, -1 for the whole javadoc, "
                    + "-2 for no javadoc (default: -2).")
    private int javadocContext = ReportOptions.DEFAULT_JAVADOC_CONTEXT;

    @Option(names = "--show-deletions",
            description = "Show the whole source of the removed types and members with the implementation changes, "
                    + "as of the added ones.")
    private boolean showDeletions;

    @Option(names = "--hide-additions",
            description = "Do not show the whole source of the added types and members with the implementation changes.")
    private boolean hideAdditions;

    @Option(names = "--hide-tests",
            description = "Do not show the changes of the test sources, the files under src/test/.")
    private boolean hideTests;

    @Option(names = "--rename-score", paramLabel = "<percent>",
            description = "Least similarity of a deleted and an added file for git to report them as a rename, "
                    + "0 - 100 (default: " + GitInspector.DEFAULT_RENAME_SCORE + ", as in the git command line).")
    private int renameScore = GitInspector.DEFAULT_RENAME_SCORE;

    @Option(names = "--color", paramLabel = "<when>", converter = ColorConverter.class,
            description = "Colors: auto (when the output is a terminal), always or never (default: auto).")
    private ColorMode color = ColorMode.AUTO;

    /** When to color the output. */
    private enum ColorMode { AUTO, ALWAYS, NEVER }

    @Option(names = "--no-line-numbers", description = "Do not show the numbers of the lines of the source on the left.")
    private boolean noLineNumbers;

    @Option(names = "--theme", paramLabel = "<file>",
            description = "Properties file with the colors, keyed by role (keyword, type, method, string, number, "
                    + "constant, annotation, comment, strike, replacement, added, removed, added_mark, removed_mark, declaration, heading, heading_mark, changed_mark, "
                    + "title, package, tag, error, line_number, tree, note, no_changes), "
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
        if (renameScore < 0 || renameScore > 100) {
            System.err.println("--rename-score must be between 0 and 100: " + renameScore);
            return 2;
        }
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
        boolean colored = color == ColorMode.ALWAYS || color == ColorMode.AUTO && terminal;
        StyleSheet styleSheet;
        try {
            styleSheet = colored ? styleSheet() : ReportStyles.plain();
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Cannot read the theme: " + e.getMessage());
            return 1;
        }
        ConsoleHtmlRenderer renderer = new ConsoleHtmlRenderer(
                new org.maraboo.fleck.console.Console(System.out, System.err, null, !colored), styleSheet);
        Reporter reporter = new Reporter(new ReportOptions(level, grouping, bodyContext, javadocContext, !noLineNumbers,
                !hideAdditions, showDeletions, !hideTests))
                .withWidth(Terminal.width());
        boolean keepRunning = commit == null || interactive;
        if (!keepRunning || !terminal && commit != null) {
            return reportCommit(reporter, renderer);
        }
        Session session = new Session(reporter, renderer, javadocContext, System.out, terminal, colored, "Watching " + projectDir + " ...");
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
        // One analyzer for the whole session, so the old versions are compiled only once.
        try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(projectDir, astLoader, renameScore)) {
            Session.Analysis analysis = commit == null ? analyzer::analyzeChanges
                    : filter -> analyzer.analyzeCommit(commit, filter);
            if (commit != null && !comparesWorkingCopy()) {
                session.run(analysis, path -> path.endsWith(".java"));
                return 0;
            }
            try (DirectoryWatcher watcher = new DirectoryWatcher(projectDir)) {
                session.watch(watcher, projectDir);
                session.run(analysis, path -> path.endsWith(".java"));
            }
            return 0;
        } finally {
            if (raw != null) {
                raw.close();
                System.out.println();
            }
        }
    }

    /**
     * Whether the {@code <rev>} compares a commit with the working copy ({@code a..}), so the report changes with
     * the files on disk. An unknown revision does not; its error is reported by the analysis.
     */
    private boolean comparesWorkingCopy() {
        try {
            return new GitInspector(projectDir, renameScore).resolveRange(commit).newCommit() == null;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /** The style sheet of {@code --theme}, or of the default file if there is one, or of the default colors. */
    private StyleSheet styleSheet() throws IOException {
        Path file = themeFile;
        if (file == null) {
            Path home = Path.of(System.getProperty("user.home"), ".config", "sedi", "theme.properties");
            file = Files.isRegularFile(home) ? home : null;
        }
        return file == null ? ReportStyles.styleSheet(ReportStyles.palette()) : ReportStyles.load(file);
    }

    /** Prints the API changes of the selected commit and returns the process exit code. */
    private int reportCommit(Reporter reporter, ConsoleHtmlRenderer renderer) {
        try (DiffAstAnalyzer analyzer = new DiffAstAnalyzer(projectDir, astLoader, renameScore)) {
            renderer.lines(reporter.report(analyzer.analyzeCommit(commit, path -> path.endsWith(".java"))))
                    .forEach(System.out::println);
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Cannot analyze commit: " + e.getMessage());
            return 1;
        }
    }
}
