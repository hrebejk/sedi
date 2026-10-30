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
 * The imports added and removed in a file; a different order of the same imports is not a change.
 *
 * @param added   the imports only the new version has, as in the source without the keyword, e.g. {@code static a.B.c}
 * @param removed the imports only the old version has
 */
public record ImportChanges(List<String> added, List<String> removed) {

    /** No imports changed. */
    static final ImportChanges NONE = new ImportChanges(List.of(), List.of());

    /**
     * Returns whether no import was added or removed.
     *
     * @return {@code true} if the imports are the same
     */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty();
    }
}
