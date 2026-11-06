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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Renders a {@link DiffAnalysis} as text, as configured by {@link ReportOptions}. Changes are grouped either by
 * file, with a heading and then, for every changed type, one line for every change, or by their
 * {@link ChangeLevel}. The lines are rendered by a {@link SignatureDiffPrinter} and colored by a {@link Theme}.
 */
public final class Reporter {

    private final SignatureDiffPrinter printer;
    private final JavadocPrinter javadoc;
    private final ReportOptions options;
    private final Theme theme;
    private final int width;

    /** The width of the lines between the files when it is not set, see {@link #withWidth}. */
    public static final int DEFAULT_WIDTH = 80;

    /**
     * Creates a reporter showing the API changes grouped by type.
     *
     * @param theme the colors of the report, {@link Theme#PLAIN} for none
     */
    public Reporter(Theme theme) {
        this(theme, ReportOptions.DEFAULT);
    }

    /**
     * Creates a reporter.
     *
     * @param theme   the colors of the report, {@link Theme#PLAIN} for none
     * @param options what to show and how to group it
     */
    public Reporter(Theme theme, ReportOptions options) {
        this(theme, options, DEFAULT_WIDTH);
    }

    private Reporter(Theme theme, ReportOptions options, int width) {
        this.width = width;
        this.printer = new SignatureDiffPrinter(theme);
        this.javadoc = new JavadocPrinter(theme, options.javadocContext());
        this.options = options;
        this.theme = theme;
    }

    /**
     * Returns a reporter like this one showing javadoc as the context says.
     *
     * @param javadocContext see {@link ReportOptions#javadocContext()}
     * @return the new reporter
     */
    public Reporter withJavadocContext(int javadocContext) {
        return new Reporter(theme, options.withJavadocContext(javadocContext), width);
    }

    /**
     * Returns a reporter like this one grouping the changes as given.
     *
     * @param grouping see {@link ReportOptions#grouping()}
     * @return the new reporter
     */
    public Reporter withGrouping(ReportOptions.Grouping grouping) {
        return new Reporter(theme, options.withGrouping(grouping), width);
    }

