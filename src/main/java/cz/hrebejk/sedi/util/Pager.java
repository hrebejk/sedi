/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.util;

import java.io.PrintStream;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Shows lines page by page on an interactive terminal, clearing the screen before the first page
 * and waiting for a key between pages (later pages just scroll the console). The last line of the output
 * is always the {@link KeyHint}. When the output is not a terminal everything is simply printed.
 */
public final class Pager {

    /** The keys that interrupt paging and are handed to the caller: the categories, javadoc, additions, deletions, tests, grouping and help. */
    public static final String HOT_KEYS = "aicmd+-tgh";

    /** How showing the lines ended. */
    public enum Kind {
        /** All the lines were shown. */
        DONE,
        /** The user asked to quit. */
        QUIT,
        /** The user pressed one of the {@link #HOT_KEYS}. */
        KEY
    }

    /**
     * The result of showing the lines.
     *
     * @param kind how showing ended
     * @param key  the key pressed for {@link Kind#KEY}, otherwise 0
     */
    public record Outcome(Kind kind, int key) {
        /** All the lines were shown. */
        public static final Outcome DONE = new Outcome(Kind.DONE, 0);
        /** The user asked to quit. */
        public static final Outcome QUIT = new Outcome(Kind.QUIT, 0);
    }

    private final PrintStream out;
    private final boolean interactive;
    private final boolean colored;
    private final IntSupplier keySource;

    /**
     * Creates a pager.
     *
     * @param out         where to print
     * @param interactive {@code true} if the output is a terminal; otherwise lines are printed as they are
     * @param colored     whether the key hint may use colors
     * @param keySource   supplies the next key press, -1 at the end of the input
     */
    public Pager(PrintStream out, boolean interactive, boolean colored, IntSupplier keySource) {
        this.out = out;
        this.interactive = interactive;
        this.colored = colored;
        this.keySource = keySource;
    }

    /**
     * Shows the lines.
     *
     * @param lines the lines to show
     * @return how showing ended
     */
    public Outcome show(List<String> lines) {
        if (!interactive) {
            lines.forEach(out::println);
            return Outcome.DONE;
        }
        int pageSize = Math.max(1, Terminal.height() - 1);
        int width = Terminal.width();
        int pages = Math.max(1, (lines.size() + pageSize - 1) / pageSize);
        out.print(Terminal.CLEAR);
        for (int page = 0; page < pages; page++) {
            lines.subList(page * pageSize, Math.min(lines.size(), (page + 1) * pageSize)).forEach(out::println);
            if (page + 1 == pages) {
                out.print(KeyHint.render(width, colored));
                out.flush();
                break;
            }
            String prompt = "-- page " + (page + 1) + "/" + pages + " -- ";
            out.print(prompt + KeyHint.render(width - prompt.length(), colored));
            out.flush();
            int key = keySource.getAsInt();
            out.print(Terminal.ERASE_LINE);
            if (key == 'q' || key == -1) {
                return Outcome.QUIT;
            }
            if (HOT_KEYS.indexOf(key) >= 0) {
                return new Outcome(Kind.KEY, key);
            }
        }
        return Outcome.DONE;
    }
}
