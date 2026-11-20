/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import cz.hrebejk.sedi.analysis.Incompatibility;
import cz.hrebejk.sedi.util.WordDiff;
import org.maraboo.fleck.html.HTML;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** The old content, struck through, without its syntax highlighting. */
    static Fragment strike(Fragment content) {
        return content.isEmpty() ? Fragment.EMPTY
                : style(Css.STRIKE, Fragment.of(unhighlighted(content.nodes()), content.changed())).asChanged();
    }

    /** The nodes with the spans of the syntax highlighting replaced by their children. */
    private static List<HTML> unhighlighted(List<HTML> nodes) {
        List<HTML> out = new ArrayList<>();
        for (HTML node : nodes) {
            switch (node) {
                case HTML.Text t -> out.add(t);
                case HTML.Tag t when isHighlight(t) -> out.addAll(unhighlighted(t.children));
                case HTML.Tag t -> out.add(HTML.Tag(t.tagName).attributes(t.attrs).children(unhighlighted(t.children)));
            }
        }
        return out;
    }

    private static boolean isHighlight(HTML.Tag tag) {
        for (Css css : Css.values()) {
            if (css.isHighlight() && css.on(tag)) {
                return true;
            }
        }
        return false;
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
    static Fragment renamedFile() {
        return style(Css.CHANGE_MARK, "→");
    }

    /**
     * The new version of a renamed or moved name, or of code which differs from the old one only by such names or by
     * how they are qualified, marked as renamed. The old and the new version show on demand, as the title of the
     * span: on the page under the mouse. It is not a change of its own.
     *
     * @param content the new version as shown
     * @param oldText the old version
     * @param newText the new version
     */
    static Fragment renamed(Fragment content, String oldText, String newText) {
        return content.isEmpty() ? content
                : Fragment.of(Css.RENAMED.span(content).attr("title", oldText + " \u2192 " + newText));
    }

    /**
     * The badge of an API change which breaks the clients, {@code ⚠}, or which may break them, {@code ⓘ}, preceded
     * by a space. Its title lists how; on the page it shows under the mouse.
     *
     * @param incompatibilities how the change breaks the clients
     * @return the badge, empty if there are no incompatibilities
     */
    static Fragment incompatible(Set<Incompatibility> incompatibilities) {
        if (incompatibilities.isEmpty()) {
            return Fragment.EMPTY;
        }
        boolean breaking = incompatibilities.stream().anyMatch(Incompatibility::isBreaking);
        String title = incompatibilities.stream().map(Incompatibility::description)
                .collect(Collectors.joining("\n"));
        HTML.Tag badge = (breaking ? Css.BREAKING : Css.RISKY).span(breaking ? "\u26a0" : "\u24d8")
                .attr("title", title);
        return Fragment.of(" ").plus(Fragment.of(badge));
    }
}
