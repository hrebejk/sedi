/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import cz.hrebejk.sedi.analysis.ChangeLevel;
import cz.hrebejk.sedi.analysis.DiffAnalysis;
import cz.hrebejk.sedi.report.Reporter;
import cz.hrebejk.sedi.util.DirectoryWatcher;
import cz.hrebejk.sedi.util.Pager;
import cz.hrebejk.sedi.util.Terminal;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PushbackInputStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The running application: shows a report, keeps the analysis it was made from and reacts to the keys and
 * to the changes of the watched directory. The keys {@code a i c m} show only the changes of one category,
 * {@code h} shows the help, {@code Esc} leaves it and {@code q} quits. All rendering happens on the thread
 * that calls {@link #run}.
 */
final class Session {

    /** Runs the analysis of the files accepted by the filter. */
    @FunctionalInterface
    interface Analysis {
        DiffAnalysis run(Predicate<String> filter) throws IOException, GitAPIException;
    }

    private sealed interface Event {
        record Key(int key) implements Event { }
        record Changed(Set<String> paths) implements Event { }
    }

    private static final List<String> HELP = List.of(
            "Keys",
            "",
            "  a        show only the API changes",
            "  i        show only the implementation changes",
            "  c        show only the cosmetic changes",
            "  m        show only the changed imports",
            "  h        this help",
            "  Esc      leave the help and show the last output again",
            "  q        quit",
            "  other    next page of a long output");

    private final String idleMessage;
    private final Reporter reporter;
    private final PrintStream out;
    private final boolean interactive;
    private final boolean colored;
    private final Pager pager;
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final Set<String> pending = new LinkedHashSet<>();

    private Analysis analysis;
    private DiffAnalysis last;
    private ChangeLevel only;
    private boolean shown;

    /**
     * Creates a session.
     *
     * @param reporter    renders the analysis as the options of the command line say
     * @param out         where to print
     * @param interactive whether the output is a terminal on which keys can be read
     * @param colored     whether the key hint may use colors
     * @param idleMessage printed after a report when the output is not a terminal
     */
    Session(Reporter reporter, PrintStream out, boolean interactive, boolean colored, String idleMessage) {
        this.idleMessage = idleMessage;
        this.reporter = reporter;
        this.out = out;
        this.interactive = interactive;
        this.colored = colored;
        this.pager = new Pager(out, interactive, colored, this::nextKey);
    }

    /** Starts reading keys from the terminal; the terminal must already be switched to reading single keys. */
    void listenToKeys() {
        Thread reader = new Thread(() -> {
            PushbackInputStream in = new PushbackInputStream(System.in, 1);
            int key;
            do {
                try {
                    key = Terminal.readKey(in);
                } catch (IOException e) {
                    key = -1;
                }
                events.add(new Event.Key(key));
            } while (key != -1);
        }, "keys");
        reader.setDaemon(true);
        reader.start();
    }

    /** Starts watching the directory; the changed files are reported relative to {@code root}. */
    void watch(DirectoryWatcher watcher, Path root) {
        Thread thread = new Thread(() -> {
            try {
                watcher.run(changed -> events.add(new Event.Changed(changed.stream()
                        .map(p -> root.relativize(p).toString().replace(java.io.File.separatorChar, '/'))
                        .collect(Collectors.toSet()))));
            } catch (IOException e) {
                System.err.println("Cannot watch: " + e.getMessage());
            } catch (ClosedWatchServiceException ignored) {
                // the application is ending
            }
        }, "watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Shows the first report and then reacts to keys and changes until the user quits.
     *
     * @param analysis the analysis to run, also for the changes reported later
     * @param filter   the files of the first report
     */
    void run(Analysis analysis, Predicate<String> filter) {
        this.analysis = analysis;
        if (!analyze(filter)) {
            return;
        }
        while (true) {
            if (!pending.isEmpty() && !refreshPending()) {
                return;
            }
            Event event = take();
            if (event == null) {
                return;
            }
            if (event instanceof Event.Changed changed) {
                pending.addAll(changed.paths());
            } else if (!handleKey(((Event.Key) event).key())) {
                return;
            }
        }
    }

    private Event take() {
        try {
            return events.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The next key press; changes of the files met on the way are kept for later. -1 ends the session. */
    private int nextKey() {
        while (true) {
            Event event = take();
            if (event == null) {
                return -1;
            }
            if (event instanceof Event.Key key) {
                return key.key();
            }
            pending.addAll(((Event.Changed) event).paths());
        }
    }

    private boolean refreshPending() {
        Set<String> changed = Set.copyOf(pending);
        pending.clear();
        return analyze(changed::contains);
    }

    /** Analyzes the files, remembers the result and shows it. Returns {@code false} if the user quit. */
    private boolean analyze(Predicate<String> filter) {
        try {
            last = analysis.run(filter);
        } catch (IOException | GitAPIException | IllegalArgumentException e) {
            System.err.println("Cannot analyze changes: " + e.getMessage());
            return true;
        }
        return show();
    }

    /** Shows the last analysis in the current view. Returns {@code false} if the user quit. */
    private boolean show() {
        List<String> lines = only == null ? reporter.report(last) : reporter.reportOnly(last, only);
        if (lines.isEmpty()) {
            if (shown) {
                // keep what is on the screen
                return true;
            }
            lines = List.of("No changes.");
        }
        shown = true;
        if (!interactive) {
            pager.show(lines);
            out.println(idleMessage);
            return true;
        }
        Pager.Outcome outcome = pager.show(lines);
        return switch (outcome.kind()) {
            case QUIT -> false;
            case DONE -> true;
            case KEY -> handleKey(outcome.key());
        };
    }

    /** Reacts to a key pressed while waiting for something to do. Returns {@code false} to quit. */
    private boolean handleKey(int key) {
        switch (key) {
            case -1, 'q':
                return false;
            case 'a':
                only = ChangeLevel.API;
                return last == null || show();
            case 'i':
                only = ChangeLevel.IMPLEMENTATION;
                return last == null || show();
            case 'c':
                only = ChangeLevel.COSMETIC;
                return last == null || show();
            case 'm':
                only = ChangeLevel.IMPORTS;
                return last == null || show();
            case 'h':
                return help();
            default:
                return true;
        }
    }

    /** Shows the help until {@code Esc} brings the last output back. Returns {@code false} if the user quit. */
    private boolean help() {
        out.print(Terminal.CLEAR);
        HELP.forEach(out::println);
        out.println();
        out.print("Esc: back  q: quit");
        out.flush();
        while (true) {
            int key = nextKey();
            if (key == -1 || key == 'q') {
                return false;
            }
            if (key == Terminal.ESC) {
                return pending.isEmpty() ? last == null || show() : refreshPending();
            }
        }
    }
}
