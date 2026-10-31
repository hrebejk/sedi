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
 * Categories of SHAs computed for each {@link ElementInfo}. Each constant delegates to its own
 * {@link HashCalculation} describing what goes into the digest.
 */
public enum ChangeCategory implements HashCalculation {

    /** Everything: names of locals and parameters and their usages included. */
    FULL(new HashFull()),

    /** Ignores names of local variables and parameters and the identifiers referring to them. */
    IGNORE_LOCAL_NAMES(new HashIgnoreLocalNames()),

    /**
     * Like {@link #IGNORE_LOCAL_NAMES}, and additionally the names of elements which were renamed are replaced
     * by their old names, so a version of the code differing only by IDE-style renames hashes like the old one.
     * Differs from {@link #IGNORE_LOCAL_NAMES} only when the scan is given the renames, see
     * {@link ElementHashCalculator#ElementHashCalculator(com.sun.source.util.Trees, java.util.Map)}.
     */
    WITHOUT_RENAMES(new HashIgnoreLocalNames()),

    /**
     * Only the signatures of elements; see {@link HashSignature}. The references to renamed elements, such as a
     * parameter of a renamed type, are hashed with their old names, but the name of the element itself is not:
     * a signature changes when the element is renamed, not when an element it refers to is.
     */
    SIGNATURE(new HashSignature()),

    /**
     * Only the bodies of elements, ignoring names of locals and parameters; like {@link #WITHOUT_RENAMES} it
     * hashes the old names of renamed elements, so it differs between two versions only if the code really changed.
     */
    BODY(new HashBody());

    private final HashCalculation calculation;

    ChangeCategory(HashCalculation calculation) {
        this.calculation = calculation;
    }

    /**
     * Returns whether the digest of this category contains the old names of renamed elements.
     *
     * @return {@code true} for {@link #WITHOUT_RENAMES} and {@link #BODY}
     */
    boolean undoesRenames() {
        return this == WITHOUT_RENAMES || this == BODY;
    }

    /**
     * Returns whether the digest of this category contains the old names of renamed elements where they are
     * referred to, not where they are declared.
     *
     * @return {@code true} for {@link #SIGNATURE} and the categories which {@link #undoesRenames() undo renames}
     */
    boolean undoesRenamedReferences() {
        return undoesRenames() || this == SIGNATURE;
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
