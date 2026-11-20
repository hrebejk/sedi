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
 * @param roles     the syntax roles of the text, {@code null} if not needed
 */
record SourceText(CompilationUnitTree unit, SourcePositions positions, String text, SyntaxRoles roles) {

    /** The line where the tree starts, {@code 0} if it has no position. */
    long line(Tree tree) {
        long start = tree == null ? -1 : positions.getStartPosition(unit, tree);
        return start < 0 ? 0 : unit.getLineMap().getLineNumber(start);
    }

    /**
     * The text of the tree as it is in the source, its lines breaks and comments kept. The text in front of the tree
     * on its first line is replaced by spaces, tabs kept, so the first line is indented like the others.
     *
     * @return the text, {@code null} if the tree has no place in the source
     */
    String text(Tree tree) {
        List<SyntaxRoles.Run> runs = textRuns(tree);
        return runs == null ? null : join(runs);
    }

    /** Like {@link #text(Tree)}, in runs of the same syntax role. */
    List<SyntaxRoles.Run> textRuns(Tree tree) {
        long start = tree == null ? -1 : positions.getStartPosition(unit, tree);
        long end = tree == null ? -1 : positions.getEndPosition(unit, tree);
        if (start < 0 || end <= start || end > text.length()) {
            return null;
        }
        int lineStart = text.lastIndexOf('\n', (int) start - 1) + 1;
        SyntaxRoles.Runs runs = new SyntaxRoles.Runs();
        for (int i = lineStart; i < end; i++) {
            char c = text.charAt(i);
            if (i < start) {
                runs.add(c == '\t' ? c : ' ', null, false);
            } else {
                add(runs, i);
            }
        }
        return runs.build();
    }

    private void add(SyntaxRoles.Runs runs, int position) {
        runs.add(text.charAt(position), roles == null ? null : roles.role(position),
                roles != null && roles.isDeclaration(position));
    }

    private static String join(List<SyntaxRoles.Run> runs) {
        StringBuilder sb = new StringBuilder();
        runs.forEach(r -> sb.append(r.text()));
        return sb.toString();
    }

    /**
     * The text of the tree on a single line: comments left out, every run of whitespace a single space, string and
     * character literals as they are.
     *
     * @return the text, {@code null} if the tree has no place in the source
     */
    String oneLine(Tree tree) {
        List<SyntaxRoles.Run> runs = oneLineRuns(tree);
        return runs == null ? null : join(runs);
    }

    /** Like {@link #oneLine(Tree)}, in runs of the same syntax role. */
    List<SyntaxRoles.Run> oneLineRuns(Tree tree) {
        long start = tree == null ? -1 : positions.getStartPosition(unit, tree);
        long end = tree == null ? -1 : positions.getEndPosition(unit, tree);
        if (start < 0 || end <= start || end > text.length()) {
            return null;
        }
        SyntaxRoles.Runs out = new SyntaxRoles.Runs();
        boolean any = false;
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
                if (space && any) {
                    out.add(' ', null, false);
                }
                space = false;
                any = true;
                if (c == '"' || c == '\'') {
                    int literal = i;
                    i++;
                    while (i < end && text.charAt(i) != c) {
                        i += text.charAt(i) == '\\' ? 2 : 1;
                    }
                    i = (int) Math.min(i + 1, end);
                    for (int j = literal; j < i; j++) {
                        add(out, j);
                    }
                } else {
                    add(out, i);
                    i++;
                }
            }
        }
        return out.build();
    }

    /**
     * A comment: its place in the text and its text, every run of whitespace a single space, so a comment which was
     * only reformatted is equal to its old version.
     *
     * @param start the position of its first character
     * @param end   the position after its last character
     * @param text  the text
     */
    record Comment(int start, int end, String text) {
    }

    /**
     * The comments in a part of the text. Comments are not a part of the trees, so they are found in the text,
     * skipping string and character literals and text blocks.
     *
     * @param from the start of the part
     * @param to   the end of the part
     * @return the comments in the order of the text
     */
    List<Comment> comments(int from, int to) {
        List<Comment> comments = new ArrayList<>();
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
                comments.add(new Comment(start, i, text.substring(start, i).strip().replaceAll("\\s+", " ")));
            } else if (text.startsWith("\"\"\"", i)) {
                int close = text.indexOf("\"\"\"", i + 3);
                i = close < 0 || close + 3 > end ? end : close + 3;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < end && text.charAt(i) != c && text.charAt(i) != '\n') {
                    i += text.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(i + 1, end);
            } else {
                i++;
            }
        }
        return comments;
    }

    /**
     * The longest common subsequence of the comments with equal texts.
     *
     * @param olds the old comments
     * @param news the new comments
     * @return pairs of old and new index, in order
     */
    static List<int[]> lcs(List<Comment> olds, List<Comment> news) {
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
