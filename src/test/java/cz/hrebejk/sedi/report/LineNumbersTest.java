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

import static cz.hrebejk.sedi.report.HtmlAssert.*;

/**
 * Unit tests for {@link LineNumbers}.
 */
public class LineNumbersTest extends TestCase {

    /** The lines between the start and the end of a fold are in its element, the folds nested as started. */
    public void testFolds() {
        List<HTML.Tag> elements = LineNumbers.render(List.of(
                Line.fold("a"),
                Line.outside(Fragment.of("A")),
                Line.fold("b"),
                Line.number(1, '+', Fragment.of("b")),
                Line.end(),
                Line.end(),
                Line.number(10, '-', Fragment.of("c"))), true);
        assertEquals(2, elements.size());
        HTML.Tag a = elements.getFirst();
        assertTrue(Css.FOLD.on(a));
        assertEquals("a", a.attrs.get("data-name"));
        assertEquals(2, a.children.size());
        HTML.Tag b = (HTML.Tag) a.children.get(1);
        assertEquals("b", b.attrs.get("data-name"));
        // The column of the numbers is as wide for the lines in the folds as for the others.
        assertEquals(List.of(" 1 + b"), texts(b.children));
        assertEquals(List.of("A", " 1 + b", "10 - c"), texts(lines(HTML.DIV().children(elements))));
    }

    public void testFoldWithoutEnd() {
        try {
            LineNumbers.render(List.of(Line.fold("a"), Line.outside(Fragment.of("A"))), true);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }
}
