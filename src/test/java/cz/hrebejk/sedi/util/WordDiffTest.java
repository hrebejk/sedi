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

import java.util.List;

/**
 * Unit tests for {@link WordDiff}.
 */
public class WordDiffTest extends TestCase {

    /** The new text with the removed words as [-old-] and the added ones as {+new+}. */
    private static String render(String oldText, String newText) {
        List<WordDiff.Change> changes = WordDiff.diff(oldText, newText);
        if (changes == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (WordDiff.Change c : changes) {
            out.append(newText, at, c.start());
            if (!c.removed().isEmpty()) {
                out.append(c.before()).append("[-").append(c.removed()).append("-]").append(c.after());
            }
            if (c.end() > c.start()) {
                out.append("{+").append(newText, c.start(), c.end()).append("+}");
            }
            at = c.end();
        }
        return out.append(newText.substring(at)).toString();
    }

    public void testReplacedWord() {
        assertEquals("\"a [-b-] {+c+} d\"", render("\"a b d\"", "\"a c d\""));
    }

    public void testRemovedWords() {
        assertEquals("a [-b-] c", render("a b c", "a c"));
        assertEquals("\"a b [-c-]\"", render("\"a b c\"", "\"a b\""));
    }

    public void testAddedWordsKeepTheirSpacing() {
        assertEquals("a {+b  c+} d", render("a d", "a b  c d"));
    }

    public void testPunctuationIsAWordOfItsOwn() {
        assertEquals("(a..b, a...b{+; a.. compares a+})", render("(a..b, a...b)", "(a..b, a...b; a.. compares a)"));
    }

    /** Texts with only punctuation and quotes in common are not similar. */
    public void testDissimilar() {
        assertNull(WordDiff.diff("Gone.", "Fresh."));
        assertNull(WordDiff.diff("\"x\"", "\"y\""));
        assertEquals(0.5, WordDiff.similarity("Inner one.", "Inner two."));
    }
}
