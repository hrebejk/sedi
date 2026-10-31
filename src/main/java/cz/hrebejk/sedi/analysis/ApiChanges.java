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
 * The changes found in one container of members: the file itself or a type.
 *
 * @param typeName simple name of the type, empty for the top level of a file; displays constructors
 * @param exposed  whether the container is a part of the public API
 * @param type     the old and new version of the type, {@code null} for the top level of a file
 * @param changes  the changes of the members, in all the levels except imports
 * @param nested   the changes of the types present in both versions, which are nested in the container
 */
public record ApiChanges(String typeName, boolean exposed, ElementPair type, List<MemberChange> changes,
                         List<ApiChanges> nested) {

    /**
     * Returns the added, removed and changed members, which are the changes of the {@link ChangeLevel#API} level.
     *
     * @return the pairs of old and new member
     */
    public List<ElementPair> pairs() {
        return changes.stream().filter(c -> c.level() == ChangeLevel.API).map(MemberChange::pair).toList();
    }

    /**
     * Returns whether neither the container nor any type nested in it has a change of the level or a more
     * important one.
     *
     * @param max the least important level to look at
     * @return {@code true} if there is nothing to report
     */
    public boolean isEmpty(ChangeLevel max) {
        return changes.stream().noneMatch(c -> c.level().compareTo(max) <= 0)
                && nested.stream().allMatch(n -> n.isEmpty(max));
    }

    /**
     * Returns whether neither the container nor any type nested in it has an API change.
     *
     * @return {@code true} if there is nothing to report
     */
    public boolean isEmpty() {
        return isEmpty(ChangeLevel.API);
    }
}
