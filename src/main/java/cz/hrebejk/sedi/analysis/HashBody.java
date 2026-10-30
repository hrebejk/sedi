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
 * Only the bodies of elements: method bodies, field initializers and initializer blocks. Like
 * {@link HashIgnoreLocalNames} it ignores the names of locals and parameters, and members of types do not cascade
 * into their parents.
 */
final class HashBody extends HashBase {

    @Override
    public void onAttribute(ChangeCategory category, ElementInfo node, String attribute, Region region) {
        if (region == Region.BODY) {
            node.updateDigest(category, attribute);
        }
    }

    @Override
    public void onLocalVariable(ChangeCategory category, ElementInfo node, VariableTree variable, Region region) {
        if (region == Region.BODY) {
            node.updateDigest(category, "KIND:" + variable.getKind());
        }
    }

    @Override
    public void onIdentifier(ChangeCategory category, ElementInfo node, IdentifierTree id, boolean isLocal, Region region) {
        if (region == Region.BODY && !isLocal) {
            appendIdentifier(category, node, id);
        }
    }

    @Override
    public void onChildCompleted(ChangeCategory category, ElementInfo parent, ElementInfo child) {
        // Members are not part of the body of the enclosing element
    }
}
