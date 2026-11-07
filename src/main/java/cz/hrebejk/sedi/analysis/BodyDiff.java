/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Diffs two versions of a body node by node. The children of two nodes are aligned by their hashes; the children
 * left between the aligned ones are paired with the next one of the same kind (or two leaves of any kind) and
 * diffed in turn, the rest were added or removed.
 * <p>
 * A simple statement (or an expression in the header of a compound statement) is a unit: it is shown with its
 * changes inline only if they are at most {@value #MAX_INLINE_EDITS} edited leaves (or small expressions replaced by
 * ones of another kind, or added or removed expressions, like an argument) and they are at most half of its text, else it is replaced as a whole. So unrelated statements
 * are shown as removed and added rather than as one statement mostly struck through. Between aligned children, as
 * many expressions as before are paired by their position.
 * Synthetic trees without a place in the source, such as an implicit {@code super()}, take no part.
 */
final class BodyDiff {

    private static final int MAX_INLINE_EDITS = 2;
    /** The most nodes an expression replaced by one of another kind may have to be struck through inline. */
    private static final int MAX_STRUCK_NODES = 5;
    private static final BodyChanges.Mark CHANGED = new BodyChanges.Changed();

    private final Map<Tree, BodyChanges.Mark> marks = new IdentityHashMap<>();
    private final Map<Tree, List<Tree>> removedBefore = new IdentityHashMap<>();
    private final Map<Tree, List<Tree>> removedAtEnd = new IdentityHashMap<>();
    /** The old and new nodes paired with each other which have the same hash: they differ at most cosmetically. */
    private final List<NodeInfo[]> equal = new ArrayList<>();
    /** The old trees paired with new ones, to the new ones (identity map). */
    private final Map<Tree, Tree> paired = new IdentityHashMap<>();
    private final CompilationUnitInfo oldUnit;
    private final CompilationUnitInfo newUnit;
    /** The source texts of the units, read when first needed and shared with the inner diffs (identity map). */
    private final Map<CompilationUnitInfo, String> texts;

    private BodyDiff(CompilationUnitInfo oldUnit, CompilationUnitInfo newUnit, Map<CompilationUnitInfo, String> texts) {
        this.oldUnit = oldUnit;
        this.newUnit = newUnit;
        this.texts = texts;
    }

    /**
     * The differences of the bodies of two versions of an element.
     *
     * @param implementation the changes of the code and of the comments, {@code null} if there are none
     * @param cosmetic       the cosmetic changes of the code which did not change otherwise, see {@link CosmeticDiff},
     *                       {@code null} if there are none
     */
    record Result(BodyChanges implementation, BodyChanges cosmetic) {

        static final Result NONE = new Result(null, null);
    }

    /**
     * Diffs the bodies of two versions of an element. Whitespace is ignored.
     *
     * @return the changes, {@link Result#NONE} if either version has no body
     */
    static Result diff(ElementInfo oldElement, ElementInfo newElement, CompilationUnitInfo oldUnit,
                       CompilationUnitInfo newUnit) {
        NodeInfo oldBody = oldElement.getBody();
        NodeInfo newBody = newElement.getBody();
        if (oldBody == null || newBody == null) {
            return Result.NONE;
        }
        BodyDiff diff = new BodyDiff(oldUnit, newUnit, new IdentityHashMap<>());
        diff.pair(oldBody, newBody, !BodyChanges.isCompound(newBody.getTree()));
        Set<Tree> unresolved = unresolved(oldElement, newElement);
        List<BodyChanges.CommentChange> comments = diff.comments(oldBody.getTree(), newBody.getTree());
        BodyChanges implementation = diff.marks.isEmpty() && comments.isEmpty() ? null
                : new BodyChanges(oldUnit, newUnit, oldBody.getTree(), newBody.getTree(), diff.marks,
                diff.removedBefore, diff.removedAtEnd, unresolved, comments);
        Map<Tree, BodyChanges.Mark> cosmetic = new CosmeticDiff(oldUnit, newUnit, diff.sourceText(newUnit),
                newBody.getTree()).marks(diff.equal);
        return new Result(implementation, cosmetic.isEmpty() ? null
                : new BodyChanges(oldUnit, newUnit, oldBody.getTree(), newBody.getTree(), cosmetic,
                Map.of(), Map.of(), unresolved, List.of()));
    }

