/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import java.util.Set;

/**
 * A pairing of an element from the old version of a type with its counterpart in the new version.
 *
 * @param oldElement    the old element, {@code null} if the element was added
 * @param newElement    the new element, {@code null} if the element was removed
 * @param changedFacets signature facets whose hashes differ; empty when only the signature hash
 *                      differs for a reason not covered by facets (for example a parameter name)
 */
public record ElementPair(ElementInfo oldElement, ElementInfo newElement, Set<Facet> changedFacets) {

    /**
     * Returns whether the element exists only in the new version.
     *
     * @return {@code true} for an added element
     */
    public boolean isAdded() {
        return oldElement == null;
    }

    /**
     * Returns whether the element exists only in the old version.
     *
     * @return {@code true} for a removed element
     */
    public boolean isRemoved() {
        return newElement == null;
    }
}
