/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.util;

import java.io.IOException;
import java.io.PushbackInputStream;

/**
 * Access to the controlling terminal through {@code stty}, which keeps the project free of terminal libraries.
 * An instance is a terminal switched to the mode in which single key presses can be read; closing it restores
 * the previous settings.
 */
public final class Terminal implements AutoCloseable {

    /** Key code of the escape key. */
    public static final int ESC = 27;
    /** Moves the cursor home and clears the screen and the scrollback. */
    public static final String CLEAR = "\033[H\033[2J\033[3J";
    /** Returns to the start of the line and erases it. */
    public static final String ERASE_LINE = "\r\033[K";

    private static final int DEFAULT_HEIGHT = 24;
    private static final int DEFAULT_WIDTH = 80;

    private final String saved;
    private final Thread restoreHook;

    private Terminal(String saved) {
        this.saved = saved;
        // Ctrl+C ends the JVM without closing the terminal, so restore from a hook too.
        this.restoreHook = new Thread(() -> restore(saved));
        Runtime.getRuntime().addShutdownHook(restoreHook);
    }

    /**
     * Switches the terminal to reading single key presses without echo.
     *
     * @return the terminal, to be closed to restore the settings
     * @throws IOException if there is no controlling terminal
     */
    public static Terminal enterRaw() throws IOException {
        String saved = stty("-g").trim();
        Terminal terminal = new Terminal(saved);
        try {
            stty("-icanon", "-echo", "min", "1");
        } catch (IOException e) {
            terminal.close();
            throw e;
        }
        return terminal;
    }

    @Override
    public void close() {
        restore(saved);
        try {
            Runtime.getRuntime().removeShutdownHook(restoreHook);
        } catch (IllegalStateException ignored) {
            // shutdown already in progress; the hook does the restoring
        }
    }

    /** The number of rows of the terminal. */
    public static int height() {
        return sizePart(0, DEFAULT_HEIGHT);
    }

    /** The number of columns of the terminal. */
    public static int width() {
        return sizePart(1, DEFAULT_WIDTH);
    }

    private static int sizePart(int index, int fallback) {
        try {
            int value = Integer.parseInt(stty("size").trim().split("\\s+")[index]);
            return value > 0 ? value : fallback;
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Reads one key press. A lone escape gives {@link #ESC}; escape sequences of the arrow and function
     * keys are skipped as a whole and the next key is read instead.
     *
     * @param in the input, which must be able to push back one byte
     * @return the key, or -1 at the end of the input
     * @throws IOException if reading fails
     */
    public static int readKey(PushbackInputStream in) throws IOException {
        while (true) {
            int key = in.read();
            if (key != ESC || in.available() == 0) {
                return key;
            }
            int next = in.read();
            if (next != '[' && next != 'O') {
                in.unread(next);
                return ESC;
            }
            int last;
            do {
                last = in.read();
            } while (last != -1 && (last < 0x40 || last > 0x7E));
        }
    }

    private static void restore(String saved) {
        try {
            stty(saved);
        } catch (IOException ignored) {
            // nothing more can be done to restore the terminal
        }
    }

    /** Runs {@code stty} against the controlling terminal and returns its output. */
    private static String stty(String... args) throws IOException {
        String command = "stty " + String.join(" ", args) + " < /dev/tty";
        Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        try {
            if (process.waitFor() != 0) {
                throw new IOException("stty failed: " + output.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        return output;
    }
}
