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
 * The line numbers of the report, shown in a column on the left of the lines, not indented with the code. The
 * printers {@link #number number} the lines they make; the report puts the numbers in the column at last, when the
 * width of the column is known.
 */
final class LineNumbers {

    private static final char MARK = '\u0000';
    private static final char UNNUMBERED = '\u0001';
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)" + MARK + "(\\d+)" + MARK);

    private LineNumbers() {
    }

    /**
     * The line of the report numbered by a line of the source. Spaces may be put in front of it.
     *
     * @param line the line of the source, {@code 0} if not known: the line is not numbered
     */
    static String number(long line, String text) {
        return line <= 0 ? text : MARK + String.valueOf(line) + MARK + text;
    }

    /** A line of the report shown left of the column of the numbers, like a heading. */
    static String outside(String text) {
        return UNNUMBERED + text;
    }

    /**
     * Puts the numbers of the lines in a column on the left, or just takes them out.
     *
     * @param show {@code false} to show no numbers
     */
    static List<String> render(List<String> lines, boolean show, Theme theme) {
        int width = 0;
        for (String line : lines) {
            Matcher m = NUMBERED.matcher(line);
            if (m.find()) {
                width = Math.max(width, m.group(2).length());
            }
        }
        boolean column = show && width > 0;
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            Matcher m = NUMBERED.matcher(line);
            if (line.startsWith(String.valueOf(UNNUMBERED))) {
                result.add(line.substring(1));
            } else if (m.find()) {
                String rest = m.group(1) + line.substring(m.end());
                result.add(column ? theme.style(Theme.Role.LINE_NUMBER, pad(m.group(2), width)) + " " + rest : rest);
            } else {
                result.add(column && !line.isEmpty() ? " ".repeat(width + 1) + line : line);
            }
        }
        return result;
    }

    private static String pad(String number, int width) {
        return " ".repeat(width - number.length()) + number;
    }
}
