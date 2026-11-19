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

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the expected HTML of the report, as {@link LineNumbers} and the printers make it, e.g.
 * <pre>
 * line(" 3", '~', "  ", tree("├── "), code("public ", strike("int"), " ", added("long"), " ", decl("a")))
 * </pre>
 * The parts are nodes or strings, for text. The spans of the syntax highlighting are left out, see
 * {@link HtmlAssert}.
 */
final class Expect {

    private Expect() {
    }

    /**
     * A numbered line, shown without the column of the numbers.
     *
     * @param marker {@code +}, {@code -}, {@code ~} or a space
     * @param parts  the indentation, the lines of the tree and the {@link #code code}
     */
    static HTML.Tag line(char marker, Object... parts) {
        List<HTML> children = new ArrayList<>();
        children.add(marker == ' ' ? HTML.Text(" ") : mark(marker));
        children.add(HTML.Text(" "));
        children.addAll(nodes(parts));
        return HTML.DIV().children(children).clazz(lineClasses(marker));
    }

    /**
     * A numbered line in the column of the numbers.
     *
     * @param number the number as shown, padded to the width of the column, blank for an unknown line
     * @param marker {@code +}, {@code -}, {@code ~} or a space
     * @param parts  the indentation, the lines of the tree and the {@link #code code}
     */
    static HTML.Tag line(String number, char marker, Object... parts) {
        List<HTML> children = new ArrayList<>();
        children.add(Css.LN.span(number));
        children.add(HTML.Text(" "));
        children.add(marker == ' ' ? HTML.Text(" ") : mark(marker));
        children.add(HTML.Text(" "));
        children.addAll(nodes(parts));
        return HTML.DIV().children(children).clazz(lineClasses(marker));
    }

    private static String[] lineClasses(char marker) {
        return switch (marker) {
            case '+' -> new String[]{"line", "add-line"};
            case '-' -> new String[]{"line", "remove-line"};
            case '~' -> new String[]{"line", "change-line"};
            default -> new String[]{"line"};
        };
    }

    /** A line without a number, indented as the numbered lines. */
    static HTML.Tag plain(Object... parts) {
        return HTML.DIV().children(nodes(parts)).clazz("line");
    }

    /** A line left of the column of the numbers, like the title of a file. */
    static HTML.Tag outside(Object... parts) {
        return HTML.DIV().children(nodes(parts)).clazz("line", "outside");
    }

    /** An empty line. */
    static HTML.Tag blank() {
        return HTML.BR();
    }

    /** The heading of a section. */
    static HTML.Tag heading(String text) {
        return outside(Css.HEADING_MARK.span("#"), " ", Css.HEADING.span(text));
    }

    /** The remark of a section without changes. */
    static HTML.Tag noChanges() {
        return plain("  ", Css.NO_CHANGES.span("NO CHANGES"));
    }

    /** The line between the files, as wide as by default. */
    static HTML.Tag separator() {
        return outside(tree("─".repeat(Reporter.DEFAULT_WIDTH)));
    }

    /** The title of a file: its mark, a space and the parts. */
    static HTML.Tag fileTitle(char mark, Object... parts) {
        List<Object> all = new ArrayList<>();
        all.add(mark(mark));
        all.add(" ");
        all.addAll(List.of(parts));
        return outside(all.toArray());
    }

    /** The mark of a line or of the title of a file: {@code +}, {@code -}, {@code ~} or {@code →}. */
    static HTML.Tag mark(char mark) {
        return switch (mark) {
            case '+' -> Css.ADD_MARK.span("+");
            case '-' -> Css.REMOVE_MARK.span("-");
            case '~', '→' -> Css.CHANGE_MARK.span(String.valueOf(mark));
            default -> throw new IllegalArgumentException("No mark: " + mark);
        };
    }

    /** The text of a numbered line. */
    static HTML.Tag code(Object... parts) {
        return span(Css.CODE, parts);
    }

    /** The lines of a tree. */
    static HTML.Tag tree(String text) {
        return Css.TREE.span(text);
    }

    static HTML.Tag strike(Object... parts) {
        return span(Css.STRIKE, parts);
    }

    static HTML.Tag added(Object... parts) {
        return span(Css.ADDED, parts);
    }

    /** Code shown as it is now, which differs from the old one by a rename; its title tells what it was. */
    static HTML.Tag renamed(String oldText, String newText, Object... parts) {
        return span(Css.RENAMED, parts).attr("title", oldText + " \u2192 " + newText);
    }

    /** The name of a declaration. */
    static HTML.Tag decl(Object... parts) {
        return span(Css.DECLARATION, parts);
    }

    static HTML.Tag error(Object... parts) {
        return span(Css.ERROR, parts);
    }

    static HTML.Tag comment(Object... parts) {
        return span(Css.COMMENT, parts);
    }

    static HTML.Tag note(Object... parts) {
        return span(Css.NOTE, parts);
    }

    /** The name of the class of a file, or of an added or removed type. */
    static HTML.Tag name(Object... parts) {
        return span(Css.TITLE, parts);
    }

    /** The package of a file, or of an added or removed type. */
    static HTML.Tag pkg(Object... parts) {
        return span(Css.PACKAGE, parts);
    }

    /** The tag of the level of a change, e.g. {@code tag("impl")} for {@code [impl]}. */
    static HTML.Tag tag(String level) {
        return Css.TAG.span("[" + level + "]");
    }

    /** A span of the class. */
    static HTML.Tag span(Css css, Object... parts) {
        return HTML.SPAN().children(nodes(parts)).clazz(css.cssClass());
    }

    /** The parts as nodes: strings as text. */
    static List<HTML> nodes(Object... parts) {
        List<HTML> nodes = new ArrayList<>();
        for (Object part : parts) {
            switch (part) {
                case String s -> nodes.add(HTML.Text(s));
                case HTML h -> nodes.add(h);
                default -> throw new IllegalArgumentException("Not a node: " + part);
            }
        }
        return nodes;
    }
}
