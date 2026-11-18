/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

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
 */
record Line(Kind kind, long number, char marker, Fragment prefix, Fragment content) {

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
        BLANK
    }

    /** A line numbered by a line of the source, without a marker. */
    static Line number(long line, Fragment content) {
        return number(line, NONE, content);
    }

    /** A line numbered by a line of the source, with its marker. */
    static Line number(long line, char marker, Fragment content) {
        return new Line(Kind.NUMBERED, Math.max(line, 0), marker, Fragment.EMPTY, content);
    }

    /** A line without a number, indented as the numbered ones. */
    static Line plain(Fragment content) {
        return new Line(Kind.PLAIN, 0, NONE, Fragment.EMPTY, content);
    }

    /** A line left of the column of the numbers. */
    static Line outside(Fragment content) {
        return new Line(Kind.OUTSIDE, 0, NONE, Fragment.EMPTY, content);
    }

    /** An empty line. */
    static Line blank() {
        return new Line(Kind.BLANK, 0, NONE, Fragment.EMPTY, Fragment.EMPTY);
    }

    /** The line with the text put in front of its prefix; a blank line stays blank. */
    Line withPrefix(String text) {
        return withPrefix(Fragment.of(text));
    }

    /** The line with the fragment put in front of its prefix; a blank line stays blank. */
    Line withPrefix(Fragment fragment) {
        return kind == Kind.BLANK || fragment.isEmpty() ? this
                : new Line(kind, number, marker, fragment.plus(prefix), content);
    }

    /** The line with the fragment added at its end. */
    Line append(Fragment fragment) {
        return new Line(kind, number, marker, prefix, content.plus(fragment));
    }
}
