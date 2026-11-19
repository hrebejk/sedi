/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import org.maraboo.fleck.html.HTML;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The line numbers of the report, shown in a column on the left of the lines, not indented with the code. Right
 * after the number comes the marker of the line, {@code +}, {@code -} or {@code ~}, and the number has the class of
 * the line, so it can be colored as the marker.
 */
final class LineNumbers {

    private LineNumbers() {
    }

    /**
     * Makes the elements of the lines: puts the numbers of the lines in a column on the left, followed by their
     * markers, or leaves the numbers out and keeps the markers.
     *
     * @param show {@code false} to show no numbers
     * @return a {@code div.line} for every line, a {@code br} for an empty one, the lines of a fold in a
     *         {@code div.fold} named by {@code data-name}, with {@code data-open="false"} if collapsed at first
     */
    static List<HTML.Tag> render(List<Line> lines, boolean show) {
        int width = 1;
        for (Line line : lines) {
            if (line.kind() == Line.Kind.NUMBERED) {
                width = Math.max(width, String.valueOf(line.number()).length());
            }
        }
        List<HTML.Tag> result = new ArrayList<>();
        // The elements of the folds started and not ended yet, with their names.
        Deque<List<HTML.Tag>> outer = new ArrayDeque<>();
        Deque<Line> folds = new ArrayDeque<>();
        for (Line line : lines) {
            if (line.kind() == Line.Kind.FOLD || line.kind() == Line.Kind.FOLDED) {
                outer.push(result);
                folds.push(line);
                result = new ArrayList<>();
                continue;
            }
            if (line.kind() == Line.Kind.END) {
                Line start = folds.pop();
                HTML.Tag fold = HTML.DIV().children(result).clazz(Css.FOLD.cssClass())
                        .attr("data-name", start.content().text());
                if (start.kind() == Line.Kind.FOLDED) {
                    fold.attr("data-open", "false");
                }
                result = outer.pop();
                result.add(fold);
                continue;
            }
            HTML.Tag element = switch (line.kind()) {
                case BLANK -> HTML.BR();
                case OUTSIDE -> div(line.prefix().plus(line.content()), Css.LINE, Css.OUTSIDE);
                case PLAIN -> div(Fragment.of(show && !line.content().isEmpty() ? " ".repeat(width + 1) : "")
                        .plus(line.prefix()).plus(line.content()), Css.LINE);
                case NUMBERED -> numbered(line, show, width);
                case FOLD, FOLDED, END -> throw new AssertionError(line);
            };
            // A line without text has nothing for the console to show: it is an empty line.
            result.add(element.getInnerText().isBlank() ? HTML.BR() : element);
        }
        if (!outer.isEmpty()) {
            throw new IllegalArgumentException("A fold without its end: " + folds.peek().content().text());
        }
        return result;
    }

    private static HTML.Tag numbered(Line line, boolean show, int width) {
        Fragment out = Fragment.EMPTY;
        if (show) {
            String number = line.number() == 0 ? "" : String.valueOf(line.number());
            out = Fragment.of(Css.LN.span(" ".repeat(width - number.length()) + number)).plus(" ");
        }
        // The marker is in a column of its own, left of the indentation, blank for a line without one.
        out = out.plus(sign(line.marker())).plus(" ").plus(line.prefix());
        if (!line.content().isEmpty()) {
            out = out.plus(Fragment.of(Css.CODE.span(line.content())));
        }
        return switch (line.marker()) {
            case '+' -> div(out, Css.LINE, Css.ADD_LINE);
            case '-' -> div(out, Css.LINE, Css.REMOVE_LINE);
            case '~' -> div(out, Css.LINE, Css.CHANGE_LINE);
            default -> div(out, Css.LINE);
        };
    }

    private static HTML.Tag div(Fragment content, Css... classes) {
        String[] names = new String[classes.length];
        for (int i = 0; i < classes.length; i++) {
            names[i] = classes[i].cssClass();
        }
        return HTML.DIV().children(content.nodes()).clazz(names);
    }

    private static Fragment sign(char marker) {
        return switch (marker) {
            case '+' -> Markup.sign(true);
            case '-' -> Markup.sign(false);
            case '~' -> Markup.modified();
            default -> Fragment.of(" ");
        };
    }
}
