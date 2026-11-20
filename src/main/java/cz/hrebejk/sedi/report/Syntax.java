/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import cz.hrebejk.sedi.analysis.SyntaxRoles;
import cz.hrebejk.sedi.analysis.SyntaxRoles.Role;
import cz.hrebejk.sedi.analysis.SyntaxRoles.Run;

import java.util.ArrayList;
import java.util.List;

/**
 * The syntax highlighting of code: the {@link SyntaxRoles syntax roles} told by the trees, as spans of their
 * {@link Css} classes. The name of a declaration is in a {@link Css#DECLARATION} span around the span of its role.
 */
final class Syntax {

    private static final int TAB_WIDTH = 4;

    private Syntax() {
    }

    /** The class of the role. */
    static Css css(Role role) {
        return switch (role) {
            case KEYWORD -> Css.KEYWORD;
            case TYPE -> Css.TYPE;
            case METHOD -> Css.METHOD;
            case FIELD -> Css.FIELD;
            case CONSTANT -> Css.CONSTANT;
            case LITERAL -> Css.LITERAL;
            case ANNOTATION -> Css.ANNOTATION;
            case COMMENT -> Css.COMMENT;
        };
    }

    /**
     * The text in the span of its role.
     *
     * @param role        the role, {@code null} for plain text
     * @param declaration whether it is the name of a declaration
     */
    static Fragment span(Role role, boolean declaration, String text) {
        Fragment inner = role == null ? Fragment.of(text) : Markup.style(css(role), text);
        return declaration ? Markup.style(Css.DECLARATION, inner) : inner;
    }

    /** A keyword the report puts in the code, like a modifier of a signature. */
    static Fragment keyword(String text) {
        return Markup.style(Css.KEYWORD, text);
    }

    /** The runs in the spans of their roles. */
    static Fragment fragment(List<Run> runs) {
        Fragment out = Fragment.EMPTY;
        for (Run run : runs) {
            out = out.plus(span(run.role(), run.declaration(), run.text()));
        }
        return out;
    }

    /** The first characters of the runs. */
    static List<Run> prefix(List<Run> runs, int length) {
        List<Run> out = new ArrayList<>();
        int left = length;
        for (Run run : runs) {
            if (left <= 0) {
                break;
            }
            out.add(run.text().length() <= left ? run
                    : new Run(run.text().substring(0, left), run.role(), run.declaration()));
            left -= run.text().length();
        }
        return out;
    }

    /**
     * Code of several lines, highlighted: the tabs expanded, the indentation all its lines have removed, the spaces
     * ending the lines left out. A comment going on over several lines is a comment on each of them.
     *
     * @param runs the code, its lines separated by {@code \n}
     * @return the lines
     */
    static List<Fragment> lines(List<Run> runs) {
        List<List<Run>> lines = new ArrayList<>();
        List<Run> line = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int column = 0;
        for (Run run : runs) {
            for (char c : run.text().toCharArray()) {
                if (c == '\n') {
                    add(line, text, run);
                    lines.add(line);
                    line = new ArrayList<>();
                    column = 0;
                } else if (c == '\t') {
                    int spaces = TAB_WIDTH - column % TAB_WIDTH;
                    text.append(" ".repeat(spaces));
                    column += spaces;
                } else if (c != '\r') {
                    text.append(c);
                    column++;
                }
            }
            add(line, text, run);
        }
        lines.add(line);
        int indent = Integer.MAX_VALUE;
        for (List<Run> l : lines) {
            String s = text(l);
            if (!s.isBlank()) {
                indent = Math.min(indent, s.length() - s.stripLeading().length());
            }
        }
        List<Fragment> out = new ArrayList<>();
        for (List<Run> l : lines) {
            String s = text(l);
            out.add(s.isBlank() ? Fragment.EMPTY : fragment(stripTrailing(drop(l, indent))));
        }
        return out;
    }

    private static void add(List<Run> line, StringBuilder text, Run run) {
        if (!text.isEmpty()) {
            line.add(new Run(text.toString(), run.role(), run.declaration()));
            text.setLength(0);
        }
    }

    private static String text(List<Run> runs) {
        StringBuilder sb = new StringBuilder();
        runs.forEach(r -> sb.append(r.text()));
        return sb.toString();
    }

    /** The runs without their first characters. */
    private static List<Run> drop(List<Run> runs, int length) {
        List<Run> out = new ArrayList<>();
        int left = length;
        for (Run run : runs) {
            if (left >= run.text().length()) {
                left -= run.text().length();
            } else {
                out.add(left == 0 ? run : new Run(run.text().substring(left), run.role(), run.declaration()));
                left = 0;
            }
        }
        return out;
    }

    private static List<Run> stripTrailing(List<Run> runs) {
        List<Run> out = new ArrayList<>(runs);
        while (!out.isEmpty()) {
            Run last = out.getLast();
            String stripped = last.text().stripTrailing();
            if (!stripped.isEmpty()) {
                out.set(out.size() - 1, new Run(stripped, last.role(), last.declaration()));
                break;
            }
            out.removeLast();
        }
        return out;
    }
}
