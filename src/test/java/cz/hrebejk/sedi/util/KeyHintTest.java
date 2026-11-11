/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.util;

import junit.framework.TestCase;

/**
 * Unit tests for {@link KeyHint}.
 */
public class KeyHintTest extends TestCase {

    private static String strip(String s) {
        return s.replaceAll("\033\\[[0-9;]*m", "");
    }

    /** Tests that everything is shown when there is room. */
    public void testFull() {
        assertEquals("[a] api  [i] impl  [c] cosmetic  [m] imports  [d] javadoc  [+] added  [-] removed  [q] quit  [h] help", KeyHint.render(200, false));
    }

    /** Tests that labels are shortened before items are dropped. */
    public void testShortLabels() {
        assertEquals("[a] api  [i] impl  [c] cos  [m] imp  [d] doc  [+] add  [-] rem  [q] quit  [h] help", KeyHint.render(82, false));
    }

    /** Tests that items are dropped, marked with an ellipsis, and that help stays last. */
    public void testDroppedItems() {
        String line = KeyHint.render(40, false);
        assertTrue(line, line.length() <= 40);
        assertTrue(line, line.contains("…"));
        assertTrue(line, line.endsWith("[h] help"));
        assertTrue(line, line.startsWith("[a] api"));
    }

    /** Tests that help is shown even when nothing else fits. */
    public void testOnlyHelp() {
        assertEquals("[h] help", KeyHint.render(3, false));
    }

    /** Tests that colored output has the same visible width and marks keys with reverse video. */
    public void testColored() {
        for (int width : new int[] {200, 82, 40, 20}) {
            String colored = KeyHint.render(width, true);
            assertTrue(colored.contains("\033[7m"));
            assertEquals(KeyHint.render(width, false).length(), strip(colored).length());
            assertTrue(strip(colored).endsWith(" h  help"));
        }
    }
}
