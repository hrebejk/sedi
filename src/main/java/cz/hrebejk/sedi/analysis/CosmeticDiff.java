/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the cosmetic changes of a body: renamed locals and parameters, references to renamed elements and names
 * qualified differently. Only the old and new nodes with the same hash are compared (see {@link BodyDiff}): they have
 * the same structure, so they are walked side by side and the own text of every node, not that of the nodes in it, is
 * compared token by token. Whitespace and comments are ignored.
 */
final class CosmeticDiff {

    private static final BodyChanges.Mark CHANGED = new BodyChanges.Changed();

    private final SourceText oldText;
    private final SourceText newText;
    private final Tree newRoot;
    private final Map<Tree, BodyChanges.Mark> marks = new IdentityHashMap<>();

    /**
     * @param oldText the old source
     * @param newText the new source
     * @param newRoot the new body, where the marks of the trees around the changes end
     */
    CosmeticDiff(SourceText oldText, SourceText newText, Tree newRoot) {
        this.oldText = oldText;
        this.newText = newText;
        this.newRoot = newRoot;
    }

    /**
     * Marks the cosmetic changes of the nodes.
     *
     * @param equal pairs of an old and a new node with the same hash
     * @return the nodes whose own text changed marked {@link BodyChanges.Renamed}, the nodes around them up to the
     *         body {@link BodyChanges.Changed} (identity map); empty if nothing changed
     */
    Map<Tree, BodyChanges.Mark> marks(List<NodeInfo[]> equal) {
        for (NodeInfo[] pair : equal) {
            walk(pair[0], pair[1]);
        }
        return marks;
    }

    private void walk(NodeInfo oldNode, NodeInfo newNode) {
        List<NodeInfo> olds = present(oldNode.getChildren(), oldText);
        List<NodeInfo> news = present(newNode.getChildren(), newText);
        List<BodyChanges.Edit> edits = edits(own(oldText, oldNode.getTree(), olds), own(newText, newNode.getTree(), news),
                newText.positions().getEndPosition(newText.unit(), newNode.getTree()));
        if (!edits.isEmpty()) {
            marks.put(newNode.getTree(), new BodyChanges.Renamed(edits));
            markParents(newNode.getPath());
        }
        if (olds.size() == news.size()) {
            for (int i = 0; i < olds.size(); i++) {
                walk(olds.get(i), news.get(i));
            }
        }
    }

    /** Marks the trees around a changed one as {@link BodyChanges.Changed}, up to the body. */
    private void markParents(TreePath path) {
        if (path.getLeaf() == newRoot) {
            return;
        }
        for (TreePath p = path.getParentPath(); p != null; p = p.getParentPath()) {
            marks.putIfAbsent(p.getLeaf(), CHANGED);
            if (p.getLeaf() == newRoot) {
                return;
            }
        }
    }

    /** The tokens of the tree which are not in the trees in it. */
    private static List<SourceText.Token> own(SourceText source, Tree tree, List<NodeInfo> children) {
        int start = (int) source.positions().getStartPosition(source.unit(), tree);
        int end = (int) source.positions().getEndPosition(source.unit(), tree);
        List<SourceText.Token> own = new ArrayList<>();
        for (SourceText.Token token : source.tokens(start, end)) {
            boolean inChild = false;
            for (NodeInfo child : children) {
                long from = source.positions().getStartPosition(source.unit(), child.getTree());
                long to = source.positions().getEndPosition(source.unit(), child.getTree());
                if (token.start() >= from && token.end() <= to) {
                    inChild = true;
                    break;
                }
            }
            if (!inChild) {
                own.add(token);
            }
        }
        return own;
    }

    /**
     * The runs of tokens which differ: the new ones replaced the old ones; where there are no new ones, the old ones
     * were removed in front of the next new token.
     *
     * @param end where the old tokens removed at the end are shown, the end of the new tree
     */
    private List<BodyChanges.Edit> edits(List<SourceText.Token> olds, List<SourceText.Token> news, long end) {
        List<BodyChanges.Edit> edits = new ArrayList<>();
        List<int[]> anchors = new ArrayList<>(SourceText.lcs(olds, news));
        anchors.add(new int[]{olds.size(), news.size()});
        int o = 0;
        int n = 0;
        for (int[] anchor : anchors) {
            if (o < anchor[0] || n < anchor[1]) {
                String old = o < anchor[0] ? oldText.text().substring(olds.get(o).start(), olds.get(anchor[0] - 1).end())
                        .replaceAll("\\s+", " ") : "";
                int start;
                int stop;
                if (n < anchor[1]) {
                    start = news.get(n).start();
                    stop = news.get(anchor[1] - 1).end();
                } else {
                    start = anchor[1] < news.size() ? news.get(anchor[1]).start() : (int) end;
                    stop = start;
                }
                edits.add(new BodyChanges.Edit(start, stop, old));
            }
            o = anchor[0] + 1;
            n = anchor[1] + 1;
        }
        return edits;
    }

    private static List<NodeInfo> present(List<NodeInfo> nodes, SourceText source) {
        return nodes.stream().filter(node -> {
            long start = source.positions().getStartPosition(source.unit(), node.getTree());
            return start >= 0 && source.positions().getEndPosition(source.unit(), node.getTree()) > start;
        }).toList();
    }
}
