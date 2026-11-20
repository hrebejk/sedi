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

import javax.lang.model.element.Modifier;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Decides whether a change of an element breaks the existing clients of the public API, see
 * {@link Incompatibility}. Only elements which were a part of the public API can break clients; added ones only by
 * clashing with what the clients declared themselves. The types are compared as written, without asking whether one
 * is a subtype of the other.
 */
final class Compatibility {

    private static final String CONSTRUCTOR = "<init>";
    private static final Set<String> CONSTANT_TYPES = Set.of("boolean", "byte", "char", "short", "int", "long",
            "float", "double", "java.lang.String");

    private Compatibility() {
    }

    /**
     * Returns the ways the change of an element breaks or may break the clients.
     *
     * @param pair             the old and new version of the element
     * @param oldContainer     the old version of the type or the compilation unit the element is in, {@code null}
     *                         if the container was added
     * @param newContainer     the new version of the container, {@code null} if it was removed
     * @param containerExposed whether the container is a part of the public API
     * @return the incompatibilities, empty for a compatible change
     */
    static Set<Incompatibility> check(ElementPair pair, ElementInfo oldContainer, ElementInfo newContainer,
                                      boolean containerExposed) {
        if (!containerExposed) {
            return Set.of();
        }
        Set<Incompatibility> result = EnumSet.noneOf(Incompatibility.class);
        if (pair.isAdded()) {
            if (oldContainer != null && newContainer != null) {
                added(pair.newElement(), oldContainer, newContainer, result);
            }
        } else if (Visibility.isExposed(pair.oldElement(), true, isInterface(oldContainer))) {
            if (pair.isRemoved()) {
                if (!isReplacedByImplicitConstructor(pair.oldElement(), newContainer)) {
                    result.add(Incompatibility.REMOVED);
                }
            } else {
                changed(pair, oldContainer, newContainer, result);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static void added(ElementInfo element, ElementInfo oldContainer, ElementInfo newContainer,
                              Set<Incompatibility> result) {
        boolean inInterface = isInterface(newContainer);
        if (isConstructor(element)) {
            // Any explicit constructor, even a private one, takes the place of the implicit one.
            if (kind(newContainer) == Tree.Kind.CLASS && constructors(oldContainer).isEmpty()
                    && constructors(newContainer).stream()
                    .noneMatch(c -> c.getParamCount() == 0 && Visibility.isExposed(c, true, false))) {
                result.add(Incompatibility.DEFAULT_CONSTRUCTOR_LOST);
            }
            return;
        }
        if (!Visibility.isExposed(element, true, inInterface)) {
            return;
        }
        if (element.isEnumConstant()) {
            result.add(Incompatibility.ENUM_CONSTANT_ADDED);
        }
        if (element.getNodeType() != ElementInfo.NodeType.METHOD || flags(element).contains(Modifier.STATIC)) {
            return;
        }
        if (kind(newContainer) == Tree.Kind.ANNOTATION_TYPE) {
            if (element.getOriginalTree() instanceof MethodTree m && m.getDefaultValue() == null) {
                result.add(Incompatibility.ANNOTATION_ELEMENT_ADDED);
            }
        } else if (!isExtensible(oldContainer)) {
            return;
        } else if (isAbstract(element, inInterface)) {
            result.add(Incompatibility.ABSTRACT_METHOD_ADDED);
        } else {
            result.add(inInterface ? Incompatibility.DEFAULT_METHOD_ADDED : Incompatibility.METHOD_ADDED);
        }
    }

    private static void changed(ElementPair pair, ElementInfo oldContainer, ElementInfo newContainer,
                                Set<Incompatibility> result) {
        ElementInfo oldElement = pair.oldElement();
        ElementInfo newElement = pair.newElement();
        Set<Facet> facets = pair.changedFacets();
        if (facets.contains(Facet.NAME) || newElement.getMovedFrom() != null
                || oldElement.getNodeType() == ElementInfo.NodeType.TYPE
                && !Objects.equals(oldElement.getElementId(), newElement.getElementId())) {
            // A type keeps its simple name when its package changes.
            result.add(Incompatibility.REMOVED);
            return;
        }
        boolean oldInInterface = isInterface(oldContainer);
        boolean newInInterface = isInterface(newContainer);
        if (access(newElement, newInInterface) < access(oldElement, oldInInterface)) {
            result.add(Incompatibility.VISIBILITY_REDUCED);
        }
        if (oldContainer.getNodeType() == ElementInfo.NodeType.TYPE
                && isStatic(oldElement, oldInInterface) != isStatic(newElement, newInInterface)) {
            result.add(Incompatibility.STATIC_CHANGED);
        }
        if (facets.contains(Facet.TYPE_PARAMS)) {
            result.add(Incompatibility.TYPE_PARAMS_CHANGED);
        }
        switch (oldElement.getNodeType()) {
            case METHOD -> {
                if (facets.contains(Facet.PARAM_TYPES)) {
                    result.add(Incompatibility.PARAMETERS_CHANGED);
                }
                if (facets.contains(Facet.RETURN_TYPE)) {
                    result.add(Incompatibility.TYPE_CHANGED);
                }
                List<String> oldThrown = oldElement.getFacetValues(Facet.THROWS);
                List<String> newThrown = newElement.getFacetValues(Facet.THROWS);
                if (!oldThrown.containsAll(newThrown)) {
                    result.add(Incompatibility.EXCEPTION_ADDED);
                }
                if (!newThrown.containsAll(oldThrown)) {
                    result.add(Incompatibility.EXCEPTION_REMOVED);
                }
                if (!isConstructor(oldElement) && !flags(newElement).contains(Modifier.STATIC)
                        && !flags(oldElement).contains(Modifier.FINAL) && flags(newElement).contains(Modifier.FINAL)
                        && isExtensible(oldContainer)) {
                    result.add(Incompatibility.FINAL_ADDED);
                }
                if (!isAbstract(oldElement, oldInInterface) && isAbstract(newElement, newInInterface)) {
                    result.add(Incompatibility.ABSTRACT_ADDED);
                }
            }
            case FIELD -> {
                if (facets.contains(Facet.RETURN_TYPE)) {
                    result.add(Incompatibility.TYPE_CHANGED);
                }
                if (!isFinal(oldElement, oldInInterface) && isFinal(newElement, newInInterface)) {
                    result.add(Incompatibility.FINAL_ADDED);
                }
                if (isConstant(oldElement, oldInInterface) && isConstant(newElement, newInInterface)
                        && !oldElement.getSha(ChangeCategory.BODY).equals(newElement.getSha(ChangeCategory.BODY))) {
                    result.add(Incompatibility.CONSTANT_CHANGED);
                }
            }
            case TYPE -> {
                Set<Modifier> oldFlags = flags(oldElement);
                Set<Modifier> newFlags = flags(newElement);
                if (kind(oldElement) != kind(newElement)) {
                    result.add(Incompatibility.KIND_CHANGED);
                } else {
                    boolean extensible = isExtensible(oldElement);
                    if (extensible && !oldFlags.contains(Modifier.FINAL) && newFlags.contains(Modifier.FINAL)) {
                        result.add(Incompatibility.FINAL_ADDED);
                    }
                    if (extensible && !oldFlags.contains(Modifier.SEALED) && newFlags.contains(Modifier.SEALED)) {
                        result.add(Incompatibility.SEALED_ADDED);
                    }
                    if (kind(oldElement) == Tree.Kind.CLASS && !oldFlags.contains(Modifier.ABSTRACT)
                            && newFlags.contains(Modifier.ABSTRACT)) {
                        result.add(Incompatibility.ABSTRACT_ADDED);
                    }
                }
                if (facets.contains(Facet.COMPONENTS)) {
                    result.add(Incompatibility.COMPONENTS_CHANGED);
                }
                if (!new HashSet<>(newElement.getFacetValues(Facet.SUPERTYPES))
                        .containsAll(oldElement.getFacetValues(Facet.SUPERTYPES))) {
                    result.add(Incompatibility.SUPERTYPE_REMOVED);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Whether the removed constructor without parameters is replaced by the implicit one, because the new version
     * of the class has no explicit constructor.
     */
    private static boolean isReplacedByImplicitConstructor(ElementInfo removed, ElementInfo newContainer) {
        return isConstructor(removed) && removed.getParamCount() == 0 && newContainer != null
                && kind(newContainer) == Tree.Kind.CLASS && constructors(newContainer).isEmpty();
    }

    /**
     * Whether the clients can implement or extend the type: an interface or a class which is neither final nor
     * sealed, a class also only if it has a public or protected constructor, maybe the implicit one.
     */
    private static boolean isExtensible(ElementInfo type) {
        if (flags(type).contains(Modifier.FINAL) || flags(type).contains(Modifier.SEALED)) {
            return false;
        }
        return switch (kind(type)) {
            case INTERFACE -> true;
            case CLASS -> {
                List<ElementInfo> constructors = constructors(type);
                yield constructors.isEmpty()
                        || constructors.stream().anyMatch(c -> Visibility.isExposed(c, true, false));
            }
            case null, default -> false;
        };
    }

    /** The visibility of the element: 3 for public, 2 protected, 1 package private and 0 private. */
    private static int access(ElementInfo element, boolean inInterface) {
        Set<Modifier> flags = flags(element);
        if (flags.contains(Modifier.PUBLIC) || element.isEnumConstant()) {
            return 3;
        } else if (flags.contains(Modifier.PROTECTED)) {
            return 2;
        } else if (flags.contains(Modifier.PRIVATE)) {
            return 0;
        }
        return inInterface ? 3 : 1;
    }

    /** Whether the element is static, explicitly or implicitly, like a field of an interface or a nested enum. */
    private static boolean isStatic(ElementInfo element, boolean inInterface) {
        if (flags(element).contains(Modifier.STATIC) || element.isEnumConstant()) {
            return true;
        }
        return switch (element.getNodeType()) {
            case FIELD -> inInterface;
            case TYPE -> inInterface || kind(element) != Tree.Kind.CLASS;
            default -> false;
        };
    }

    /** Whether the field is final, explicitly or as a field of an interface. */
    private static boolean isFinal(ElementInfo field, boolean inInterface) {
        return flags(field).contains(Modifier.FINAL) || inInterface || field.isEnumConstant();
    }

    /** Whether the field is a constant whose value javac copies into the clients. */
    private static boolean isConstant(ElementInfo field, boolean inInterface) {
        List<String> type = field.getFacetValues(Facet.RETURN_TYPE);
        return isStatic(field, inInterface) && isFinal(field, inInterface)
                && type.size() == 1 && CONSTANT_TYPES.contains(type.getFirst());
    }

    /** Whether the method is abstract, explicitly or as a method of an interface without a body. */
    private static boolean isAbstract(ElementInfo method, boolean inInterface) {
        Set<Modifier> flags = flags(method);
        if (flags.contains(Modifier.ABSTRACT)) {
            return true;
        }
        return inInterface && method.getOriginalTree() instanceof MethodTree m && m.getBody() == null
                && !flags.contains(Modifier.STATIC) && !flags.contains(Modifier.DEFAULT);
    }

    private static boolean isConstructor(ElementInfo element) {
        return element.getNodeType() == ElementInfo.NodeType.METHOD && element.getName().equals(CONSTRUCTOR);
    }

    /** The explicit constructors of the type; the implicit one is not an element. */
    private static List<ElementInfo> constructors(ElementInfo type) {
        return type.getChildren().stream().filter(Compatibility::isConstructor).toList();
    }

    private static boolean isInterface(ElementInfo container) {
        return container != null && Visibility.isInterface(container);
    }

    /** The kind of a type, {@code null} for other elements. */
    private static Tree.Kind kind(ElementInfo element) {
        return element.getOriginalTree() instanceof ClassTree c ? c.getKind() : null;
    }

    private static Set<Modifier> flags(ElementInfo element) {
        ModifiersTree modifiers = Visibility.modifiers(element);
        return modifiers == null ? Set.of() : modifiers.getFlags();
    }
}