    /**
     * The comments added and removed, compared by their text with the whitespace collapsed. A removed comment is
     * shown where the code next to it in the old version is in the new one: after the code in front of it on its
     * line, else in front of the code following it.
     */
    private List<BodyChanges.CommentChange> comments(Tree oldRoot, Tree newRoot) {
        SourceText oldText = sourceText(oldUnit);
        SourceText newText = sourceText(newUnit);
        List<SourceText.Comment> olds = oldText.comments(start(oldText, oldRoot), end(oldText, oldRoot));
        List<SourceText.Comment> news = newText.comments(start(newText, newRoot), end(newText, newRoot));
        if (olds.stream().map(SourceText.Comment::text).toList().equals(news.stream().map(SourceText.Comment::text).toList())) {
            return List.of();
        }
        Map<Tree, Tree> mapping = mapping();
        List<BodyChanges.CommentChange> changes = new ArrayList<>();
        List<int[]> anchors = new ArrayList<>(SourceText.lcs(olds, news));
        anchors.add(new int[]{olds.size(), news.size()});
        int o = 0;
        int n = 0;
        int newEnd = end(newText, newRoot) - (newRoot instanceof com.sun.source.tree.BlockTree ? 1 : 0);
        for (int[] anchor : anchors) {
            for (; o < anchor[0]; o++) {
                SourceText.Comment old = olds.get(o);
                int at = anchor(oldText, newText, mapping, old, newEnd);
                changes.add(new BodyChanges.CommentChange(old.start(), old.end(), at, at));
            }
            for (; n < anchor[1]; n++) {
                SourceText.Comment added = news.get(n);
                changes.add(new BodyChanges.CommentChange(-1, -1, added.start(), added.end()));
            }
            o = anchor[0] + 1;
            n = anchor[1] + 1;
        }
        return changes;
    }

    /** The position in the new source a removed comment is shown at, see {@link #comments}. */
    private static int anchor(SourceText oldText, SourceText newText, Map<Tree, Tree> mapping, SourceText.Comment comment,
                              int fallback) {
        String text = oldText.text();
        int lineStart = text.lastIndexOf('\n', comment.start() - 1) + 1;
        boolean trailing = !text.substring(lineStart, comment.start()).isBlank();
        Tree best = null;
        long bestPosition = 0;
        for (Map.Entry<Tree, Tree> e : mapping.entrySet()) {
            long position = trailing ? end(oldText, e.getKey()) : start(oldText, e.getKey());
            boolean candidate = trailing ? position <= comment.start() && position > lineStart
                    : position >= comment.end();
            if (candidate && (best == null || (trailing ? position > bestPosition : position < bestPosition)
                    || position == bestPosition && size(oldText, e.getKey()) > size(oldText, best))) {
                best = e.getKey();
                bestPosition = position;
            }
        }
        if (best == null) {
            return fallback;
        }
        Tree counterpart = mapping.get(best);
        int at = trailing ? end(newText, counterpart) : start(newText, counterpart);
        return at < 0 ? fallback : at;
    }

    private static int start(SourceText source, Tree tree) {
        return (int) source.positions().getStartPosition(source.unit(), tree);
    }

    private static int end(SourceText source, Tree tree) {
        return (int) source.positions().getEndPosition(source.unit(), tree);
    }

    private static int size(SourceText source, Tree tree) {
        return end(source, tree) - start(source, tree);
    }

