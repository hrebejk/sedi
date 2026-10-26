/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import org.eclipse.jgit.diff.DiffEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Describes the API (signature) changes of a file: for every changed type the list of added, removed
 * and changed members, one line each, rendered by a {@link SignatureDiffPrinter}.
 */
public final class ApiDiffReporter {

    private final SignatureDiffPrinter printer;

    /**
     * Creates a reporter.
     *
     * @param ansi {@code true} to use ANSI escape codes for strike through
     */
    public ApiDiffReporter(boolean ansi) {
        this.printer = new SignatureDiffPrinter(ansi);
    }

    /**
     * Reports the API changes between two versions of a compilation unit.
     *
     * @param analysis the old and new version of a changed file
     * @return indented lines, empty if the API did not change
     */
    public List<String> report(FileDiffAnalysis analysis) {
        return report(analysis, Renames.EMPTY);
    }

    /**
     * Reports the API changes between two versions of a compilation unit. The new version is scanned
     * again with the renames, so that its {@link ChangeCategory#WITHOUT_RENAMES} hashes ignore them.
     *
     * @param analysis the old and new version of a changed file
     * @param renames  the result of {@link #detectRenames}
     * @return indented lines, empty if the API did not change
     */
    public List<String> report(FileDiffAnalysis analysis, Renames renames) {
        Versions v = versions(analysis, renames);
        List<String> lines = new ArrayList<>();
        if (v.oldPrimary() != null) {
            lines.add("  " + printer.format(new ElementPair(v.oldPrimary(), v.newPrimary(), Set.of()), "").stripLeading());
            diff(v.oldPrimary(), v.newPrimary(), v.newPrimary().getName(), 1, lines);
        }
        diff(v.oldRoot(), v.newRoot(), "", 0, lines);
        return lines;
    }

    /**
     * Finds all the renamed types, fields (including enum constants) and methods, nested ones too, of the
     * changed files: top level types renamed together with their file by git and elements paired by
     * {@link MemberMatcher}.
     *
     * @param analyses the changed files
     * @return the renamed elements
     */
    public Renames detectRenames(List<FileDiffAnalysis> analyses) {
        Renames renames = new Renames();
        for (FileDiffAnalysis analysis : analyses) {
            Versions v = versions(analysis, Renames.EMPTY);
            if (v.oldPrimary() != null) {
                renames.add(v.oldPrimary(), v.newPrimary());
                collectRenames(v.oldPrimary(), v.newPrimary(), renames);
            }
            collectRenames(v.oldRoot(), v.newRoot(), renames);
        }
        return renames;
    }

    private void collectRenames(ElementInfo oldContainer, ElementInfo newContainer, Renames renames) {
        List<ElementPair> pairs = MemberMatcher.match(oldContainer, newContainer);
        for (ElementPair pair : pairs) {
            if (!pair.isAdded() && !pair.isRemoved()) {
                renames.add(pair.oldElement(), pair.newElement());
            }
        }
        for (ElementPair type : MemberMatcher.nestedTypes(oldContainer, newContainer, pairs)) {
            renames.add(type.oldElement(), type.newElement());
            collectRenames(type.oldElement(), type.newElement(), renames);
        }
    }

    /**
     * The two versions of a file as element trees. {@code oldPrimary} and {@code newPrimary} are the top level
     * types renamed together with the file by git (both {@code null} otherwise); they are left out of the roots.
     */
    private record Versions(ElementInfo oldRoot, ElementInfo newRoot, ElementInfo oldPrimary, ElementInfo newPrimary) {
    }

    private static Versions versions(FileDiffAnalysis analysis, Renames renames) {
        ElementInfo oldRoot = root(analysis.oldCompilationUnit(), analysis.oldTrees(), Renames.EMPTY);
        ElementInfo newRoot = root(analysis.newCompilationUnit(), analysis.newTrees(), renames);
        ElementInfo oldPrimary = renamedType(analysis, oldRoot, true);
        ElementInfo newPrimary = renamedType(analysis, newRoot, false);
        if (oldPrimary == null || newPrimary == null) {
            return new Versions(oldRoot, newRoot, null, null);
        }
        // The file was renamed by git, so its top level type of the same name was renamed with it.
        return new Versions(without(oldRoot, oldPrimary), without(newRoot, newPrimary), oldPrimary, newPrimary);
    }

    /**
     * Finds the top level type named like the file, if git reports the file as renamed to a differently
     * named file.
     *
     * @return the type, or {@code null} if the file was not renamed or has no such type
     */
    private static ElementInfo renamedType(FileDiffAnalysis analysis, ElementInfo root, boolean old) {
        DiffEntry diff = analysis.diff();
        if (diff.getChangeType() != DiffEntry.ChangeType.RENAME) {
            return null;
        }
        String oldName = typeName(diff.getOldPath());
        String newName = typeName(diff.getNewPath());
        if (oldName.equals(newName)) {
            return null;
        }
        return types(root).get(old ? oldName : newName);
    }

