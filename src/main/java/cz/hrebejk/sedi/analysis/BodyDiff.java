/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diffs two versions of a body node by node. The children of two nodes are aligned by their hashes; the children
 * left between the aligned ones are paired with the next one of the same kind (or two leaves of any kind) and
 * diffed in turn, the rest were added or removed.
 * <p>
 * A simple statement (or an expression in the header of a compound statement) is a unit: it is shown with its
 * changes inline only if they are at most {@value #MAX_INLINE_EDITS} edited leaves, else it is replaced as a whole.
 * Synthetic trees without a place in the source, such as an implicit {@code super()}, take no part.
 */
final class BodyDiff {

    private static final int MAX_INLINE_EDITS = 2;
    private static final BodyChanges.Mark CHANGED = new BodyChanges.Changed();

    private final Map<Tree, BodyChanges.Mark> marks = new IdentityHashMap<>();
    private final Map<Tree, List<Tree>> removedBefore = new IdentityHashMap<>();
    private final Map<Tree, List<Tree>> removedAtEnd = new IdentityHashMap<>();
    private final CompilationUnitInfo oldUnit;
    private final CompilationUnitInfo newUnit;

    private BodyDiff(CompilationUnitInfo oldUnit, CompilationUnitInfo newUnit) {
        this.oldUnit = oldUnit;
        this.newUnit = newUnit;
    }

    /**
     * Diffs the bodies of two versions of an element.
     *
     * @return the changes, {@code null} if either version has no body or the bodies do not differ
     */
    static BodyChanges diff(ElementInfo oldElement, ElementInfo newElement, CompilationUnitInfo oldUnit,
                            CompilationUnitInfo newUnit) {
        NodeInfo oldBody = oldElement.getBody();
        NodeInfo newBody = newElement.getBody();
        if (oldBody == null || newBody == null || oldBody.getSha().equals(newBody.getSha())) {
            return null;
        }
        BodyDiff diff = new BodyDiff(oldUnit, newUnit);
        diff.pair(oldBody, newBody, !BodyChanges.isCompound(newBody.getTree()));
        boolean blocks = oldBody.getTree() instanceof BlockTree && newBody.getTree() instanceof BlockTree;
        List<NodeInfo> oldStatements = present(oldBody.getChildren(), oldUnit);
        List<NodeInfo> newStatements = present(newBody.getChildren(), newUnit);
        return new BodyChanges(oldUnit, newUnit, newBody.getTree(), diff.marks, diff.removedBefore, diff.removedAtEnd,
                blocks ? oldStatements.size() : 0, blocks ? newStatements.size() : 0,
                blocks ? lcs(oldStatements, newStatements).size() : 0);
    }

    /** Diffs two nodes paired with each other. */
    private void pair(NodeInfo oldNode, NodeInfo newNode, boolean unit) {
        if (oldNode.getSha().equals(newNode.getSha())) {
            return;
        }
        if (oldNode.getTree().getKind() != newNode.getTree().getKind()) {
            marks.put(newNode.getTree(), isLeaf(oldNode) && isLeaf(newNode) ? new BodyChanges.Edited(oldNode.getTree())
                    : new BodyChanges.Replaced(oldNode.getTree()));
        } else if (unit) {
            BodyDiff inner = new BodyDiff(oldUnit, newUnit);
            inner.node(oldNode, newNode);
            if (inner.isInline()) {
                marks.putAll(inner.marks);
            } else {
                marks.put(newNode.getTree(), new BodyChanges.Replaced(oldNode.getTree()));
            }
        } else {
            node(oldNode, newNode);
        }
    }

    /** Whether the changes are a few edited leaves, so they can be shown inline. */
    private boolean isInline() {
        if (!removedBefore.isEmpty() || !removedAtEnd.isEmpty()) {
            return false;
        }
        int edits = 0;
        for (BodyChanges.Mark mark : marks.values()) {
            if (mark instanceof BodyChanges.Added || mark instanceof BodyChanges.Replaced) {
                return false;
            }
            if (mark instanceof BodyChanges.Edited) {
                edits++;
            }
        }
        return edits <= MAX_INLINE_EDITS;
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
        int o = 0;
        int lastPaired = -1;
        for (int n = 0; n < news.size(); n++) {
            NodeInfo newChild = news.get(n);
            int match = -1;
            for (int i = o; i < olds.size() && match < 0; i++) {
                if (isPairable(olds.get(i), newChild)) {
                    match = i;
                }
            }
            if (match < 0) {
                marks.put(newChild.getTree(), new BodyChanges.Added());
                continue;
            }
            for (int i = o; i < match; i++) {
                remove(olds.get(i), newChild.getTree(), parent);
            }
            pair(olds.get(match), newChild, isUnit(parent, newChild.getTree()));
            o = match + 1;
            lastPaired = n;
        }
        // What was not paired was replaced by the following added children, or just removed.
        Tree anchor = lastPaired + 1 < news.size() ? news.get(lastPaired + 1).getTree() : next;
        for (int i = o; i < olds.size(); i++) {
            remove(olds.get(i), anchor, parent);
        }
    }

    private void remove(NodeInfo old, Tree before, Tree parent) {
        if (before != null) {
            removedBefore.computeIfAbsent(before, t -> new ArrayList<>()).add(old.getTree());
        } else {
            removedAtEnd.computeIfAbsent(parent, t -> new ArrayList<>()).add(old.getTree());
        }
    }

    /** The nodes which have a place in the source of the unit. */
    private static List<NodeInfo> present(List<NodeInfo> nodes, CompilationUnitInfo unit) {
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

    private static boolean isLeaf(NodeInfo node) {
        return node.getChildren().isEmpty();
    }

    /** A simple statement, or a part of the header of a compound statement. */
    private static boolean isUnit(Tree parent, Tree child) {
        return child instanceof StatementTree ? !BodyChanges.isCompound(child) : BodyChanges.isCompound(parent);
    }

    /** The longest common subsequence of equal hashes, as pairs of old and new index. */
    private static List<int[]> lcs(List<NodeInfo> olds, List<NodeInfo> news) {
        int[][] length = new int[olds.size() + 1][news.size() + 1];
        for (int i = olds.size() - 1; i >= 0; i--) {
            for (int j = news.size() - 1; j >= 0; j--) {
                length[i][j] = olds.get(i).getSha().equals(news.get(j).getSha()) ? length[i + 1][j + 1] + 1
                        : Math.max(length[i + 1][j], length[i][j + 1]);
            }
        }
        List<int[]> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < olds.size() && j < news.size()) {
            if (olds.get(i).getSha().equals(news.get(j).getSha())) {
                result.add(new int[]{i++, j++});
            } else if (length[i + 1][j] >= length[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }
}
