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
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether the changes of the members of one container break the existing clients of the public API, see
 * {@link Incompatibility}. Only elements which were a part of the public API can break clients; added ones only by
 * clashing with what the clients declared themselves. A member of a type which is not a part of the public API is
 * one if a type of the public API inherits it. The types are compared as written, without asking whether one is a
 * subtype of the other; what is inherited is asked javac, in the version the clients were compiled against for the
 * old elements and in the new one for the new elements.
 */
final class Compatibility {

    private static final String CONSTRUCTOR = "<init>";
    private static final Set<String> CONSTANT_TYPES = Set.of("boolean", "byte", "char", "short", "int", "long",
            "float", "double", "java.lang.String");

    private final ElementInfo oldContainer;
    private final ElementInfo newContainer;
    private final boolean containerExposed;
    private final CompilationUnitInfo oldUnit;
    private final CompilationUnitInfo newUnit;
    /** The types of the public API which inherit the members of a container which is not a part of it. */
    private List<TypeElement> heirs;

    /**
     * Creates the checker of the changes of the members of a container.
     *
     * @param oldContainer     the old version of the type or the compilation unit, {@code null} if it was added
     * @param newContainer     the new version of the container, {@code null} if it was removed
     * @param containerExposed whether the container is a part of the public API
     * @param oldUnit          the old version of the source the old container is in, {@code null} if there is none
     * @param newUnit          the new version of the source the new container is in, {@code null} if there is none
     */
    Compatibility(ElementInfo oldContainer, ElementInfo newContainer, boolean containerExposed,
                  CompilationUnitInfo oldUnit, CompilationUnitInfo newUnit) {
        this.oldContainer = oldContainer;
        this.newContainer = newContainer;
        this.containerExposed = containerExposed;
        this.oldUnit = oldUnit;
        this.newUnit = newUnit;
    }

