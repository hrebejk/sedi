/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import cz.hrebejk.sedi.analysis.ApiChanges;
import cz.hrebejk.sedi.analysis.BodyChanges;
import cz.hrebejk.sedi.analysis.ChangeLevel;
import cz.hrebejk.sedi.analysis.DiffAnalysis;
import cz.hrebejk.sedi.analysis.ElementInfo;
import cz.hrebejk.sedi.analysis.ElementPair;
import cz.hrebejk.sedi.analysis.FileAnalysis;
import cz.hrebejk.sedi.analysis.MemberChange;
import cz.hrebejk.sedi.analysis.Visibility;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;
import org.eclipse.jgit.diff.DiffEntry;
import org.maraboo.fleck.html.HTML;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Renders a {@link DiffAnalysis} as HTML, as configured by {@link ReportOptions}. Changes are grouped either by
 * file, with a heading and then, for every changed type, one line for every change, or by their
 * {@link ChangeLevel}. The report is a {@code div.report} with a {@code div.line} for every line and a {@code br}
 * for every empty one; its look is given by a style sheet, see {@link ReportStyles}. The lines are rendered by a
 * {@link SignatureDiffPrinter}.
 */
public final class Reporter {

    private final SignatureDiffPrinter printer;
    private final SignatureDiffPrinter cosmeticPrinter;
    private final JavadocPrinter javadoc;
    private final ReportOptions options;
    private final int width;

    /** The width of the lines between the files when it is not set, see {@link #withWidth}. */
    public static final int DEFAULT_WIDTH = 80;

    /**
     * Creates a reporter showing the API changes grouped by type.
     */
    public Reporter() {
        this(ReportOptions.DEFAULT);
    }

    /**
     * Creates a reporter.
     *
     * @param options what to show and how to group it
     */
    public Reporter(ReportOptions options) {
        this(options, DEFAULT_WIDTH);
    }

    private Reporter(ReportOptions options, int width) {
        this.width = width;
        this.printer = new SignatureDiffPrinter();
        this.cosmeticPrinter = printer.cosmetic();
        this.javadoc = new JavadocPrinter(options.javadocContext());
        this.options = options;
    }

    /**
     * Returns a reporter like this one showing javadoc as the context says.
     *
     * @param javadocContext see {@link ReportOptions#javadocContext()}
     * @return the new reporter
     */
    public Reporter withJavadocContext(int javadocContext) {
        return new Reporter(options.withJavadocContext(javadocContext), width);
    }

    /**
     * Returns a reporter like this one grouping the changes as given.
     *
     * @param grouping see {@link ReportOptions#grouping()}
     * @return the new reporter
     */
    public Reporter withGrouping(ReportOptions.Grouping grouping) {
        return new Reporter(options.withGrouping(grouping), width);
    }

    /**
     * Returns a reporter like this one showing the whole source of the added types and members, or not.
     *
     * @param show see {@link ReportOptions#showAdditions()}
     * @return the new reporter
     */
    public Reporter withAdditions(boolean show) {
        return new Reporter(options.withAdditions(show), width);
    }

    /**
     * Returns a reporter like this one showing the whole source of the removed types and members, or not.
     *
     * @param show see {@link ReportOptions#showDeletions()}
     * @return the new reporter
     */
    public Reporter withDeletions(boolean show) {
        return new Reporter(options.withDeletions(show), width);
    }

    /**
     * Returns a reporter like this one showing the changes of the levels up to the given one.
     *
     * @param level see {@link ReportOptions#maxLevel()}
     * @return the new reporter
     */
    public Reporter withMaxLevel(ChangeLevel level) {
        return new Reporter(options.withMaxLevel(level), width);
    }

    /**
     * Returns a reporter like this one showing the changes of the test sources, or not.
     *
     * @param show see {@link ReportOptions#showTests()}
     * @return the new reporter
     */
    public Reporter withTests(boolean show) {
        return new Reporter(options.withTests(show), width);
    }

    /**
     * Returns what this reporter shows and how.
     *
     * @return the options
     */
    public ReportOptions options() {
        return options;
    }