    /** The old trees paired with new ones, those of the nodes with the same hash with all the trees in them. */
    private Map<Tree, Tree> mapping() {
        Map<Tree, Tree> mapping = new IdentityHashMap<>(paired);
        for (NodeInfo[] pair : equal) {
            mapEqual(pair[0], pair[1], mapping);
        }
        return mapping;
    }

    private void mapEqual(NodeInfo oldNode, NodeInfo newNode, Map<Tree, Tree> mapping) {
        mapping.put(oldNode.getTree(), newNode.getTree());
        List<NodeInfo> olds = present(oldNode.getChildren(), oldUnit);
        List<NodeInfo> news = present(newNode.getChildren(), newUnit);
        if (olds.size() == news.size()) {
            for (int i = 0; i < olds.size(); i++) {
                mapEqual(olds.get(i), news.get(i), mapping);
            }
        }
    }

    /** The names javac could not resolve in either version (identity set). */
    private static Set<Tree> unresolved(ElementInfo oldElement, ElementInfo newElement) {
        Set<Tree> unresolved = Collections.newSetFromMap(new IdentityHashMap<>());
        unresolved.addAll(oldElement.getUnresolved());
        unresolved.addAll(newElement.getUnresolved());
        return unresolved;
    }

    /** Diffs two nodes paired with each other. */
    private void pair(NodeInfo oldNode, NodeInfo newNode, boolean unit) {
        paired.put(oldNode.getTree(), newNode.getTree());
        if (oldNode.getSha().equals(newNode.getSha())) {
            equal.add(new NodeInfo[]{oldNode, newNode});
            return;
        }
        if (isExpression(oldNode.getTree()) && isExpression(newNode.getTree())) {
            // Code added around the old expression, or removed around the new one: only that code is the change.
            NodeInfo kept = find(newNode, oldNode.getSha(), newUnit);
            if (kept != null && isKept(kept, newNode)) {
                marks.put(newNode.getTree(), new BodyChanges.Wrapped(kept.getTree()));
                equal.add(new NodeInfo[]{oldNode, kept});
                return;
            }
            NodeInfo oldKept = find(oldNode, newNode.getSha(), oldUnit);
            if (oldKept != null && isKept(oldKept, oldNode)) {
                marks.put(newNode.getTree(), new BodyChanges.Unwrapped(oldNode.getTree(), oldKept.getTree()));
                equal.add(new NodeInfo[]{oldKept, newNode});
                return;
            }
        }
        if (oldNode.getTree().getKind() != newNode.getTree().getKind()) {
            // A small expression is struck through as a whole, e.g. a literal replaced by a field access.
            boolean small = isExpression(oldNode.getTree()) && isExpression(newNode.getTree())
                    && size(oldNode) <= MAX_STRUCK_NODES && size(newNode) <= MAX_STRUCK_NODES;
            marks.put(newNode.getTree(), small || isLeaf(oldNode) && isLeaf(newNode)
                    ? new BodyChanges.Edited(oldNode.getTree()) : new BodyChanges.Replaced(oldNode.getTree()));
        } else if (unit) {
            BodyDiff inner = new BodyDiff(oldUnit, newUnit, texts);
            inner.node(oldNode, newNode);
            if (inner.isInline() && !inner.isMostlyChanged(oldNode.getTree(), newNode.getTree())) {
                marks.putAll(inner.marks);
                inner.removedBefore.forEach((tree, olds) -> removedBefore.computeIfAbsent(tree, t -> new ArrayList<>()).addAll(olds));
                inner.removedAtEnd.forEach((tree, olds) -> removedAtEnd.computeIfAbsent(tree, t -> new ArrayList<>()).addAll(olds));
                equal.addAll(inner.equal);
                paired.putAll(inner.paired);
            } else {
                marks.put(newNode.getTree(), new BodyChanges.Replaced(oldNode.getTree()));
            }
        } else {
            node(oldNode, newNode);
        }
    }

