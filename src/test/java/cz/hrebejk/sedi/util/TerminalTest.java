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

import java.io.ByteArrayInputStream;
import java.io.PushbackInputStream;

/**
 * Unit tests for {@link Terminal#readKey(PushbackInputStream)}.
 */
public class TerminalTest extends TestCase {

    private static PushbackInputStream in(int... bytes) {
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            data[i] = (byte) bytes[i];
        }
        return new PushbackInputStream(new ByteArrayInputStream(data), 1);
    }

    /** Tests plain keys and the end of the input. */
    public void testPlainKeysAndEof() throws Exception {
        PushbackInputStream in = in('a', 'q');
        assertEquals('a', Terminal.readKey(in));
        assertEquals('q', Terminal.readKey(in));
        assertEquals(-1, Terminal.readKey(in));
    }

    /** Tests that a lone escape is reported. */
    public void testLoneEscape() throws Exception {
        assertEquals(Terminal.ESC, Terminal.readKey(in(27)));
    }

    /** Tests that an arrow key sequence is skipped. */
    public void testArrowSkipped() throws Exception {
        assertEquals('x', Terminal.readKey(in(27, '[', 'A', 'x')));
        assertEquals('x', Terminal.readKey(in(27, '[', '1', ';', '5', 'C', 'x')));
    }

    /** Tests that a byte after an escape which does not start a sequence is kept. */
    public void testEscapeFollowedByKey() throws Exception {
        PushbackInputStream in = in(27, 'x');
        assertEquals(Terminal.ESC, Terminal.readKey(in));
        assertEquals('x', Terminal.readKey(in));
    }
}
