/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;

/**
 * The text of a compilation unit and the positions of its trees in it.
 *
 * @param unit      the compilation unit
 * @param positions the positions of its trees
 * @param text      its source text
 */
record SourceText(CompilationUnitTree unit, SourcePositions positions, String text) {

    /** The line where the tree starts, {@code 0} if it has no position. */
    long line(Tree tree) {
        long start = tree == null ? -1 : positions.getStartPosition(unit, tree);
        return start < 0 ? 0 : unit.getLineMap().getLineNumber(start);
    }

    /**
     * The text of the tree on a single line: comments left out, every run of whitespace a single space, string and
     * character literals as they are.
     *
     * @return the text, {@code null} if the tree has no place in the source
     */
    String oneLine(Tree tree) {
        long start = tree == null ? -1 : positions.getStartPosition(unit, tree);
        long end = tree == null ? -1 : positions.getEndPosition(unit, tree);
        if (start < 0 || end <= start || end > text.length()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        boolean space = false;
        int i = (int) start;
        while (i < end) {
            char c = text.charAt(i);
            char next = i + 1 < end ? text.charAt(i + 1) : 0;
            if (c == '/' && next == '/') {
                while (i < end && text.charAt(i) != '\n') {
                    i++;
                }
                space = true;
            } else if (c == '/' && next == '*') {
                int close = text.indexOf("*/", i + 2);
                i = close < 0 || close + 2 > end ? (int) end : close + 2;
                space = true;
            } else if (Character.isWhitespace(c)) {
                space = true;
                i++;
            } else {
                if (space && !out.isEmpty()) {
                    out.append(' ');
                }
                space = false;
                if (c == '"' || c == '\'') {
                    int literal = i;
                    i++;
                    while (i < end && text.charAt(i) != c) {
                        i += text.charAt(i) == '\\' ? 2 : 1;
                    }
                    i = (int) Math.min(i + 1, end);
                    out.append(text, literal, i);
                } else {
                    out.append(c);
                    i++;
                }
            }
        }
        return out.toString();
    }
}