    /** Whether the changes are a few edited leaves, so they can be shown inline. */
    private boolean isInline() {
        int edits = 0;
        for (List<Tree> olds : removed()) {
            for (Tree old : olds) {
                if (!isExpression(old)) {
                    return false;
                }
                edits++;
            }
        }
        for (Map.Entry<Tree, BodyChanges.Mark> entry : marks.entrySet()) {
            BodyChanges.Mark mark = entry.getValue();
            if (mark instanceof BodyChanges.Replaced
                    || mark instanceof BodyChanges.Added && !isExpression(entry.getKey())) {
                return false;
            }
            if (mark instanceof BodyChanges.Edited || mark instanceof BodyChanges.Wrapped
                    || mark instanceof BodyChanges.Unwrapped || mark instanceof BodyChanges.Added) {
                edits++;
            }
        }
        return edits <= MAX_INLINE_EDITS;
    }

    /** The old nodes without a counterpart, in groups. */
    private List<List<Tree>> removed() {
        List<List<Tree>> removed = new ArrayList<>(removedBefore.values());
        removed.addAll(removedAtEnd.values());
        return removed;
    }

    /** Diffs two different nodes of the same kind. */
    private void node(NodeInfo oldNode, NodeInfo newNode) {
        List<NodeInfo> oldChildren = present(oldNode.getChildren(), oldUnit);
        List<NodeInfo> newChildren = present(newNode.getChildren(), newUnit);
        boolean ownChange = !oldNode.getOwnSha().equals(newNode.getOwnSha());
        if (oldChildren.isEmpty() && newChildren.isEmpty()
                || ownChange && !BodyChanges.isCompound(newNode.getTree())) {
            // A leaf, or a node which itself differs, like the name in System.out and System.err.
            marks.put(newNode.getTree(), new BodyChanges.Edited(oldNode.getTree()));
            return;
        }
        marks.put(newNode.getTree(), CHANGED);
        List<int[]> anchors = new ArrayList<>(lcs(oldChildren, newChildren));
        anchors.add(new int[]{oldChildren.size(), newChildren.size()});
        for (int[] anchor : anchors) {
            if (anchor[0] < oldChildren.size()) {
                paired.put(oldChildren.get(anchor[0]).getTree(), newChildren.get(anchor[1]).getTree());
                equal.add(new NodeInfo[]{oldChildren.get(anchor[0]), newChildren.get(anchor[1])});
            }
        }
        int o = 0;
        int n = 0;
        for (int[] anchor : anchors) {
            Tree next = anchor[1] < newChildren.size() ? newChildren.get(anchor[1]).getTree() : null;
            segment(newNode.getTree(), oldChildren.subList(o, anchor[0]), newChildren.subList(n, anchor[1]), next);
            o = anchor[0] + 1;
            n = anchor[1] + 1;
        }
    }