    /**
     * Returns a reporter like this one drawing the lines between the files as wide as given.
     *
     * @param width the width, usually that of the terminal
     * @return the new reporter
     */
    public Reporter withWidth(int width) {
        return new Reporter(options, width);
    }

    /**
     * Returns how this reporter groups the changes.
     *
     * @return the grouping
     */
    public ReportOptions.Grouping grouping() {
        return options.grouping();
    }

    /**
     * Reports the changes of all the files of the diff. A file is left out if it has no change to show,
     * unless git added, deleted or renamed it.
     *
     * @param analysis the analyzed diff
     * @return the headings and the indented lines under them; if there is nothing to show but imports, the headings
     *         of the shown levels, each with {@code NO CHANGES} or the changes
     */
    public HTML.Tag report(DiffAnalysis analysis) {
        List<FileAnalysis> files = sorted(analysis);
        List<Line> result = new ArrayList<>();
        if (options.grouping() == ReportOptions.Grouping.BY_TYPE) {
            // Changed imports alone are no reason to leave the other levels out: show every level then.
            ReportOptions withoutImports = options.maxLevel() == ChangeLevel.IMPORTS
                    ? options.withMaxLevel(ChangeLevel.COSMETIC)
                    : options;
            if (!new Reporter(withoutImports, width).byType(files).isEmpty()) {
                return numbered(byType(files));
            }
            // Nothing to show by type: say so for every shown level, as the grouping by category does.
        }
        for (ChangeLevel level : ChangeLevel.values()) {
            if (level.compareTo(options.maxLevel()) > 0) {
                continue;
            }
            if (!result.isEmpty() && result.getLast().kind() != Line.Kind.BLANK) {
                result.add(Line.blank());
            }
            result.addAll(section(files, level));
        }
        return numbered(result);
    }

    /**
     * The report of the lines, with the line numbers in the column on the left or without them, see
     * {@link LineNumbers}.
     */
    private HTML.Tag numbered(List<Line> lines) {
        return HTML.DIV().children(LineNumbers.render(lines, options.lineNumbers())).clazz(Css.REPORT.cssClass());
    }

    /** The changes of every file, the files separated by a line as in the sections of the levels. */
    private List<Line> byType(List<FileAnalysis> files) {
        List<Line> result = new ArrayList<>();
        for (FileAnalysis file : files) {
            List<Line> part = new ArrayList<>();
            addFile(part, file, fileLines(file), file.isStructural());
            if (!part.isEmpty()) {
                if (!result.isEmpty()) {
                    result.add(separator());
                }
                result.addAll(part);
            }
        }
        return result;
    }

    /**
     * Reports only the changes of one level, ignoring {@link ReportOptions#maxLevel()} and the grouping.
     *
     * @param analysis the analyzed diff
     * @param level    the only level to show
     * @return the heading of the level and the changes under it, or {@code NO CHANGES} if there are none
     */
    public HTML.Tag reportOnly(DiffAnalysis analysis, ChangeLevel level) {
        return numbered(section(sorted(analysis), level));
    }

    /** The files to report, sorted by package and class; the tests are left out unless they are shown. */
    private List<FileAnalysis> sorted(DiffAnalysis analysis) {
        return analysis.files().stream()
                .filter(f -> options.showTests() || !isTest(f))
                .sorted(Comparator.comparing(FileAnalysis::newPackage).thenComparing(FileAnalysis::newClassName))
                .toList();
    }

    /** The heading of the level followed by its changes, or by a remark that there are none. */
    private List<Line> section(List<FileAnalysis> files, ChangeLevel level) {
        List<Line> lines = level == ChangeLevel.API ? apiLines(files) : levelLines(files, level);
        List<Line> section = new ArrayList<>();
        section.add(Line.outside(Markup.style(Css.HEADING_MARK, "#").plus(" ")
                .plus(Markup.style(Css.HEADING, SECTIONS.get(level)))));
        section.add(Line.blank());
        if (lines.isEmpty()) {
            section.add(Line.plain(Fragment.of("  ").plus(Markup.style(Css.NO_CHANGES, "NO CHANGES"))));
            section.add(Line.blank());
        }
        section.addAll(lines);
        return section;
    }

