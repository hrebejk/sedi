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
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.WhileLoopTree;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The difference of two versions of the body of a method or of a field initializer, as marks on the nodes of the new
 * version. Unmarked nodes did not change, up to renames and names of locals.
 *
 * @param oldUnit             the compilation unit of the old version
 * @param newUnit             the compilation unit of the new version
 * @param oldRoot             the body of the old version: a block, or the expression initializing a field
 * @param newRoot             the body of the new version: a block, or the expression initializing a field
 * @param marks               what happened to the marked nodes of the new version (identity map)
 * @param removedBefore       old nodes without a counterpart, by the new node they are shown in front of (identity map)
 * @param removedAtEnd        old nodes without a counterpart, by the new node they are shown at the end of (identity map)
 * @param oldStatements       number of statements of the old body, zero if it is not a block
 * @param newStatements       number of statements of the new body, zero if it is not a block
 * @param unchangedStatements number of statements present unchanged in both bodies
 * @param unresolved          names javac could not resolve in either version, as in code which does not compile
 *                            (identity set)
 */
public record BodyChanges(CompilationUnitInfo oldUnit, CompilationUnitInfo newUnit, Tree oldRoot, Tree newRoot,
                          Map<Tree, Mark> marks, Map<Tree, List<Tree>> removedBefore,
                          Map<Tree, List<Tree>> removedAtEnd,
                          int oldStatements, int newStatements, int unchangedStatements, Set<Tree> unresolved) {

    /** What happened to a node of the new version. */
    public sealed interface Mark permits Changed, Edited, Added, Replaced, Wrapped, Unwrapped, WrappedStatements,
            UnwrappedStatements, Kept {
    }

    /**
     * A new statement around old statements, like an {@code if} or a {@code try} added around them; shown with its
     * own lines marked as added and the old statements as they are.
     *
     * @param kept the statements inside the new one which are old statements, up to renames and names of locals
     *             (identity set)
     */
    public record WrappedStatements(Set<Tree> kept) implements Mark {
    }

    /**
     * New statements which were inside a removed statement, like the body of a removed {@code if}; shown, at the
     * place of the first of them, as the removed statement with its own lines marked as removed and, in place of
     * its statements kept, the new statements. The other new statements are marked {@link Kept}.
     *
     * @param oldTree the removed statement
     * @param kept    the statements inside the removed one by the new statements they are (identity map)
     */
    public record UnwrappedStatements(Tree oldTree, Map<Tree, Tree> kept) implements Mark {
    }

    /**
     * A new statement shown as a part of the {@link UnwrappedStatements} of another one.
     */
    public record Kept() implements Mark {
    }

    /**
     * An expression made of its old version and new code around it, like {@code p} changed to {@code !p} or to
     * {@code f(p, 1)}; shown with the new code marked as added and the old version as it is.
     *
     * @param kept the tree inside the new version which is the old version, up to renames and names of locals
     */
    public record Wrapped(Tree kept) implements Mark {
    }

    /**
     * An expression which is a part of its old version, the code around it removed, like {@code !p} changed to
     * {@code p}; shown as the old version with the removed code struck through.
     *
     * @param oldTree the old version
     * @param oldKept the tree inside the old version which is the new version, up to renames and names of locals
     */
    public record Unwrapped(Tree oldTree, Tree oldKept) implements Mark {
    }

    /**
     * Something inside the node changed; the node is shown as the context of the change.
     */
    public record Changed() implements Mark {
    }

    /**
     * A node without children which differs from its old version, such as a literal; shown inline, struck through.
     *
     * @param oldTree the old version
     */
    public record Edited(Tree oldTree) implements Mark {
    }

    /**
     * A node without an old version.
     */
    public record Added() implements Mark {
    }

    /**
     * A node which changed too much to show the changes inline; shown as the old version removed and the new added.
     *
     * @param oldTree the old version
     */
    public record Replaced(Tree oldTree) implements Mark {
    }

    /**
     * Returns whether the statement contains other statements, like blocks, conditions, loops or {@code try}.
     *
     * @param tree the tree
     * @return {@code true} for a compound statement
     */
    public static boolean isCompound(Tree tree) {
        return tree instanceof BlockTree || tree instanceof IfTree || tree instanceof ForLoopTree
                || tree instanceof EnhancedForLoopTree || tree instanceof WhileLoopTree
                || tree instanceof DoWhileLoopTree || tree instanceof TryTree || tree instanceof CatchTree
                || tree instanceof SwitchTree || tree instanceof CaseTree || tree instanceof SynchronizedTree
                || tree instanceof LabeledStatementTree;
    }
}
