/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.ImportTree;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;
import org.eclipse.jgit.diff.DiffEntry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hashes the old and new versions of the changed files, finds the renames among them and pairs their
 * members into {@link ApiChanges}, classifying every change by its {@link ChangeLevel}.
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
        Paired paired = pairRenames(diffs, oldUnits, newUnits);
        return analyzePaired(paired.diffs(), paired.oldUnits(), paired.newUnits());
    }

    /** The files after the added and deleted ones which are one renamed file were joined. */
    private record Paired(List<DiffEntry> diffs, List<CompilationUnitInfo> oldUnits, List<CompilationUnitInfo> newUnits) {
    }

    /** A rename found by the types in the files, as git could not find it by the text. */
    private static final class RenameEntry extends DiffEntry {
        RenameEntry(DiffEntry deleted, DiffEntry added) {
            oldId = deleted.getOldId();
            oldMode = deleted.getOldMode();
            oldPath = deleted.getOldPath();
            newId = added.getNewId();
            newMode = added.getNewMode();
            newPath = added.getNewPath();
            changeType = ChangeType.RENAME;
        }
    }

    /**
     * Joins the added and the deleted files git did not find similar, if the top level type named like the file
     * has mostly the same members, compared by their kinds, names and numbers of parameters: the type was renamed, and the file with it.
     */
    private static Paired pairRenames(List<DiffEntry> diffs, List<CompilationUnitInfo> oldUnits,
                                      List<CompilationUnitInfo> newUnits) {
        record Candidate(int deleted, int added, double similarity) {
        }
        Map<Integer, Set<String>> members = new LinkedHashMap<>();
        for (int i = 0; i < diffs.size(); i++) {
            DiffEntry.ChangeType type = diffs.get(i).getChangeType();
            if (type == DiffEntry.ChangeType.ADD || type == DiffEntry.ChangeType.DELETE) {
                boolean old = type == DiffEntry.ChangeType.DELETE;
                ElementInfo root = root(old ? oldUnits.get(i) : newUnits.get(i), Renames.EMPTY);
                ElementInfo primary = types(root).get(typeName(old ? diffs.get(i).getOldPath() : diffs.get(i).getNewPath()));
                if (primary != null) {
                    Set<String> keys = new LinkedHashSet<>();
                    for (ElementInfo child : primary.getChildren()) {
                        // Constructors are left out, as all the classes have one, even when it is not declared.
                        if (!(child.getOriginalTree() instanceof com.sun.source.tree.MethodTree m
                                && m.getName().contentEquals("<init>"))) {
                            keys.add(child.getNodeType() + "|" + child.getName() + "|" + child.getParamCount());
                        }
                    }
                    members.put(i, keys);
                }
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int d : members.keySet()) {
            for (int a : members.keySet()) {
                if (diffs.get(d).getChangeType() == DiffEntry.ChangeType.DELETE
                        && diffs.get(a).getChangeType() == DiffEntry.ChangeType.ADD) {
                    Set<String> common = new LinkedHashSet<>(members.get(d));
                    common.retainAll(members.get(a));
                    double similarity = (double) common.size() / Math.max(members.get(d).size(), members.get(a).size());
                    if (!common.isEmpty() && similarity >= 0.5) {
                        candidates.add(new Candidate(d, a, similarity));
                    }
                }
            }
        }
        candidates.sort((x, y) -> Double.compare(y.similarity(), x.similarity()));
        Map<Integer, Integer> addedToDeleted = new LinkedHashMap<>();
        Set<Integer> deleted = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            if (!deleted.contains(c.deleted()) && !addedToDeleted.containsKey(c.added())) {
                deleted.add(c.deleted());
                addedToDeleted.put(c.added(), c.deleted());
            }
        }
        List<DiffEntry> resultDiffs = new ArrayList<>();
        List<CompilationUnitInfo> resultOld = new ArrayList<>();
        List<CompilationUnitInfo> resultNew = new ArrayList<>();
        for (int i = 0; i < diffs.size(); i++) {
            if (deleted.contains(i)) {
                continue;
            }
            Integer d = addedToDeleted.get(i);
            resultDiffs.add(d == null ? diffs.get(i) : new RenameEntry(diffs.get(d), diffs.get(i)));
            resultOld.add(d == null ? oldUnits.get(i) : oldUnits.get(d));
            resultNew.add(newUnits.get(i));
        }
        return new Paired(resultDiffs, resultOld, resultNew);
    }

    private static DiffAnalysis analyzePaired(List<DiffEntry> diffs, List<CompilationUnitInfo> oldUnits,
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
            Sources sources = new Sources(oldUnit, newUnit);
            ApiChanges primary = v.oldPrimary() == null ? null
                    : changes(v.oldPrimary(), v.newPrimary(), v.newPrimary().getName(),
                    Visibility.isExposed(v.newPrimary(), true, false),
                    new ElementPair(v.oldPrimary(), v.newPrimary(), Set.of()), sources);
            files.add(new FileAnalysis(diff, oldUnit, newUnit, oldRoots.get(i), newRoot, v.oldPrimary(),
                    v.newPrimary(), className(oldUnit, diff.getOldPath()), className(newUnit, diff.getNewPath()),
                    packageName(oldUnit), packageName(newUnit), primary,
                    changes(v.oldRoot(), v.newRoot(), "", true, null, sources), importChanges(oldUnit, newUnit)));
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

    /** The two compilation units, to diff the bodies. */
    private record Sources(CompilationUnitInfo oldUnit, CompilationUnitInfo newUnit) {

        /**
         * Whether the signature differs cosmetically, though not its hash: a renamed parameter, a renamed type
         * referred to or a name qualified differently. Its parts are compared by their full hashes.
         */
        static boolean signatureDiffers(ElementPair pair) {
            List<NodeInfo> olds = pair.oldElement().getSignatureRoots();
            List<NodeInfo> news = pair.newElement().getSignatureRoots();
            if (olds.size() != news.size()) {
                return true;
            }
            for (int i = 0; i < olds.size(); i++) {
                if (!olds.get(i).getFullSha().equals(news.get(i).getFullSha())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Adds the implementation and the cosmetic change of the body of a member present in both versions, if it
         * has them; the cosmetic change of an unchanged signature too.
         *
         * @param signatureChanged whether the signature changed, so it is an API change already
         */
        void addBodyChanges(List<MemberChange> changes, ElementPair pair, boolean publicApi, boolean signatureChanged) {
            BodyDiff.Result body = BodyDiff.diff(pair.oldElement(), pair.newElement(), oldUnit, newUnit);
            if (bodyChanged(pair) || body.implementation() != null) {
                changes.add(new MemberChange(pair, ChangeLevel.IMPLEMENTATION, publicApi, body.implementation()));
            }
            if (body.cosmetic() != null || !signatureChanged && signatureDiffers(pair)) {
                changes.add(new MemberChange(pair, ChangeLevel.COSMETIC, publicApi, body.cosmetic()));
            }
        }
    }

    /**
     * Pairs the members of two versions of a container and classifies their changes, then descends into the
     * types present in both.
     *
     * @param exposed whether the container is a part of the public API
     */
    private static ApiChanges changes(ElementInfo oldContainer, ElementInfo newContainer, String typeName,
                                      boolean exposed, ElementPair typePair, Sources sources) {
        MemberMatcher.Matches matches = MemberMatcher.matchAll(oldContainer, newContainer);
        boolean inInterface = Visibility.isInterface(newContainer);
        List<MemberChange> changes = new ArrayList<>();
        for (ElementPair pair : matches.changed()) {
            ElementInfo shown = pair.isRemoved() ? pair.oldElement() : pair.newElement();
            boolean publicApi = Visibility.isExposed(shown, exposed, inInterface);
            changes.add(new MemberChange(pair, ChangeLevel.API, publicApi));
            if (!pair.isAdded() && !pair.isRemoved() && isCode(pair)) {
                sources.addBodyChanges(changes, pair, publicApi, true);
            }
        }
        for (ElementPair pair : matches.unchanged()) {
            boolean publicApi = Visibility.isExposed(pair.newElement(), exposed, inInterface);
            if (!pair.oldElement().getJavadoc().equals(pair.newElement().getJavadoc())) {
                // The javadoc is a part of the API, even when the signature did not change.
                changes.add(new MemberChange(pair, ChangeLevel.API, publicApi));
            }
            if (isCode(pair)) {
                sources.addBodyChanges(changes, pair, publicApi, false);
            }
        }
        ElementPair self = new ElementPair(oldContainer, newContainer, Set.of());
        if (newContainer.getNodeType() == ElementInfo.NodeType.TYPE && bodyChanged(self)) {
            // Initializer blocks belong to the type itself.
            changes.add(new MemberChange(self, ChangeLevel.IMPLEMENTATION, exposed));
        }

        List<ApiChanges> nested = new ArrayList<>();
        for (ElementPair type : MemberMatcher.nestedTypes(oldContainer, newContainer, matches.changed())) {
            nested.add(changes(type.oldElement(), type.newElement(), type.newElement().getName(),
                    Visibility.isExposed(type.newElement(), exposed, inInterface), type, sources));
        }
        return new ApiChanges(typeName, exposed, typePair, changes, nested);
    }

    /** Whether the element has a body of its own which can change: methods and fields, not types. */
    private static boolean isCode(ElementPair pair) {
        return pair.newElement().getNodeType() != ElementInfo.NodeType.TYPE;
    }

    private static boolean bodyChanged(ElementPair pair) {
        return !pair.oldElement().getSha(ChangeCategory.BODY).equals(pair.newElement().getSha(ChangeCategory.BODY));
    }

    private static ImportChanges importChanges(CompilationUnitInfo oldUnit, CompilationUnitInfo newUnit) {
        Set<String> oldImports = imports(oldUnit);
        Set<String> newImports = imports(newUnit);
        return new ImportChanges(newImports.stream().filter(i -> !oldImports.contains(i)).toList(),
                oldImports.stream().filter(i -> !newImports.contains(i)).toList());
    }

    private static Set<String> imports(CompilationUnitInfo unit) {
        Set<String> result = new LinkedHashSet<>();
        if (unit != null) {
            for (ImportTree i : unit.compilationUnit().getImports()) {
                result.add((i.isStatic() ? "static " : "") + i.getQualifiedIdentifier());
            }
        }
        return result;
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