    /**
     * Returns the ways the change of a member of the container breaks or may break the clients.
     *
     * @param pair the old and new version of the member
     * @return the incompatibilities, empty for a compatible change
     */
    Set<Incompatibility> check(ElementPair pair) {
        if (!containerExposed && heirs().isEmpty()) {
            return Set.of();
        }
        Set<Incompatibility> result = EnumSet.noneOf(Incompatibility.class);
        if (pair.isAdded()) {
            if (oldContainer != null && newContainer != null) {
                added(pair.newElement(), result);
            }
        } else if (Visibility.isExposed(pair.oldElement(), true, isInterface(oldContainer))) {
            if (pair.isRemoved()) {
                removed(pair.oldElement(), result);
            } else {
                changed(pair, result);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private void added(ElementInfo element, Set<Incompatibility> result) {
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
            return;
        }
        if (!isExtensible()) {
            return;
        }
        boolean isAbstract = isAbstract(element, inInterface);
        ExecutableElement overridden = overridden(element);
        if (overridden != null) {
            // The clients have the method already, unless it was implemented and is abstract now.
            if (isAbstract && !overridden.getModifiers().contains(Modifier.ABSTRACT)
                    && !isObject(overridden.getEnclosingElement())) {
                result.add(Incompatibility.ABSTRACT_METHOD_ADDED);
            }
        } else if (isAbstract) {
            result.add(Incompatibility.ABSTRACT_METHOD_ADDED);
        } else {
            result.add(inInterface ? Incompatibility.DEFAULT_METHOD_ADDED : Incompatibility.METHOD_ADDED);
        }
    }

    /** A removed member is still there for the clients if the new version of the container inherits it. */
    private void removed(ElementInfo element, Set<Incompatibility> result) {
        if (isReplacedByImplicitConstructor(element)) {
            return;
        }
        Element old = element.getElement();
        Element inherited = isConstructor(element) ? null : inherited(old);
        if (inherited == null) {
            result.add(Incompatibility.REMOVED);
            return;
        }
        if (access(inherited) < access(old)) {
            result.add(Incompatibility.VISIBILITY_REDUCED);
        }
        if (inherited.getModifiers().contains(Modifier.STATIC) != old.getModifiers().contains(Modifier.STATIC)) {
            result.add(Incompatibility.STATIC_CHANGED);
        }
        if (old instanceof ExecutableElement oldMethod && inherited instanceof ExecutableElement newMethod) {
            if (!old.getModifiers().contains(Modifier.ABSTRACT) && newMethod.getModifiers().contains(Modifier.ABSTRACT)) {
                result.add(Incompatibility.ABSTRACT_ADDED);
            }
            if (!thrown(oldMethod, oldUnit.types()).containsAll(thrown(newMethod, newUnit.types()))) {
                result.add(Incompatibility.EXCEPTION_ADDED);
            }
        }
    }

    private void changed(ElementPair pair, Set<Incompatibility> result) {
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
                        && isExtensible()) {
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
                if (!supertypes(newElement).containsAll(oldElement.getFacetValues(Facet.SUPERTYPES))) {
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
    private boolean isReplacedByImplicitConstructor(ElementInfo removed) {
        return isConstructor(removed) && removed.getParamCount() == 0 && newContainer != null
                && kind(newContainer) == Tree.Kind.CLASS && constructors(newContainer).isEmpty();
    }

    /**
     * The member of the new version of the container, inherited from a supertype, which the clients use instead of
     * the old one removed from the container: of the same kind and name, and for a method of the same erased
     * parameter and return types, for a field of the same erased type.
     *
     * @return the inherited member, {@code null} if there is none
     */
    private Element inherited(Element old) {
        TypeElement type = typeElement(newContainer);
        if (old == null || type == null || oldUnit == null || newUnit == null) {
            return null;
        }
        String key = erasedSignature(old, oldUnit.types());
        for (Element member : newUnit.elements().getAllMembers(type)) {
            if (member.getKind() == old.getKind() && member.getSimpleName().contentEquals(old.getSimpleName())
                    && !member.getEnclosingElement().equals(type)
                    && erasedSignature(member, newUnit.types()).equals(key)) {
                return member;
            }
        }
        return null;
    }

    /**
     * The method of a supertype of the new version of the container the added method overrides, so the clients
     * have it already.
     *
     * @return the overridden method, {@code null} if there is none
     */
    private ExecutableElement overridden(ElementInfo added) {
        TypeElement type = typeElement(newContainer);
        if (!(added.getElement() instanceof ExecutableElement method) || type == null || newUnit == null) {
            return null;
        }
        Types types = newUnit.types();
        String params = erasedParameters(method, types);
        for (TypeMirror supertype : allSupertypes(type.asType(), types)) {
            if (!(types.asElement(supertype) instanceof TypeElement superElement)) {
                continue;
            }
            for (ExecutableElement candidate : ElementFilter.methodsIn(superElement.getEnclosedElements())) {
                Set<Modifier> modifiers = candidate.getModifiers();
                if (candidate.getSimpleName().contentEquals(method.getSimpleName())
                        && !modifiers.contains(Modifier.PRIVATE) && !modifiers.contains(Modifier.STATIC)
                        && (newUnit.elements().overrides(method, candidate, type)
                        || erasedParameters(candidate, types).equals(params))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** The supertypes of the new version of a type, direct and indirect, as javac prints them. */
    private Set<String> supertypes(ElementInfo type) {
        Set<String> result = new HashSet<>(type.getFacetValues(Facet.SUPERTYPES));
        if (type.getElement() instanceof TypeElement element && newUnit != null) {
            allSupertypes(element.asType(), newUnit.types()).forEach(t -> result.add(String.valueOf(t)));
        }
        return result;
    }

    private static Set<TypeMirror> allSupertypes(TypeMirror type, Types types) {
        Set<TypeMirror> result = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<TypeMirror> todo = new ArrayDeque<>(types.directSupertypes(type));
        while (!todo.isEmpty()) {
            TypeMirror supertype = todo.pop();
            if (supertype.getKind() == TypeKind.DECLARED && seen.add(String.valueOf(supertype))) {
                result.add(supertype);
                todo.addAll(types.directSupertypes(supertype));
            }
        }
        return result;
    }

    /**
     * Whether the clients can implement or extend the members of the container: the container itself, or, if it is
     * not a part of the public API, a type of the public API which inherits them.
     */
    private boolean isExtensible() {
        return containerExposed ? isExtensible(oldContainer) : heirs().stream().anyMatch(Compatibility::isExtensible);
    }

    /**
     * The types of the public API in the package of the old version of the container which inherit its members:
     * a type which is not a part of the public API can only be extended in its package.
     */
    private List<TypeElement> heirs() {
        if (heirs == null) {
            heirs = List.of();
            TypeElement base = typeElement(oldContainer);
            if (base != null && oldUnit != null) {
                try {
                    heirs = heirs(base);
                } catch (RuntimeException e) {
                    // javac could not read a type of the package, as in code which does not compile.
                }
            }
        }
        return heirs;
    }

    private List<TypeElement> heirs(TypeElement base) {
        Types types = oldUnit.types();
        TypeMirror erased = types.erasure(base.asType());
        Deque<TypeElement> todo = new ArrayDeque<>(
                ElementFilter.typesIn(oldUnit.elements().getPackageOf(base).getEnclosedElements()));
        List<TypeElement> result = new ArrayList<>();
        while (!todo.isEmpty()) {
            TypeElement type = todo.pop();
            todo.addAll(ElementFilter.typesIn(type.getEnclosedElements()));
            if (!type.equals(base) && isExposed(type) && types.isSubtype(types.erasure(type.asType()), erased)) {
                result.add(type);
            }
        }
        return result;
    }

    /** Whether the type is a part of the public API: it and the types it is nested in can be used anywhere. */
    private static boolean isExposed(TypeElement type) {
        Element element = type;
        while (element instanceof TypeElement t) {
            Set<Modifier> modifiers = t.getModifiers();
            Element enclosing = t.getEnclosingElement();
            boolean inInterface = enclosing.getKind().isInterface();
            if (!(modifiers.contains(Modifier.PUBLIC) || enclosing instanceof TypeElement
                    && (modifiers.contains(Modifier.PROTECTED) || inInterface && !modifiers.contains(Modifier.PRIVATE)))) {
                return false;
            }
            element = enclosing;
        }
        return true;
    }

    /** Whether the clients can implement or extend the type, see {@link #isExtensible(ElementInfo)}. */
    private static boolean isExtensible(TypeElement type) {
        Set<Modifier> modifiers = type.getModifiers();
        if (modifiers.contains(Modifier.FINAL) || modifiers.contains(Modifier.SEALED)) {
            return false;
        }
        return switch (type.getKind()) {
            case INTERFACE -> true;
            case CLASS -> {
                List<ExecutableElement> constructors = ElementFilter.constructorsIn(type.getEnclosedElements());
                yield constructors.isEmpty() || constructors.stream().anyMatch(c ->
                        c.getModifiers().contains(Modifier.PUBLIC) || c.getModifiers().contains(Modifier.PROTECTED));
            }
            default -> false;
        };
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

    /** The visibility of a javac element, as {@link #access(ElementInfo, boolean)}. */
    private static int access(Element element) {
        Set<Modifier> modifiers = element.getModifiers();
        if (modifiers.contains(Modifier.PUBLIC)) {
            return 3;
        } else if (modifiers.contains(Modifier.PROTECTED)) {
            return 2;
        } else if (modifiers.contains(Modifier.PRIVATE)) {
            return 0;
        }
        return element.getEnclosingElement().getKind().isInterface() ? 3 : 1;
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

    private static boolean isObject(Element element) {
        return element instanceof TypeElement type && type.getQualifiedName().contentEquals("java.lang.Object");
    }

    /** What a member is used by in the class files: the erased parameter and return types, or the erased type. */
    private static String erasedSignature(Element element, Types types) {
        return switch (element) {
            case ExecutableElement method -> erasedParameters(method, types) + types.erasure(method.getReturnType());
            case VariableElement field -> String.valueOf(types.erasure(field.asType()));
            default -> "";
        };
    }

    private static String erasedParameters(ExecutableElement method, Types types) {
        return method.getParameters().stream().map(p -> String.valueOf(types.erasure(p.asType())))
                .collect(Collectors.joining(",", "(", ")"));
    }

    private static Set<String> thrown(ExecutableElement method, Types types) {
        return method.getThrownTypes().stream().map(t -> String.valueOf(types.erasure(t))).collect(Collectors.toSet());
    }

    /** The explicit constructors of the type; the implicit one is not an element. */
    private static List<ElementInfo> constructors(ElementInfo type) {
        return type.getChildren().stream().filter(Compatibility::isConstructor).toList();
    }

    private static boolean isInterface(ElementInfo container) {
        return container != null && Visibility.isInterface(container);
    }

    private static TypeElement typeElement(ElementInfo container) {
        return container != null && container.getElement() instanceof TypeElement type ? type : null;
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
