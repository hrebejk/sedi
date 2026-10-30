/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The renamed elements of a change. Elements are identified by their {@link ElementIds id} in the new
 * version, so only references javac resolved to the renamed element itself are affected, never other elements
 * which merely have the same simple name.
 */
public final class Renames {

    /** No renames. */
    static final Renames EMPTY = new Renames();

    /** Id in the new version to the old simple name, for renamed types, fields and methods. */
    private final Map<String, String> oldNames = new HashMap<>();
    /** Qualified name of a type in the new version to its old qualified name. */
    private final Map<String, String> oldTypes = new HashMap<>();
    private Pattern typePattern;

    /**
     * Records that two versions of an element are the same element, renamed or moved if their names differ.
     * Elements javac could not resolve (without an id) are ignored.
     *
     * @param oldElement the old version
     * @param newElement the new version
     */
    void add(ElementInfo oldElement, ElementInfo newElement) {
        String oldId = oldElement.getElementId();
        String newId = newElement.getElementId();
        if (oldId == null || newId == null) {
            return;
        }
        if (newElement.getNodeType() == ElementInfo.NodeType.TYPE && !oldId.equals(newId)) {
            oldTypes.put(newId, oldId);
            typePattern = null;
        }
        if (!oldElement.getName().equals(newElement.getName())) {
            oldNames.put(newId, oldElement.getName());
        }
    }

    /**
     * Returns whether no element was renamed or moved.
     *
     * @return {@code true} if there are no renames
     */
    public boolean isEmpty() {
        return oldNames.isEmpty() && oldTypes.isEmpty();
    }

    /**
     * Returns the old simple name of an element.
     *
     * @param newId id of the element in the new version, may be {@code null}
     * @param name  the current simple name of the element
     * @return the old name if the element was renamed, else {@code name}
     */
    public String oldName(String newId, String name) {
        return newId == null ? name : oldNames.getOrDefault(newId, name);
    }

    /**
     * Replaces the qualified names of renamed or moved types in a text, e.g. a type printed by javac.
     *
     * @param text the text
     * @return the text with the old qualified names
     */
    public String undoTypeNames(String text) {
        if (oldTypes.isEmpty()) {
            return text;
        }
        if (typePattern == null) {
            String alternatives = oldTypes.keySet().stream()
                    .sorted((a, b) -> b.length() - a.length()).map(Pattern::quote)
                    .reduce((a, b) -> a + "|" + b).orElseThrow();
            typePattern = Pattern.compile("(?<![\\w$.])(?:" + alternatives + ")(?![\\w$])");
        }
        return typePattern.matcher(text).replaceAll(m -> Matcher.quoteReplacement(oldTypes.get(m.group())));
    }
}
