/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

/**
 * Context passed along the AST scan to track the current ReducedAstNode being built
 * and update its message digests.
 */
public class HashContext {

    private ElementInfo currentNode;

    /**
     * Creates a new ShaContext.
     *
     * @param initialNode initial ReducedAstNode
     */
    public HashContext(ElementInfo initialNode) {
        this.currentNode = initialNode;
    }

    /**
     * Returns the current reduced AST node.
     *
     * @return current ReducedAstNode
     */
    public ElementInfo getCurrentNode() {
        return currentNode;
    }

    /**
     * Sets the current reduced AST node.
     *
     * @param currentNode target ReducedAstNode
     */
    public void setCurrentNode(ElementInfo currentNode) {
        this.currentNode = currentNode;
    }

    /**
     * Updates the current node's message digest at FULL category with an attribute string.
     *
     * @param attribute attribute text
     */
    public void updateDigest(String attribute) {
        if (currentNode != null) {
            currentNode.updateDigest(ChangeCategory.FULL, attribute);
        }
    }
}
