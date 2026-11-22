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
import com.sun.source.tree.Tree;
import cz.hrebejk.sedi.analysis.ApiChanges;
import cz.hrebejk.sedi.analysis.BodyChanges;
import cz.hrebejk.sedi.analysis.ChangeLevel;
import cz.hrebejk.sedi.analysis.DiffAnalysis;
import cz.hrebejk.sedi.analysis.ElementInfo;
import cz.hrebejk.sedi.analysis.ElementPair;
import cz.hrebejk.sedi.analysis.FileAnalysis;
import cz.hrebejk.sedi.analysis.MemberChange;
import cz.hrebejk.sedi.analysis.SyntaxRoles;
import cz.hrebejk.sedi.analysis.Visibility;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;
import org.eclipse.jgit.diff.DiffEntry;
import org.maraboo.fleck.html.HTML;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

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
    /** Shows the signatures as they are, only the renamed names marked, see {@link SignatureDiffPrinter#plain()}. */
    private final SignatureDiffPrinter plainPrinter;
    private final JavadocPrinter javadoc;
    private final ReportOptions options;
    private final int width;

    /** The width of the lines between the files when it is not set, see {@link #withWidth}. */
    public static final int DEFAULT_WIDTH = 80;

    /**
     * Creates a reporter showing the API changes.
     */
    public Reporter() {
        this(ReportOptions.DEFAULT);
    }

    /**
     * Creates a reporter.
     *
     * @param options what to show and how
     */
    public Reporter(ReportOptions options) {
        this(options, DEFAULT_WIDTH);
    }

    private Reporter(ReportOptions options, int width) {
        this.width = width;
        this.printer = new SignatureDiffPrinter();
        this.cosmeticPrinter = printer.cosmetic();
        this.plainPrinter = printer.plain();
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
     * Returns a reporter like this one showing the changes of the test sources, or not.
     *
     * @param show see {@link ReportOptions#showTests()}
     * @return the new reporter
     */
    public Reporter withTests(boolean show) {
        return new Reporter(options.withTests(show), width);
    }

    /**
     * Returns a reporter like this one showing only the API changes of the public API, or not.
     *
     * @param only see {@link ReportOptions#publicOnly()}
     * @return the new reporter
     */
    public Reporter withPublicOnly(boolean only) {
        return new Reporter(options.withPublicOnly(only), width);
    }

    /**
     * Returns a reporter like this one keeping the unchanged lines of a body collapsed to a note behind it, or not.
     *
     * @param fold see {@link ReportOptions#foldContext()}
     * @return the new reporter
     */
    public Reporter withFoldContext(boolean fold) {
        return new Reporter(options.withFoldContext(fold), width);
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
     * Reports the changes of all the files of the diff by level: the heading of each level up to
     * {@link ReportOptions#maxLevel()}, then the changes of the level file by file.
     *
     * @param analysis the analyzed diff
     * @return the headings, each with the lines under it or {@code NO CHANGES}
     */
    public HTML.Tag report(DiffAnalysis analysis) {
        List<FileAnalysis> files = sorted(analysis);
        List<Line> result = new ArrayList<>();
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

    /**
     * Reports only the changes of one level, ignoring {@link ReportOptions#maxLevel()}.
     *
     * @param analysis the analyzed diff
     * @param level    the only level to show
     * @return the heading of the level and the changes under it, or {@code NO CHANGES} if there are none
     */
    public HTML.Tag reportOnly(DiffAnalysis analysis, ChangeLevel level) {
        return numbered(section(sorted(analysis), level));
    }

    /**
     * Counts the changed elements of one level, or of all of them, of the files the report shows. An element
     * changed in more than one level counts once among all of them; a change of the javadoc alone does not count
     * when the javadoc is not shown.
     *
     * @param analysis the analyzed diff
     * @param level    the level to count, {@code null} for all the levels but the imports
     * @return the number of the changed types and members
     */
    public int countChanged(DiffAnalysis analysis, ChangeLevel level) {
        boolean hideJavadoc = options.javadocContext() == ReportOptions.NO_JAVADOC;
        return count(analysis, change -> (level == null || change.level() == level)
                && !(hideJavadoc && change.isJavadocOnly()) && (change.level() != ChangeLevel.API || isShownApi(change)));
    }

    /**
     * Counts the added, or the removed, types and members of the files the report shows, those whose whole source
     * the implementation changes show, see {@link ReportOptions#showAdditions()} and
     * {@link ReportOptions#showDeletions()}.
     *
     * @param analysis the analyzed diff
     * @param added    {@code true} to count the added elements, {@code false} the removed ones
     * @return the number of the added or the removed types and members
     */
    public int countWhole(DiffAnalysis analysis, boolean added) {
        return count(analysis, change -> change.level() == ChangeLevel.API
                && (added ? change.pair().isAdded() : change.pair().isRemoved()));
    }

    /**
     * Whether the API change is shown: any of them, or only one of the public API, see
     * {@link ReportOptions#publicOnly()}.
     */
    private boolean isShownApi(MemberChange change) {
        return change.level() == ChangeLevel.API && (!options.publicOnly() || change.publicApi());
    }

    /** Counts the elements of the changes the filter accepts, in the files the report shows, each element once. */
    private int count(DiffAnalysis analysis, Predicate<MemberChange> filter) {
        Set<ElementPair> changed = new HashSet<>();
        for (FileAnalysis file : sorted(analysis)) {
            for (ApiChanges changes : file.oldPrimary() == null ? List.of(file.rootChanges())
                    : List.of(file.primaryChanges(), file.rootChanges())) {
                collect(changes, filter, changed);
            }
        }
        return changed.size();
    }

    private static void collect(ApiChanges changes, Predicate<MemberChange> filter, Set<ElementPair> changed) {
        for (MemberChange change : changes.changes()) {
            if (filter.test(change)) {
                changed.add(change.pair());
            }
        }
        changes.nested().forEach(nested -> collect(nested, filter, changed));
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
            List<Line> part = new ArrayList<>();
            addFile(part, file, levelLines(file, level), false);
            if (!part.isEmpty()) {
                if (!lines.isEmpty()) {
                    lines.add(separator());
                }
                lines.addAll(part);
            }
        }
        return lines;
    }

    /** The changes of one level other than the API of a file, without its title. */
    private List<Line> levelLines(FileAnalysis file, ChangeLevel level) {
        List<Line> lines = new ArrayList<>();
        if (level == ChangeLevel.IMPORTS) {
            if (file.diff().getChangeType() != DiffEntry.ChangeType.DELETE) {
                importLines(file, false, lines);
            }
            return lines;
        }
        // The added and removed elements are listed in the API changes, their code is shown here.
        return lines(file, (container, c) -> c.level() == level
                || level == ChangeLevel.IMPLEMENTATION && isShownWhole(c), false, false, false, true);
    }

    /**
     * Reports all the changes, whatever {@link ReportOptions#maxLevel()} is, file by file: under
     * the title of a file the changes of each level of it, under the name of the level. A level without changes is
     * left out, and so is a file without any. A file and each level of it are {@linkplain Line#fold folds}; the
     * levels other than the API are collapsed at first.
     *
     * @param analysis the analyzed diff
     * @return the report, empty if there is nothing to show
     */
    public HTML.Tag reportByFile(DiffAnalysis analysis) {
        List<Line> result = new ArrayList<>();
        for (FileAnalysis file : sorted(analysis)) {
            List<Line> levels = new ArrayList<>();
            for (ChangeLevel level : ChangeLevel.values()) {
                List<Line> lines = level == ChangeLevel.API ? apiLines(file) : levelLines(file, level);
                if (!lines.isEmpty()) {
                    levels.add(Line.fold(LEVELS.get(level), level == ChangeLevel.API));
                    levels.add(Line.outside(Fragment.of("  ").plus(Markup.style(Css.HEADING_MARK, "#")).plus(" ")
                            .plus(Markup.style(Css.LEVEL, LEVELS.get(level)))));
                    levels.addAll(lines);
                    levels.add(Line.end());
                }
            }
            if (!levels.isEmpty()) {
                if (!result.isEmpty()) {
                    result.add(separator());
                }
                addFile(result, file, levels, false);
            }
        }
        return numbered(result);
    }

    /** The names of the levels in the {@linkplain #reportByFile report by file}. */
    private static final Map<ChangeLevel, String> LEVELS = Map.of(
            ChangeLevel.API, "API",
            ChangeLevel.IMPLEMENTATION, "Implementation",
            ChangeLevel.COSMETIC, "Cosmetic",
            ChangeLevel.IMPORTS, "Imports");

    /**
     * The API changes of a file, without its title: the public API of the types of an added file, the names of the
     * types of a removed one, else the changes of its types followed by its added types and the names of its
     * removed ones.
     */
    private List<Line> apiLines(FileAnalysis file) {
        List<TypeRef> added = new ArrayList<>();
        List<TypeRef> removed = new ArrayList<>();
        collectTypes(file, added, removed);
        List<Line> lines = new ArrayList<>();
        DiffEntry.ChangeType change = file.diff().getChangeType();
        if (change == DiffEntry.ChangeType.ADD) {
            added.forEach(type -> addTree(lines, "  ", addedNode(type.element(), "", movedInto(file, type))));
            // The types moved here to the top level of the file.
            lines.addAll(apiChangeLines(file));
            return lines;
        }
        if (change != DiffEntry.ChangeType.DELETE) {
            lines.addAll(apiChangeLines(file));
            added.forEach(type -> addTree(lines, "  ", declarationNode(new ElementPair(null, type.element(), Set.of()),
                    "", null, Fragment.EMPTY, List.of(), addedMembers(type.element(), movedInto(file, type)))));
        }
        removed.forEach(type -> lines.add(Line.number(type.element().getLine(), '-',
                Markup.style(Css.TITLE, type.element().getName())).withPrefix("  ")));
        return lines;
    }

    /** A top level type added or removed, with the file it is in. */
    private record TypeRef(FileAnalysis file, ElementInfo element) { }

    /** The API changes of the types of a file, the top level types added or removed left out. */
    private List<Line> apiChangeLines(FileAnalysis file) {
        return lines(file, (container, c) -> isShownApi(c) && !isListedApart(container, c),
                true, false, true, false);
    }

    /** The changes of the types moved into an added top level type, {@code null} if none moved there. */
    private static ApiChanges movedInto(FileAnalysis file, TypeRef type) {
        return nestedOf(file.rootChanges(), type.element());
    }

    /** The changes of the type nested in the container, {@code null} if there are none. */
    private static ApiChanges nestedOf(ApiChanges container, ElementInfo type) {
        if (container == null) {
            return null;
        }
        for (ApiChanges nested : container.nested()) {
            if (nested.type().newElement() == type) {
                return nested;
            }
        }
        return null;
    }

    /**
     * Whether all the types of a deleted file moved elsewhere, into other types or out of them: the file has no
     * changes of its own to show.
     */
    private static boolean isAllMoved(FileAnalysis file) {
        return file.diff().getChangeType() == DiffEntry.ChangeType.DELETE && !file.oldRoot().getChildren().isEmpty()
                && file.rootChanges().changes().isEmpty() && file.rootChanges().nested().isEmpty();
    }

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
                List<Line> types = new ArrayList<>();
                for (TypeRef type : added) {
                    if (type.file() == file) {
                        addTree(types, "  ", addedNode(type.element(), "", movedInto(file, type)));
                    }
                }
                // The types moved here to the top level of the file.
                types.addAll(apiChangeLines(file));
                if (types.isEmpty() && options.publicOnly()) {
                    // No type of the file is a part of the public API.
                    continue;
                }
                List<Line> list = new ArrayList<>();
                addFile(list, file, types, true);
                parts.add(list);
            }
        }
        for (TypeRef type : added) {
            if (type.file().diff().getChangeType() != DiffEntry.ChangeType.ADD) {
                List<Line> lines = new ArrayList<>();
                addTree(lines, "", declarationNode(new ElementPair(null, type.element(), Set.of()), "", null,
                        location(type, true), List.of(), addedMembers(type.element(), movedInto(type.file(), type))));
                parts.add(lines);
            }
        }
        List<Line> names = new ArrayList<>();
        for (FileAnalysis file : files) {
            if (file.diff().getChangeType() == DiffEntry.ChangeType.DELETE && !isAllMoved(file)
                    && (!options.publicOnly() || removed.stream().anyMatch(type -> type.file() == file))) {
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
                addFile(renamed, file, lines(file, (container, c) -> isShownApi(c)
                        && !isListedApart(container, c), true, false, true, false), true);
                parts.add(renamed);
            }
        }
        for (FileAnalysis file : files) {
            List<Line> changed = new ArrayList<>();
            DiffEntry.ChangeType change = file.diff().getChangeType();
            if (change != DiffEntry.ChangeType.RENAME && change != DiffEntry.ChangeType.ADD) {
                addFile(changed, file, apiChangeLines(file), false);
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

    /** Collects the added and removed top level types of the file whose API changes are shown. */
    private void collectTypes(FileAnalysis file, List<TypeRef> added, List<TypeRef> removed) {
        for (MemberChange change : file.rootChanges().changes()) {
            if (isShownApi(change) && isTypeAddedOrRemoved(change)) {
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

    /**
     * The members of an added type, only those which are a part of the public API if only it is shown, see
     * {@link ReportOptions#publicOnly()}, and the types nested in it, without the {@code +} as all of them are new.
     * A type moved there is shown with its changes instead.
     *
     * @param moved the changes of the types moved into the type, {@code null} if none moved there
     */
    private List<Node> addedMembers(ElementInfo type, ApiChanges moved) {
        boolean inInterface = Visibility.isInterface(type);
        List<Node> nodes = new ArrayList<>();
        for (ElementInfo member : type.getChildren()) {
            if (!options.publicOnly() || Visibility.isExposed(member, true, inInterface)) {
                ApiChanges nested = nestedOf(moved, member);
                nodes.add(nested != null && !nested.type().isAdded()
                        ? typeNode(nested, type.getName(), true,
                                render(nested, (container, c) -> isShownApi(c), false, true, false))
                        : addedNode(member, type.getName(), nested));
            }
        }
        return nodes;
    }

    /**
     * An element of an added type with its javadoc and, if it is a type, its members.
     *
     * @param moved the changes of the types moved into the element, {@code null} if none moved there
     */
    private Node addedNode(ElementInfo element, String typeName, ApiChanges moved) {
        List<Line> above = new ArrayList<>(javadoc.plain(element));
        above.addAll(printer.declaration(element, typeName));
        Line head = above.removeLast();
        return new Node(above, head, List.of(),
                element.getNodeType() == ElementInfo.NodeType.TYPE ? addedMembers(element, moved) : List.of(), element);
    }

    /**
     * An element shown in a tree: the lines above its signature, like the javadoc and the annotations, the line of
     * the signature, the lines below it, like the body, the elements in it, and the lines after them, like the
     * closing brace of a type; the element itself, as it is now unless it was removed.
     */
    private record Node(List<Line> above, Line head, List<Line> below, List<Node> children, List<Line> after,
                        ElementInfo element) {

        Node(List<Line> above, Line head, List<Line> below, List<Node> children, ElementInfo element) {
            this(above, head, below, children, List.of(), element);
        }

        /** Whether there is something under the signature to fold. */
        boolean isFoldable() {
            return !below.isEmpty() || !children.isEmpty() || !after.isEmpty();
        }
    }

    /** Adds a top level element, its signature indented by {@code indent}, with the tree of the elements in it. */
    private void addTree(List<Line> lines, String indent, Node node) {
        node.above().forEach(l -> lines.add(l.withPrefix(indent)));
        foldStart(lines, node);
        lines.add(node.head().withPrefix(indent));
        node.below().forEach(l -> lines.add(l.withPrefix(indent)));
        addBranches(lines, Fragment.of(indent), node.children());
        node.after().forEach(l -> lines.add(l.withPrefix(indent)));
        foldEnd(lines, node);
    }

    /**
     * Starts the fold of an element with something under its signature, its lines or the elements in it, so the
     * signature can collapse them; the javadoc and the annotations above it stay out of the fold.
     */
    private static void foldStart(List<Line> lines, Node node) {
        if (node.isFoldable()) {
            lines.add(Line.elementFold(node.head().content().text(),
                    node.element().getNodeType() == ElementInfo.NodeType.TYPE, node.element().getJavadoc()));
        }
    }

    /** Ends the fold {@link #foldStart} started, if any. */
    private static void foldEnd(List<Line> lines, Node node) {
        if (node.isFoldable()) {
            lines.add(Line.end());
        }
    }

    /** Adds the elements below their parent, each on a branch starting under the signature of the parent. */
    private void addBranches(List<Line> lines, Fragment prefix, List<Node> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            boolean last = i == nodes.size() - 1;
            Fragment through = prefix.plus(branch("\u2502   "));
            Fragment below = prefix.plus(branch(last ? "    " : "\u2502   "));
            node.above().forEach(l -> lines.add(l.withPrefix(through)));
            foldStart(lines, node);
            lines.add(node.head().withPrefix(prefix.plus(branch(last ? "\u2514\u2500\u2500 " : "\u251c\u2500\u2500 "))));
            node.below().forEach(l -> lines.add(l.withPrefix(below)));
            addBranches(lines, below, node.children());
            node.after().forEach(l -> lines.add(l.withPrefix(below)));
            foldEnd(lines, node);
        }
    }

    /** A piece of the lines of a tree in their style. */
    private static Fragment branch(String text) {
        return text.isBlank() ? Fragment.of(text) : Markup.style(Css.TREE, text);
    }

    /** Adds the lines of a file below its title, folded under it if there are any. */
    private void addFile(List<Line> result, FileAnalysis file, List<Line> lines, boolean keepEmpty) {
        if (!lines.isEmpty()) {
            result.add(Line.fold(path(file)));
            result.add(fileTitle(file));
            result.addAll(lines);
            result.add(Line.end());
        } else if (keepEmpty) {
            result.add(fileTitle(file));
        }
    }

    /** The path of the file, of the old one if it was deleted. */
    private static String path(FileAnalysis file) {
        return file.diff().getChangeType() == DiffEntry.ChangeType.DELETE ? file.diff().getOldPath()
                : file.diff().getNewPath();
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
        return declarationNode(pair, printer, typeName, initializer, suffix, below, children);
    }

    /**
     * Like {@link #declarationNode(ElementPair, String, Line, Fragment, List, List)}.
     *
     * @param signatures the printer of the signature; unless it is the one showing the changes of the API, the
     *                   javadoc and annotations are shown as they are
     */
    private Node declarationNode(ElementPair pair, SignatureDiffPrinter signatures, String typeName, Line initializer,
                                 Fragment suffix, List<Line> below, List<Node> children) {
        boolean asIs = signatures != printer;
        ElementPair unchanged = asIs ? new ElementPair(pair.newElement(), pair.newElement(), Set.of()) : pair;
        List<Line> above = new ArrayList<>(javadocLines(unchanged));
        above.addAll(printer.annotations(unchanged));
        boolean changedLine = initializer != null && initializer.marker() != ' ' && initializer.marker() != Line.NONE;
        // The arguments and the class body of an enum constant follow its name, an initializer follows =.
        ElementInfo element = pair.isRemoved() ? pair.oldElement() : pair.newElement();
        Fragment rest = initializer == null ? Fragment.EMPTY
                : Fragment.of(element.isEnumConstant() ? "" : " = ")
                        .plus(initializer.prefix().plus(initializer.content()).strip());
        Line head = signatures.format(pair, typeName, changedLine);
        return new Node(above, head.append(rest.plus(suffix)), below, children, element);
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
                nodes.add(typeNode(file.primaryChanges(), "", diff, members));
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
    private Node typeNode(ApiChanges changes, String outerName, boolean diff, List<Node> children) {
        ElementPair type = changes.type();
        ElementPair shown = diff ? type : new ElementPair(type.newElement(), type.newElement(), Set.of());
        List<Line> above = new ArrayList<>(javadocLines(shown));
        above.addAll(printer.annotations(shown));
        // Without the changes, a renamed or moved type is still marked as such.
        // The badge of the change of the type goes with the changes of the signatures.
        Line head = diff ? printer.formatType(type, outerName).append(Markup.incompatible(changes.incompatibilities()))
                : plainPrinter.formatType(type.isAdded() ? shown : type, outerName);
        return new Node(above, head, List.of(), children, type.isRemoved() ? type.oldElement() : type.newElement());
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
                .forEach(i -> lines.add(Line.number(line(file.oldUnit(), i), '-', Markup.strike("import " + i))
                        .withPrefix("  ").append(tag)));
        file.importChanges().added()
                .forEach(i -> lines.add(Line.number(line(file.newUnit(), i), '+', importCode(file.newUnit(), i))
                        .withPrefix("  ").append(tag)));
    }

    /** An import of the version of the file, highlighted, see {@link #importTree}. */
    private static Fragment importCode(CompilationUnitInfo unit, String name) {
        ImportTree tree = importTree(unit, name);
        if (tree == null) {
            return Fragment.of("import " + name);
        }
        CompilationUnitTree root = unit.compilationUnit();
        Tree identifier = tree.getQualifiedIdentifier();
        long start = unit.trees().getSourcePositions().getStartPosition(root, identifier);
        long end = unit.trees().getSourcePositions().getEndPosition(root, identifier);
        Fragment code = Syntax.fragment(SyntaxRoles.of(unit, tree).runs((int) start, (int) end));
        return Syntax.keyword("import").plus(" ")
                .plus(tree.isStatic() ? Syntax.keyword("static").plus(" ") : Fragment.EMPTY).plus(code);
    }

    /**
     * The line of an import in a version of the file.
     *
     * @param name the import as {@link cz.hrebejk.sedi.analysis.ImportChanges} has it, e.g. {@code static a.B.c}
     * @return the line, {@code 0} if not known
     */
    private static long line(CompilationUnitInfo unit, String name) {
        ImportTree i = importTree(unit, name);
        if (i == null) {
            return 0;
        }
        CompilationUnitTree tree = unit.compilationUnit();
        long start = unit.trees().getSourcePositions().getStartPosition(tree, i);
        return start < 0 ? 0 : tree.getLineMap().getLineNumber(start);
    }

    /**
     * The tree of an import in a version of the file.
     *
     * @param name the import as {@link cz.hrebejk.sedi.analysis.ImportChanges} has it, e.g. {@code static a.B.c}
     * @return the tree, {@code null} if not found
     */
    private static ImportTree importTree(CompilationUnitInfo unit, String name) {
        if (unit == null) {
            return null;
        }
        for (ImportTree i : unit.compilationUnit().getImports()) {
            if (((i.isStatic() ? "static " : "") + i.getQualifiedIdentifier()).equals(name)) {
                return i;
            }
        }
        return null;
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
        boolean deleted = type == DiffEntry.ChangeType.DELETE;
        Fragment name = deleted ? Markup.strike(oldName) : Markup.style(Css.TITLE, printer.slot(oldName, newName));
        Fragment title = name;
        String deletedPackage = deleted ? withSourceRoot(file.diff().getOldPath(), file.oldPackage()) : "";
        if (!deletedPackage.isEmpty()) {
            title = title.plus(" ").plus(Markup.strike(deletedPackage));
        } else if (!deleted) {
            // The package and the source root are compared apart: an unchanged one is shown once.
            boolean added = type == DiffEntry.ChangeType.ADD;
            String oldPackage = added ? "" : file.oldPackage();
            String oldRoot = added ? "" : sourceRoot(file.diff().getOldPath(), file.oldPackage());
            String newRoot = sourceRoot(file.diff().getNewPath(), file.newPackage());
            for (Fragment part : List.of(printer.slot(oldPackage, file.newPackage()), printer.slot(oldRoot, newRoot))) {
                if (!part.isEmpty()) {
                    title = title.plus(" ").plus(Markup.style(Css.PACKAGE, part));
                }
            }
        }
        Fragment mark = switch (type) {
            case ADD -> Markup.sign(true);
            case DELETE -> Markup.sign(false);
            case RENAME -> Markup.renamedFile();
            default -> Markup.modified();
        };
        return mark.plus(" ").plus(title);
    }

    /**
     * The lines showing what changed in a body: the changed statements with the unchanged lines between them, see
     * {@link ReportOptions#bodyContext()}.
     */
    private List<Line> body(BodyChanges body) {
        return new AstPrinter(body, options.bodyContext(), options.foldContext()).print();
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
                if (type.type().isAdded() && (diff || whole && isShownWhole(changes, type, filter))) {
                    // The types moved into an added type are shown with it.
                    continue;
                }
                List<Node> nested = render(type, filter, tagged, diff, whole);
                boolean own = changes.changes().stream()
                        .anyMatch(c -> filter.test(changes, c) && isSignatureOf(c, type));
                if (own || !nested.isEmpty()) {
                    nodes.add(typeNode(type, changes.typeName(), diff, nested));
                }
            }
        }
        return nodes;
    }

    /** Whether the added nested type is shown with its whole source. */
    private boolean isShownWhole(ApiChanges container, ApiChanges type,
                                 BiPredicate<ApiChanges, MemberChange> filter) {
        return container.changes().stream().anyMatch(c -> c.pair().isAdded()
                && c.pair().newElement() == type.type().newElement() && isShownWhole(c) && filter.test(container, c));
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
        boolean api = change.level() == ChangeLevel.API;
        boolean both = !change.pair().isAdded() && !change.pair().isRemoved();
        SignatureDiffPrinter signatures = api || !both ? printer : cosmetic ? cosmeticPrinter : plainPrinter;
        ElementPair pair = api || both ? change.pair()
                : new ElementPair(change.pair().newElement(), change.pair().newElement(), Set.of());
        List<Line> body = change.body() == null ? List.of() : body(change.body());
        // The initializer of a field continues the line of its declaration.
        boolean inline = body.size() == 1 && body.getFirst().kind() == Line.Kind.NUMBERED
                && !(change.body().newRoot() instanceof BlockTree);
        // A shown block is enclosed in its braces, as in the source.
        boolean block = !body.isEmpty() && change.body().newRoot() instanceof BlockTree;
        List<Line> below = new ArrayList<>();
        if (!inline) {
            body.forEach(l -> below.add(l.withPrefix("      ")));
        }
        if (block) {
            below.add(Line.number(closingLine(change.body()), ' ', Fragment.of("}")));
        }
        List<Node> children = change.level() == ChangeLevel.API && change.pair().isAdded()
                && change.pair().newElement().getNodeType() == ElementInfo.NodeType.TYPE
                ? addedMembers(change.pair().newElement(), nestedOf(changes, change.pair().newElement())) : List.of();
        return declarationNode(pair, signatures, changes.typeName(), inline ? body.getFirst() : null,
                Fragment.of(block ? " {" : "").plus(tagged ? tag(change) : Fragment.EMPTY)
                        .plus(Markup.incompatible(change.incompatibilities())), below, children);
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
     * javadoc of an added one above it. A type is shown as a tree of its members.
     */
    private Node wholeNode(MemberChange change) {
        boolean added = change.pair().isAdded();
        ElementInfo element = added ? change.pair().newElement() : change.pair().oldElement();
        if (element.getSourceText() == null) {
            return declarationNode(change.pair(), "", null, Markup.incompatible(change.incompatibilities()),
                    List.of(), List.of());
        }
        Whole whole = new Whole(Syntax.lines(element.getSourceTextRuns()), element.getStartLine(), added);
        Node node = whole.node(element, javadocLines(change.pair()), true);
        return new Node(node.above(), node.head().append(Markup.incompatible(change.incompatibilities())),
                node.below(), node.children(), node.after(), element);
    }

    /**
     * The whole source of an added or removed element, cut into the nodes of the elements in it.
     *
     */
    private final class Whole {

        /** The lines of the source of the element. */
        private final List<Fragment> code;
        /** The line of the source where the element starts, {@code 0} if not known. */
        private final long first;
        /** Whether the element was added, else it was removed. */
        private final boolean added;

        Whole(List<Fragment> code, long first, boolean added) {
            this.code = code;
            this.first = first;
            this.added = added;
        }

        /**
         * The node of an element of the source, or of the element itself.
         *
         * @param above the lines shown above its signature
         * @param top   whether it is the element itself
         */
        Node node(ElementInfo element, List<Line> above, boolean top) {
            int from = top ? 0 : index(element.getStartLine());
            int to = top ? code.size() - 1
                    : Math.min(from + (int) element.getSourceText().lines().count() - 1, code.size() - 1);
            int indent = indent(from);
            // The annotations go above the signature.
            int declaration = first <= 0 ? from : Math.clamp(index(element.getLine()), from, to);
            List<Line> allAbove = new ArrayList<>(above);
            allAbove.addAll(lines(from, declaration - 1, indent, false));
            Line head = line(declaration, indent);
            if (!top && added && element.getMovedFrom() != null) {
                return new Node(allAbove, head, moved(element, declaration, to, indent), List.of(), element);
            }
            List<ElementInfo> members = members(element, declaration, to);
            if (members.isEmpty()) {
                return new Node(allAbove, head, lines(declaration + 1, to, indent, false), List.of(), element);
            }
            // The rest of the header of the type, up to the opening brace.
            int open = declaration;
            while (open < to && !code.get(open).text().contains("{")) {
                open++;
            }
            List<Line> below = lines(declaration + 1, open, indent, false);
            List<Node> children = new ArrayList<>();
            int next = open + 1;
            for (ElementInfo member : members) {
                int start = javadocStart(member, next);
                // What is between the members, like comments and initializers, goes above the next one.
                List<Line> gap = lines(next, start - 1, indent(index(member.getStartLine())), true);
                gap.addAll(member.getMovedFrom() != null && added ? List.of() : javadoc(member));
                children.add(node(member, gap, false));
                next = index(member.getStartLine()) + (int) member.getSourceText().lines().count();
            }
            return new Node(allAbove, head, below, children, lines(next, to, indent, true), element);
        }

        /** The members with a place of their own in the lines of the type, after its first line. */
        private List<ElementInfo> members(ElementInfo type, int from, int to) {
            if (type.getNodeType() != ElementInfo.NodeType.TYPE || first <= 0) {
                return List.of();
            }
            List<ElementInfo> members = new ArrayList<>(type.getChildren().stream()
                    .filter(m -> m.getSourceText() != null && m.getStartLine() > 0)
                    .sorted(Comparator.comparingLong(ElementInfo::getStartLine))
                    .toList());
            List<ElementInfo> result = new ArrayList<>();
            int end = from;
            for (ElementInfo member : members) {
                int start = index(member.getStartLine());
                // Enum constants or fields declared together share their lines.
                if (start > end) {
                    end = start + (int) member.getSourceText().lines().count() - 1;
                    if (end <= to) {
                        result.add(member);
                    }
                }
            }
            return result;
        }

        /** The first line of the member with its javadoc, which starts after {@code next}. */
        private int javadocStart(ElementInfo member, int next) {
            int start = index(member.getStartLine());
            if (member.getJavadocLine() > 0) {
                // The javadoc line is that of its first text, the /** can be above it.
                int line = Math.max(index(member.getJavadocLine()), next);
                while (line > next && !code.get(line).text().contains("/**")) {
                    line--;
                }
                start = Math.min(start, line);
            }
            return start;
        }

        /** The javadoc of a member shown above it. */
        private List<Line> javadoc(ElementInfo member) {
            return added ? javadoc.print(new ElementPair(null, member, Set.of())) : List.of();
        }

        /**
         * The lines of a moved type after its first one: its first line stands for it, a page can expand the rest of
         * it, as it is, not marked as added.
         */
        private List<Line> moved(ElementInfo type, int from, int to, int indent) {
            List<Line> lines = new ArrayList<>();
            boolean fold = options.foldContext() && to > from;
            if (fold) {
                lines.add(Line.elided());
            }
            lines.add(Line.number(0, ' ', Markup.style(Css.NOTE,
                    "\u2026 moved from " + type.getMovedFrom() + " (" + (to - from) + " lines)")));
            if (fold) {
                lines.addAll(lines(from + 1, to, indent, false).stream()
                        .map(l -> Line.number(l.number(), ' ', l.content())).toList());
                lines.add(Line.end());
            }
            return lines;
        }

        /** The lines from {@code from} to {@code to}, without the indentation, the blank ones left out if asked. */
        private List<Line> lines(int from, int to, int indent, boolean skipBlank) {
            List<Line> lines = new ArrayList<>();
            for (int i = Math.max(from, 0); i <= to && i < code.size(); i++) {
                if (!(skipBlank && code.get(i).text().isBlank())) {
                    lines.add(line(i, indent));
                }
            }
            return lines;
        }

        private Line line(int index, int indent) {
            return Line.number(first <= 0 ? 0 : first + index, added ? '+' : '-', code.get(index).dedent(indent));
        }

        /** The indentation of a line. */
        private int indent(int index) {
            String text = code.get(index).text();
            return text.length() - text.stripLeading().length();
        }

        private int index(long line) {
            return (int) (line - first);
        }
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