    /**
     * Returns a reporter like this one drawing the lines between the files as wide as given.
     *
     * @param width the width, usually that of the terminal
     * @return the new reporter
     */
    public Reporter withWidth(int width) {
        return new Reporter(theme, options, width);
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
    public List<String> report(DiffAnalysis analysis) {
        List<FileAnalysis> files = sorted(analysis);
        List<String> result = new ArrayList<>();
        if (options.grouping() == ReportOptions.Grouping.BY_TYPE) {
            // Changed imports alone are no reason to leave the other levels out: show every level then.
            ReportOptions withoutImports = options.maxLevel() == ChangeLevel.IMPORTS
                    ? options.withMaxLevel(ChangeLevel.COSMETIC)
                    : options;
            if (!new Reporter(theme, withoutImports, width).byType(files).isEmpty()) {
                return numbered(byType(files));
            }
            // Nothing to show by type: say so for every shown level, as the grouping by category does.
        }
        for (ChangeLevel level : ChangeLevel.values()) {
            if (level.compareTo(options.maxLevel()) > 0) {
                continue;
            }
            if (!result.isEmpty() && !result.get(result.size() - 1).isEmpty()) {
                result.add("");
            }
            result.addAll(section(files, level));
        }
        return numbered(result);
    }

    /** The lines with the line numbers in the column on the left, or without them, see {@link LineNumbers}. */
    private List<String> numbered(List<String> lines) {
        return LineNumbers.render(lines, options.lineNumbers(), theme);
    }

    /** The changes of every file, the files separated by a line as in the sections of the levels. */
    private List<String> byType(List<FileAnalysis> files) {
        List<String> result = new ArrayList<>();
        for (FileAnalysis file : files) {
            List<String> part = new ArrayList<>();
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
    public List<String> reportOnly(DiffAnalysis analysis, ChangeLevel level) {
        return numbered(section(sorted(analysis), level));
    }

    private static List<FileAnalysis> sorted(DiffAnalysis analysis) {
        return analysis.files().stream()
                .sorted(Comparator.comparing(FileAnalysis::newPackage).thenComparing(FileAnalysis::newClassName))
                .toList();
    }

    /** The heading of the level followed by its changes, or by a remark that there are none. */
    private List<String> section(List<FileAnalysis> files, ChangeLevel level) {
        List<String> lines = level == ChangeLevel.API ? apiLines(files) : levelLines(files, level);
        List<String> section = new ArrayList<>();
        section.add(LineNumbers.outside(theme.style(Theme.Role.HEADING_MARK, "#") + " "
                + theme.style(Theme.Role.HEADING, SECTIONS.get(level))));
        section.add("");
        if (lines.isEmpty()) {
            section.add("  " + theme.style(Theme.Role.NO_CHANGES, "NO CHANGES"));
            section.add("");
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
    private List<String> levelLines(List<FileAnalysis> files, ChangeLevel level) {
        List<String> lines = new ArrayList<>();
        for (FileAnalysis file : files) {
            List<String> fileLines = new ArrayList<>();
            if (level == ChangeLevel.IMPORTS) {
                if (file.diff().getChangeType() == DiffEntry.ChangeType.DELETE) {
                    continue;
                }
                importLines(file, false, fileLines);
            } else {
                fileLines = lines(file, (container, c) -> c.level() == level, false, false, false);
            }
            List<String> part = new ArrayList<>();
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
    private List<String> apiLines(List<FileAnalysis> files) {
        List<TypeRef> added = new ArrayList<>();
        List<TypeRef> removed = new ArrayList<>();
        for (FileAnalysis file : files) {
            collectTypes(file, added, removed);
        }
        List<List<String>> parts = new ArrayList<>();
        for (FileAnalysis file : files) {
            if (file.diff().getChangeType() == DiffEntry.ChangeType.ADD) {
                List<String> list = new ArrayList<>();
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
                List<String> lines = new ArrayList<>();
                addTree(lines, "", declarationNode(new ElementPair(null, type.element(), Set.of()), "", null,
                        location(type, true), List.of(), addedMembers(type.element())));
                parts.add(lines);
            }
        }
        List<String> names = new ArrayList<>();
        for (FileAnalysis file : files) {
            if (file.diff().getChangeType() == DiffEntry.ChangeType.DELETE) {
                names.add(fileTitle(file));
            }
        }
        for (TypeRef type : removed) {
            if (type.file().diff().getChangeType() != DiffEntry.ChangeType.DELETE) {
                names.add(LineNumbers.number(type.element().getLine(), '-',
                        theme.style(Theme.Role.TITLE, type.element().getName()) + location(type, false)));
            }
        }
        parts.add(names);
        for (FileAnalysis file : files) {
            if (file.diff().getChangeType() == DiffEntry.ChangeType.RENAME) {
                List<String> renamed = new ArrayList<>();
                addFile(renamed, file, lines(file, (container, c) -> c.level() == ChangeLevel.API
                        && !isListedApart(container, c), true, false, true), true);
                parts.add(renamed);
            }
        }
        for (FileAnalysis file : files) {
            List<String> changed = new ArrayList<>();
            if (file.diff().getChangeType() != DiffEntry.ChangeType.RENAME) {
                addFile(changed, file, lines(file, (container, c) -> c.level() == ChangeLevel.API
                        && !isListedApart(container, c), true, false, true), false);
            }
            parts.add(changed);
        }
        List<String> result = new ArrayList<>();
        for (List<String> part : parts) {
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
    private String location(TypeRef type, boolean added) {
        String path = added ? type.file().diff().getNewPath() : type.file().diff().getOldPath();
        String pkg = added ? type.file().newPackage() : type.file().oldPackage();
        String root = sourceRoot(path, pkg);
        String where = root.isEmpty() || pkg.isEmpty() ? pkg : pkg + " " + root;
        return where.isEmpty() ? "" : " " + theme.style(Theme.Role.PACKAGE, where);
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
        List<String> above = new ArrayList<>(javadoc.plain(element));
        above.addAll(printer.declaration(element, typeName));
        String head = above.remove(above.size() - 1);
        return new Node(above, head, List.of(),
                element.getNodeType() == ElementInfo.NodeType.TYPE ? addedMembers(element) : List.of());
    }

    /**
     * An element shown in a tree: the lines above its signature, like the javadoc and the annotations, the line of
     * the signature, the lines below it, like the body, and the elements in it.
     */
    private record Node(List<String> above, String head, List<String> below, List<Node> children) { }

    /** Adds a top level element, its signature indented by {@code indent}, with the tree of the elements in it. */
    private void addTree(List<String> lines, String indent, Node node) {
        node.above().forEach(l -> lines.add(indent + l));
        lines.add(indent + node.head());
        node.below().forEach(l -> lines.add(indent + l));
        addBranches(lines, indent, node.children());
    }

    /** Adds the elements below their parent, each on a branch starting under the signature of the parent. */
    private void addBranches(List<String> lines, String prefix, List<Node> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            boolean last = i == nodes.size() - 1;
            String through = prefix + branch("\u2502   ");
            String below = prefix + branch(last ? "    " : "\u2502   ");
            node.above().forEach(l -> lines.add(through + l));
            lines.add(prefix + branch(last ? "\u2514\u2500\u2500 " : "\u251c\u2500\u2500 ") + node.head());
            node.below().forEach(l -> lines.add(below + l));
            addBranches(lines, below, node.children());
        }
    }

    /** A piece of the lines of a tree in their style. */
    private String branch(String text) {
        return text.isBlank() ? text : theme.style(Theme.Role.TREE, text);
    }

    /** Adds the lines of a file below its title. */
    private void addFile(List<String> result, FileAnalysis file, List<String> lines, boolean keepEmpty) {
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
    private Node declarationNode(ElementPair pair, String typeName, String initializer, String suffix,
                                 List<String> below, List<Node> children) {
        List<String> above = new ArrayList<>(javadocLines(pair));
        above.addAll(printer.annotations(pair));
        boolean changedLine = initializer != null && LineNumbers.marker(initializer) != ' '
                && LineNumbers.marker(initializer) != LineNumbers.NONE;
        String rest = initializer == null ? "" : " = " + LineNumbers.text(initializer).strip();
        return new Node(above, printer.format(pair, typeName, changedLine) + rest + suffix, below, children);
    }

    /**
     * Reports the changes of a file, those of the levels up to {@link ReportOptions#maxLevel()}.
     *
     * @param file the analyzed file
     * @return indented lines, empty if there is nothing to show
     */
    public List<String> report(FileAnalysis file) {
        return numbered(fileLines(file));
    }

    private List<String> fileLines(FileAnalysis file) {
        List<String> lines = lines(file, (container, c) -> c.level().compareTo(options.maxLevel()) <= 0, true, true, true);
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
     */
    private List<String> lines(FileAnalysis file, BiPredicate<ApiChanges, MemberChange> accepted, boolean header,
                               boolean tagged, boolean diff) {
        // A change of the javadoc alone has nothing to show when the javadoc is not shown.
        boolean hideJavadoc = options.javadocContext() == ReportOptions.NO_JAVADOC;
        BiPredicate<ApiChanges, MemberChange> filter = (container, c) -> accepted.test(container, c)
                && !(hideJavadoc && c.isJavadocOnly());
        List<Node> nodes = new ArrayList<>();
        if (file.oldPrimary() != null) {
            List<Node> members = render(file.primaryChanges(), filter, tagged, diff);
            if (header || !members.isEmpty()) {
                nodes.add(typeNode(file.primaryChanges().type(), "", diff, members));
            }
        }
        nodes.addAll(render(file.rootChanges(), filter, tagged, diff));
        List<String> lines = new ArrayList<>();
        nodes.forEach(node -> addTree(lines, "  ", node));
        return lines;
    }

    /**
     * The node of a changed type with its javadoc, annotations and signature, the changes of them shown if
     * {@code diff}, and the changes in it.
     */
    private Node typeNode(ElementPair type, String outerName, boolean diff, List<Node> children) {
        ElementPair shown = diff ? type : new ElementPair(type.newElement(), type.newElement(), Set.of());
        List<String> above = new ArrayList<>(javadocLines(shown));
        above.addAll(printer.annotations(shown));
        return new Node(above, printer.formatType(shown, outerName), List.of(), children);
    }

    /**
     * The javadoc shown above a declaration: the changes of it, or for an element shown without its changes, as in
     * the sections other than the API, its javadoc as it is.
     */
    private List<String> javadocLines(ElementPair pair) {
        return pair.oldElement() == pair.newElement() ? javadoc.unchanged(pair.newElement()) : javadoc.print(pair);
    }

    private void importLines(FileAnalysis file, boolean tagged, List<String> lines) {
        String tag = tagged ? tag("import") : "";
        file.importChanges().removed()
                .forEach(i -> lines.add("  " + importLine(false, i, line(file.oldUnit(), i)) + tag));
        file.importChanges().added()
                .forEach(i -> lines.add("  " + importLine(true, i, line(file.newUnit(), i)) + tag));
    }

    private String importLine(boolean added, String name, long line) {
        return LineNumbers.number(line, added ? '+' : '-',
                added ? theme.code("import " + name) : theme.strike("import " + name));
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

    private String tag(String text) {
        return " " + theme.style(Theme.Role.TAG, "[" + text + "]");
    }

    /** The marker of the level of a change, empty for the API changes. */
    private String tag(MemberChange change) {
        return switch (change.level()) {
            case API -> "";
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

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Follows the package with the source root it is in, e.g. {@code com.acme src/test/java}. */
    private static String withSourceRoot(String path, String pkg) {
        String root = sourceRoot(path, pkg);
        return root.isEmpty() ? pkg : pkg + " " + root;
    }

    /** The heading of a file in the report, starting at the left edge, left of the column of the line numbers. */
    private String fileTitle(FileAnalysis file) {
        return LineNumbers.outside(title(file));
    }

    /** The thin line separating the files, see {@link #withWidth}. */
    private String separator() {
        return LineNumbers.outside(theme.style(Theme.Role.TREE, "\u2500".repeat(width)));
    }

    /**
     * Returns the heading of a changed file: the name of the file, a space, its package and its source root,
     * e.g. {@code Foo.java com.acme src/main/java}. Added files are prefixed with {@code +}, deleted ones with {@code -}, and
     * a renamed file or moved package shows the old value struck through.
     *
     * @param file the analyzed file
     * @return the heading line
     */
    public String title(FileAnalysis file) {
        DiffEntry.ChangeType type = file.diff().getChangeType();
        String oldName = type == DiffEntry.ChangeType.ADD ? "" : fileName(file.diff().getOldPath());
        String newName = type == DiffEntry.ChangeType.DELETE ? "" : fileName(file.diff().getNewPath());
        String oldPackage = type == DiffEntry.ChangeType.ADD ? ""
                : withSourceRoot(file.diff().getOldPath(), file.oldPackage());
        String newPackage = type == DiffEntry.ChangeType.DELETE ? ""
                : withSourceRoot(file.diff().getNewPath(), file.newPackage());
        boolean deleted = type == DiffEntry.ChangeType.DELETE;
        String name = deleted ? theme.strike(oldName) : theme.style(Theme.Role.TITLE, printer.slot(oldName, newName));
        String pkg = deleted ? (oldPackage.isEmpty() ? "" : theme.strike(oldPackage))
                : theme.style(Theme.Role.PACKAGE, printer.slot(oldPackage, newPackage));
        String title = name + (pkg.isEmpty() ? "" : " " + pkg);
        return switch (type) {
            case ADD -> theme.sign(true) + " " + title;
            case DELETE -> theme.sign(false) + " " + title;
            case RENAME -> theme.renamed() + " " + title;
            default -> theme.modified() + " " + title;
        };
    }

    /**
     * The lines showing what changed in a body: the changed statements with the unchanged lines between them, see
     * {@link ReportOptions#bodyContext()}.
     */
    private List<String> body(BodyChanges body) {
        return new AstPrinter(body, theme, options.bodyContext()).print();
    }

    /**
     * Renders the changes of a container accepted by the filter and the types nested in it which have some, each
     * of them with its signature, in the order of the new source (a removed one at its line in the old source).
     */
    private List<Node> render(ApiChanges changes, BiPredicate<ApiChanges, MemberChange> filter, boolean tagged,
                              boolean diff) {
        List<Object> items = new ArrayList<>(changes.changes());
        items.addAll(changes.nested());
        items.sort(Comparator.comparingLong(Reporter::newLine));
        List<Node> nodes = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof MemberChange change) {
                if (filter.test(changes, change) && !isSignatureOfNested(changes, change)) {
                    nodes.add(memberNode(changes, change, tagged));
                }
            } else if (item instanceof ApiChanges type) {
                List<Node> nested = render(type, filter, tagged, diff);
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
        ElementPair pair = change.level() == ChangeLevel.API ? change.pair()
                : new ElementPair(change.pair().newElement(), change.pair().newElement(), Set.of());
        List<String> body = change.body() == null ? List.of() : body(change.body());
        // The initializer of a field continues the line of its declaration.
        boolean inline = body.size() == 1 && !(change.body().newRoot() instanceof BlockTree);
        // A shown block is enclosed in its braces, as in the source.
        boolean block = !body.isEmpty() && change.body().newRoot() instanceof BlockTree;
        List<String> below = new ArrayList<>();
        if (!inline) {
            body.forEach(l -> below.add("      " + l));
        }
        if (block) {
            below.add(LineNumbers.number(closingLine(change.body()), ' ', theme.code("}")));
        }
        List<Node> children = change.level() == ChangeLevel.API && change.pair().isAdded()
                && change.pair().newElement().getNodeType() == ElementInfo.NodeType.TYPE
                ? addedMembers(change.pair().newElement()) : List.of();
        return declarationNode(pair, changes.typeName(), inline ? body.get(0) : null,
                (block ? " {" : "") + (tagged ? tag(change) : ""), below, children);
    }

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
