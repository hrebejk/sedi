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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

/**
 * Unit tests for {@link Pager} on a stream that is not a terminal.
 */
public class PagerTest extends TestCase {

    /** Tests that all lines are printed plainly, without the screen clearing or the hint. */
    public void testNonInteractive() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Pager pager = new Pager(new PrintStream(bytes, true), false, false, () -> {
            throw new AssertionError("no key should be read");
        });
        assertEquals(Pager.Outcome.DONE, pager.show(List.of("one", "two")));
        assertEquals("one" + System.lineSeparator() + "two" + System.lineSeparator(), bytes.toString());
    }
}
