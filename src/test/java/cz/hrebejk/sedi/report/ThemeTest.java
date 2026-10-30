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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link Theme}.
 */
public class ThemeTest extends TestCase {

    private static final String E = "\u001b[";

    /** A theme with no style but the given ones, as pairs of a role name and its attributes. */
    static Theme only(String... roleAndSpec) {
        Map<String, String> specs = new HashMap<>();
        Arrays.stream(Theme.Role.values()).forEach(r -> specs.put(r.name().toLowerCase(), "none"));
        for (int i = 0; i < roleAndSpec.length; i += 2) {
            specs.put(roleAndSpec[i], roleAndSpec[i + 1]);
        }
        return Theme.of(specs);
    }

    public void testStyleAttributes() {
        Theme.Style style = Theme.Style.parse("#ff8000 bg:#000102 bold underline strike");
        assertEquals(E + "38;2;255;128;0m" + E + "48;2;0;1;2m" + E + "1m" + E + "4m" + E + "9mx"
                + E + "29m" + E + "24m" + E + "22m" + E + "49m" + E + "39m", style.apply("x"));
    }

    public void testReplacementUnderlined() {
        assertEquals(E + "9mold" + E + "29m " + E + "4mnew" + E + "24m",
                only("strike", "strike", "replacement", "underline").replace("old", "new"));
        assertEquals("~~old~~ new", Theme.PLAIN.replace("old", "new"));
    }

    public void testNoneLeavesTextAsItIs() {
        assertEquals("x", Theme.Style.parse("none").apply("x"));
    }

    public void testNestedStyleRestoresOuterColor() {
        Theme.Style outer = Theme.Style.parse("fg:#010101");
        Theme.Style inner = Theme.Style.parse("#020202");
        assertEquals(E + "38;2;1;1;1ma " + E + "38;2;2;2;2mb" + E + "38;2;1;1;1m c" + E + "39m",
                outer.apply("a " + inner.apply("b") + " c"));
    }

    public void testBadColor() {
        try {
            Theme.Style.parse("#12345");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testUnknownRole() {
        try {
            Theme.of(Map.of("nothing", "bold"));
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testCodeRoles() {
        Theme theme = only("keyword", "#000001", "type", "#000002", "method", "#000003", "string", "#000004",
                "number", "#000005", "constant", "#000006", "annotation", "#000007");
        assertEquals(c(1, "return") + " " + c(2, "Math") + "." + c(3, "max") + "(" + c(5, "1") + ", " + c(6, "MAX")
                + ", " + c(4, "\"a\\\"b\"") + ", x);", theme.code("return Math.max(1, MAX, \"a\\\"b\", x);"));
        assertEquals(c(7, "@Deprecated") + " " + c(1, "void") + " " + c(3, "m") + "()",
                theme.code("@Deprecated void m()"));
    }

    public void testStruckTextIsNotHighlighted() {
        Theme theme = only("keyword", "#000001", "strike", "strike");
        assertEquals(E + "9mint" + E + "29m " + c(1, "long"), theme.code(theme.strike("int") + " long"));
    }

    public void testPlain() {
        assertEquals("~~a~~", Theme.PLAIN.strike("a"));
        assertEquals("int a", Theme.PLAIN.code("int a"));
        assertEquals("+ a", Theme.PLAIN.changed(true, "+ a"));
    }

    public void testLoad() throws Exception {
        Path file = Files.createTempFile("theme", ".properties");
        try {
            Files.writeString(file, "keyword = #0000ff bold\n");
            Theme theme = Theme.load(file);
            assertEquals(E + "38;2;0;0;255m" + E + "1mif" + E + "22m" + E + "39m",
                    theme.style(Theme.Role.KEYWORD, "if"));
            assertTrue(theme.style(Theme.Role.TYPE, "T").startsWith(E + "38;2;"));
        } finally {
            Files.delete(file);
        }
    }

    private static String c(int color, String text) {
        return E + "38;2;0;0;" + color + "m" + text + E + "39m";
    }
}
