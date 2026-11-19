/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import java.util.List;

/**
 * A line of the report before it is put into its element, see {@link LineNumbers#render}. A numbered line has the
 * number of its line in the source and a marker; the printers make the lines, the report puts the lines of the tree
 * in front of them and the numbers in their column at last, when the width of the column is known.
 *
 * @param kind    what the line is
 * @param number  the line of the source, {@code 0} if not known; only for a numbered line
 * @param marker  {@code +} for added code, {@code -} for removed, {@code ~} for changed, a space for unchanged code
 *                shown as the context, {@link #NONE} for no marker; only for a numbered line
 * @param prefix  the indentation and the lines of the tree, in front of the content
 * @param content the text of the line; added and removed code is not styled as such yet
 * @param javadoc the lines of the javadoc of the element whose fold this starts, see {@link #elementFold}; empty for
 *                any other line
 */
record Line(Kind kind, long number, char marker, Fragment prefix, Fragment content, List<String> javadoc) {

    /** No marker: the line has no column for it, like the members of an added type. */
    static final char NONE = 0;

    /** What a line is. */
    enum Kind {
        /** A line with a number and a marker in the column on the left. */
        NUMBERED,
        /** A line without a number, indented as the numbered ones. */
        PLAIN,
        /** A line left of the column of the numbers, like a heading. */
        OUTSIDE,
        /** An empty line. */
        BLANK,
        /** Not a line: the start of a {@link Css#FOLD fold}, named by the text of the content. */
        FOLD,
        /** Not a line: the start of a fold collapsed at first, else as {@link #FOLD}. */
        FOLDED,
        /** Not a line: the start of the fold of a type, else as {@link #FOLD}. */
        TYPE_FOLD,
        /** Not a line: the start of the fold of a member, like a method, else as {@link #FOLD}. */
        MEMBER_FOLD,
        /** Not a line: the start of {@linkplain Css#ELIDED unchanged lines hidden} behind the note, its first line. */
        ELIDED,
        /** Not a line: the end of the fold started last. */
        END
    }

    /** A line numbered by a line of the source, without a marker. */
    static Line number(long line, Fragment content) {
        return number(line, NONE, content);
    }

    /** A line numbered by a line of the source, with its marker. */
    static Line number(long line, char marker, Fragment content) {
        return new Line(Kind.NUMBERED, Math.max(line, 0), marker, Fragment.EMPTY, content, List.of());
    }

    /** A line without a number, indented as the numbered ones. */
    static Line plain(Fragment content) {
        return new Line(Kind.PLAIN, 0, NONE, Fragment.EMPTY, content, List.of());
    }

    /** A line left of the column of the numbers. */
    static Line outside(Fragment content) {
        return new Line(Kind.OUTSIDE, 0, NONE, Fragment.EMPTY, content, List.of());
    }

    /** An empty line. */
    static Line blank() {
        return new Line(Kind.BLANK, 0, NONE, Fragment.EMPTY, Fragment.EMPTY, List.of());
    }

    /**
     * The start of a part of the report which can be collapsed to its first line; the lines up to the
     * {@link #end()} are in it.
     *
     * @param name names the part, e.g. the path of a file
     */
    static Line fold(String name) {
        return fold(name, true);
    }

    /**
     * The start of a fold, see {@link #fold(String)}.
     *
     * @param open whether the part is shown expanded at first, where it can be collapsed
     */
    static Line fold(String name, boolean open) {
        return new Line(open ? Kind.FOLD : Kind.FOLDED, 0, NONE, Fragment.EMPTY, Fragment.of(name), List.of());
    }

    /**
     * The start of the fold of an element, see {@link #fold(String)}.
     *
     * @param signature the text of the signature of the element
     * @param type      whether the element is a type, else a member
     * @param javadoc   the lines of the javadoc of the element, empty if it has none
     */
    static Line elementFold(String signature, boolean type, List<String> javadoc) {
        return new Line(type ? Kind.TYPE_FOLD : Kind.MEMBER_FOLD, 0, NONE, Fragment.EMPTY, Fragment.of(signature),
                javadoc);
    }

    /** The start of unchanged lines hidden behind a note, the first line up to the {@link #end()}. */
    static Line elided() {
        return new Line(Kind.ELIDED, 0, NONE, Fragment.EMPTY, Fragment.EMPTY, List.of());
    }

    /** The end of the fold started last. */
    static Line end() {
        return new Line(Kind.END, 0, NONE, Fragment.EMPTY, Fragment.EMPTY, List.of());
    }

    /** The line with the text put in front of its prefix; a blank line stays blank. */
    Line withPrefix(String text) {
        return withPrefix(Fragment.of(text));
    }

    /** The line with the fragment put in front of its prefix; a blank line and the bounds of a fold stay as they are. */
    Line withPrefix(Fragment fragment) {
        return kind == Kind.BLANK || isFoldStart() || kind == Kind.END || fragment.isEmpty() ? this
                : new Line(kind, number, marker, fragment.plus(prefix), content, javadoc);
    }

    /** Whether this is no line but the start of a fold, of any kind. */
    boolean isFoldStart() {
        return switch (kind) {
            case FOLD, FOLDED, TYPE_FOLD, MEMBER_FOLD, ELIDED -> true;
            case NUMBERED, PLAIN, OUTSIDE, BLANK, END -> false;
        };
    }

    /** The line with the fragment added at its end. */
    Line append(Fragment fragment) {
        return new Line(kind, number, marker, prefix, content.plus(fragment), javadoc);
    }
}
