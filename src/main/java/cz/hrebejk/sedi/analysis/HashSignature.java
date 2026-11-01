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
 * Only the signatures of elements: modifiers, annotations, names (not those of parameters), types, type parameters,
 * parameters and thrown exceptions. Ignores method bodies, field initializers and
 * members of types (nested nodes do not cascade into their parents).
 */
final class HashSignature extends HashBase {

    @Override
    public void onAttribute(ChangeCategory category, ElementInfo node, String attribute, Region region) {
        if (region == Region.SIGNATURE) {
            node.updateDigest(category, attribute);
        }
    }

    @Override
    public void onLocalVariable(ChangeCategory category, ElementInfo node, VariableTree variable, Region region) {
        // Reached in the signature region only for method parameters. Their names are left out: a renamed
        // parameter is a cosmetic change, like a renamed local.
        if (region == Region.SIGNATURE) {
            node.updateDigest(category, "KIND:" + variable.getKind());
        }
    }

    @Override
    public void onIdentifier(ChangeCategory category, ElementInfo node, IdentifierTree id, boolean isLocal, Region region) {
        if (region == Region.SIGNATURE) {
            appendIdentifier(category, node, id);
        }
    }

    @Override
    public void onChildCompleted(ChangeCategory category, ElementInfo parent, ElementInfo child) {
        // Members are not part of the signature of the enclosing element
    }
}
