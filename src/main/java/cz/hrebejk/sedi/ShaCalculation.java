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
 * Strategy deciding what a single {@link ShaCategory} contributes to the digest of a
 * {@link ReducedAstNode} for each kind of AST event seen by {@link MemberShaCalculator}.
 * Implementations update the digest of their own category on the given node.
 */
public interface ShaCalculation {

    /**
     * A declaration level attribute (kind, name, FQN, types, modifiers, literals, package, imports).
     *
     * @param category  the category this calculation belongs to
     * @param node      node to update
     * @param attribute attribute text
     */
    void onAttribute(ShaCategory category, ReducedAstNode node, String attribute);

    /**
     * A local variable or parameter declaration found inside a member.
     *
     * @param category the category this calculation belongs to
     * @param node     node to update
     * @param variable the declared variable
     */
    void onLocalVariable(ShaCategory category, ReducedAstNode node, VariableTree variable);

    /**
     * An identifier usage found inside a member.
     *
     * @param category the category this calculation belongs to
     * @param node     node to update
     * @param id       the identifier
     * @param isLocal  true if the identifier refers to a local variable or parameter
     */
    void onIdentifier(ShaCategory category, ReducedAstNode node, IdentifierTree id, boolean isLocal);
}
