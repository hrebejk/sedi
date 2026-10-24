/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

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
 * Elements with an equal {@link ChangeCategory#SIGNATURE} hash are considered unchanged and dropped.
 * The remaining elements are paired in passes of decreasing confidence:
 * <ol>
 *   <li>equal {@link ChangeCategory#IGNORE_LOCAL_NAMES} hash (only names of locals and parameters differ),</li>
 *   <li>same name, parameter types and type parameters (modifiers, annotations, throws or return type changed),</li>
 *   <li>same name, different parameters (changed overload),</li>
 *   <li>same parameter types, type parameters and return type, different name (rename, unique candidates only).</li>
 * </ol>
 * Whatever is left is reported as removed or added. Elements of different kinds never pair, and
 * constructors pair only with constructors.
 */
public final class MemberMatcher {

    private static final String CONSTRUCTOR_NAME = "<init>";

    private MemberMatcher() {
    }

    /**
     * Pairs the children of two versions of a type.
     *
     * @param oldType the old version of the type
     * @param newType the new version of the type
     * @return pairs of changed, removed and added members; unchanged members are omitted
     */
    public static List<ElementPair> match(ElementInfo oldType, ElementInfo newType) {
        List<ElementInfo> oldRest = new ArrayList<>(oldType.getChildren());
        List<ElementInfo> newRest = new ArrayList<>(newType.getChildren());
        List<ElementPair> pairs = new ArrayList<>();

        // Pass 0: unchanged members.
        pass(oldRest, newRest, null, e -> kind(e) + "|" + e.getSha(ChangeCategory.SIGNATURE), false, false);

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

        for (ElementInfo removed : oldRest) {
            pairs.add(new ElementPair(removed, null, Set.of()));
        }
        for (ElementInfo added : newRest) {
            pairs.add(new ElementPair(null, added, Set.of()));
        }
        return pairs;
    }

    /**
     * One pairing pass. Matched elements are removed from both lists.
     *
     * @param pairs            receives the matches; {@code null} to just drop matched (unchanged) elements
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
            if (pairs != null) {
                pairs.add(new ElementPair(old, match, changedFacets(old, match)));
            }
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

    /** Pairing gate: node type, with constructors distinguished from methods. */
    private static String kind(ElementInfo element) {
        boolean constructor = element.getNodeType() == ElementInfo.NodeType.METHOD
                && CONSTRUCTOR_NAME.equals(element.getName());
        return element.getNodeType() + (constructor ? "-ctor" : "");
    }
}
