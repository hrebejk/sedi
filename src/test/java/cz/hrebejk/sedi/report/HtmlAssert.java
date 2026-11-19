/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import junit.framework.Assert;
import org.maraboo.fleck.html.HTML;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Compares the HTML of the report by its content: the names, attributes and children of the elements, the text
 * of adjacent text nodes taken together. The spans of the syntax highlighting are left out of the comparison,
 * unless it is {@linkplain #assertHtmlExact exact}, so the expected trees need not repeat them.
 */
final class HtmlAssert {

    private HtmlAssert() {
    }

    /** The lines of the report: its {@code div.line} and {@code br} elements, also those in the folds. */
    static List<HTML> lines(HTML.Tag report) {
        List<HTML> lines = new ArrayList<>();
        addLines(report.children, lines);
        return lines;
    }

    private static void addLines(List<HTML> nodes, List<HTML> lines) {
        for (HTML node : nodes) {
            if (node instanceof HTML.Tag tag && Css.FOLD.on(tag)) {
                addLines(tag.children, lines);
            } else {
                lines.add(node);
            }
        }
    }

    /** The report has exactly the lines. */
    static void assertLines(List<? extends HTML> expected, HTML.Tag report) {
        assertLines(expected, lines(report));
    }

    /** The lines are equal. */
    static void assertLines(List<? extends HTML> expected, List<? extends HTML> actual) {
        List<HTML> e = normalize(expected, false);
        List<HTML> a = normalize(actual, false);
        if (!equal(e, a)) {
            Assert.assertEquals(markup(e), markup(a));
            Assert.fail("Different elements, same markup:\n" + markup(a));
        }
    }

    /** The nodes are equal, but for the spans of the syntax highlighting. */
    static void assertHtml(HTML expected, HTML actual) {
        assertLines(List.of(expected), List.of(actual));
    }

    /** The nodes are equal, the spans of the syntax highlighting included. */
    static void assertHtmlExact(HTML expected, HTML actual) {
        List<HTML> e = normalize(List.of(expected), true);
        List<HTML> a = normalize(List.of(actual), true);
        if (!equal(e, a)) {
            Assert.assertEquals(markup(e), markup(a));
            Assert.fail("Different elements, same markup:\n" + markup(a));
        }
    }

    /** The fragment has the nodes. */
    static void assertFragment(List<? extends HTML> expected, Fragment actual) {
        assertLines(expected, actual.nodes());
    }

    /** One of the lines equals the expected line. */
    static void assertContains(HTML expected, List<? extends HTML> lines) {
        Assert.assertTrue("No line\n" + markup(normalize(List.of(expected), false)) + "\nin\n"
                + markup(normalize(lines, false)), contains(expected, lines));
    }

    /** One of the lines of the report equals the expected line. */
    static void assertContains(HTML expected, HTML.Tag report) {
        assertContains(expected, lines(report));
    }

    /** Whether one of the lines equals the expected line. */
    static boolean contains(HTML expected, List<? extends HTML> lines) {
        List<HTML> e = normalize(List.of(expected), false);
        return lines.stream().anyMatch(l -> equal(e, normalize(List.of(l), false)));
    }

    /** The text of every line, without markup. */
    static List<String> texts(List<? extends HTML> lines) {
        return lines.stream().map(HTML::getInnerText).toList();
    }

    /** The text of the whole report, its lines separated by new lines. */
    static String text(HTML.Tag report) {
        return String.join("\n", texts(lines(report)));
    }

    /** Whether some node in the lines is a span of the class with the text. */
    static boolean hasSpan(List<? extends HTML> lines, Css css, String text) {
        for (HTML line : lines) {
            if (hasSpan(line, css, text)) {
                return true;
            }
        }
        return false;
    }

    /** Whether some node in the lines is a span of the class. */
    static boolean hasClass(List<? extends HTML> lines, Css css) {
        for (HTML line : lines) {
            if (line instanceof HTML.Tag tag && (css.on(tag) || hasClass(tag.children, css))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSpan(HTML node, Css css, String text) {
        if (node instanceof HTML.Tag tag) {
            if (css.on(tag) && tag.getInnerText().equals(text)) {
                return true;
            }
            return hasSpan(tag.children, css, text);
        }
        return false;
    }

    /**
     * The nodes as they are compared: the spans of the syntax highlighting replaced by their children unless
     * {@code exact}, adjacent text nodes merged, empty ones left out.
     */
    static List<HTML> normalize(List<? extends HTML> nodes, boolean exact) {
        List<HTML> flat = new ArrayList<>();
        for (HTML node : nodes) {
            switch (node) {
                case HTML.Text t -> flat.add(t);
                case HTML.Tag t when !exact && isHighlight(t) -> flat.addAll(normalize(t.children, false));
                case HTML.Tag t -> flat.add(new HTML.Tag(t.tagName).attributes(t.attrs)
                        .children(normalize(t.children, exact)));
            }
        }
        List<HTML> merged = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (HTML node : flat) {
            if (node instanceof HTML.Text t) {
                text.append(t.text);
            } else {
                if (!text.isEmpty()) {
                    merged.add(HTML.Text(text.toString()));
                    text.setLength(0);
                }
                merged.add(node);
            }
        }
        if (!text.isEmpty()) {
            merged.add(HTML.Text(text.toString()));
        }
        return merged;
    }

    private static boolean isHighlight(HTML.Tag tag) {
        for (Css css : Css.values()) {
            if (css.isHighlight() && css.on(tag)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the normalized nodes are equal. */
    private static boolean equal(List<HTML> a, List<HTML> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!equal(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean equal(HTML a, HTML b) {
        return switch (a) {
            case HTML.Text ta -> b instanceof HTML.Text tb && ta.text.equals(tb.text);
            case HTML.Tag ta -> b instanceof HTML.Tag tb && ta.tagName.equalsIgnoreCase(tb.tagName)
                    && ta.attrs.equals(tb.attrs) && equal(new ArrayList<>(ta.children), new ArrayList<>(tb.children));
        };
    }

    /** The nodes as markup, one line for every node, for the messages of the failed comparisons. */
    static String markup(List<? extends HTML> nodes) {
        return nodes.stream().map(HtmlAssert::markup).collect(Collectors.joining("\n"));
    }

    static String markup(HTML node) {
        StringBuilder sb = new StringBuilder();
        markup(node, sb);
        return sb.toString();
    }

    private static void markup(HTML node, StringBuilder sb) {
        switch (node) {
            case HTML.Text t -> sb.append(t.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
            case HTML.Tag t -> {
                String name = t.tagName.toLowerCase();
                sb.append('<').append(name);
                new TreeMap<>(t.attrs).forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(v).append('"'));
                sb.append('>');
                if (!name.equals("br")) {
                    t.children.forEach(c -> markup(c, sb));
                    sb.append("</").append(name).append('>');
                }
            }
        }
    }
}
