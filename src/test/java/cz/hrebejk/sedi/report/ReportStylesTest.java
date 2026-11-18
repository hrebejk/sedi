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
import org.maraboo.fleck.css.CssValues;
import org.maraboo.fleck.css.Palette;
import org.maraboo.fleck.css.Style;
import org.maraboo.fleck.css.StyleSheet;
import org.maraboo.fleck.html.HTML;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static cz.hrebejk.sedi.report.Expect.*;

/**
 * Unit tests for {@link ReportStyles}.
 */
public class ReportStylesTest extends TestCase {

    private static final Palette PALETTE = ReportStyles.palette();

    /** The computed style of the last element of the path, each element the child of the one before it. */
    private static Style style(StyleSheet styleSheet, HTML.Tag... path) {
        Style style = null;
        for (HTML.Tag tag : path) {
            style = styleSheet.computeStyle(tag, style);
        }
        return style;
    }

    private static CssValues.Color color(String hex) {
        return new CssValues.Color(hex);
    }

    public void testCodeColors() {
        StyleSheet ss = ReportStyles.styleSheet(PALETTE);
        HTML.Tag keyword = span(Css.KEYWORD, "int");
        HTML.Tag code = code(keyword);
        HTML.Tag ln = line(" 1", '~', code);
        assertEquals(PALETTE.get("keyword"), style(ss, ln, code, (HTML.Tag) code.children.getFirst()).foreground());
    }

    /** Struck through text in added text has both decorations and the background of the struck through text. */
    public void testStrikeInAdded() {
        StyleSheet ss = ReportStyles.styleSheet(PALETTE);
        HTML.Tag added = added(strike("x"));
        HTML.Tag strike = (HTML.Tag) added.children.getFirst();
        Style style = style(ss, added, strike);
        assertEquals(Set.of(CssValues.TextDecoration.UNDERLINE, CssValues.TextDecoration.STRIKETHROUGH),
                style.textDecorations());
        assertEquals(PALETTE.get("strike-background"), style.background());
        assertEquals(PALETTE.get("added-background"), style(ss, added).background());
    }

    /** The number of a line has the color of the mark of the line. */
    public void testNumberHasColorOfMark() {
        StyleSheet ss = ReportStyles.styleSheet(PALETTE);
        for (char mark : new char[]{'+', '-', '~', ' '}) {
            HTML.Tag line = line(" 1", mark, code("x"));
            HTML.Tag number = (HTML.Tag) line.children.getFirst();
            String expected = switch (mark) {
                case '+' -> "added-mark";
                case '-' -> "removed-mark";
                case '~' -> "changed-mark";
                default -> "line-number";
            };
            assertEquals(String.valueOf(mark), PALETTE.get(expected), style(ss, line, number).foreground());
        }
    }

    public void testParse() {
        Style style = new Style();
        ReportStyles.parse("#ff8000 bg:#000102 bold underline strike", style);
        assertEquals(color("#ff8000"), style.foreground());
        assertEquals(color("#000102"), style.background());
        assertEquals(Set.of(CssValues.FontStyle.BOLD), style.fontStyles());
        assertEquals(Set.of(CssValues.TextDecoration.UNDERLINE, CssValues.TextDecoration.STRIKETHROUGH),
                style.textDecorations());
    }

    public void testNoneLeavesStyleUnset() {
        StyleSheet ss = ReportStyles.styleSheet(PALETTE, Map.of("keyword", "none"));
        assertNull(style(ss, span(Css.KEYWORD, "int")).foreground());
    }

    public void testBadColor() {
        try {
            ReportStyles.parse("#12345", new Style());
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testUnknownRole() {
        try {
            ReportStyles.styleSheet(PALETTE, Map.of("nothing", "bold"));
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testLoad() throws Exception {
        Path file = Files.createTempFile("theme", ".properties");
        try {
            Files.writeString(file, "keyword = #0000ff bold\nreplacement = bg:#010203\n");
            StyleSheet ss = ReportStyles.load(file);
            Style keyword = style(ss, span(Css.KEYWORD, "if"));
            assertEquals(color("#0000ff"), keyword.foreground());
            assertEquals(Set.of(CssValues.FontStyle.BOLD), keyword.fontStyles());
            Style added = style(ss, added("x"));
            assertEquals(color("#010203"), added.background());
            // The replaced style replaces the whole default one.
            assertTrue(added.textDecorations().isEmpty());
            assertEquals(PALETTE.get("type"), style(ss, span(Css.TYPE, "T")).foreground());
        } finally {
            Files.delete(file);
        }
    }

    /** Without colors, the changes are shown by marks around them. */
    public void testPlain() {
        StyleSheet ss = ReportStyles.plain();
        HTML.Tag strike = strike("a");
        assertEquals("~~", ss.computeStyle(strike, org.maraboo.fleck.css.Selector.PseudoElement.BEFORE,
                ss.computeStyle(strike, null)).content());
        HTML.Tag added = added("a");
        assertEquals("__", ss.computeStyle(added, org.maraboo.fleck.css.Selector.PseudoElement.AFTER,
                ss.computeStyle(added, null)).content());
    }
}