    /**
     * Diffs the children between two aligned ones.
     *
     * @param parent the new version of the node holding the children
     * @param next   the new child following the segment, {@code null} at the end of the parent
     */
    private void segment(Tree parent, List<NodeInfo> olds, List<NodeInfo> news, Tree next) {
        if (olds.size() == news.size() && olds.stream().allMatch(c -> isExpression(c.getTree()))
                && news.stream().allMatch(c -> isExpression(c.getTree()))) {
            // As many expressions as before, like the arguments of a call: each replaces the one at its place.
            for (int i = 0; i < news.size(); i++) {
                pair(olds.get(i), news.get(i), isUnit(parent, news.get(i).getTree()));
            }
            return;
        }
        int o = 0;
        int lastPaired = -1;
        List<NodeInfo> added = new ArrayList<>();
        List<Removal> removals = new ArrayList<>();
        for (int n = 0; n < news.size(); n++) {
            NodeInfo newChild = news.get(n);
            int match = -1;
            for (int i = o; i < olds.size() && match < 0; i++) {
                if (isPairable(olds.get(i), newChild) && paired(olds.get(i), newChild, parent)) {
                    match = i;
                }
            }
            if (match < 0) {
                marks.put(newChild.getTree(), new BodyChanges.Added());
                added.add(newChild);
                continue;
            }
            // Removed in front of the children added since the last pair, as in a diff.
            for (int i = o; i < match; i++) {
                removals.add(new Removal(olds.get(i), news.get(lastPaired + 1).getTree()));
            }
            o = match + 1;
            lastPaired = n;
        }
        // What was not paired was replaced by the following added children, or just removed.
        Tree anchor = lastPaired + 1 < news.size() ? news.get(lastPaired + 1).getTree() : next;
        for (int i = o; i < olds.size(); i++) {
            removals.add(new Removal(olds.get(i), anchor));
        }
        wrapStatements(added, removals);
        for (Removal removal : removals) {
            remove(removal.old(), removal.before(), parent);
        }
    }

    /**
     * Diffs two children, unless the new one would just replace the old one as a whole: such children are not paired,
     * so the old one is shown as removed and the new one as added, in the order of a diff.
     *
     * @return whether the children were paired
     */
    private boolean paired(NodeInfo oldChild, NodeInfo newChild, Tree parent) {
        pair(oldChild, newChild, isUnit(parent, newChild.getTree()));
        if (marks.get(newChild.getTree()) instanceof BodyChanges.Replaced) {
            marks.remove(newChild.getTree());
            paired.remove(oldChild.getTree());
            return false;
        }
        return true;
    }

    /** An old child without a counterpart, shown in front of a new child, or at the end if {@code before} is null. */
    private record Removal(NodeInfo old, Tree before) {
    }

