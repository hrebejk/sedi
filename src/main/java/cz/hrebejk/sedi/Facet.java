/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

/**
 * An individual part of an element's signature. Every {@link ElementInfo} keeps a separate hash
 * per facet so that two versions of an element can be compared facet by facet when pairing them.
 */
public enum Facet {
    /** Modifier flags, such as {@code public} or {@code static}. */
    MODIFIERS,
    /** Annotations present on the element. */
    ANNOTATIONS,
    /** Thrown exception types (order independent). */
    THROWS,
    /** Simple name of the element; {@code <init>} for constructors. */
    NAME,
    /** Return type of a method or declared type of a field. */
    RETURN_TYPE,
    /** Parameter types of a method; parameter names are deliberately not included. */
    PARAM_TYPES,
    /** Type parameters including their bounds. */
    TYPE_PARAMS
}