    /** Simple name of the type a Java file is expected to declare. */
    private static String typeName(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.endsWith(".java") ? file.substring(0, file.length() - ".java".length()) : file;
    }

    /** Returns a compilation unit element holding all the types of {@code root} except {@code excluded}. */
    private static ElementInfo without(ElementInfo root, ElementInfo excluded) {
        ElementInfo copy = new ElementInfo(null, "ROOT", ElementInfo.NodeType.COMPILATION_UNIT);
        for (ElementInfo child : root.getChildren()) {
            if (child != excluded) {
                copy.addChild(child);
            }
        }
        return copy;
    }

    /**
     * Returns the heading of a changed file: the simple name of its top level class, a space and its package,
     * e.g. {@code Foo com.acme}. Added files are prefixed with {@code +}, deleted ones with {@code -}, and
     * a renamed class or moved package shows the old value struck through.
     *
     * @param analysis the old and new version of a changed file
     * @return the heading line
     */
    public String title(FileDiffAnalysis analysis) {
        DiffEntry diff = analysis.diff();
        DiffEntry.ChangeType type = diff.getChangeType();
        String oldName = type == DiffEntry.ChangeType.ADD ? "" : className(analysis.oldCompilationUnit(), diff.getOldPath());
        String newName = type == DiffEntry.ChangeType.DELETE ? "" : className(analysis.newCompilationUnit(), diff.getNewPath());
        String oldPackage = packageName(analysis.oldCompilationUnit());
        String newPackage = packageName(analysis.newCompilationUnit());
        if (type == DiffEntry.ChangeType.ADD) {
            oldPackage = "";
        }
        boolean deleted = type == DiffEntry.ChangeType.DELETE;
        String name = deleted ? oldName : printer.slot(oldName, newName);
        String pkg = deleted ? oldPackage : printer.slot(oldPackage, newPackage);
        String prefix = type == DiffEntry.ChangeType.ADD ? "+ " : type == DiffEntry.ChangeType.DELETE ? "- " : "";
        return prefix + name + (pkg.isEmpty() ? "" : " " + pkg);
    }

    /**
     * Returns whether the change of the file is worth reporting even if no line describes it: files that
     * were added, deleted or renamed by git.
     */
    public static boolean isStructural(FileDiffAnalysis analysis) {
        DiffEntry.ChangeType type = analysis.diff().getChangeType();
        return type == DiffEntry.ChangeType.ADD || type == DiffEntry.ChangeType.DELETE
                || type == DiffEntry.ChangeType.RENAME;
    }

    /** The top level class named like the file, else the first one, else the file name without extension. */
    private static String className(com.sun.source.tree.CompilationUnitTree cut, String path) {
        String fileName = typeName(path);
        if (cut == null) {
            return fileName;
        }
        String first = null;
        for (com.sun.source.tree.Tree decl : cut.getTypeDecls()) {
            if (decl instanceof com.sun.source.tree.ClassTree c) {
                String name = c.getSimpleName().toString();
                if (name.equals(fileName)) {
                    return name;
                }
                if (first == null) {
                    first = name;
                }
            }
        }
        return first != null ? first : fileName;
    }

    private static String packageName(com.sun.source.tree.CompilationUnitTree cut) {
        return cut == null || cut.getPackageName() == null ? "" : cut.getPackageName().toString();
    }

    private static ElementInfo root(com.sun.source.tree.CompilationUnitTree cut, com.sun.source.util.Trees trees,
                                    Renames renames) {
        return cut == null ? new ElementInfo(null, "ROOT", ElementInfo.NodeType.COMPILATION_UNIT)
                : new ElementHashCalculator(trees, renames).calculate(cut);
    }

    /** Diffs the members of two versions of a container, then descends into the types present in both. */
    private void diff(ElementInfo oldContainer, ElementInfo newContainer, String typeName, int depth,
                      List<String> lines) {
        List<ElementPair> pairs = MemberMatcher.match(oldContainer, newContainer);
        for (ElementPair pair : pairs) {
            lines.add("  ".repeat(depth + 1) + printer.format(pair, typeName));
        }
        for (ElementPair type : MemberMatcher.nestedTypes(oldContainer, newContainer, pairs)) {
            ElementInfo newType = type.newElement();
            List<String> nested = new ArrayList<>();
            diff(type.oldElement(), newType, newType.getName(), depth + 1, nested);
            if (!nested.isEmpty()) {
                lines.add("  ".repeat(depth + 1) + newType.getName());
                lines.addAll(nested);
            }
        }
    }

    private static Map<String, ElementInfo> types(ElementInfo container) {
        Map<String, ElementInfo> types = new LinkedHashMap<>();
        for (ElementInfo child : container.getChildren()) {
            if (child.getNodeType() == ElementInfo.NodeType.TYPE) {
                types.put(child.getName(), child);
            }
        }
        return types;
    }
}
