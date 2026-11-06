/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The line numbers of the report, shown in a column on the left of the lines, not indented with the code. Right
 * after the number comes the marker of the line, {@code +}, {@code -} or {@code ~}, and the number has the color of
 * the marker. The printers {@link #number number} the lines they make; the report puts the numbers in the column at
 * last, when the width of the column is known.
 */
final class LineNumbers {

    private static final char MARK = '\u0000';
    private static final char UNNUMBERED = '\u0001';
    /** No marker: the line has no column for it, like the members of an added type. */
    static final char NONE = 0;
    private static final Pattern NUMBERED = Pattern.compile("^([^" + MARK + UNNUMBERED + "]*)" + MARK + "(\\d+)" + MARK + "([-+~ ]?)" + MARK);

    private LineNumbers() {
    }

    /**
     * The line of the report numbered by a line of the source, without a marker. An indentation, spaces or the
     * lines of a tree, may be put in front of it.
     *
     * @param line the line of the source, {@code 0} if not known
     */
    static String number(long line, String text) {
        return number(line, NONE, text);
    }

    /**
     * The line of the report numbered by a line of the source, with its marker. An indentation, spaces or the
     * lines of a tree, may be put in front of it.
     *
     * @param line   the line of the source, {@code 0} if not known
     * @param marker {@code +} for added code, {@code -} for removed, {@code ~} for changed, a space for unchanged
     *               code shown as the context, {@link #NONE} for no marker
     * @param text   the line without the marker; added and removed code is not styled as such yet
     */
    static String number(long line, char marker, String text) {
        return MARK + String.valueOf(Math.max(line, 0)) + MARK + (marker == NONE ? "" : String.valueOf(marker)) + MARK
                + text;
    }

    /** The text of a numbered line, without its number and marker. */
    static String text(String line) {
        Matcher m = NUMBERED.matcher(line);
        return m.find() ? m.group(1) + line.substring(m.end()) : line;
    }

    /** The marker of a numbered line, {@link #NONE} if it has none. */
    static char marker(String line) {
        Matcher m = NUMBERED.matcher(line);
        return m.find() && !m.group(3).isEmpty() ? m.group(3).charAt(0) : NONE;
    }

    /** A line of the report shown left of the column of the numbers, like a heading. */
    static String outside(String text) {
        return UNNUMBERED + text;
    }

    /**
     * Puts the numbers of the lines and their markers in a column on the left, or takes the numbers out and puts
     * the markers back in front of the text.
     *
     * @param show {@code false} to show no numbers
     */
    static List<String> render(List<String> lines, boolean show, Theme theme) {
        int width = 1;
        for (String line : lines) {
            Matcher m = NUMBERED.matcher(line);
            if (m.find()) {
                width = Math.max(width, m.group(2).length());
            }
        }
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            Matcher m = NUMBERED.matcher(line);
            if (line.startsWith(String.valueOf(UNNUMBERED))) {
                result.add(line.substring(1));
            } else if (m.find()) {
                String indent = m.group(1);
                String marker = m.group(3);
                String text = line.substring(m.end());
                // The marker is in a column of its own, left of the indentation, blank for a line without one.
                String code = sign(marker, theme) + " " + indent + styled(marker, text, theme);
                if (!show) {
                    result.add(code);
                    continue;
                }
                String number = m.group(2).equals("0") ? "" : m.group(2);
                String column = theme.style(role(marker), " ".repeat(width - number.length()) + number) + " ";
                result.add(column + code);
            } else {
                result.add(show && !line.isEmpty() ? " ".repeat(width + 1) + line : line);
            }
        }
        return result;
    }

    private static String sign(String marker, Theme theme) {
        return switch (marker) {
            case "+" -> theme.sign(true);
            case "-" -> theme.sign(false);
            case "~" -> theme.modified();
            default -> " ";
        };
    }

    /** The text of an added or removed line in the style of such lines. */
    private static String styled(String marker, String text, Theme theme) {
        return switch (marker) {
            case "+" -> theme.style(Theme.Role.ADDED, text);
            case "-" -> theme.style(Theme.Role.REMOVED, text);
            default -> text;
        };
    }

    /** The color of the number: that of the marker. */
    private static Theme.Role role(String marker) {
        return switch (marker) {
            case "+" -> Theme.Role.ADDED_MARK;
            case "-" -> Theme.Role.REMOVED_MARK;
            case "~" -> Theme.Role.CHANGED_MARK;
            default -> Theme.Role.LINE_NUMBER;
        };
    }
}
