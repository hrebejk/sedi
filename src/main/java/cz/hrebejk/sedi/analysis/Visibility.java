/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;

import javax.lang.model.element.Modifier;

/**
 * Decides which elements are a part of the public API: public or protected ones, and the members of interfaces,
 * all of them only if the enclosing types are part of it too.
 */
public final class Visibility {

    private Visibility() {
    }

    /**
     * Returns whether the element is a part of the public API.
     *
     * @param element          the element, its new version if it has one
     * @param containerExposed whether the enclosing type is a part of the public API
     * @param inInterface      whether the enclosing type is an interface or annotation type
     * @return {@code true} if the element can be used from outside of its package
     */
    public static boolean isExposed(ElementInfo element, boolean containerExposed, boolean inInterface) {
        ModifiersTree modifiers = modifiers(element);
        if (!containerExposed) {
            return false;
        }
        if (modifiers == null) {
            return true;
        }
        if (modifiers.getFlags().contains(Modifier.PUBLIC) || modifiers.getFlags().contains(Modifier.PROTECTED)) {
            return true;
        }
        return inInterface && !modifiers.getFlags().contains(Modifier.PRIVATE);
    }

    /**
     * Returns the modifiers as written in the source of the element.
     *
     * @param element the element
     * @return the modifiers, {@code null} if the element has none, like a compilation unit
     */
    static ModifiersTree modifiers(ElementInfo element) {
        return switch (element.getOriginalTree()) {
            case ClassTree c -> c.getModifiers();
            case MethodTree m -> m.getModifiers();
            case VariableTree v -> v.getModifiers();
            case null, default -> null;
        };
    }

    /**
     * Returns whether the element is an interface or an annotation type, whose members are public by default.
     *
     * @param type the type
     * @return {@code true} for an interface or annotation type
     */
    public static boolean isInterface(ElementInfo type) {
        return type.getOriginalTree() instanceof ClassTree c
                && (c.getKind() == Tree.Kind.INTERFACE || c.getKind() == Tree.Kind.ANNOTATION_TYPE);
    }
}
