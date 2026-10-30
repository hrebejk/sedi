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

    /** Changes of method bodies, field initializers and initializer blocks which are more than renames. */
    IMPLEMENTATION,

    /** Renames of locals, parameters and of the elements referred to in bodies, whitespace and comments. */
    COSMETIC,

    /** Added and removed imports. */
    IMPORTS
}
