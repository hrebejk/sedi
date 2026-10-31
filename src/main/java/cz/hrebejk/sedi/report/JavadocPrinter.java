/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import cz.hrebejk.sedi.analysis.ElementPair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Renders the javadoc of an {@link ElementPair}: the changes of the javadoc of a changed element, the whole javadoc
 * of an added one, nothing for a removed one. Every line starts with a mark, {@code ~} for a line changed by a few
 * words (the removed words struck through, the added ones underlined), {@code -} for a removed line, struck through,
 * and {@code +} for an added one. A line changed completely is shown as removed, the new line right under it.
 */
final class JavadocPrinter {

    /** Lines at least this similar, by the words they share, are shown as one line with the changed words. */
    private static final double SIMILAR = 0.5;

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
     * The javadoc lines of the pair, to be shown above its declaration.
     *
     * @param pair the old and new version of the element
     * @return the lines, each starting with its mark; empty if there is nothing to show
     */
    List<String> print(ElementPair pair) {
        if (context == ReportOptions.NO_JAVADOC || pair.isRemoved()) {
            return List.of();
        }
        if (pair.isAdded()) {
            return pair.newElement().getJavadoc().stream().map(this::added).toList();
        }
        List<String> oldLines = pair.oldElement().getJavadoc();
        List<String> newLines = pair.newElement().getJavadoc();
        if (oldLines.equals(newLines)) {
            return List.of();
        }
        return withContext(diff(oldLines, newLines));
    }

    /**
     * The javadoc of an element shown as it is, without marks, e.g. a member of an added type.
     *
     * @param javadoc the lines of the javadoc
     * @return the lines, empty if javadoc is not shown
     */
    List<String> plain(List<String> javadoc) {
        return context == ReportOptions.NO_JAVADOC ? List.of()
                : javadoc.stream().map(this::comment).toList();
    }

    /** A rendered line; {@code changed} is false for the unchanged lines which may be left out. */
    private record Line(String text, boolean changed) { }

    private List<Line> diff(List<String> oldLines, List<String> newLines) {
        List<Line> out = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> inserted = new ArrayList<>();
        int o = 0;
        int n = 0;
        for (int[] match : lcs(oldLines.stream().map(String::strip).toList(),
                newLines.stream().map(String::strip).toList())) {
            removed.addAll(oldLines.subList(o, match[0]));
            inserted.addAll(newLines.subList(n, match[1]));
            hunk(removed, inserted, out);
            out.add(new Line("  " + comment(newLines.get(match[1])), false));
            o = match[0] + 1;
            n = match[1] + 1;
        }
        removed.addAll(oldLines.subList(o, oldLines.size()));
        inserted.addAll(newLines.subList(n, newLines.size()));
        hunk(removed, inserted, out);
        return out;
    }

    /**
     * Renders the removed lines and the lines inserted in their place, then clears both. A removed line similar to an
     * inserted one is shown with the changed words; one which is not is followed by the next inserted line which is
     * not similar to any other removed line.
     */
    private void hunk(List<String> removed, List<String> inserted, List<Line> out) {
        int next = 0;
        for (int r = 0; r < removed.size(); r++) {
            String old = removed.get(r);
            int similar = -1;
            for (int i = next; i < inserted.size() && similar < 0; i++) {
                if (similarity(old, inserted.get(i)) >= SIMILAR) {
                    similar = i;
                }
            }
            if (similar >= 0) {
                for (int i = next; i < similar; i++) {
                    out.add(new Line(added(inserted.get(i)), true));
                }
                out.add(new Line(theme.modified() + " " + comment(words(old, inserted.get(similar))), true));
                next = similar + 1;
            } else {
                out.add(new Line(removed(old), true));
                List<String> laterRemoved = removed.subList(r + 1, removed.size());
                String candidate = next < inserted.size() ? inserted.get(next) : null;
                if (candidate != null && laterRemoved.stream().noneMatch(l -> similarity(l, candidate) >= SIMILAR)) {
                    out.add(new Line(added(inserted.get(next)), true));
                    next++;
                }
            }
        }
        for (int i = next; i < inserted.size(); i++) {
            out.add(new Line(added(inserted.get(i)), true));
        }
        removed.clear();
        inserted.clear();
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
                    out.add("  " + theme.style(Theme.Role.NOTE, "…"));
                    skipped = false;
                }
                out.add(lines.get(i).text());
            } else {
                skipped = true;
            }
        }
        if (skipped) {
            out.add("  " + theme.style(Theme.Role.NOTE, "…"));
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

    private String added(String line) {
        return theme.changed(true, "+ " + comment(line));
    }

    private String removed(String line) {
        return theme.changed(false, "- " + theme.style(Theme.Role.COMMENT, line.isEmpty() ? "*" : "* " + theme.strike(line)));
    }

    /** A line of the javadoc, starting with its {@code *}. */
    private String comment(String line) {
        return theme.style(Theme.Role.COMMENT, line.isEmpty() ? "*" : "* " + line);
    }

    /** The new line with the words removed from the old one struck through and the added ones underlined. */
    private String words(String oldLine, String newLine) {
        List<String> o = words(oldLine);
        List<String> n = words(newLine);
        List<String> out = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> inserted = new ArrayList<>();
        int i = 0;
        int j = 0;
        for (int[] match : lcs(o, n)) {
            removed.addAll(o.subList(i, match[0]));
            inserted.addAll(n.subList(j, match[1]));
            flush(removed, inserted, out);
            out.add(n.get(match[1]));
            i = match[0] + 1;
            j = match[1] + 1;
        }
        removed.addAll(o.subList(i, o.size()));
        inserted.addAll(n.subList(j, n.size()));
        flush(removed, inserted, out);
        String indent = newLine.substring(0, newLine.length() - newLine.stripLeading().length());
        return indent + String.join(" ", out);
    }

    private void flush(List<String> removed, List<String> inserted, List<String> out) {
        if (!removed.isEmpty()) {
            out.add(theme.strike(String.join(" ", removed)));
        }
        if (!inserted.isEmpty()) {
            out.add(theme.style(Theme.Role.REPLACEMENT, String.join(" ", inserted)));
        }
        removed.clear();
        inserted.clear();
    }

    private static List<String> words(String line) {
        String stripped = line.strip();
        return stripped.isEmpty() ? List.of() : Arrays.asList(stripped.split("\\s+"));
    }

    /** How many of the words of the two lines are common, from 0 to 1. */
    private static double similarity(String a, String b) {
        List<String> x = words(a);
        List<String> y = words(b);
        return x.isEmpty() && y.isEmpty() ? 1 : 2.0 * lcs(x, y).size() / (x.size() + y.size());
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
