/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import cz.hrebejk.sedi.compilation.CompilationUnitInfo;
import org.eclipse.jgit.diff.DiffEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hashes the old and new versions of the changed files, finds the renames among them and pairs their
 * members into {@link ApiChanges}.
 */
final class ApiDiffAnalyzer {

    private ApiDiffAnalyzer() {
    }

    /**
     * The two versions of a file as element trees. {@code oldPrimary} and {@code newPrimary} are the top level
     * types renamed together with the file by git (both {@code null} otherwise); they are left out of the roots.
     */
    private record Versions(ElementInfo oldRoot, ElementInfo newRoot, ElementInfo oldPrimary, ElementInfo newPrimary) {
    }

    /**
     * Analyzes the changed files.
     *
     * @param diffs    the changed files
     * @param oldUnits the old versions, a {@code null} for every added file
     * @param newUnits the new versions, a {@code null} for every deleted file
     * @return the analysis of all the files
     */
    static DiffAnalysis analyze(List<DiffEntry> diffs, List<CompilationUnitInfo> oldUnits,
                                List<CompilationUnitInfo> newUnits) {
        List<ElementInfo> oldRoots = new ArrayList<>();
        Renames renames = new Renames();
        for (int i = 0; i < diffs.size(); i++) {
            ElementInfo oldRoot = root(oldUnits.get(i), Renames.EMPTY);
            oldRoots.add(oldRoot);
            Versions v = versions(diffs.get(i), oldRoot, root(newUnits.get(i), Renames.EMPTY));
            if (v.oldPrimary() != null) {
                renames.add(v.oldPrimary(), v.newPrimary());
                collectRenames(v.oldPrimary(), v.newPrimary(), renames);
            }
            collectRenames(v.oldRoot(), v.newRoot(), renames);
        }

        List<FileAnalysis> files = new ArrayList<>();
        for (int i = 0; i < diffs.size(); i++) {
            DiffEntry diff = diffs.get(i);
            CompilationUnitInfo oldUnit = oldUnits.get(i);
            CompilationUnitInfo newUnit = newUnits.get(i);
            ElementInfo newRoot = root(newUnit, renames);
            Versions v = versions(diff, oldRoots.get(i), newRoot);
            ApiChanges primary = v.oldPrimary() == null ? null
                    : changes(v.oldPrimary(), v.newPrimary(), v.newPrimary().getName());
            files.add(new FileAnalysis(diff, oldUnit, newUnit, oldRoots.get(i), newRoot, v.oldPrimary(),
                    v.newPrimary(), className(oldUnit, diff.getOldPath()), className(newUnit, diff.getNewPath()),
                    packageName(oldUnit), packageName(newUnit), primary, changes(v.oldRoot(), v.newRoot(), "")));
        }
        return new DiffAnalysis(files, renames);
    }

    private static void collectRenames(ElementInfo oldContainer, ElementInfo newContainer, Renames renames) {
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

    /** Pairs the members of two versions of a container, then descends into the types present in both. */
    private static ApiChanges changes(ElementInfo oldContainer, ElementInfo newContainer, String typeName) {
        List<ElementPair> pairs = MemberMatcher.match(oldContainer, newContainer);
        List<ApiChanges> nested = new ArrayList<>();
        for (ElementPair type : MemberMatcher.nestedTypes(oldContainer, newContainer, pairs)) {
            nested.add(changes(type.oldElement(), type.newElement(), type.newElement().getName()));
        }
        return new ApiChanges(typeName, pairs, nested);
    }

    private static Versions versions(DiffEntry diff, ElementInfo oldRoot, ElementInfo newRoot) {
        ElementInfo oldPrimary = renamedType(diff, oldRoot, true);
        ElementInfo newPrimary = renamedType(diff, newRoot, false);
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
    private static ElementInfo renamedType(DiffEntry diff, ElementInfo root, boolean old) {
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

    /** The top level class named like the file, else the first one, else the file name without extension. */
    private static String className(CompilationUnitInfo unit, String path) {
        String fileName = typeName(path);
        if (unit == null) {
            return fileName;
        }
        String first = null;
        for (com.sun.source.tree.Tree decl : unit.compilationUnit().getTypeDecls()) {
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

    private static String packageName(CompilationUnitInfo unit) {
        return unit == null || unit.compilationUnit().getPackageName() == null
                ? "" : unit.compilationUnit().getPackageName().toString();
    }

    private static ElementInfo root(CompilationUnitInfo unit, Renames renames) {
        return unit == null ? new ElementInfo(null, "ROOT", ElementInfo.NodeType.COMPILATION_UNIT)
                : new ElementHashCalculator(unit.trees(), renames).calculate(unit.compilationUnit());
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
