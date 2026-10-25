/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/**
 * Shows lines page by page on an interactive terminal, clearing the screen before the first page
 * and waiting for a key between pages (later pages just scroll the console; there is no wait after
 * the last page). When the output is not a terminal everything is simply printed.
 * <p>
 * Single key reading and the terminal height are obtained through {@code stty}, which keeps the
 * project free of terminal libraries.
 */
final class Pager {

    private static final int DEFAULT_HEIGHT = 24;
    private static final String CLEAR = "\033[H\033[2J\033[3J";
    private static final String ERASE_LINE = "\r\033[K";

    private final PrintStream out;
    private final boolean interactive;

    /**
     * Creates a pager.
     *
     * @param out         where to print
     * @param interactive {@code true} if the output is a terminal; otherwise lines are printed as they are
     */
    Pager(PrintStream out, boolean interactive) {
        this.out = out;
        this.interactive = interactive;
    }

    /**
     * Shows the lines.
     *
     * @param lines the lines to show
     * @return {@code false} if the user asked to quit, {@code true} otherwise
     */
    boolean show(List<String> lines) {
        if (!interactive) {
            lines.forEach(out::println);
            return true;
        }
        int pageSize = Math.max(1, terminalHeight() - 1);
        int pages = Math.max(1, (lines.size() + pageSize - 1) / pageSize);
        out.print(CLEAR);
        for (int page = 0; page < pages; page++) {
            lines.subList(page * pageSize, Math.min(lines.size(), (page + 1) * pageSize)).forEach(out::println);
            if (page + 1 == pages) {
                out.flush();
                break;
            }
            out.print("-- page " + (page + 1) + "/" + pages + ", q: quit, any other key: next page --");
            out.flush();
            boolean quit = readKey() == 'q';
            out.print(ERASE_LINE);
            if (quit) {
                return false;
            }
        }
        return true;
    }

    private static int terminalHeight() {
        try {
            String size = stty("size").trim();
            return Integer.parseInt(size.substring(0, size.indexOf(' ')));
        } catch (IOException | RuntimeException e) {
            return DEFAULT_HEIGHT;
        }
    }

    /** Reads a single key press without waiting for enter; returns -1 at the end of input. */
    private static int readKey() {
        String saved = null;
        try {
            saved = stty("-g").trim();
            stty("-icanon", "-echo", "min", "1");
            return System.in.read();
        } catch (IOException e) {
            return -1;
        } finally {
            if (saved != null) {
                try {
                    stty(saved);
                } catch (IOException ignored) {
                    // nothing more can be done to restore the terminal
                }
            }
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
