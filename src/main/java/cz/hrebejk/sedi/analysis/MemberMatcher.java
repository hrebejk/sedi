/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pairs the members of the old and new version of a type that are "the same element, changed".
 * <p>
 * Elements with an equal {@link ChangeCategory#SIGNATURE} hash have an unchanged signature; {@link #match}
 * drops them, {@link #matchAll} returns them separately.
 * The remaining elements are paired in passes of decreasing confidence:
 * <ol>
 *   <li>equal {@link ChangeCategory#IGNORE_LOCAL_NAMES} hash (only names of locals and parameters differ),</li>
 *   <li>same name, parameter types and type parameters (modifiers, annotations, throws or return type changed),</li>
 *   <li>same name, different parameters (changed overload),</li>
 *   <li>same parameter types, type parameters and return type, different name (method rename, unique candidates only),</li>
 *   <li>same type, modifiers and annotations, different name (field rename, unique candidates only),</li>
 *   <li>same kind, modifiers and member signatures, different name (type rename, unique candidates only).</li>
 * </ol>
 * Whatever is left is reported as removed or added. Elements of different kinds never pair, and
 * constructors pair only with constructors.
 */
final class MemberMatcher {

    private static final String CONSTRUCTOR_NAME = "<init>";

    private MemberMatcher() {
    }

    /**
     * The members of two versions of a type.
     *
     * @param changed   pairs of members with a changed signature, removed and added members
     * @param unchanged pairs of members with an unchanged signature, whose bodies can still differ
     */
    record Matches(List<ElementPair> changed, List<ElementPair> unchanged) {
    }

    /**
     * Pairs the children of two versions of a type.
     *
     * @param oldType the old version of the type
     * @param newType the new version of the type
     * @return pairs of changed, removed and added members; unchanged members are omitted
     */
    public static List<ElementPair> match(ElementInfo oldType, ElementInfo newType) {
        return matchAll(oldType, newType).changed();
    }

    /**
     * Pairs the children of two versions of a type, keeping the members whose signature did not change.
     *
     * @param oldType the old version of the type
     * @param newType the new version of the type
     * @return the changed and the unchanged pairs
     */
    static Matches matchAll(ElementInfo oldType, ElementInfo newType) {
        List<ElementInfo> oldRest = new ArrayList<>(oldType.getChildren());
        List<ElementInfo> newRest = new ArrayList<>(newType.getChildren());
        List<ElementPair> pairs = new ArrayList<>();
        List<ElementPair> unchanged = new ArrayList<>();

        // Pass 0: unchanged members.
        pass(oldRest, newRest, unchanged, e -> kind(e) + "|" + e.getSha(ChangeCategory.SIGNATURE), false, false);

        // Pass 1: same code up to names of locals and parameters; the same element whatever else changed.
        pass(oldRest, newRest, pairs, e -> kind(e) + "|" + e.getSha(ChangeCategory.IGNORE_LOCAL_NAMES), false, false);

        // Pass 2: same identity, other facets changed.
        pass(oldRest, newRest, pairs,
                e -> kind(e) + "|" + e.getName() + "|" + e.getFacetSha(Facet.PARAM_TYPES)
                        + "|" + e.getFacetSha(Facet.TYPE_PARAMS),
                false, false);

        // Pass 3: changed overload (same name, different parameters).
        pass(oldRest, newRest, pairs, e -> kind(e) + "|" + e.getName(), false, true);

        // Pass 4: rename of a method (same parameters and return type), only if unambiguous.
        pass(oldRest, newRest, pairs,
                e -> e.getNodeType() != ElementInfo.NodeType.METHOD ? null
                        : kind(e) + "|" + e.getFacetSha(Facet.PARAM_TYPES) + "|" + e.getFacetSha(Facet.TYPE_PARAMS)
                        + "|" + e.getFacetSha(Facet.RETURN_TYPE),
                true, false);

        // Pass 5: rename of a field or enum constant (same type, modifiers and annotations), only if unambiguous.
        pass(oldRest, newRest, pairs,
                e -> e.getNodeType() != ElementInfo.NodeType.FIELD ? null
                        : kind(e) + "|" + e.getFacetSha(Facet.RETURN_TYPE) + "|" + e.getFacetSha(Facet.MODIFIERS)
                        + "|" + e.getFacetSha(Facet.ANNOTATIONS),
                true, false);

        // Pass 6: rename of a type with the same members (compared by their signatures), only if unambiguous.
        pass(oldRest, newRest, pairs, MemberMatcher::typeShape, true, false);

        for (ElementInfo removed : oldRest) {
            pairs.add(new ElementPair(removed, null, Set.of()));
        }
        for (ElementInfo added : newRest) {
            pairs.add(new ElementPair(null, added, Set.of()));
        }
        return new Matches(pairs, unchanged);
    }

    /**
     * One pairing pass. Matched elements are removed from both lists.
     *
     * @param pairs            receives the matches
     * @param key              pairing key, {@code null} result means the element does not take part
     * @param uniqueOnly       pair only if exactly one old and one new element share the key
     * @param preferParamCount among several candidates prefer one with the same parameter count
     */
    private static void pass(List<ElementInfo> oldRest, List<ElementInfo> newRest, List<ElementPair> pairs,
                             Function<ElementInfo, String> key, boolean uniqueOnly, boolean preferParamCount) {
        Map<String, List<ElementInfo>> newByKey = group(newRest, key);
        Map<String, List<ElementInfo>> oldByKey = uniqueOnly ? group(oldRest, key) : Map.of();
        Set<ElementInfo> usedOld = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ElementInfo> usedNew = Collections.newSetFromMap(new IdentityHashMap<>());

        for (ElementInfo old : oldRest) {
            String k = key.apply(old);
            List<ElementInfo> candidates = k == null ? null : newByKey.get(k);
            if (candidates == null) {
                continue;
            }
            if (uniqueOnly && (oldByKey.get(k).size() != 1 || candidates.size() != 1)) {
                continue;
            }
            ElementInfo match = choose(old, candidates, usedNew, preferParamCount);
            if (match == null) {
                continue;
            }
            usedOld.add(old);
            usedNew.add(match);
            pairs.add(new ElementPair(old, match, changedFacets(old, match)));
        }
        oldRest.removeIf(usedOld::contains);
        newRest.removeIf(usedNew::contains);
    }

    private static ElementInfo choose(ElementInfo old, List<ElementInfo> candidates, Set<ElementInfo> used,
                                      boolean preferParamCount) {
        ElementInfo first = null;
        for (ElementInfo candidate : candidates) {
            if (used.contains(candidate)) {
                continue;
            }
            if (!preferParamCount || candidate.getParamCount() == old.getParamCount()) {
                return candidate;
            }
            if (first == null) {
                first = candidate;
            }
        }
        return first;
    }

    private static Map<String, List<ElementInfo>> group(List<ElementInfo> elements,
                                                        Function<ElementInfo, String> key) {
        Map<String, List<ElementInfo>> result = new LinkedHashMap<>();
        for (ElementInfo element : elements) {
            String k = key.apply(element);
            if (k != null) {
                result.computeIfAbsent(k, x -> new ArrayList<>()).add(element);
            }
        }
        return result;
    }

    private static Set<Facet> changedFacets(ElementInfo old, ElementInfo updated) {
        Set<Facet> changed = EnumSet.noneOf(Facet.class);
        for (Facet facet : Facet.values()) {
            if (!old.getFacetSha(facet).equals(updated.getFacetSha(facet))) {
                changed.add(facet);
            }
        }
        return changed;
    }

    /**
     * Pairs the nested types of two versions of a container: the types paired by {@code memberPairs}
     * (changed or renamed) and the remaining types of the same name, which can differ in their members only.
     *
     * @param oldContainer the old version of the container
     * @param newContainer the new version of the container
     * @param memberPairs  the result of {@link #match} for the two containers
     * @return pairs of old and new type, none of them added or removed
     */
    public static List<ElementPair> nestedTypes(ElementInfo oldContainer, ElementInfo newContainer,
                                                List<ElementPair> memberPairs) {
        List<ElementPair> result = new ArrayList<>();
        Set<ElementInfo> covered = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ElementPair pair : memberPairs) {
            if (!pair.isAdded() && !pair.isRemoved() && pair.newElement().getNodeType() == ElementInfo.NodeType.TYPE) {
                result.add(pair);
                covered.add(pair.oldElement());
                covered.add(pair.newElement());
            }
        }
        Map<String, ElementInfo> oldByName = new LinkedHashMap<>();
        for (ElementInfo child : oldContainer.getChildren()) {
            if (child.getNodeType() == ElementInfo.NodeType.TYPE && !covered.contains(child)) {
                oldByName.put(child.getName(), child);
            }
        }
        for (ElementInfo child : newContainer.getChildren()) {
            ElementInfo old = child.getNodeType() == ElementInfo.NodeType.TYPE && !covered.contains(child)
                    ? oldByName.get(child.getName()) : null;
            if (old != null) {
                result.add(new ElementPair(old, child, Set.of()));
            }
        }
        return result;
    }

    /** Key of a type independent of its name: kind, modifiers, type parameters and the signatures of its non-constructor members. */
    private static String typeShape(ElementInfo type) {
        // Constructors do not count: javac adds a default one to every class, so they can't tell types apart.
        List<ElementInfo> members = type.getChildren().stream().filter(c -> !CONSTRUCTOR_NAME.equals(c.getName())).toList();
        if (type.getNodeType() != ElementInfo.NodeType.TYPE || members.isEmpty()) {
            return null;
        }
        String declKind = type.getOriginalTree() instanceof com.sun.source.tree.ClassTree c ? c.getKind().name() : "";
        return kind(type) + "|" + declKind + "|" + type.getFacetSha(Facet.MODIFIERS)
                + "|" + type.getFacetSha(Facet.TYPE_PARAMS) + "|"
                + members.stream().map(c -> c.getSha(ChangeCategory.SIGNATURE)).sorted().toList();
    }

    /** Pairing gate: node type, with constructors distinguished from methods. */
    private static String kind(ElementInfo element) {
        boolean constructor = element.getNodeType() == ElementInfo.NodeType.METHOD
                && CONSTRUCTOR_NAME.equals(element.getName());
        return element.getNodeType() + (constructor ? "-ctor" : "");
    }
}
