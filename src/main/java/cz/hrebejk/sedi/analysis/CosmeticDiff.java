/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the cosmetic changes of a body: renamed locals and parameters, references to renamed elements and names
 * qualified differently. Only the old and new nodes with the same {@link ChangeCategory#BODY} hash are compared (see
 * {@link BodyDiff}): they have the same structure, so they are walked side by side and compared by their
 * {@link ChangeCategory#FULL} hashes, which hash the names as they are written. A node whose own full hash differs is
 * the change.
 */
final class CosmeticDiff {

    private static final BodyChanges.Mark CHANGED = new BodyChanges.Changed();

    private final CompilationUnitInfo oldUnit;
    private final CompilationUnitInfo newUnit;
    private final SourceText newSource;
    private final Tree newRoot;
    private final Map<Tree, BodyChanges.Mark> marks = new IdentityHashMap<>();

    /**
     * @param oldUnit   the old compilation unit
     * @param newUnit   the new compilation unit
     * @param newSource the source of the new one, to find where the names are
     * @param newRoot   the new body, where the marks of the trees around the changes end
     */
    CosmeticDiff(CompilationUnitInfo oldUnit, CompilationUnitInfo newUnit, SourceText newSource, Tree newRoot) {
        this.oldUnit = oldUnit;
        this.newUnit = newUnit;
        this.newSource = newSource;
        this.newRoot = newRoot;
    }

    /**
     * Marks the cosmetic changes of the nodes.
     *
     * @param equal pairs of an old and a new node with the same body hash
     * @return the changed names marked {@link BodyChanges.Renamed}, or {@link BodyChanges.Edited} if the name is a
     *         node of its own, and the nodes around them up to the body {@link BodyChanges.Changed} (identity map);
     *         empty if nothing changed
     */
    Map<Tree, BodyChanges.Mark> marks(List<NodeInfo[]> equal) {
        for (NodeInfo[] pair : equal) {
            walk(pair[0], pair[1]);
        }
        return marks;
    }

    private void walk(NodeInfo oldNode, NodeInfo newNode) {
        if (oldNode.getFullSha().equals(newNode.getFullSha())) {
            return;
        }
        List<NodeInfo> olds = BodyDiff.present(oldNode.getChildren(), oldUnit);
        List<NodeInfo> news = BodyDiff.present(newNode.getChildren(), newUnit);
        if (!oldNode.getFullOwnSha().equals(newNode.getFullOwnSha())) {
            BodyChanges.Mark mark = mark(oldNode.getTree(), newNode.getTree(), news.isEmpty());
            marks.put(newNode.getTree(), mark);
            markParents(newNode.getPath());
            if (mark instanceof BodyChanges.Edited) {
                // Shown as a whole, the old version struck through.
                return;
            }
        }
        if (olds.size() == news.size()) {
            for (int i = 0; i < olds.size(); i++) {
                walk(olds.get(i), news.get(i));
            }
        }
    }

    /**
     * The mark of a node whose own part changed: a declaration or a selected member renamed, else the node as a whole,
     * like a name which is a node of its own or a qualified name of a type.
     */
    private BodyChanges.Mark mark(Tree oldTree, Tree newTree, boolean leaf) {
        if (!leaf && oldTree instanceof VariableTree oldVariable && newTree instanceof VariableTree newVariable) {
            int start = declaredName(newVariable);
            if (start >= 0) {
                return new BodyChanges.Renamed(start, start + newVariable.getName().length(),
                        oldVariable.getName().toString());
            }
        }
        String oldName = selectedName(oldTree);
        String newName = selectedName(newTree);
        if (!leaf && oldName != null && newName != null) {
            int end = end(newSource, newTree);
            return new BodyChanges.Renamed(end - newName.length(), end, oldName);
        }
        return new BodyChanges.Edited(oldTree);
    }

    /** The name after the dot or the {@code ::} of a member select or reference, {@code null} for other trees. */
    private static String selectedName(Tree tree) {
        return switch (tree) {
            case MemberSelectTree select -> select.getIdentifier().toString();
            case MemberReferenceTree reference -> reference.getName().toString();
            default -> null;
        };
    }

    /**
     * The position of the name of a declared variable: after its modifiers and type, before its initializer. javac
     * does not tell where the name is, so it is looked up there.
     *
     * @return the position, {@code -1} if not found
     */
    private int declaredName(VariableTree variable) {
        int from = Math.max(start(newSource, variable), Math.max(end(newSource, variable.getModifiers()),
                variable.getType() == null ? -1 : end(newSource, variable.getType())));
        int to = variable.getInitializer() == null || start(newSource, variable.getInitializer()) < 0
                ? end(newSource, variable) : start(newSource, variable.getInitializer());
        String text = newSource.text();
        String name = variable.getName().toString();
        for (int i = text.indexOf(name, from); i >= 0 && i + name.length() <= to; i = text.indexOf(name, i + 1)) {
            boolean before = i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1));
            boolean after = i + name.length() >= text.length()
                    || !Character.isJavaIdentifierPart(text.charAt(i + name.length()));
            if (before && after) {
                return i;
            }
        }
        return -1;
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

    private static int start(SourceText source, Tree tree) {
        return tree == null ? -1 : (int) source.positions().getStartPosition(source.unit(), tree);
    }

    private static int end(SourceText source, Tree tree) {
        return tree == null ? -1 : (int) source.positions().getEndPosition(source.unit(), tree);
    }
}
