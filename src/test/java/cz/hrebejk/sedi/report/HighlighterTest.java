/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import junit.framework.TestCase;
import org.maraboo.fleck.html.HTML;

import java.util.List;

import static cz.hrebejk.sedi.report.Expect.*;
import static cz.hrebejk.sedi.report.HtmlAssert.*;

/**
 * Unit tests for {@link Highlighter}.
 */
public class HighlighterTest extends TestCase {

    /** The fragment in a span, to compare it as one node. */
    private static HTML.Tag all(Fragment fragment) {
        return HTML.SPAN().children(fragment.nodes());
    }

    private static HTML.Tag all(Object... parts) {
        return HTML.SPAN().children(Expect.nodes(parts));
    }

    public void testCodeRoles() {
        assertHtmlExact(all(span(Css.KEYWORD, "return"), " ", span(Css.TYPE, "Math"), ".", span(Css.METHOD, "max"),
                        "(", span(Css.NUMBER, "1"), ", ", span(Css.CONSTANT, "MAX"), ", ",
                        span(Css.STRING, "\"a\\\"b\""), ", x);"),
                all(Highlighter.code("return Math.max(1, MAX, \"a\\\"b\", x);")));
        assertHtmlExact(all(span(Css.ANNOTATION, "@Deprecated"), " ", span(Css.KEYWORD, "void"), " ",
                        span(Css.METHOD, "m"), "()"),
                all(Highlighter.code("@Deprecated void m()")));
    }

    /** A word is a method if a parenthesis follows it, even in the next node. */
    public void testMethodBeforeOtherNode() {
        assertHtmlExact(all(span(Css.METHOD, "m"), " ", added("(", span(Css.NUMBER, "1"), ")")),
                all(Highlighter.code(Fragment.of("m ").plus(Markup.added("(1)")))));
    }

    public void testStruckTextIsNotHighlighted() {
        assertHtmlExact(all(strike("int"), " ", span(Css.KEYWORD, "long")),
                all(Highlighter.code(Markup.strike("int").plus(" long"))));
    }

    /** Error text is not highlighted, also inside added text, and the code after it is. */
    public void testErrorTextIsNotHighlighted() {
        assertHtmlExact(all(error("Missing"), " ", span(Css.KEYWORD, "int")),
                all(Highlighter.code(Markup.error("Missing").plus(" int"))));
        assertHtmlExact(all(added(error("Missing"), " ", span(Css.KEYWORD, "int")), " ", span(Css.KEYWORD, "int")),
                all(Highlighter.code(Markup.added(Markup.error("Missing").plus(" int")).plus(" int"))));
    }

    /** Text already marked as a comment, like a line of a block comment, is not highlighted again. */
    public void testCommentTextIsNotHighlighted() {
        assertHtmlExact(all(span(Css.KEYWORD, "int"), " ", comment("return if not")),
                all(Highlighter.code(Fragment.of("int ").plus(Markup.style(Css.COMMENT, "return if not")))));
    }

    /** A block comment going on over several lines is a comment on each of them. */
    public void testCodeLines() {
        List<Fragment> lines = Highlighter.codeLines("int a; /* one\n two */ int b;\n// c");
        assertEquals(3, lines.size());
        assertHtmlExact(all(span(Css.KEYWORD, "int"), " a; ", comment("/* one")), all(lines.get(0)));
        assertHtmlExact(all(comment(" two */"), " ", span(Css.KEYWORD, "int"), " b;"), all(lines.get(1)));
        assertHtmlExact(all(comment("// c")), all(lines.get(2)));
    }

    /** The highlighting keeps whether the fragment shows a change. */
    public void testKeepsChange() {
        assertTrue(Highlighter.code(Markup.strike("a").plus(" b")).changed());
        assertFalse(Highlighter.code("a b").changed());
    }
}
