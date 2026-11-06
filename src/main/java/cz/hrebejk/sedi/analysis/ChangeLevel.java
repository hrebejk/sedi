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
 * How important a change is to a reviewer. The constants are ordered from the most to the least important.
 */
public enum ChangeLevel {

    /** Signature changes: added, removed, renamed or retyped types and members. */
    API,

    /**
     * Changes of method bodies, field initializers and initializer blocks which are more than renames, the changes
     * of the comments in them included.
     */
    IMPLEMENTATION,

    /**
     * Renames of locals and parameters, references to renamed elements and names qualified differently. Changes of
     * whitespace alone are not reported at all.
     */
    COSMETIC,

    /** Added and removed imports. */
    IMPORTS
}