    private static final Map<ChangeLevel, String> SECTIONS = Map.of(
            ChangeLevel.API, "API changes",
            ChangeLevel.IMPLEMENTATION, "Implementation changes",
            ChangeLevel.COSMETIC, "Cosmetic changes",
            ChangeLevel.IMPORTS, "Imports");

    /** The changes of one level other than the API, grouped by file. */
    private List<Line> levelLines(List<FileAnalysis> files, ChangeLevel level) {
        List<Line> lines = new ArrayList<>();
        for (FileAnalysis file : files) {
            List<Line> fileLines = new ArrayList<>();
            if (level == ChangeLevel.IMPORTS) {
                if (file.diff().getChangeType() == DiffEntry.ChangeType.DELETE) {
                    continue;
                }
                importLines(file, false, fileLines);
            } else {
                // The added and removed elements are listed in the API changes, their code is shown here.
                fileLines = lines(file, (container, c) -> c.level() == level
                        || level == ChangeLevel.IMPLEMENTATION && isShownWhole(c), false, false, false, true);
            }
            List<Line> part = new ArrayList<>();
            addFile(part, file, fileLines, false);
            if (!part.isEmpty()) {
                if (!lines.isEmpty()) {
                    lines.add(separator());
                }
                lines.addAll(part);
            }
        }
        return lines;
    }

    /** A top level type added or removed, with the file it is in. */
    private record TypeRef(FileAnalysis file, ElementInfo element) { }

    /**
     * The API changes: the public API of the added types, the names of the removed types, then the changes of the
     * types present in both versions. The parts are separated by a line.
     */
    private List<Line> apiLines(List<FileAnalysis> files) {
        List<TypeRef> added = new ArrayList<>();
        List<TypeRef> removed = new ArrayList<>();
        for (FileAnalysis file : files) {
            collectTypes(file, added, removed);
        }
        List<List<Line>> parts = new ArrayList<>();
        for (FileAnalysis file : files) {
            if (file.diff().getChangeType() == DiffEntry.ChangeType.ADD) {
                List<Line> list = new ArrayList<>();
                list.add(fileTitle(file));
                for (TypeRef type : added) {
                    if (type.file() == file) {
                        addTree(list, "  ", addedNode(type.element(), ""));
                    }
                }
                parts.add(list);
            }
        }
        for (TypeRef type : added) {
            if (type.file().diff().getChangeType() != DiffEntry.ChangeType.ADD) {
                List<Line> lines = new ArrayList<>();
                addTree(lines, "", declarationNode(new ElementPair(null, type.element(), Set.of()), "", null,
                        location(type, true), List.of(), addedMembers(type.element())));
                parts.add(lines);
            }
        }
        List<Line> names = new ArrayList<>();
        for (FileAnalysis file : files) {
            if (file.diff().getChangeType() == DiffEntry.ChangeType.DELETE) {
                names.add(fileTitle(file));
            }
        }
        for (TypeRef type : removed) {
            if (type.file().diff().getChangeType() != DiffEntry.ChangeType.DELETE) {
                names.add(Line.number(type.element().getLine(), '-',
                        Markup.style(Css.TITLE, type.element().getName()).plus(location(type, false))));
            }
        }
        parts.add(names);
        for (FileAnalysis file : files) {
            if (file.diff().getChangeType() == DiffEntry.ChangeType.RENAME) {
                List<Line> renamed = new ArrayList<>();
                addFile(renamed, file, lines(file, (container, c) -> c.level() == ChangeLevel.API
                        && !isListedApart(container, c), true, false, true, false), true);
                parts.add(renamed);
            }
        }
        for (FileAnalysis file : files) {
            List<Line> changed = new ArrayList<>();
            if (file.diff().getChangeType() != DiffEntry.ChangeType.RENAME) {
                addFile(changed, file, lines(file, (container, c) -> c.level() == ChangeLevel.API
                        && !isListedApart(container, c), true, false, true, false), false);
            }
            parts.add(changed);
        }
        List<Line> result = new ArrayList<>();
        for (List<Line> part : parts) {
            if (!part.isEmpty()) {
                if (!result.isEmpty()) {
                    result.add(separator());
                }
                result.addAll(part);
            }
        }
        return result;
    }

