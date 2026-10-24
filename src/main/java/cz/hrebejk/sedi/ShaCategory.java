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
 * Categories of SHAs computed for each {@link ReducedAstNode}. Each constant carries its own
 * {@link ShaCalculation} implementation describing what goes into the digest.
 */
public enum ShaCategory implements ShaCalculation {

    /** Everything: names of locals and parameters and their usages included. */
    FULL {
        @Override
        public void onLocalVariable(ShaCategory category, ReducedAstNode node, VariableTree variable) {
            node.updateDigest(category, "KIND:" + variable.getKind());
            if (variable.getName() != null) {
                node.updateDigest(category, "NAME:" + variable.getName());
            }
        }

        @Override
        public void onIdentifier(ShaCategory category, ReducedAstNode node, IdentifierTree id, boolean isLocal) {
            appendIdentifier(category, node, id);
        }
    },

    /** Ignores names of local variables and parameters and the identifiers referring to them. */
    IGNORE_LOCAL_NAMES {
        @Override
        public void onLocalVariable(ShaCategory category, ReducedAstNode node, VariableTree variable) {
            node.updateDigest(category, "KIND:" + variable.getKind());
        }

        @Override
        public void onIdentifier(ShaCategory category, ReducedAstNode node, IdentifierTree id, boolean isLocal) {
            if (!isLocal) {
                appendIdentifier(category, node, id);
            }
        }
    };

    @Override
    public void onAttribute(ShaCategory category, ReducedAstNode node, String attribute) {
        node.updateDigest(category, attribute);
    }

    private static void appendIdentifier(ShaCategory category, ReducedAstNode node, IdentifierTree id) {
        node.updateDigest(category, "KIND:" + id.getKind());
        if (id.getName() != null) {
            node.updateDigest(category, "IDENTIFIER:" + id.getName());
        }
    }
}
