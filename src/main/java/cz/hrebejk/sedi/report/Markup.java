/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import cz.hrebejk.sedi.util.WordDiff;

import java.util.List;

/**
 * Marks up the pieces of the report with their {@link Css} classes: styled text, the old text struck through and
 * the new text marked as added.
 */
final class Markup {

    private Markup() {
    }

    /** The text in a span of the class; empty text gives the empty fragment. */
    static Fragment style(Css css, String text) {
        return text.isEmpty() ? Fragment.EMPTY : Fragment.of(css.span(text));
    }

    /** The content in a span of the class, still showing its changes; empty content is left as it is. */
    static Fragment style(Css css, Fragment content) {
        return content.isEmpty() ? content : Fragment.of(List.of(css.span(content)), content.changed());
    }

    /** The old text, struck through. */
    static Fragment strike(String text) {
        return strike(Fragment.of(text));
    }

    /** The old content, struck through. */
    static Fragment strike(Fragment content) {
        return content.isEmpty() ? Fragment.EMPTY : style(Css.STRIKE, content).asChanged();
    }

    /** Code javac could not resolve. */
    static Fragment error(String text) {
        return style(Css.ERROR, text);
    }

    /** The added text. */
    static Fragment added(String text) {
        return added(Fragment.of(text));
    }

    /** The added content. */
    static Fragment added(Fragment content) {
        return content.isEmpty() ? Fragment.EMPTY : style(Css.ADDED, content).asChanged();
    }

    /** The old text struck through, followed by the new text which replaced it. */
    static Fragment replace(String oldText, String newText) {
        return replace(oldText, Fragment.of(newText));
    }

    /** The old text struck through, followed by the new content which replaced it. */
    static Fragment replace(String oldText, Fragment newContent) {
        return strike(oldText).plus(" ").plus(added(newContent));
    }

    /**
     * The new text with the words removed from the old one struck through and the added ones marked, see
     * {@link WordDiff}; if the texts differ too much, the old text struck through followed by the new one.
     */
    static Fragment replaceWords(String oldText, String newText) {
        List<WordDiff.Change> changes = WordDiff.diff(oldText, newText);
        if (changes == null) {
            return replace(oldText, newText);
        }
        Fragment out = Fragment.EMPTY;
        int at = 0;
        for (WordDiff.Change change : changes) {
            out = out.plus(newText.substring(at, change.start())).plus(removed(change))
                    .plus(added(newText.substring(change.start(), change.end())));
            at = change.end();
        }
        return out.plus(newText.substring(at));
    }

    /** The removed text of a word change struck through, on one line, with the spaces around it; empty if none. */
    static Fragment removed(WordDiff.Change change) {
        return change.removed().isEmpty() ? Fragment.EMPTY
                : Fragment.of(change.before()).plus(strike(change.removed().replaceAll("\\s+", " ")))
                        .plus(change.after());
    }

    /** The {@code +} or {@code -} starting a line of added or removed code or the title of a file. */
    static Fragment sign(boolean added) {
        return added ? style(Css.ADD_MARK, "+") : style(Css.REMOVE_MARK, "-");
    }

    /** The {@code ~} starting the title of a changed file or a changed line. */
    static Fragment modified() {
        return style(Css.CHANGE_MARK, "~");
    }

    /** The {@code →} starting the title of a renamed file. */
    static Fragment renamed() {
        return style(Css.CHANGE_MARK, "→");
    }
}
