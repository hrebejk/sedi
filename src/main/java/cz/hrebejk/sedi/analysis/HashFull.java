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

/** Everything: names of locals and parameters and their usages included. */
final class HashFull extends HashBase {

    @Override
    public void onLocalVariable(ChangeCategory category, ElementInfo node, VariableTree variable, Region region) {
        appendVariable(category, node, variable);
    }

    @Override
    public void onIdentifier(ChangeCategory category, ElementInfo node, IdentifierTree id, boolean isLocal, Region region) {
        appendIdentifier(category, node, id);
    }
}
