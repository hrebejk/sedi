/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import cz.hrebejk.sedi.analysis.ApiChanges;
import cz.hrebejk.sedi.analysis.BodyChanges;
import cz.hrebejk.sedi.analysis.ChangeLevel;
import cz.hrebejk.sedi.analysis.DiffAnalysis;
import cz.hrebejk.sedi.analysis.ElementInfo;
import cz.hrebejk.sedi.analysis.ElementPair;
import cz.hrebejk.sedi.analysis.FileAnalysis;
import cz.hrebejk.sedi.analysis.MemberChange;
import cz.hrebejk.sedi.analysis.Visibility;
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
    private final ReportOptions options;
    private final Theme theme;

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
        this.printer = new SignatureDiffPrinter(theme);
        this.options = options;
        this.theme = theme;
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
                    ? new ReportOptions(ChangeLevel.COSMETIC, options.grouping(), options.bodyLines())
                    : options;
            if (!new Reporter(theme, withoutImports).byType(files).isEmpty()) {
                return byType(files);
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
        return result;
    }

    private List<String> byType(List<FileAnalysis> files) {
        List<String> result = new ArrayList<>();
        for (FileAnalysis file : files) {
            addFile(result, file, report(file), file.isStructural());
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
        return section(sorted(analysis), level);
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
        section.add(theme.style(Theme.Role.HEADING_MARK, "#") + " "
                + theme.style(Theme.Role.HEADING, SECTIONS.get(level)));
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
                    lines.add("");
                }
                lines.addAll(part);
            }
        }
        return lines;
    }

    /** A type added or removed, with the file it is in and the names of the types it is nested in. */
    private record TypeRef(FileAnalysis file, String outer, ElementInfo element) { }

    /**
     * The API changes: the public API of the added types, the names of the removed types, then the changes of the
     * types present in both versions. The parts are separated by an empty line.
     */
    private List<String> apiLines(List<FileAnalysis> files) {
        List<TypeRef> added = new ArrayList<>();
        List<TypeRef> removed = new ArrayList<>();
        for (FileAnalysis file : files) {
            if (file.primaryChanges() != null) {
                collectTypes(file.primaryChanges(), file, file.primaryChanges().typeName() + ".", added, removed);
            }
            collectTypes(file.rootChanges(), file, "", added, removed);
        }
        List<List<String>> parts = new ArrayList<>();
        for (FileAnalysis file : files) {
            if (file.diff().getChangeType() == DiffEntry.ChangeType.ADD) {
                List<String> list = new ArrayList<>();
                list.add(title(file));
                for (TypeRef type : added) {
                    if (type.file() == file) {
                        printer.declaration(type.element(), "").forEach(l -> list.add("  " + l));
                        addedMembers(type.element(), 2, list);
                    }
                }
                parts.add(list);
            }
        }
        for (TypeRef type : added) {
            if (type.file().diff().getChangeType() != DiffEntry.ChangeType.ADD) {
                List<String> lines = new ArrayList<>();
                addDeclaration(lines, 0, new ElementPair(null, type.element(), Set.of()), "", location(type, true));
                addedMembers(type.element(), 2, lines);
                parts.add(lines);
            }
        }
        List<String> names = new ArrayList<>();
        for (FileAnalysis file : files) {
            if (file.diff().getChangeType() == DiffEntry.ChangeType.DELETE) {
                names.add(title(file));
            }
        }
        for (TypeRef type : removed) {
            if (type.file().diff().getChangeType() != DiffEntry.ChangeType.DELETE) {
                names.add(theme.sign(false) + " " + theme.style(Theme.Role.TITLE, type.element().getName())
                        + location(type, false));
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
                    result.add("");
                }
                result.addAll(part);
            }
        }
        return result;
    }

    /** Whether the change is listed in a part of the API changes of its own: a new top level or a removed type. */
    private static boolean isListedApart(ApiChanges container, MemberChange change) {
        return isTypeAddedOrRemoved(change) && (change.pair().isRemoved() || container.typeName().isEmpty());
    }

    private static boolean isTypeAddedOrRemoved(MemberChange change) {
        ElementPair pair = change.pair();
        return (pair.isAdded() || pair.isRemoved())
                && (pair.isAdded() ? pair.newElement() : pair.oldElement()).getNodeType() == ElementInfo.NodeType.TYPE;
    }

    private static void collectTypes(ApiChanges changes, FileAnalysis file, String outer, List<TypeRef> added,
                                     List<TypeRef> removed) {
        for (MemberChange change : changes.changes()) {
            if (change.level() == ChangeLevel.API && isTypeAddedOrRemoved(change)) {
                if (change.pair().isRemoved()) {
                    removed.add(new TypeRef(file, outer, change.pair().oldElement()));
                } else if (outer.isEmpty()) {
                    added.add(new TypeRef(file, outer, change.pair().newElement()));
                }
            }
        }
        for (ApiChanges nested : changes.nested()) {
            collectTypes(nested, file, outer + nested.typeName() + ".", added, removed);
        }
    }

    /** Where the type is: its package and the types it is nested in, preceded by a space; empty if nowhere. */
    private String location(TypeRef type, boolean added) {
        String path = added ? type.file().diff().getNewPath() : type.file().diff().getOldPath();
        String pkg = added ? type.file().newPackage() : type.file().oldPackage();
        String outer = type.outer().isEmpty() ? "" : type.outer().substring(0, type.outer().length() - 1);
        String root = sourceRoot(path, pkg);
        String where = pkg.isEmpty() ? outer : outer.isEmpty() ? pkg : pkg + "." + outer;
        where = root.isEmpty() || where.isEmpty() ? where : where + " " + root;
        return where.isEmpty() ? "" : " " + theme.style(Theme.Role.PACKAGE, where);
    }

    /** Adds the members of an added type which are a part of the public API, and the types nested in it,
     * without the {@code +} as all of them are new. */
    private void addedMembers(ElementInfo type, int depth, List<String> lines) {
        boolean inInterface = Visibility.isInterface(type);
        for (ElementInfo member : type.getChildren()) {
            if (Visibility.isExposed(member, true, inInterface)) {
                printer.declaration(member, type.getName()).forEach(l -> lines.add("  ".repeat(depth) + l));
                if (member.getNodeType() == ElementInfo.NodeType.TYPE) {
                    addedMembers(member, depth + 1, lines);
                }
            }
        }
    }

    /** Adds the lines of a file below its title. */
    private void addFile(List<String> result, FileAnalysis file, List<String> lines, boolean keepEmpty) {
        if (!lines.isEmpty() || keepEmpty) {
            result.add(title(file));
            result.addAll(lines);
        }
    }

    /**
     * Adds the annotations and the signature of an element, each on a line of its own and indented by levels.
     *
     * @param suffix the text added to the line of the signature
     */
    private void addDeclaration(List<String> lines, int depth, ElementPair pair, String typeName, String suffix) {
        String indent = "  ".repeat(depth);
        printer.annotations(pair).forEach(l -> lines.add(indent + l));
        lines.add(indent + printer.format(pair, typeName) + suffix);
    }

    /**
     * Reports the changes of a file, those of the levels up to {@link ReportOptions#maxLevel()}.
     *
     * @param file the analyzed file
     * @return indented lines, empty if there is nothing to show
     */
    public List<String> report(FileAnalysis file) {
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
    private List<String> lines(FileAnalysis file, BiPredicate<ApiChanges, MemberChange> filter, boolean header,
                               boolean tagged, boolean diff) {
        List<String> lines = new ArrayList<>();
        if (file.oldPrimary() != null) {
            List<String> members = new ArrayList<>();
            render(file.primaryChanges(), 2, members, filter, tagged, diff);
            if (header || !members.isEmpty()) {
                addType(lines, 1, file.primaryChanges().type(), "", diff);
                lines.addAll(members);
            }
        }
        render(file.rootChanges(), 1, lines, filter, tagged, diff);
        return lines;
    }

    /** Adds the annotations and the signature of a changed type, the changes of them shown if {@code diff}. */
    private void addType(List<String> lines, int depth, ElementPair type, String outerName, boolean diff) {
        ElementPair shown = diff ? type : new ElementPair(type.newElement(), type.newElement(), Set.of());
        String indent = "  ".repeat(depth);
        printer.annotations(shown).forEach(l -> lines.add(indent + l));
        lines.add(indent + printer.formatType(shown, outerName));
    }

    private void importLines(FileAnalysis file, boolean tagged, List<String> lines) {
        String tag = tagged ? tag("import") : "";
        file.importChanges().removed().forEach(i -> lines.add("  " + importLine(false, i) + tag));
        file.importChanges().added().forEach(i -> lines.add("  " + importLine(true, i) + tag));
    }

    private String importLine(boolean added, String name) {
        return theme.changed(added, added ? "+ " + theme.code("import " + name) : "- " + theme.strike("import " + name));
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
     * The lines showing what changed in a body: the changed statements, at most {@link ReportOptions#bodyLines()}
     * of them, or a single line if almost all the statements changed.
     */
    private List<String> body(BodyChanges body) {
        if (options.bodyLines() <= 0) {
            return List.of();
        }
        int size = Math.max(body.oldStatements(), body.newStatements());
        int unchanged = body.unchangedStatements();
        if (unchanged * 3 < size && size - unchanged > 3) {
            return List.of(theme.style(Theme.Role.NOTE, "body rewritten (-" + (body.oldStatements() - unchanged) + " +"
                    + (body.newStatements() - unchanged) + " statements)"));
        }
        List<String> lines = new AstPrinter(body, theme).print();
        if (lines.size() <= options.bodyLines()) {
            return lines;
        }
        List<String> shown = new ArrayList<>(lines.subList(0, options.bodyLines()));
        shown.add(theme.style(Theme.Role.NOTE, "… " + (lines.size() - options.bodyLines()) + " more lines"));
        return shown;
    }

    /**
     * Renders the changes of a container accepted by the filter, then the types nested in it which have some, each
     * of them with its signature.
     *
     * @param depth the indentation of the lines, in levels
     */
    private void render(ApiChanges changes, int depth, List<String> lines, BiPredicate<ApiChanges, MemberChange> filter,
                        boolean tagged, boolean diff) {
        for (MemberChange change : changes.changes()) {
            if (filter.test(changes, change) && !isSignatureOfNested(changes, change)) {
                ElementPair pair = change.level() == ChangeLevel.API ? change.pair()
                        : new ElementPair(change.pair().newElement(), change.pair().newElement(), Set.of());
                addDeclaration(lines, depth, pair, changes.typeName(), tagged ? tag(change) : "");
                if (change.level() == ChangeLevel.API && change.pair().isAdded()
                        && change.pair().newElement().getNodeType() == ElementInfo.NodeType.TYPE) {
                    addedMembers(change.pair().newElement(), depth + 2, lines);
                }
                if (change.body() != null) {
                    body(change.body()).forEach(l -> lines.add("  ".repeat(depth + 3) + l));
                }
            }
        }
        for (ApiChanges type : changes.nested()) {
            List<String> nested = new ArrayList<>();
            render(type, depth + 1, nested, filter, tagged, diff);
            boolean own = changes.changes().stream()
                    .anyMatch(c -> filter.test(changes, c) && isSignatureOf(c, type));
            if (own || !nested.isEmpty()) {
                addType(lines, depth, type.type(), changes.typeName(), diff);
                lines.addAll(nested);
            }
        }
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
