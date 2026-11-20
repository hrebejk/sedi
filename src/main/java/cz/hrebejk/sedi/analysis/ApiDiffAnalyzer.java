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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
        return analyze(diffs, oldUnits, newUnits, ApiDiffAnalyzer::root);
    }

    /**
     * Analyzes the changed files, taking the element trees of the old versions from {@code oldRoot}, which may
     * return the trees of an earlier analysis.
     *
     * @param diffs    the changed files
     * @param oldUnits the old versions, a {@code null} for every added file
     * @param newUnits the new versions, a {@code null} for every deleted file
     * @param oldRoot  the element tree of an old version, see {@link #root(CompilationUnitInfo)}; never called
     *                 with {@code null}
     * @return the analysis of all the files
     */
    static DiffAnalysis analyze(List<DiffEntry> diffs, List<CompilationUnitInfo> oldUnits,
                                List<CompilationUnitInfo> newUnits,
                                Function<CompilationUnitInfo, ElementInfo> oldRoot) {
        Function<CompilationUnitInfo, ElementInfo> roots =
                unit -> unit == null ? root(null, Renames.EMPTY) : oldRoot.apply(unit);
        Paired paired = pairRenames(diffs, oldUnits, newUnits, roots);
        return analyzePaired(paired.diffs(), paired.oldUnits(), paired.newUnits(), roots);
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
                                      List<CompilationUnitInfo> newUnits,
                                      Function<CompilationUnitInfo, ElementInfo> oldRoot) {
        record Candidate(int deleted, int added, double similarity) {
        }
        Map<Integer, Set<String>> members = new LinkedHashMap<>();
        for (int i = 0; i < diffs.size(); i++) {
            DiffEntry.ChangeType type = diffs.get(i).getChangeType();
            if (type == DiffEntry.ChangeType.ADD || type == DiffEntry.ChangeType.DELETE) {
                boolean old = type == DiffEntry.ChangeType.DELETE;
                ElementInfo root = old ? oldRoot.apply(oldUnits.get(i)) : root(newUnits.get(i), Renames.EMPTY);
                ElementInfo primary = types(root).get(typeName(old ? diffs.get(i).getOldPath() : diffs.get(i).getNewPath()));
                if (primary != null) {
                    members.put(i, memberKeys(primary));
                }
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int d : members.keySet()) {
            for (int a : members.keySet()) {
                if (diffs.get(d).getChangeType() == DiffEntry.ChangeType.DELETE
                        && diffs.get(a).getChangeType() == DiffEntry.ChangeType.ADD) {
                    double similarity = similarity(members.get(d), members.get(a));
                    if (similarity >= 0.5) {
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

    /** The members of a type compared by their kinds, names and numbers of parameters, constructors left out. */
    private static Set<String> memberKeys(ElementInfo type) {
        Set<String> keys = new LinkedHashSet<>();
        for (ElementInfo child : type.getChildren()) {
            // Constructors are left out, as all the classes have one, even when it is not declared.
            if (!(child.getOriginalTree() instanceof com.sun.source.tree.MethodTree m
                    && m.getName().contentEquals("<init>"))) {
                keys.add(child.getNodeType() + "|" + child.getName() + "|" + child.getParamCount());
            }
        }
        return keys;
    }

    /** The share of the members two types have in common, {@code 0} if they have none in common. */
    private static double similarity(Set<String> a, Set<String> b) {
        Set<String> common = new LinkedHashSet<>(a);
        common.retainAll(b);
        return common.isEmpty() ? 0 : (double) common.size() / Math.max(a.size(), b.size());
    }

    /** A type of one version of a file with the compilation unit it is in. */
    private record Located(ElementInfo type, CompilationUnitInfo unit) {
    }

    /** The types removed from and added to the containers of the files, to find the moved ones among them. */
    private record Unpaired(List<Located> removed, List<Located> added) {
    }

    /**
     * The types which moved into another type or out of one, by the ids of their new versions, and the ids of their
     * old versions. Ids are used as the new versions are hashed again with the renames.
     */
    private static final class Moves {

        static final Moves NONE = new Moves();

        private final Map<String, Located> byNewId = new HashMap<>();
        private final Set<String> oldIds = new HashSet<>();

        /** The old version of a type moved here, {@code null} if it did not move. */
        Located from(ElementInfo newType) {
            String id = newType.getElementId();
            return id == null ? null : byNewId.get(id);
        }

        /** Whether the type moved elsewhere. */
        boolean isMovedAway(ElementInfo oldType) {
            return oldType.getElementId() != null && oldIds.contains(oldType.getElementId());
        }

        /** Whether some type nested in the new type, at any depth, moved there. */
        boolean hasTargetsIn(ElementInfo newType) {
            for (ElementInfo child : newType.getChildren()) {
                if (child.getNodeType() == ElementInfo.NodeType.TYPE && (from(child) != null || hasTargetsIn(child))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Pairs the removed and the added types of the same name and kind with mostly the same members, compared by
     * their kinds, names and numbers of parameters: the type moved, like a top level type which became a nested one,
     * or the other way around. Types without members pair by their names and kinds alone. The moves are added to the
     * renames, as are the renames of their members.
     */
    private static Moves pairMoves(Unpaired unpaired, Renames renames) {
        record Candidate(Located old, Located young, double similarity) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Located o : unpaired.removed()) {
            for (Located n : unpaired.added()) {
                if (o.type().getName().equals(n.type().getName()) && declarationKind(o.type()) != null
                        && declarationKind(o.type()) == declarationKind(n.type())
                        && o.type().getElementId() != null && n.type().getElementId() != null) {
                    Set<String> oldKeys = memberKeys(o.type());
                    Set<String> newKeys = memberKeys(n.type());
                    double similarity = oldKeys.isEmpty() && newKeys.isEmpty() ? 1 : similarity(oldKeys, newKeys);
                    if (similarity >= 0.5) {
                        candidates.add(new Candidate(o, n, similarity));
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return Moves.NONE;
        }
        candidates.sort((x, y) -> Double.compare(y.similarity(), x.similarity()));
        Moves moves = new Moves();
        Set<ElementInfo> used = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Candidate c : candidates) {
            // A type moves with the types nested in it, so neither of them moves on its own.
            if (isAnyUsed(c.old().type(), used) || isAnyUsed(c.young().type(), used)) {
                continue;
            }
            markUsed(c.old().type(), used);
            markUsed(c.young().type(), used);
            moves.byNewId.put(c.young().type().getElementId(), c.old());
            moves.oldIds.add(c.old().type().getElementId());
            renames.add(c.old().type(), c.young().type());
            collectRenames(c.old().type(), c.young().type(), renames, null);
        }
        return moves;
    }

    /** Whether the type or a type nested in it is used. */
    private static boolean isAnyUsed(ElementInfo type, Set<ElementInfo> used) {
        if (used.contains(type)) {
            return true;
        }
        for (ElementInfo child : type.getChildren()) {
            if (child.getNodeType() == ElementInfo.NodeType.TYPE && isAnyUsed(child, used)) {
                return true;
            }
        }
        return false;
    }

    private static void markUsed(ElementInfo type, Set<ElementInfo> used) {
        used.add(type);
        for (ElementInfo child : type.getChildren()) {
            if (child.getNodeType() == ElementInfo.NodeType.TYPE) {
                markUsed(child, used);
            }
        }
    }

    /** The kind of the declaration of a type, like a class or an enum, {@code null} if it is not known. */
    private static com.sun.source.tree.Tree.Kind declarationKind(ElementInfo type) {
        return type.getOriginalTree() instanceof com.sun.source.tree.ClassTree c ? c.getKind() : null;
    }

    private static DiffAnalysis analyzePaired(List<DiffEntry> diffs, List<CompilationUnitInfo> oldUnits,
                                              List<CompilationUnitInfo> newUnits,
                                              Function<CompilationUnitInfo, ElementInfo> oldRoot) {
        List<ElementInfo> oldRoots = new ArrayList<>();
        Renames renames = new Renames();
        Unpaired unpaired = new Unpaired(new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < diffs.size(); i++) {
            ElementInfo old = oldRoot.apply(oldUnits.get(i));
            oldRoots.add(old);
            Versions v = versions(diffs.get(i), old, root(newUnits.get(i), Renames.EMPTY));
            Collector collector = new Collector(oldUnits.get(i), newUnits.get(i), unpaired);
            if (v.oldPrimary() != null) {
                renames.add(v.oldPrimary(), v.newPrimary());
                collectRenames(v.oldPrimary(), v.newPrimary(), renames, collector);
            }
            collectRenames(v.oldRoot(), v.newRoot(), renames, collector);
        }
        Moves moves = pairMoves(unpaired, renames);

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
                    new ElementPair(v.oldPrimary(), v.newPrimary(), Set.of()), sources, moves,
                    Compatibility.check(new ElementPair(v.oldPrimary(), v.newPrimary(),
                            MemberMatcher.changedFacets(v.oldPrimary(), v.newPrimary())),
                            v.oldRoot(), v.newRoot(), true));
            files.add(new FileAnalysis(diff, oldUnit, newUnit, oldRoots.get(i), newRoot, v.oldPrimary(),
                    v.newPrimary(), className(oldUnit, diff.getOldPath()), className(newUnit, diff.getNewPath()),
                    packageName(oldUnit), packageName(newUnit), primary,
                    changes(v.oldRoot(), v.newRoot(), "", true, null, sources, moves, Set.of()),
                    importChanges(oldUnit, newUnit)));
        }
        return new DiffAnalysis(files, renames);
    }

    /** Collects the removed and the added types of the containers of a file, see {@link Unpaired}. */
    private record Collector(CompilationUnitInfo oldUnit, CompilationUnitInfo newUnit, Unpaired unpaired) {

        /** Adds a removed or an added type and the types nested in it, which can move on their own. */
        void add(ElementInfo type, boolean removed) {
            if (removed) {
                unpaired.removed().add(new Located(type, oldUnit));
            } else {
                unpaired.added().add(new Located(type, newUnit));
            }
            for (ElementInfo child : type.getChildren()) {
                if (child.getNodeType() == ElementInfo.NodeType.TYPE) {
                    add(child, removed);
                }
            }
        }
    }

    /**
     * Records the renamed members of two versions of a container, and of the types nested in it.
     *
     * @param collector receives the removed and added types, {@code null} if they are not needed
     */
    private static void collectRenames(ElementInfo oldContainer, ElementInfo newContainer, Renames renames,
                                       Collector collector) {
        List<ElementPair> pairs = MemberMatcher.match(oldContainer, newContainer);
        for (ElementPair pair : pairs) {
            if (!pair.isAdded() && !pair.isRemoved()) {
                renames.add(pair.oldElement(), pair.newElement());
            } else if (collector != null) {
                ElementInfo type = pair.isAdded() ? pair.newElement() : pair.oldElement();
                if (type.getNodeType() == ElementInfo.NodeType.TYPE) {
                    collector.add(type, pair.isRemoved());
                }
            }
        }
        for (ElementPair type : MemberMatcher.nestedTypes(oldContainer, newContainer, pairs)) {
            renames.add(type.oldElement(), type.newElement());
            collectRenames(type.oldElement(), type.newElement(), renames, collector);
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
         * @param signatureChanged  whether the signature changed, so it is an API change already
         * @param incompatibilities how the implementation change breaks the clients, like a changed constant
         */
        void addBodyChanges(List<MemberChange> changes, ElementPair pair, boolean publicApi, boolean signatureChanged,
                            Set<Incompatibility> incompatibilities) {
            BodyDiff.Result body = BodyDiff.diff(pair.oldElement(), pair.newElement(), oldUnit, newUnit);
            if (bodyChanged(pair) || body.implementation() != null) {
                changes.add(new MemberChange(pair, ChangeLevel.IMPLEMENTATION, publicApi, body.implementation(),
                        incompatibilities));
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
     * @param exposed           whether the container is a part of the public API
     * @param incompatibilities how the change of the container itself breaks the clients, see
     *                          {@link ApiChanges#incompatibilities()}
     */
    private static ApiChanges changes(ElementInfo oldContainer, ElementInfo newContainer, String typeName,
                                      boolean exposed, ElementPair typePair, Sources sources, Moves moves,
                                      Set<Incompatibility> incompatibilities) {
        MemberMatcher.Matches matches = MemberMatcher.matchAll(oldContainer, newContainer);
        boolean inInterface = Visibility.isInterface(newContainer);
        List<MemberChange> changes = new ArrayList<>();
        List<ElementPair> changed = new ArrayList<>();
        // The moved types are diffed with the file their old version is in.
        Map<ElementPair, Sources> movedSources = new IdentityHashMap<>();
        List<ApiChanges> nested = new ArrayList<>();
        for (ElementPair matched : matches.changed()) {
            ElementPair pair = matched;
            if (isType(pair) && pair.isRemoved() && moves.isMovedAway(pair.oldElement())) {
                continue;
            }
            if (isType(pair) && pair.isAdded()) {
                Located from = moves.from(pair.newElement());
                if (from != null) {
                    pair = moved(from, pair.newElement());
                    movedSources.put(pair, new Sources(from.unit(), sources.newUnit()));
                } else if (moves.hasTargetsIn(pair.newElement())) {
                    nested.add(movedInto(pair, Visibility.isExposed(pair.newElement(), exposed, inInterface),
                            sources, moves));
                }
            }
            changed.add(pair);
            ElementInfo shown = pair.isRemoved() ? pair.oldElement() : pair.newElement();
            boolean publicApi = Visibility.isExposed(shown, exposed, inInterface);
            changes.add(new MemberChange(pair, ChangeLevel.API, publicApi, null,
                    Compatibility.check(pair, oldContainer, newContainer, exposed)));
            if (!pair.isAdded() && !pair.isRemoved() && isCode(pair)) {
                sources.addBodyChanges(changes, pair, publicApi, true, Set.of());
            }
        }
        for (ElementPair pair : matches.unchanged()) {
            boolean publicApi = Visibility.isExposed(pair.newElement(), exposed, inInterface);
            if (!pair.oldElement().getJavadoc().equals(pair.newElement().getJavadoc())) {
                // The javadoc is a part of the API, even when the signature did not change.
                changes.add(new MemberChange(pair, ChangeLevel.API, publicApi));
            }
            if (isCode(pair)) {
                sources.addBodyChanges(changes, pair, publicApi, false,
                        Compatibility.check(pair, oldContainer, newContainer, exposed));
            } else {
                // A type of the same signature can still break the clients, if its package changed.
                Set<Incompatibility> moved = Compatibility.check(pair, oldContainer, newContainer, exposed);
                if (!moved.isEmpty()) {
                    changes.add(new MemberChange(pair, ChangeLevel.API, publicApi, null, moved));
                }
            }
        }
        ElementPair self = new ElementPair(oldContainer, newContainer, Set.of());
        if (newContainer.getNodeType() == ElementInfo.NodeType.TYPE && bodyChanged(self)) {
            // Initializer blocks belong to the type itself.
            changes.add(new MemberChange(self, ChangeLevel.IMPLEMENTATION, exposed));
        }

        for (ElementPair type : MemberMatcher.nestedTypes(oldContainer, newContainer, changed)) {
            nested.add(changes(type.oldElement(), type.newElement(), type.newElement().getName(),
                    Visibility.isExposed(type.newElement(), exposed, inInterface), type,
                    movedSources.getOrDefault(type, sources), moves,
                    Compatibility.check(type, oldContainer, newContainer, exposed)));
        }
        return new ApiChanges(typeName, exposed, typePair, changes, nested, incompatibilities);
    }

    /** The pair of the old and the new version of a moved type, the new one knowing where it moved from. */
    private static ElementPair moved(Located from, ElementInfo newType) {
        newType.setMoved(from.type().getElementId(), newType.getElementId());
        return new ElementPair(from.type(), newType, MemberMatcher.changedFacets(from.type(), newType));
    }

    /**
     * The changes of an added type in which there are types moved there: the moves of the types and their changes,
     * nothing about the added members, which are shown as a part of the added type.
     *
     * @param added   the added type
     * @param exposed whether the added type is a part of the public API
     */
    private static ApiChanges movedInto(ElementPair added, boolean exposed, Sources sources, Moves moves) {
        ElementInfo type = added.newElement();
        boolean inInterface = Visibility.isInterface(type);
        List<MemberChange> changes = new ArrayList<>();
        List<ApiChanges> nested = new ArrayList<>();
        for (ElementInfo child : type.getChildren()) {
            if (child.getNodeType() != ElementInfo.NodeType.TYPE) {
                continue;
            }
            boolean childExposed = Visibility.isExposed(child, exposed, inInterface);
            Located from = moves.from(child);
            if (from != null) {
                ElementPair pair = moved(from, child);
                Set<Incompatibility> incompatibilities = Compatibility.check(pair, null, type, exposed);
                changes.add(new MemberChange(pair, ChangeLevel.API, childExposed, null, incompatibilities));
                nested.add(changes(from.type(), child, child.getName(), childExposed, pair,
                        new Sources(from.unit(), sources.newUnit()), moves, incompatibilities));
            } else if (moves.hasTargetsIn(child)) {
                nested.add(movedInto(new ElementPair(null, child, Set.of()), childExposed, sources, moves));
            }
        }
        return new ApiChanges(type.getName(), exposed, added, changes, nested, Set.of());
    }

    private static boolean isType(ElementPair pair) {
        return (pair.isAdded() ? pair.newElement() : pair.oldElement()).getNodeType() == ElementInfo.NodeType.TYPE;
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

    /** The element tree of a version of a file, hashed without renames. */
    static ElementInfo root(CompilationUnitInfo unit) {
        return root(unit, Renames.EMPTY);
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
