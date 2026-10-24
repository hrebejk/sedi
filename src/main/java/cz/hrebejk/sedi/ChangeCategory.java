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
 * Categories of SHAs computed for each {@link ElementInfo}. Each constant delegates to its own
 * {@link HashCalculation} describing what goes into the digest.
 */
public enum ChangeCategory implements HashCalculation {

    /** Everything: names of locals and parameters and their usages included. */
    FULL(new HashFull()),

    /** Ignores names of local variables and parameters and the identifiers referring to them. */
    IGNORE_LOCAL_NAMES(new HashIgnoreLocalNames()),

    /** Only the signatures of elements; see {@link HashSignature}. */
    SIGNATURE(new HashSignature());

    private final HashCalculation calculation;

    ChangeCategory(HashCalculation calculation) {
        this.calculation = calculation;
    }

    @Override
    public void onAttribute(ChangeCategory category, ElementInfo node, String attribute, Region region) {
        calculation.onAttribute(category, node, attribute, region);
    }

    @Override
    public void onLocalVariable(ChangeCategory category, ElementInfo node, VariableTree variable, Region region) {
        calculation.onLocalVariable(category, node, variable, region);
    }

    @Override
    public void onIdentifier(ChangeCategory category, ElementInfo node, IdentifierTree id, boolean isLocal, Region region) {
        calculation.onIdentifier(category, node, id, isLocal, region);
    }

    @Override
    public void onChildCompleted(ChangeCategory category, ElementInfo parent, ElementInfo child) {
        calculation.onChildCompleted(category, parent, child);
    }
}
