/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

/**
 * One reported change of a member or type.
 *
 * @param pair      the old and new version of the member, one of them {@code null} if it was added or removed
 * @param level     the importance of the change
 * @param publicApi {@code true} if the member is visible outside of the package, see {@link Visibility}
 * @param body      what changed in the body, for {@link ChangeLevel#IMPLEMENTATION} changes of methods and fields;
 *                  else {@code null}
 */
public record MemberChange(ElementPair pair, ChangeLevel level, boolean publicApi, BodyChanges body) {

    /**
     * Creates a change without the details of the body.
     *
     * @param pair      the old and new version of the member
     * @param level     the importance of the change
     * @param publicApi {@code true} if the member is visible outside of the package
     */
    public MemberChange(ElementPair pair, ChangeLevel level, boolean publicApi) {
        this(pair, level, publicApi, null);
    }

    /**
     * Returns whether only the javadoc of the element changed: an API change of an element present in both versions
     * whose signature is the same.
     *
     * @return {@code true} for a change of the javadoc alone
     */
    public boolean isJavadocOnly() {
        return level == ChangeLevel.API && !pair.isAdded() && !pair.isRemoved()
                && pair.oldElement().getSha(ChangeCategory.SIGNATURE).equals(pair.newElement().getSha(ChangeCategory.SIGNATURE));
    }
}
