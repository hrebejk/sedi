/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.util;

import java.util.ArrayList;
import java.util.List;

/**
 * The one line hint of the keys of the interactive mode, such as {@code [a] api  [i] impl  ...  [h] help}. The line
 * is made to fit the width of the terminal: labels are shortened first, then keys are dropped and replaced by
 * {@code …}. The help key is always the last item.
 */
public final class KeyHint {

    private static final String SEPARATOR = "  ";
    private static final String ELLIPSIS = "…";
    private static final String REVERSE = "\033[7m";
    private static final String NORMAL = "\033[27m";

    private record Item(char key, String label, String shortLabel) { }

    private static final List<Item> ITEMS = List.of(
            new Item('l', "all", "all"),
            new Item('a', "api", "api"),
            new Item('i', "impl", "impl"),
            new Item('c', "cosmetic", "cos"),
            new Item('m', "imports", "imp"),
            new Item('d', "javadoc", "doc"),
            new Item('+', "added", "add"),
            new Item('-', "removed", "rem"),
            new Item('t', "tests", "test"),
            new Item('q', "quit", "quit"));
    private static final Item HELP = new Item('h', "help", "help");

    private KeyHint() {
    }

    /**
     * Renders the hint.
     *
     * @param width   the most visible characters the line may have
     * @param colored {@code true} to show the keys on a reversed background, {@code false} to put them in brackets
     * @return the line; just the help item if nothing more fits
     */
    public static String render(int width, boolean colored) {
        for (boolean shortLabels : new boolean[] {false, true}) {
            if (build(ITEMS, false, false, shortLabels).length() <= width) {
                return build(ITEMS, false, colored, shortLabels);
            }
        }
        for (int kept = ITEMS.size() - 1; kept > 0; kept--) {
            List<Item> items = ITEMS.subList(0, kept);
            if (build(items, true, false, true).length() <= width) {
                return build(items, true, colored, true);
            }
        }
        return build(List.of(), false, colored, true);
    }

    /** The items, then the ellipsis if asked for, and always the help item. The colored and plain keys are equally wide. */
    private static String build(List<Item> items, boolean ellipsis, boolean colored, boolean shortLabels) {
        List<String> parts = new ArrayList<>();
        for (Item item : items) {
            parts.add(format(item, colored, shortLabels));
        }
        if (ellipsis) {
            parts.add(ELLIPSIS);
        }
        parts.add(format(HELP, colored, shortLabels));
        return String.join(SEPARATOR, parts);
    }

    private static String format(Item item, boolean colored, boolean shortLabels) {
        String label = shortLabels ? item.shortLabel() : item.label();
        return colored
                ? REVERSE + " " + item.key() + " " + NORMAL + " " + label
                : "[" + item.key() + "] " + label;
    }
}