    /**
     * Whether the change is listed in a part of the API changes of its own: an added or removed top level type.
     * Nested types are shown in the types they are nested in, like the other members.
     */
    private static boolean isListedApart(ApiChanges container, MemberChange change) {
        return isTypeAddedOrRemoved(change) && container.typeName().isEmpty();
    }

    private static boolean isTypeAddedOrRemoved(MemberChange change) {
        ElementPair pair = change.pair();
        return (pair.isAdded() || pair.isRemoved())
                && (pair.isAdded() ? pair.newElement() : pair.oldElement()).getNodeType() == ElementInfo.NodeType.TYPE;
    }

    /** Collects the added and removed top level types of the file. */
    private static void collectTypes(FileAnalysis file, List<TypeRef> added, List<TypeRef> removed) {
        for (MemberChange change : file.rootChanges().changes()) {
            if (change.level() == ChangeLevel.API && isTypeAddedOrRemoved(change)) {
                if (change.pair().isRemoved()) {
                    removed.add(new TypeRef(file, change.pair().oldElement()));
                } else {
                    added.add(new TypeRef(file, change.pair().newElement()));
                }
            }
        }
    }

    /** Where the type is: its package and source root, preceded by a space; empty if nowhere. */
    private Fragment location(TypeRef type, boolean added) {
        String path = added ? type.file().diff().getNewPath() : type.file().diff().getOldPath();
        String pkg = added ? type.file().newPackage() : type.file().oldPackage();
        String root = sourceRoot(path, pkg);
        String where = root.isEmpty() || pkg.isEmpty() ? pkg : pkg + " " + root;
        return where.isEmpty() ? Fragment.EMPTY : Fragment.of(" ").plus(Markup.style(Css.PACKAGE, where));
    }

    /** The members of an added type which are a part of the public API, and the types nested in it,
     * without the {@code +} as all of them are new. */
    private List<Node> addedMembers(ElementInfo type) {
        boolean inInterface = Visibility.isInterface(type);
        List<Node> nodes = new ArrayList<>();
        for (ElementInfo member : type.getChildren()) {
            if (Visibility.isExposed(member, true, inInterface)) {
                nodes.add(addedNode(member, type.getName()));
            }
        }
        return nodes;
    }

    /** An element of an added type with its javadoc and, if it is a type, its members. */
    private Node addedNode(ElementInfo element, String typeName) {
        List<Line> above = new ArrayList<>(javadoc.plain(element));
        above.addAll(printer.declaration(element, typeName));
        Line head = above.removeLast();
        return new Node(above, head, List.of(),
                element.getNodeType() == ElementInfo.NodeType.TYPE ? addedMembers(element) : List.of());
    }

    /**
     * An element shown in a tree: the lines above its signature, like the javadoc and the annotations, the line of
     * the signature, the lines below it, like the body, and the elements in it.
     */
    private record Node(List<Line> above, Line head, List<Line> below, List<Node> children) { }

    /** Adds a top level element, its signature indented by {@code indent}, with the tree of the elements in it. */
    private void addTree(List<Line> lines, String indent, Node node) {
        node.above().forEach(l -> lines.add(l.withPrefix(indent)));
        lines.add(node.head().withPrefix(indent));
        node.below().forEach(l -> lines.add(l.withPrefix(indent)));
        addBranches(lines, Fragment.of(indent), node.children());
    }

