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

import java.util.Locale;

/**
 * The CSS classes of the elements of the report. The style of each class comes from the style sheet, see
 * {@link ReportStyles}.
 */
enum Css {
    /** The whole report. */
    REPORT,
    /** A line of the report. */
    LINE,
    /** A line shown left of the column of the line numbers, like a heading. */
    OUTSIDE,
    /** The number of a line of the source. */
    LN,
    /** The text of a numbered line, after its marker and the lines of the tree. */
    CODE,
    /** A line of added code. */
    ADD_LINE,
    /** A line of removed code. */
    REMOVE_LINE,
    /** A line with changes. */
    CHANGE_LINE,
    /** The {@code +} starting a line of added code or the title of an added file. */
    ADD_MARK,
    /** The {@code -} starting a line of removed code or the title of a deleted file. */
    REMOVE_MARK,
    /** The {@code ~} of a changed line, the {@code ~} or {@code →} starting the title of a changed file. */
    CHANGE_MARK,
    /** Java keywords, including modifiers, primitive types and literals like {@code null}. */
    KEYWORD,
    /** Names starting with a capital letter. */
    TYPE,
    /** Names followed by a parenthesis. */
    METHOD,
    /** String and character literals. */
    STRING,
    /** Number literals. */
    NUMBER,
    /** Names in capitals, like {@code MAX_VALUE}. */
    CONSTANT,
    /** Annotations. */
    ANNOTATION,
    /** Comments. */
    COMMENT,
    /** Old text replaced by new one or removed. */
    STRIKE,
    /** New text, added or replacing the struck through text. */
    ADDED,
    /** The name of a declared type, method or field. */
    DECLARATION,
    /** Section headings. */
    HEADING,
    /** The {@code #} starting a section heading. */
    HEADING_MARK,
    /** The name of a changed file's class. */
    TITLE,
    /** The package in a file heading. */
    PACKAGE,
    /** The tag of the level of a change. */
    TAG,
    /** Names javac could not resolve, as in code which does not compile. */
    ERROR,
    /** The lines of the tree of the members of a type and the lines between the files. */
    TREE,
    /** Remarks of the report, like collapsed or left out code. */
    NOTE,
    /** The remark of a section without changes. */
    NO_CHANGES;

    private final String cssClass = name().toLowerCase(Locale.ROOT).replace('_', '-');

    /** The name of the class, e.g. {@code heading-mark}. */
    String cssClass() {
        return cssClass;
    }

    /** Whether the class is one of the syntax highlighting, see {@link Highlighter}. */
    boolean isHighlight() {
        return switch (this) {
            case KEYWORD, TYPE, METHOD, STRING, NUMBER, CONSTANT, ANNOTATION -> true;
            default -> false;
        };
    }

    /** A span of this class with the text. */
    HTML.Tag span(String text) {
        return HTML.SPAN(text).clazz(cssClass);
    }

    /** A span of this class with the nodes. */
    HTML.Tag span(Fragment content) {
        return HTML.SPAN().children(content.nodes()).clazz(cssClass);
    }

    /** Whether the tag has this class. */
    boolean on(HTML.Tag tag) {
        String classes = tag.attrs.get("class");
        if (classes == null) {
            return false;
        }
        for (String c : classes.split(" ")) {
            if (c.equals(cssClass)) {
                return true;
            }
        }
        return false;
    }
}
