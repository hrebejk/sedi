/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import org.maraboo.fleck.html.HTML;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A piece of inline content of the report: text and spans, put together as strings were. A fragment also knows
 * whether it shows a change, that is whether some of it was {@link Markup#strike struck through} or
 * {@link Markup#added marked as added}, so a printer can tell a changed line from an unchanged one without
 * comparing what it rendered.
 * <p>
 * The nodes are not added to any tree; appending them to an element copies them, so a fragment can be used more
 * than once.
 */
final class Fragment {

    static final Fragment EMPTY = new Fragment(List.of(), false);

    private final List<HTML> nodes;
    private final boolean changed;

    private Fragment(List<HTML> nodes, boolean changed) {
        this.nodes = Collections.unmodifiableList(nodes);
        this.changed = changed;
    }

    /** Plain text; empty text gives the empty fragment. */
    static Fragment of(String text) {
        return text.isEmpty() ? EMPTY : new Fragment(List.of(HTML.Text(text)), false);
    }

    /** The nodes, showing no change. */
    static Fragment of(HTML... nodes) {
        return of(List.of(nodes), false);
    }

    static Fragment of(List<? extends HTML> nodes, boolean changed) {
        return new Fragment(new ArrayList<>(nodes), changed);
    }

    /** The nodes, in the order of the source. */
    List<HTML> nodes() {
        return nodes;
    }

    /** Whether the fragment shows a change. */
    boolean changed() {
        return changed;
    }

    /** The same nodes, marked as showing a change. */
    Fragment asChanged() {
        return changed ? this : new Fragment(nodes, true);
    }

    /** The text without the markup. */
    String text() {
        StringBuilder sb = new StringBuilder();
        nodes.forEach(n -> sb.append(n.getInnerText()));
        return sb.toString();
    }

    /** Whether there is no text. */
    boolean isEmpty() {
        return text().isEmpty();
    }

    /** This fragment followed by the text. */
    Fragment plus(String text) {
        return plus(of(text));
    }

    /** This fragment followed by the others. */
    Fragment plus(Fragment... others) {
        List<HTML> all = new ArrayList<>(nodes);
        boolean anyChanged = changed;
        for (Fragment f : others) {
            all.addAll(f.nodes);
            anyChanged |= f.changed;
        }
        return new Fragment(all, anyChanged);
    }

    /** The fragments, the empty ones left out, separated by the separator. */
    static Fragment join(List<Fragment> parts, String separator) {
        Fragment result = EMPTY;
        for (Fragment part : parts) {
            if (part.isEmpty()) {
                result = part.changed ? result.asChanged() : result;
                continue;
            }
            result = result.isEmpty() ? result.plus(part) : result.plus(separator).plus(part);
        }
        return result;
    }

    /** The fragment without the white space at its start and end, as far as it is in its text nodes. */
    Fragment strip() {
        return stripLeading().stripTrailing();
    }

    /** The fragment without the white space at its start, as far as it is in its text nodes. */
    Fragment stripLeading() {
        List<HTML> list = new ArrayList<>(nodes);
        while (!list.isEmpty() && list.getFirst() instanceof HTML.Text t) {
            String s = t.text.stripLeading();
            if (!s.isEmpty()) {
                list.set(0, HTML.Text(s));
                break;
            }
            list.removeFirst();
        }
        return new Fragment(list, changed);
    }

    /** The fragment without at most {@code columns} spaces at its start, as far as they are in its text nodes. */
    Fragment dedent(int columns) {
        List<HTML> list = new ArrayList<>(nodes);
        int left = columns;
        while (left > 0 && !list.isEmpty() && list.getFirst() instanceof HTML.Text t) {
            int spaces = 0;
            while (spaces < left && spaces < t.text.length() && t.text.charAt(spaces) == ' ') {
                spaces++;
            }
            left -= spaces;
            if (spaces < t.text.length()) {
                list.set(0, HTML.Text(t.text.substring(spaces)));
                break;
            }
            list.removeFirst();
        }
        return new Fragment(list, changed);
    }

    /** The fragment without the white space at its end, as far as it is in its text nodes. */
    Fragment stripTrailing() {
        List<HTML> list = new ArrayList<>(nodes);
        while (!list.isEmpty() && list.getLast() instanceof HTML.Text t) {
            String s = t.text.stripTrailing();
            if (!s.isEmpty()) {
                list.set(list.size() - 1, HTML.Text(s));
                break;
            }
            list.removeLast();
        }
        return new Fragment(list, changed);
    }

    @Override
    public String toString() {
        return text();
    }
}