    /** Adds the elements below their parent, each on a branch starting under the signature of the parent. */
    private void addBranches(List<Line> lines, Fragment prefix, List<Node> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            boolean last = i == nodes.size() - 1;
            Fragment through = prefix.plus(branch("\u2502   "));
            Fragment below = prefix.plus(branch(last ? "    " : "\u2502   "));
            node.above().forEach(l -> lines.add(l.withPrefix(through)));
            lines.add(node.head().withPrefix(prefix.plus(branch(last ? "\u2514\u2500\u2500 " : "\u251c\u2500\u2500 "))));
            node.below().forEach(l -> lines.add(l.withPrefix(below)));
            addBranches(lines, below, node.children());
        }
    }

    /** A piece of the lines of a tree in their style. */
    private static Fragment branch(String text) {
        return text.isBlank() ? Fragment.of(text) : Markup.style(Css.TREE, text);
    }

    /** Adds the lines of a file below its title. */
    private void addFile(List<Line> result, FileAnalysis file, List<Line> lines, boolean keepEmpty) {
        if (!lines.isEmpty() || keepEmpty) {
            result.add(fileTitle(file));
            result.addAll(lines);
        }
    }

    /**
     * The node of an element: its javadoc and annotations above its signature.
     *
     * @param initializer the numbered line of the initializer shown after the signature on its line, such as
     *                    {@code = value}, {@code null} for none
     * @param suffix      the text added to the line of the signature
     */
    private Node declarationNode(ElementPair pair, String typeName, Line initializer, Fragment suffix,
                                 List<Line> below, List<Node> children) {
        return declarationNode(pair, false, typeName, initializer, suffix, below, children);
    }

    /**
     * Like {@link #declarationNode(ElementPair, String, String, String, List, List)}.
     *
     * @param cosmetic whether to show the cosmetic changes of the signature, the javadoc and annotations as they are
     */
    private Node declarationNode(ElementPair pair, boolean cosmetic, String typeName, Line initializer,
                                 Fragment suffix, List<Line> below, List<Node> children) {
        ElementPair unchanged = cosmetic ? new ElementPair(pair.newElement(), pair.newElement(), Set.of()) : pair;
        List<Line> above = new ArrayList<>(javadocLines(unchanged));
        above.addAll(printer.annotations(unchanged));
        boolean changedLine = initializer != null && initializer.marker() != ' ' && initializer.marker() != Line.NONE;
        // The arguments and the class body of an enum constant follow its name, an initializer follows =.
        ElementInfo element = pair.isRemoved() ? pair.oldElement() : pair.newElement();
        Fragment rest = initializer == null ? Fragment.EMPTY
                : Fragment.of(element.isEnumConstant() ? "" : " = ")
                        .plus(initializer.prefix().plus(initializer.content()).strip());
        Line head = (cosmetic ? cosmeticPrinter : printer).format(pair, typeName, changedLine);
        return new Node(above, head.append(rest.plus(suffix)), below, children);
    }

    /**
     * Reports the changes of a file, those of the levels up to {@link ReportOptions#maxLevel()}.
     *
     * @param file the analyzed file
     * @return the report of the indented lines, without any if there is nothing to show
     */
    public HTML.Tag report(FileAnalysis file) {
        return numbered(fileLines(file));
    }

    private List<Line> fileLines(FileAnalysis file) {
        boolean whole = options.maxLevel().compareTo(ChangeLevel.IMPLEMENTATION) >= 0;
        List<Line> lines = lines(file, (container, c) -> c.level().compareTo(options.maxLevel()) <= 0, true, true,
                true, whole);
        if (options.maxLevel() == ChangeLevel.IMPORTS) {
            importLines(file, true, lines);
        }
        return lines;
    }

    /**
     * Renders the changes of a file accepted by the filter. Every changed type is shown with its whole signature.
     *
     * @param header whether to show the type renamed together with the file
     * @param tagged whether to end the lines with the level of the change
     * @param diff   whether to show the changes of the signatures of the types
     * @param whole  whether to show the added and removed elements with their whole source, see
     *               {@link #isShownWhole}
     */
    private List<Line> lines(FileAnalysis file, BiPredicate<ApiChanges, MemberChange> accepted, boolean header,
                               boolean tagged, boolean diff, boolean whole) {
        // A change of the javadoc alone has nothing to show when the javadoc is not shown.
        boolean hideJavadoc = options.javadocContext() == ReportOptions.NO_JAVADOC;
        BiPredicate<ApiChanges, MemberChange> filter = (container, c) -> accepted.test(container, c)
                && !(hideJavadoc && c.isJavadocOnly());
        List<Node> nodes = new ArrayList<>();
        if (file.oldPrimary() != null) {
            List<Node> members = render(file.primaryChanges(), filter, tagged, diff, whole);
            if (header || !members.isEmpty()) {
                nodes.add(typeNode(file.primaryChanges().type(), "", diff, members));
            }
        }
        nodes.addAll(render(file.rootChanges(), filter, tagged, diff, whole));
        List<Line> lines = new ArrayList<>();
        nodes.forEach(node -> addTree(lines, "  ", node));
        return lines;
    }

    /**
     * The node of a changed type with its javadoc, annotations and signature, the changes of them shown if
     * {@code diff}, and the changes in it.
     */
    private Node typeNode(ElementPair type, String outerName, boolean diff, List<Node> children) {
        ElementPair shown = diff ? type : new ElementPair(type.newElement(), type.newElement(), Set.of());
        List<Line> above = new ArrayList<>(javadocLines(shown));
        above.addAll(printer.annotations(shown));
        return new Node(above, printer.formatType(shown, outerName), List.of(), children);
    }

    /**
     * The javadoc shown above a declaration: the changes of it, or for an element shown without its changes, as in
     * the sections other than the API, its javadoc as it is.
     */
    private List<Line> javadocLines(ElementPair pair) {
        return pair.oldElement() == pair.newElement() ? javadoc.unchanged(pair.newElement()) : javadoc.print(pair);
    }

    private void importLines(FileAnalysis file, boolean tagged, List<Line> lines) {
        Fragment tag = tagged ? tag("import") : Fragment.EMPTY;
        file.importChanges().removed()
                .forEach(i -> lines.add(importLine(false, i, line(file.oldUnit(), i)).withPrefix("  ").append(tag)));
        file.importChanges().added()
                .forEach(i -> lines.add(importLine(true, i, line(file.newUnit(), i)).withPrefix("  ").append(tag)));
    }

    private static Line importLine(boolean added, String name, long line) {
        return Line.number(line, added ? '+' : '-',
                added ? Highlighter.code("import " + name) : Markup.strike("import " + name));
    }

    /**
     * The line of an import in a version of the file.
     *
     * @param name the import as {@link cz.hrebejk.sedi.analysis.ImportChanges} has it, e.g. {@code static a.B.c}
     * @return the line, {@code 0} if not known
     */
    private static long line(CompilationUnitInfo unit, String name) {
        if (unit == null) {
            return 0;
        }
        CompilationUnitTree tree = unit.compilationUnit();
        for (ImportTree i : tree.getImports()) {
            if (((i.isStatic() ? "static " : "") + i.getQualifiedIdentifier()).equals(name)) {
                long start = unit.trees().getSourcePositions().getStartPosition(tree, i);
                return start < 0 ? 0 : tree.getLineMap().getLineNumber(start);
            }
        }
        return 0;
    }

    private static Fragment tag(String text) {
        return Fragment.of(" ").plus(Markup.style(Css.TAG, "[" + text + "]"));
    }

    /** The marker of the level of a change, empty for the API changes. */
    private static Fragment tag(MemberChange change) {
        return switch (change.level()) {
            case API -> Fragment.EMPTY;
            case IMPLEMENTATION -> tag("impl");
            case COSMETIC -> tag("cosmetic");
            case IMPORTS -> tag("import");
        };
    }

    /**
     * Returns the source root of the file, e.g. {@code src/test/java}: what remains of its path after the
     * directories of the package and the file name are removed. Empty if there is no package or the file
     * is not in the directories of its package.
     */
    private static String sourceRoot(String path, String pkg) {
        if (pkg.isEmpty()) {
            return "";
        }
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        String packageDir = pkg.replace('.', '/');
        return dir.endsWith("/" + packageDir) ? dir.substring(0, dir.length() - packageDir.length() - 1) : "";
    }

    /** Whether the file is a test: either of its paths is under a {@code src/test/} directory. */
    private static boolean isTest(FileAnalysis file) {
        return isTestPath(file.diff().getOldPath()) || isTestPath(file.diff().getNewPath());
    }

    private static boolean isTestPath(String path) {
        return path.startsWith("src/test/") || path.contains("/src/test/");
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Follows the package with the source root it is in, e.g. {@code com.acme src/test/java}. */
    private static String withSourceRoot(String path, String pkg) {
        String root = sourceRoot(path, pkg);
        return root.isEmpty() ? pkg : pkg + " " + root;
    }

    /** The heading of a file in the report, starting at the left edge, left of the column of the line numbers. */
    private Line fileTitle(FileAnalysis file) {
        return Line.outside(title(file));
    }

    /** The thin line separating the files, see {@link #withWidth}. */
    private Line separator() {
        return Line.outside(Markup.style(Css.TREE, "\u2500".repeat(width)));
    }

    /**
     * Returns the heading of a changed file: the name of the file, a space, its package and its source root,
     * e.g. {@code Foo.java com.acme src/main/java}. Added files are prefixed with {@code +}, deleted ones with {@code -}, and
     * a renamed file or moved package shows the old value struck through.
     *
     * @param file the analyzed file
     * @return the heading line
     */
    Fragment title(FileAnalysis file) {
        DiffEntry.ChangeType type = file.diff().getChangeType();
        String oldName = type == DiffEntry.ChangeType.ADD ? "" : fileName(file.diff().getOldPath());
        String newName = type == DiffEntry.ChangeType.DELETE ? "" : fileName(file.diff().getNewPath());
        String oldPackage = type == DiffEntry.ChangeType.ADD ? ""
                : withSourceRoot(file.diff().getOldPath(), file.oldPackage());
        String newPackage = type == DiffEntry.ChangeType.DELETE ? ""
                : withSourceRoot(file.diff().getNewPath(), file.newPackage());
        boolean deleted = type == DiffEntry.ChangeType.DELETE;
        Fragment name = deleted ? Markup.strike(oldName) : Markup.style(Css.TITLE, printer.slot(oldName, newName));
        Fragment pkg = deleted ? Markup.strike(oldPackage)
                : Markup.style(Css.PACKAGE, printer.slot(oldPackage, newPackage));
        Fragment title = pkg.isEmpty() ? name : name.plus(" ").plus(pkg);
        Fragment mark = switch (type) {
            case ADD -> Markup.sign(true);
            case DELETE -> Markup.sign(false);
            case RENAME -> Markup.renamed();
            default -> Markup.modified();
        };
        return mark.plus(" ").plus(title);
    }

    /**
     * The lines showing what changed in a body: the changed statements with the unchanged lines between them, see
     * {@link ReportOptions#bodyContext()}.
     */
    private List<Line> body(BodyChanges body) {
        return new AstPrinter(body, options.bodyContext()).print();
    }

    /**
     * Renders the changes of a container accepted by the filter and the types nested in it which have some, each
     * of them with its signature, in the order of the new source (a removed one at its line in the old source).
     */
    private List<Node> render(ApiChanges changes, BiPredicate<ApiChanges, MemberChange> filter, boolean tagged,
                              boolean diff, boolean whole) {
        List<Object> items = new ArrayList<>(changes.changes());
        items.addAll(changes.nested());
        items.sort(Comparator.comparingLong(Reporter::newLine));
        List<Node> nodes = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof MemberChange change) {
                if (filter.test(changes, change) && !isSignatureOfNested(changes, change)) {
                    nodes.add(whole && isShownWhole(change) ? wholeNode(change) : memberNode(changes, change, tagged));
                }
            } else if (item instanceof ApiChanges type) {
                List<Node> nested = render(type, filter, tagged, diff, whole);
                boolean own = changes.changes().stream()
                        .anyMatch(c -> filter.test(changes, c) && isSignatureOf(c, type));
                if (own || !nested.isEmpty()) {
                    nodes.add(typeNode(type.type(), changes.typeName(), diff, nested));
                }
            }
        }
        return nodes;
    }

    /** The line of a member change or of a nested type in the new source, in the old one if it was removed. */
    private static long newLine(Object item) {
        ElementPair pair = item instanceof MemberChange change ? change.pair() : ((ApiChanges) item).type();
        return pair.isRemoved() ? pair.oldElement().getLine() : pair.newElement().getLine();
    }

    /** The node of a changed member with the changes of its body below it. */
    private Node memberNode(ApiChanges changes, MemberChange change, boolean tagged) {
        // The signature shows the changes of its level: of the API, the cosmetic ones, or none.
        boolean cosmetic = change.level() == ChangeLevel.COSMETIC;
        ElementPair pair = change.level() == ChangeLevel.API || cosmetic ? change.pair()
                : new ElementPair(change.pair().newElement(), change.pair().newElement(), Set.of());
        List<Line> body = change.body() == null ? List.of() : body(change.body());
        // The initializer of a field continues the line of its declaration.
        boolean inline = body.size() == 1 && !(change.body().newRoot() instanceof BlockTree);
        // A shown block is enclosed in its braces, as in the source.
        boolean block = !body.isEmpty() && change.body().newRoot() instanceof BlockTree;
        List<Line> below = new ArrayList<>();
        if (!inline) {
            body.forEach(l -> below.add(l.withPrefix("      ")));
        }
        if (block) {
            below.add(Line.number(closingLine(change.body()), ' ', Highlighter.code("}")));
        }
        List<Node> children = change.level() == ChangeLevel.API && change.pair().isAdded()
                && change.pair().newElement().getNodeType() == ElementInfo.NodeType.TYPE
                ? addedMembers(change.pair().newElement()) : List.of();
        return declarationNode(pair, cosmetic, changes.typeName(), inline ? body.getFirst() : null,
                Fragment.of(block ? " {" : "").plus(tagged ? tag(change) : Fragment.EMPTY), below, children);
    }

    /**
     * Whether the change is an added or removed element whose whole source is shown with the implementation changes,
     * see {@link ReportOptions#showAdditions()} and {@link ReportOptions#showDeletions()}.
     */
    private boolean isShownWhole(MemberChange change) {
        ElementPair pair = change.pair();
        return change.level() == ChangeLevel.API
                && (pair.isAdded() && options.showAdditions() || pair.isRemoved() && options.showDeletions());
    }

    /**
     * The node of an added or removed element showing its whole source, every line marked as added or removed, the
     * javadoc of an added one above it.
     */
    private Node wholeNode(MemberChange change) {
        boolean added = change.pair().isAdded();
        ElementInfo element = added ? change.pair().newElement() : change.pair().oldElement();
        String text = element.getSourceText();
        if (text == null) {
            return declarationNode(change.pair(), "", null, Fragment.EMPTY, List.of(), List.of());
        }
        List<Fragment> code = Highlighter.codeLines(dedent(text));
        char marker = added ? '+' : '-';
        long first = element.getStartLine();
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            lines.add(Line.number(first <= 0 ? 0 : first + i, marker, code.get(i)));
        }
        return new Node(javadocLines(change.pair()), lines.get(0), lines.subList(1, lines.size()), List.of());
    }

    /** The text with its tabs expanded and the indentation all its lines have removed. */
    private static String dedent(String text) {
        List<String> lines = text.lines().map(Reporter::expandTabs).toList();
        int indent = lines.stream().filter(l -> !l.isBlank())
                .mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        return String.join("\n", lines.stream()
                .map(l -> l.isBlank() ? "" : l.substring(indent).stripTrailing()).toList());
    }

    private static String expandTabs(String line) {
        StringBuilder out = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '\t') {
                out.append(" ".repeat(TAB_WIDTH - out.length() % TAB_WIDTH));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static final int TAB_WIDTH = 4;

    /** The line of the closing brace of a body which is a block. */
    private static long closingLine(BodyChanges body) {
        CompilationUnitTree unit = body.newUnit().compilationUnit();
        long end = body.newUnit().trees().getSourcePositions().getEndPosition(unit, body.newRoot());
        return end <= 0 ? 0 : unit.getLineMap().getLineNumber(end - 1);
    }

    /** Whether the change is the change of the signature of a type, which is shown in the heading of its changes. */
    private static boolean isSignatureOfNested(ApiChanges container, MemberChange change) {
        return container.nested().stream().anyMatch(type -> isSignatureOf(change, type));
    }

    private static boolean isSignatureOf(MemberChange change, ApiChanges type) {
        return change.level() == ChangeLevel.API && !change.pair().isAdded() && !change.pair().isRemoved()
                && change.pair().newElement() == type.type().newElement();
    }
}
