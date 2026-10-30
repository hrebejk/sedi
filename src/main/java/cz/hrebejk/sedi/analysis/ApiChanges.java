/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import java.util.List;

/**
 * The API changes found in one container of members: the file itself or a type.
 *
 * @param typeName simple name of the type, empty for the top level of a file; displays constructors
 * @param pairs    the added, removed and changed members
 * @param nested   the changes of the types present in both versions, which are nested in the container
 */
public record ApiChanges(String typeName, List<ElementPair> pairs, List<ApiChanges> nested) {

    /**
     * Returns whether neither the container nor any type nested in it has a change.
     *
     * @return {@code true} if there is nothing to report
     */
    public boolean isEmpty() {
        return pairs.isEmpty() && nested.stream().allMatch(ApiChanges::isEmpty);
    }
}