    /**
     * Finds the added statements which are made of removed ones and code around them, like an {@code if} added around
     * statements, and the removed statements which were made of added ones, like a removed {@code if}. The removed
     * statements kept this way are taken out of the removals.
     */
    private void wrapStatements(List<NodeInfo> added, List<Removal> removals) {
        Set<Tree> used = Collections.newSetFromMap(new IdentityHashMap<>());
        for (NodeInfo wrapper : added) {
            if (!(wrapper.getTree() instanceof StatementTree)) {
                continue;
            }
            Set<Tree> kept = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Iterator<Removal> it = removals.iterator(); it.hasNext(); ) {
                NodeInfo old = it.next().old();
                NodeInfo inside = old.getTree() instanceof StatementTree
                        ? findStatement(wrapper, old.getSha(), newUnit, used) : null;
                if (inside != null) {
                    kept.add(inside.getTree());
                    used.add(inside.getTree());
                    equal.add(new NodeInfo[]{old, inside});
                    it.remove();
                }
            }
            if (!kept.isEmpty()) {
                marks.put(wrapper.getTree(), new BodyChanges.WrappedStatements(kept));
            }
        }
        for (Iterator<Removal> it = removals.iterator(); it.hasNext(); ) {
            NodeInfo old = it.next().old();
            if (!(old.getTree() instanceof StatementTree)) {
                continue;
            }
            Map<Tree, Tree> kept = new IdentityHashMap<>();
            List<NodeInfo> shown = new ArrayList<>();
            for (NodeInfo statement : added) {
                if (statement.getTree() instanceof StatementTree
                        && marks.get(statement.getTree()) instanceof BodyChanges.Added) {
                    NodeInfo inside = findStatement(old, statement.getSha(), oldUnit, used);
                    if (inside != null) {
                        kept.put(inside.getTree(), statement.getTree());
                        used.add(inside.getTree());
                        equal.add(new NodeInfo[]{inside, statement});
                        shown.add(statement);
                    }
                }
            }
            if (!kept.isEmpty()) {
                marks.put(shown.getFirst().getTree(), new BodyChanges.UnwrappedStatements(old.getTree(), kept));
                for (NodeInfo other : shown.subList(1, shown.size())) {
                    marks.put(other.getTree(), new BodyChanges.Kept());
                }
                it.remove();
            }
        }
    }

    /**
     * The outermost statement inside the node (not the node itself) with the hash and not used yet, the first in the
     * source; only statements with a place in the source count.
     *
     * @return the node, {@code null} if there is none
     */
    private static NodeInfo findStatement(NodeInfo node, String sha, CompilationUnitInfo unit, Set<Tree> used) {
        List<NodeInfo> level = present(node.getChildren(), unit);
        while (!level.isEmpty()) {
            List<NodeInfo> next = new ArrayList<>();
            for (NodeInfo child : level) {
                if (child.getTree() instanceof StatementTree && !used.contains(child.getTree())
                        && child.getSha().equals(sha)) {
                    return child;
                }
                next.addAll(present(child.getChildren(), unit));
            }
            level = next;
        }
        return null;
    }

    private void remove(NodeInfo old, Tree before, Tree parent) {
        if (before != null) {
            removedBefore.computeIfAbsent(before, t -> new ArrayList<>()).add(old.getTree());
        } else {
            removedAtEnd.computeIfAbsent(parent, t -> new ArrayList<>()).add(old.getTree());
        }
    }

    /**
     * Whether an expression found inside another one is kept in it rather than equal by chance: a single name or
     * literal, like the {@code 1} of {@code return 1;} changed to {@code return list.size() > 1 ? 2 : 0;}, is kept
     * only in a small expression, like {@code !p} or {@code i + 1}.
     */
    private static boolean isKept(NodeInfo kept, NodeInfo container) {
        return !isLeaf(kept) || size(container) <= MAX_STRUCK_NODES;
    }

    /**
     * The outermost expression inside the node (not the node itself) with the hash, the first in the source; only
     * expressions with a place in the source count, and blocks, as of lambdas, are not searched.
     *
     * @return the node, {@code null} if there is none
     */
    private static NodeInfo find(NodeInfo node, String sha, CompilationUnitInfo unit) {
        List<NodeInfo> level = present(node.getChildren(), unit);
        while (!level.isEmpty()) {
            List<NodeInfo> next = new ArrayList<>();
            for (NodeInfo child : level) {
                if (!isExpression(child.getTree())) {
                    continue;
                }
                if (child.getSha().equals(sha)) {
                    return child;
                }
                next.addAll(present(child.getChildren(), unit));
            }
            level = next;
        }
        return null;
    }

    /** The nodes which have a place in the source of the unit. */
    static List<NodeInfo> present(List<NodeInfo> nodes, CompilationUnitInfo unit) {
        SourcePositions positions = unit.trees().getSourcePositions();
        return nodes.stream().filter(node -> {
            long start = positions.getStartPosition(unit.compilationUnit(), node.getTree());
            return start >= 0 && positions.getEndPosition(unit.compilationUnit(), node.getTree()) > start;
        }).toList();
    }

    /** Nodes of the same kind, or two leaves, such as an identifier replaced by a literal. */
    private static boolean isPairable(NodeInfo oldNode, NodeInfo newNode) {
        return oldNode.getTree().getKind() == newNode.getTree().getKind() || isLeaf(oldNode) && isLeaf(newNode);
    }

    /**
     * Whether the changes inline would mark more than half of the old and the new version together, counting the
     * characters other than whitespace: what is struck through in the old version and what is underlined in the new.
     */
    private boolean isMostlyChanged(Tree oldTree, Tree newTree) {
        int changed = 0;
        for (Map.Entry<Tree, BodyChanges.Mark> entry : marks.entrySet()) {
            changed += switch (entry.getValue()) {
                case BodyChanges.Edited edited -> chars(oldUnit, edited.oldTree()) + chars(newUnit, entry.getKey());
                case BodyChanges.Wrapped wrapped -> chars(newUnit, entry.getKey()) - chars(newUnit, wrapped.kept());
                case BodyChanges.Unwrapped unwrapped ->
                        chars(oldUnit, unwrapped.oldTree()) - chars(oldUnit, unwrapped.oldKept());
                case BodyChanges.Added added -> chars(newUnit, entry.getKey());
                default -> 0;
            };
        }
        for (List<Tree> olds : removed()) {
            for (Tree old : olds) {
                changed += chars(oldUnit, old);
            }
        }
        return 2 * changed > chars(oldUnit, oldTree) + chars(newUnit, newTree);
    }

    /** The number of characters of the tree other than whitespace, {@code 0} if it has no place in the source. */
    private int chars(CompilationUnitInfo unit, Tree tree) {
        SourcePositions positions = unit.trees().getSourcePositions();
        long start = positions.getStartPosition(unit.compilationUnit(), tree);
        long end = positions.getEndPosition(unit.compilationUnit(), tree);
        String text = texts.computeIfAbsent(unit, BodyDiff::text);
        if (start < 0 || end <= start || end > text.length()) {
            return 0;
        }
        int count = 0;
        for (int i = (int) start; i < end; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static String text(CompilationUnitInfo unit) {
        try {
            return unit.compilationUnit().getSourceFile().getCharContent(true).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The number of nodes of the subtree. */
    private static int size(NodeInfo node) {
        int size = 1;
        for (NodeInfo child : node.getChildren()) {
            size += size(child);
        }
        return size;
    }

    private static boolean isExpression(Tree tree) {
        return tree instanceof ExpressionTree;
    }

    private static boolean isLeaf(NodeInfo node) {
        return node.getChildren().isEmpty();
    }

    /** A simple statement, or a part of the header of a compound statement. */
    private static boolean isUnit(Tree parent, Tree child) {
        return child instanceof StatementTree ? !BodyChanges.isCompound(child) : BodyChanges.isCompound(parent);
    }

    /**
     * The longest common subsequence of equal hashes, as pairs of old and new index. Of the longest ones, the one
     * pairing the most children equal as written (by their full hashes): hashes ignore the names of locals, so {@code a.size()} and
     * {@code b.size()} are equal, but where both are in the code each is paired with itself.
     */
    private List<int[]> lcs(List<NodeInfo> olds, List<NodeInfo> news) {
        // A pair counts more than any number of pairs equal as written.
        int pairWeight = Math.min(olds.size(), news.size()) + 1;
        int[][] weight = new int[olds.size()][news.size()];
        for (int i = 0; i < olds.size(); i++) {
            for (int j = 0; j < news.size(); j++) {
                if (olds.get(i).getSha().equals(news.get(j).getSha())) {
                    weight[i][j] = pairWeight + (olds.get(i).getFullSha().equals(news.get(j).getFullSha()) ? 1 : 0);
                }
            }
        }
        int[][] best = new int[olds.size() + 1][news.size() + 1];
        for (int i = olds.size() - 1; i >= 0; i--) {
            for (int j = news.size() - 1; j >= 0; j--) {
                best[i][j] = Math.max(best[i + 1][j], best[i][j + 1]);
                if (weight[i][j] > 0) {
                    best[i][j] = Math.max(best[i][j], best[i + 1][j + 1] + weight[i][j]);
                }
            }
        }
        List<int[]> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < olds.size() && j < news.size()) {
            if (weight[i][j] > 0 && best[i][j] == best[i + 1][j + 1] + weight[i][j]) {
                result.add(new int[]{i++, j++});
            } else if (best[i + 1][j] >= best[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }

    private SourceText sourceText(CompilationUnitInfo unit) {
        return new SourceText(unit.compilationUnit(), unit.trees().getSourcePositions(),
                texts.computeIfAbsent(unit, BodyDiff::text));
    }
}
