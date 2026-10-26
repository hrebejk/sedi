/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.VariableTree;

/**
 * Common base of the {@link HashCalculation}s behind {@link ChangeCategory}. By default every
 * attribute goes into the digest.
 */
abstract class HashBase implements HashCalculation {

    @Override
    public void onAttribute(ChangeCategory category, ElementInfo node, String attribute, Region region) {
        node.updateDigest(category, attribute);
    }

    static void appendVariable(ChangeCategory category, ElementInfo node, VariableTree variable) {
        node.updateDigest(category, "KIND:" + variable.getKind());
        if (variable.getName() != null) {
            node.updateDigest(category, "NAME:" + variable.getName());
        }
    }

    static void appendIdentifier(ChangeCategory category, ElementInfo node, IdentifierTree id) {
        node.updateDigest(category, "KIND:" + id.getKind());
        if (id.getName() != null) {
            node.updateDigest(category, "IDENTIFIER:" + id.getName());
        }
    }
}
