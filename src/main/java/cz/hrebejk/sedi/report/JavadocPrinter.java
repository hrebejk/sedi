/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import cz.hrebejk.sedi.analysis.ElementInfo;
import cz.hrebejk.sedi.analysis.ElementPair;
import cz.hrebejk.sedi.util.WordDiff;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the javadoc of an {@link ElementPair}: the changes of the javadoc of a changed element, the whole javadoc
 * of an added one, nothing for a removed one. Every line starts with a mark, {@code ~} for a line changed by a few
 * words (the removed words struck through, the added ones underlined), {@code -} for a removed line, struck through,
 * and {@code +} for an added one. A block of lines changed completely is shown as all the removed lines,
 * then all the new ones.
 */
final class JavadocPrinter {

    /** Lines at least this similar, by the words they share, are shown as one line with the changed words. */

    private final Theme theme;
    private final int context;

    /**
     * Creates a printer.
     *
     * @param theme   the colors
     * @param context the unchanged lines shown around a change; {@link ReportOptions#NO_JAVADOC} for no javadoc at
     *                all, {@link ReportOptions#WHOLE_JAVADOC} for all the lines
     */
    JavadocPrinter(Theme theme, int context) {
        this.theme = theme;
        this.context = context;
    }

    /**
     * The javadoc lines of the pair, to be shown above its declaration, numbered by the lines of the source, see
     * {@link LineNumbers}.
     *
     * @param pair the old and new version of the element
     * @return the lines, each starting with its mark; empty if there is nothing to show
     */
    List<String> print(ElementPair pair) {
        if (context == ReportOptions.NO_JAVADOC || pair.isRemoved()) {
            return List.of();
        }
        if (pair.isAdded()) {
            List<String> lines = pair.newElement().getJavadoc();
            long first = pair.newElement().getJavadocLine();
            List<String> out = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                out.add(added(lines.get(i), line(first, i)));
            }
            return out;
        }
        List<String> oldLines = pair.oldElement().getJavadoc();
        List<String> newLines = pair.newElement().getJavadoc();
        if (oldLines.equals(newLines)) {
            return List.of();
        }
        return withContext(new Diff(oldLines, newLines, pair.oldElement().getJavadocLine(),
                pair.newElement().getJavadocLine()).lines());
    }

    /**
     * The javadoc of an element shown as it is, without marks, e.g. a member of an added type.
     *
     * @param element the element
     * @return the lines, empty if javadoc is not shown
     */
    List<String> plain(ElementInfo element) {
        if (context == ReportOptions.NO_JAVADOC) {
            return List.of();
        }
        List<String> lines = element.getJavadoc();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            out.add(LineNumbers.number(line(element.getJavadocLine(), i), comment(lines.get(i))));
        }
        return out;
    }

    /**
     * The javadoc of an element whose javadoc did not change, e.g. one with a changed implementation, shown without
     * marks. With a limited context only its first lines are shown, as many as the context, followed by {@code …}.
     *
     * @param element the element
     * @return the lines, empty if javadoc is not shown
     */
    List<String> unchanged(ElementInfo element) {
        List<String> lines = plain(element);
        if (context < 0 || lines.size() <= context) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.subList(0, context));
        out.add(LineNumbers.number(0, ' ', theme.style(Theme.Role.NOTE, "…")));
        return out;
    }

    /** The line of the source of a line of a javadoc, {@code 0} if not known. */
    private static long line(long first, int index) {
        return first <= 0 ? 0 : first + index;
    }

    /** A rendered line; {@code changed} is false for the unchanged lines which may be left out. */
    private record Line(String text, boolean changed) { }

    /**
     * The diff of two versions of a javadoc. The lines are referred to by their indexes.
     *
     * @param oldFirst the line of the source of the first old line, {@code 0} if not known
     * @param newFirst the line of the source of the first new line, {@code 0} if not known
     */
    private final class Diff {

        private final List<String> oldLines;
        private final List<String> newLines;
        private final long oldFirst;
        private final long newFirst;
        private final List<Line> out = new ArrayList<>();

        Diff(List<String> oldLines, List<String> newLines, long oldFirst, long newFirst) {
            this.oldLines = oldLines;
            this.newLines = newLines;
            this.oldFirst = oldFirst;
            this.newFirst = newFirst;
        }

        List<Line> lines() {
            List<Integer> removed = new ArrayList<>();
            List<Integer> inserted = new ArrayList<>();
            int o = 0;
            int n = 0;
            for (int[] match : lcs(oldLines.stream().map(String::strip).toList(),
                    newLines.stream().map(String::strip).toList())) {
                range(removed, o, match[0]);
                range(inserted, n, match[1]);
                hunk(removed, inserted);
                out.add(new Line(LineNumbers.number(line(newFirst, match[1]), ' ',
                        comment(newLines.get(match[1]))), false));
                o = match[0] + 1;
                n = match[1] + 1;
            }
            range(removed, o, oldLines.size());
            range(inserted, n, newLines.size());
            hunk(removed, inserted);
            return out;
        }

        private static void range(List<Integer> indexes, int from, int to) {
            for (int i = from; i < to; i++) {
                indexes.add(i);
            }
        }

        /**
         * Renders the removed lines and the lines inserted in their place, then clears both. A removed line similar
         * to an inserted one is shown with the changed words. The lines which are not, up to the next similar pair,
         * are shown as a block: first all the removed lines, then all the inserted ones.
         */
        private void hunk(List<Integer> removed, List<Integer> inserted) {
            List<Integer> blockRemoved = new ArrayList<>();
            List<Integer> blockInserted = new ArrayList<>();
            int next = 0;
            for (int old : removed) {
                int similar = -1;
                for (int i = next; i < inserted.size() && similar < 0; i++) {
                    if (WordDiff.similarity(oldLines.get(old), newLines.get(inserted.get(i))) >= WordDiff.SIMILAR) {
                        similar = i;
                    }
                }
                if (similar >= 0) {
                    blockInserted.addAll(inserted.subList(next, similar));
                    block(blockRemoved, blockInserted);
                    int index = inserted.get(similar);
                    out.add(new Line(LineNumbers.number(line(newFirst, index), '~',
                            comment(words(oldLines.get(old), newLines.get(index)))), true));
                    next = similar + 1;
                } else {
                    blockRemoved.add(old);
                }
            }
            blockInserted.addAll(inserted.subList(next, inserted.size()));
            block(blockRemoved, blockInserted);
            removed.clear();
            inserted.clear();
        }

        /** Renders the removed lines, then the inserted ones, and clears both. */
        private void block(List<Integer> removed, List<Integer> inserted) {
            removed.forEach(i -> out.add(new Line(removed(oldLines.get(i), line(oldFirst, i)), true)));
            inserted.forEach(i -> out.add(new Line(added(newLines.get(i), line(newFirst, i)), true)));
            removed.clear();
            inserted.clear();
        }
    }

    /** Leaves out the unchanged lines farther than {@link #context} from a change, a run of them shown as {@code …}. */
    private List<String> withContext(List<Line> lines) {
        if (context == ReportOptions.WHOLE_JAVADOC) {
            return lines.stream().map(Line::text).toList();
        }
        List<String> out = new ArrayList<>();
        boolean skipped = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).changed() || nearChange(lines, i)) {
                if (skipped) {
                    out.add(LineNumbers.number(0, ' ', theme.style(Theme.Role.NOTE, "…")));
                    skipped = false;
                }
                out.add(lines.get(i).text());
            } else {
                skipped = true;
            }
        }
        if (skipped) {
            out.add(LineNumbers.number(0, ' ', theme.style(Theme.Role.NOTE, "…")));
        }
        return out;
    }

    private boolean nearChange(List<Line> lines, int index) {
        for (int i = Math.max(0, index - context); i <= Math.min(lines.size() - 1, index + context); i++) {
            if (lines.get(i).changed()) {
                return true;
            }
        }
        return false;
    }

    private String added(String text, long line) {
        return LineNumbers.number(line, '+', comment(text));
    }

    private String removed(String text, long line) {
        return LineNumbers.number(line, '-',
                theme.style(Theme.Role.COMMENT, text.isEmpty() ? "*" : "* " + theme.strike(text)));
    }

    /** A line of the javadoc, starting with its {@code *}. */
    private String comment(String line) {
        return theme.style(Theme.Role.COMMENT, line.isEmpty() ? "*" : "* " + line);
    }

    /** The new line with the words removed from the old one struck through and the added ones underlined. */
    private String words(String oldLine, String newLine) {
        String indent = newLine.substring(0, newLine.length() - newLine.stripLeading().length());
        return indent + theme.replaceWords(oldLine.strip(), newLine.strip());
    }

    /** The longest common subsequence of the lists, as pairs of the indexes of the equal items. */
    private static <T> List<int[]> lcs(List<T> a, List<T> b) {
        int[][] length = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                length[i][j] = a.get(i).equals(b.get(j)) ? length[i + 1][j + 1] + 1
                        : Math.max(length[i + 1][j], length[i][j + 1]);
            }
        }
        List<int[]> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
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
