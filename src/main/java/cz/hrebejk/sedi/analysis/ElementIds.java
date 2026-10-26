/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import java.util.stream.Collectors;

/**
 * Identity of a program element as a string, computed the same way for a declaration and for every
 * reference to it: {@code p.Type}, {@code p.Type#field}, {@code p.Type#method(paramType,...)}.
 */
final class ElementIds {

    private ElementIds() {
    }

    /**
     * Returns the identity of a type, field, enum constant, method or constructor.
     *
     * @param element the element, may be {@code null}
     * @return the id, {@code null} for other elements (locals, parameters, packages), anonymous types and
     *         elements javac could not resolve
     */
    static String of(Element element) {
        if (element == null || element.asType().getKind() == TypeKind.ERROR) {
            return null;
        }
        return switch (element.getKind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> {
                String name = ((TypeElement) element).getQualifiedName().toString();
                yield name.isEmpty() ? null : name;
            }
            case FIELD, ENUM_CONSTANT -> member(element, ((VariableElement) element).getSimpleName().toString());
            case METHOD, CONSTRUCTOR -> {
                ExecutableElement method = (ExecutableElement) element;
                yield member(element, method.getSimpleName() + method.getParameters().stream()
                        .map(p -> String.valueOf(p.asType())).collect(Collectors.joining(",", "(", ")")));
            }
            default -> null;
        };
    }

    private static String member(Element element, String name) {
        String owner = of(element.getEnclosingElement());
        return owner == null ? null : owner + "#" + name;
    }
}
