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

import java.util.ArrayList;
import java.util.List;

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

    /**
     * A token of the code or a comment: its place in the text and its text.
     *
     * @param start the position of its first character
     * @param end   the position after its last character
     * @param text  the text; for a comment with every run of whitespace a single space
     */
    record Token(int start, int end, String text) {
    }

    /**
     * The tokens of a part of the text: names and numbers, string and character literals and text blocks as a whole,
     * every other character on its own. Whitespace and comments are left out.
     *
     * @param from the start of the part
     * @param to   the end of the part
     * @return the tokens in the order of the text
     */
    List<Token> tokens(int from, int to) {
        List<Token> tokens = new ArrayList<>();
        scan(from, to, tokens, null);
        return tokens;
    }

    /**
     * The comments in a part of the text, their whitespace collapsed, so a comment which was only reformatted is
     * equal to its old version.
     *
     * @param from the start of the part
     * @param to   the end of the part
     * @return the comments in the order of the text
     */
    List<Token> comments(int from, int to) {
        List<Token> comments = new ArrayList<>();
        scan(from, to, null, comments);
        return comments;
    }

    /** Splits a part of the text into tokens and comments, either list {@code null} if not wanted. */
    private void scan(int from, int to, List<Token> tokens, List<Token> comments) {
        int i = Math.max(from, 0);
        int end = Math.min(to, text.length());
        while (i < end) {
            char c = text.charAt(i);
            char next = i + 1 < end ? text.charAt(i + 1) : 0;
            int start = i;
            if (c == '/' && (next == '/' || next == '*')) {
                if (next == '/') {
                    int eol = text.indexOf('\n', i);
                    i = eol < 0 || eol > end ? end : eol;
                } else {
                    int close = text.indexOf("*/", i + 2);
                    i = close < 0 || close + 2 > end ? end : close + 2;
                }
                if (comments != null) {
                    comments.add(new Token(start, i, text.substring(start, i).strip().replaceAll("\\s+", " ")));
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (text.startsWith("\"\"\"", i)) {
                int close = text.indexOf("\"\"\"", i + 3);
                i = close < 0 || close + 3 > end ? end : close + 3;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < end && text.charAt(i) != c && text.charAt(i) != '\n') {
                    i += text.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(i + 1, end);
            } else if (Character.isJavaIdentifierPart(c)) {
                while (i < end && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
            } else {
                i++;
            }
            if (tokens != null) {
                tokens.add(new Token(start, i, text.substring(start, i)));
            }
        }
    }

    /**
     * The longest common subsequence of the tokens with equal texts.
     *
     * @param olds the old tokens
     * @param news the new tokens
     * @return pairs of old and new index, in order
     */
    static List<int[]> lcs(List<Token> olds, List<Token> news) {
        int[][] length = new int[olds.size() + 1][news.size() + 1];
        for (int i = olds.size() - 1; i >= 0; i--) {
            for (int j = news.size() - 1; j >= 0; j--) {
                length[i][j] = olds.get(i).text().equals(news.get(j).text()) ? length[i + 1][j + 1] + 1
                        : Math.max(length[i + 1][j], length[i][j + 1]);
            }
        }
        List<int[]> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < olds.size() && j < news.size()) {
            if (olds.get(i).text().equals(news.get(j).text())) {
                result.add(new int[]{i++, j++});
            } else if (length[i + 1][j] >= length[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }
}
