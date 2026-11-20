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
 * A way an API change can break the existing clients of the public API, when they are compiled again or when
 * they run compiled against the old version.
 */
public enum Incompatibility {

    /** The element was removed, renamed or moved, so its old name is gone. */
    REMOVED(true, "removed, renamed or moved"),
    /** The element became less visible. */
    VISIBILITY_REDUCED(true, "visibility reduced"),
    /** The element became static or stopped being static. */
    STATIC_CHANGED(true, "static added or removed"),
    /** The parameter types of a method changed, which removes the old overload. */
    PARAMETERS_CHANGED(true, "parameter types changed"),
    /** The return type of a method or the type of a field changed. */
    TYPE_CHANGED(true, "type changed"),
    /** The components of a record changed, and with them its canonical constructor and accessors. */
    COMPONENTS_CHANGED(true, "record components changed"),
    /** The type parameters of a type or a method changed. */
    TYPE_PARAMS_CHANGED(true, "type parameters changed"),
    /** A method throws an exception it did not throw before. */
    EXCEPTION_ADDED(true, "exception added to throws"),
    /** A type, a method or a field became final. */
    FINAL_ADDED(true, "final added"),
    /** A class or a method became abstract. */
    ABSTRACT_ADDED(true, "abstract added"),
    /** A type became sealed. */
    SEALED_ADDED(true, "sealed added"),
    /** A class became an interface, an enum became a record and the like. */
    KIND_CHANGED(true, "kind of the type changed"),
    /** A type does not extend or implement one of its supertypes anymore. */
    SUPERTYPE_REMOVED(true, "supertype removed"),
    /** An abstract method was added to a type the clients implement or extend. */
    ABSTRACT_METHOD_ADDED(true, "abstract method added, implementors break"),
    /** An element without a default value was added to an annotation type. */
    ANNOTATION_ELEMENT_ADDED(true, "annotation element without default added"),
    /** The first explicit constructor took the place of the implicit constructor without parameters. */
    DEFAULT_CONSTRUCTOR_LOST(true, "implicit no-arg constructor lost"),

    /** A method was added to a class the clients may extend: a method of a subclass may clash with it. */
    METHOD_ADDED(false, "method added to an extensible class, may clash with subclasses"),
    /** A default method was added to an interface: a method of an implementor may clash with it. */
    DEFAULT_METHOD_ADDED(false, "default method added, may clash with implementors"),
    /** A constant was added to an enum: exhaustive switches over it stop compiling. */
    ENUM_CONSTANT_ADDED(false, "enum constant added, exhaustive switches break"),
    /** A method does not throw an exception anymore: a catch of the clients may become unreachable. */
    EXCEPTION_REMOVED(false, "exception removed from throws, a catch may become unreachable"),
    /** The value of a constant changed: clients compiled before keep the old value inlined. */
    CONSTANT_CHANGED(false, "constant value changed, compiled clients keep the old one");

    private final boolean breaking;
    private final String description;

    Incompatibility(boolean breaking, String description) {
        this.breaking = breaking;
        this.description = description;
    }

    /**
     * Returns whether the change certainly breaks some clients, rather than only possibly.
     *
     * @return {@code true} for a breaking change, {@code false} for a risky one
     */
    public boolean isBreaking() {
        return breaking;
    }

    /**
     * Returns a short description of the incompatibility for the reader of the report.
     *
     * @return the description
     */
    public String description() {
        return description;
    }
}
