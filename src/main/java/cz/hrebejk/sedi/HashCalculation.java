/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.VariableTree;

/**
 * Strategy deciding what a single {@link ChangeCategory} contributes to the digest of a
 * {@link ElementInfo} for each kind of AST event seen by {@link ElementHashCalculator}.
 * Implementations update the digest of their own category on the given node.
 */
public interface HashCalculation {

    /** Part of a member the scanner is currently in. */
    enum Region {
        /** Declaration header: modifiers, annotations, types, names, parameters, throws, ... */
        SIGNATURE,
        /** Method bodies, initializers of fields and blocks. */
        BODY
    }

    /**
     * A declaration level attribute (kind, name, FQN, types, modifiers, literals, package, imports).
     *
     * @param category  the category this calculation belongs to
     * @param node      node to update
     * @param attribute attribute text
     * @param region    part of the member the attribute comes from
     */
    void onAttribute(ChangeCategory category, ElementInfo node, String attribute, Region region);

    /**
     * A local variable or parameter declaration found inside a member.
     *
     * @param category the category this calculation belongs to
     * @param node     node to update
     * @param variable the declared variable
     * @param region   part of the member the variable is declared in
     */
    void onLocalVariable(ChangeCategory category, ElementInfo node, VariableTree variable, Region region);

    /**
     * An identifier usage found inside a member.
     *
     * @param category the category this calculation belongs to
     * @param node     node to update
     * @param id       the identifier
     * @param isLocal  true if the identifier refers to a local variable or parameter
     * @param region   part of the member the identifier is used in
     */
    void onIdentifier(ChangeCategory category, ElementInfo node, IdentifierTree id, boolean isLocal, Region region);

    /**
     * A nested node (type, method or field) has been completely scanned. By default its digest
     * is folded into the digest of its parent.
     *
     * @param category the category this calculation belongs to
     * @param parent   enclosing node
     * @param child    completed nested node
     */
    default void onChildCompleted(ChangeCategory category, ElementInfo parent, ElementInfo child) {
        parent.updateDigest(category, child.getSha(category));
    }
}
