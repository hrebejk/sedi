/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import cz.hrebejk.sedi.analysis.DiffAnalysis;
import cz.hrebejk.sedi.analysis.ChangeLevel;
import cz.hrebejk.sedi.report.ReportOptions;
import cz.hrebejk.sedi.report.Reporter;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.maraboo.fleck.css.StyleSheet;
import org.maraboo.fleck.html.HTML;
import org.maraboo.fleck.web.WebEvent;
import org.maraboo.fleck.web.WebPage;
import org.maraboo.fleck.web.WebServer;
import org.maraboo.fleck.web.WebSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Shows the report in the browser. Each loaded page has its own {@link View}, changed by the buttons of its tool
 * bar or by the same keys as on the console; all the pages show the last analysis and are refreshed when it is
 * made again.
 */
final class WebReport {

    private final Reporter reporter;
    private final String title;
    /** The open pages; a page left by the browser goes away with the session of the server. */
    private final Set<WebSession> sessions = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Guards the analysis, which the pages render on the threads of the server. */
    private final Object lock = new Object();
    private DiffAnalysis last;
    private String error;

    /**
     * @param reporter renders the analysis as the options of the command line say
     * @param title    what is compared, shown in the tool bar and as the title of the page
     */
    WebReport(Reporter reporter, String title) {
        this.reporter = reporter;
        this.title = title;
    }

    /**
     * Starts the web server.
     *
     * @param port       the port, 0 for any free one
     * @param styleSheet the styles of the report
     * @return the started server
     */
    WebServer start(int port, StyleSheet styleSheet) throws IOException {
        return new WebServer(port, session -> {
            sessions.add(session);
            return new Page();
        })
                .title("sedi " + title)
                .styleSheet(styleSheet)
                .resources(WebReport.class, "web")
                .styleSheet("sedi.css")
                .script("sedi.js")
                .start();
    }

    /** Runs the analysis and shows its result, or its error, on all the open pages. */
    void analyze(Session.Analysis analysis, Predicate<String> filter) {
        DiffAnalysis result = null;
        String failure = null;
        try {
            result = analysis.run(filter);
        } catch (IOException | GitAPIException | IllegalArgumentException e) {
            failure = "Cannot analyze changes: " + e.getMessage();
            System.err.println(failure);
        }
        synchronized (lock) {
            if (result != null) {
                last = result;
            }
            error = failure;
        }
        List<WebSession> open;
        synchronized (sessions) {
            open = new ArrayList<>(sessions);
        }
        open.forEach(WebSession::refresh);
    }

    /** The client component of a fold of the report, see {@code sedi.js}. */
    private static final String FOLD_COMPONENT = "sedi-fold";
    /** The class of a fold of the report, see {@code Css.FOLD}. */
    private static final String FOLD_CLASS = "fold";
    /** The client component of unchanged lines hidden behind their note, see {@code sedi.js}. */
    private static final String ELIDED_COMPONENT = "sedi-elided";
    /** The client component showing in the tool bar the file or the element scrolled to, see {@code sedi.js}. */
    private static final String SPY_COMPONENT = "sedi-spy";
    /** The class of unchanged lines hidden behind their note, see {@code Css.ELIDED}. */
    private static final String ELIDED_CLASS = "elided";

    /**
     * The tree with its folds made the client components which collapse and expand them, also the unchanged lines
     * hidden behind their note. Each gets a key of the key of the fold it is in, its name and how many folds of the
     * same key come before it, so the browser keeps the state of a fold when the report is rendered again, also in
     * another view of the same changes.
     *
     * @param outer the key of the fold the tag is in, empty for none
     * @param seen  the folds of each key seen so far
     */
    private static HTML.Tag withFoldComponents(HTML.Tag tag, String outer, Map<String, Integer> seen) {
        boolean fold = hasClass(tag, FOLD_CLASS);
        boolean elided = hasClass(tag, ELIDED_CLASS);
        HTML.Tag copy = new HTML.Tag(fold ? FOLD_COMPONENT : elided ? ELIDED_COMPONENT : tag.tagName)
                .attributes(tag.attrs);
        String key = outer;
        if (fold || elided) {
            String name = outer + "/" + tag.attrs.getOrDefault("data-name", "\u2026");
            key = name + "#" + seen.merge(name, 1, Integer::sum);
            copy.attr("key", key);
        }
        List<HTML> children = new ArrayList<>();
        for (HTML child : tag.children) {
            children.add(child instanceof HTML.Tag childTag ? withFoldComponents(childTag, key, seen) : child);
        }
        return copy.children(children);
    }

    private static final String HEADING_CLASS = "heading";
    /** The class of a line left of the column of the numbers, like the line between the files. */
    private static final String OUTSIDE_CLASS = "outside";
    private static final String HEADING_MARK_CLASS = "heading-mark";

    /**
     * The report for the page instead of the console. The lines between the files are left out, the style sheet
     * sets the files apart. The {@code #} of a heading is left blank, so
     * the mark of a fold shows there under the mouse (see {@code sedi.css}), the indentation in front of it is left
     * out, as the style sheet places the heading, and the heading of the only level shown
     * is left out with the blank line after it, as its button in the tool bar says which level it is.
     *
     * @param withoutHeading whether the heading of the level is left out
     */
    private static HTML.Tag forPage(HTML.Tag tag, boolean withoutHeading) {
        HTML.Tag copy = new HTML.Tag(tag.tagName).attributes(tag.attrs);
        if (hasClass(tag, HEADING_MARK_CLASS)) {
            return copy.children(HTML.Text(" "));
        }
        List<HTML> children = new ArrayList<>();
        boolean afterHeading = false;
        boolean beforeMark = tag.children.stream()
                .anyMatch(c -> c instanceof HTML.Tag span && hasClass(span, HEADING_MARK_CLASS));
        for (HTML child : tag.children) {
            if (beforeMark) {
                if (!(child instanceof HTML.Tag span && hasClass(span, HEADING_MARK_CLASS))) {
                    continue;
                }
                beforeMark = false;
            }
            boolean heading = child instanceof HTML.Tag line && line.children.stream()
                    .anyMatch(c -> c instanceof HTML.Tag span && hasClass(span, HEADING_CLASS));
            boolean blank = child instanceof HTML.Tag br && br.tagName.equals("br");
            boolean separator = child instanceof HTML.Tag line && hasClass(line, OUTSIDE_CLASS)
                    && line.getInnerText().matches("\u2500+");
            if (!separator && !(withoutHeading && (heading || afterHeading && blank))) {
                children.add(child instanceof HTML.Tag childTag ? forPage(childTag, withoutHeading) : child);
            }
            afterHeading = heading;
        }
        return copy.children(children);
    }

    private static boolean hasClass(HTML.Tag tag, String clazz) {
        return Arrays.asList(tag.attrs.getOrDefault("class", "").split(" ")).contains(clazz);
    }

    /** The key of the help, as on the console; also the id of its button. */
    private static final char HELP_KEY = 'h';
    /** The id of the tool bar, which a click on hides the help. */
    private static final String TOOLBAR = "toolbar";

    /** The help, shown instead of the report. */
    private static final HTML.Tag HELP = HTML.DIV(
            HTML.Tag("h2", HTML.Text("Buttons and keys")),
            keys(
                    "l", "All", "All the changes by file; under each file its API, implementation, cosmetic and "
                            + "import changes",
                    "a", "API", "Only the API changes",
                    "i", "Implementation", "Only the implementation changes",
                    "c", "Cosmetic", "Only the cosmetic changes: formatting, comments, javadoc",
                    "m", "Imports", "Only the changed imports",
                    "d", "Javadoc", "The javadoc shown: none, 3 lines around the changes, whole",
                    "+", "Additions", "Show or hide the whole source of the added types and members",
                    "-", "Deletions", "Show or hide the whole source of the removed types and members",
                    "t", "Tests", "Show or hide the changes of the tests",
                    "h", "?", "This help; a click on the tool bar brings the report back"),
            HTML.Tag("h2", HTML.Text("Folding")),
            HTML.Tag("p", HTML.Text("A click on the title of a file or of a category of its changes collapses or expands "
                    + "it. The mark in front of the title shows which: \u25be collapses, \u25b8 expands.")),
            HTML.Tag("h2", HTML.Text("Marks")),
            keys(
                    "+", "", "An added file, type, member or line",
                    "-", "", "A removed file, type, member or line",
                    "~", "", "A changed file, type, member or line",
                    "\u2192", "", "A moved or renamed file"))
            .clazz("help");

    /** A table of keys, a label and a description in each row, given in turns. */
    private static HTML.Tag keys(String... cells) {
        List<HTML> rows = new ArrayList<>();
        for (int i = 0; i < cells.length; i += 3) {
            rows.add(HTML.Tag("tr",
                    HTML.Tag("td", HTML.Tag("kbd", HTML.Text(cells[i]))).clazz("key"),
                    HTML.Tag("td", HTML.Text(cells[i + 1])).clazz("label"),
                    HTML.Tag("td", HTML.Text(cells[i + 2]))));
        }
        return HTML.Tag("table").children(rows);
    }

    /** One loaded page. */
    private final class Page implements WebPage {


        /** At first the level the command line asks for, alone; the page does not list the levels up to it. */
        private View view = new View(reporter, reporter.options().maxLevel(), false);
        /** Whether the help is shown instead of the report. */
        private boolean help;

        @Override
        public HTML render() {
            List<HTML> content = new ArrayList<>();
            HTML.Tag toolbar = toolbar();
            content.add(help ? toolbar.id(TOOLBAR).listen("click") : toolbar);
            // While the help is shown the button closes it.
            content.add(HTML.Tag("button", HTML.Text(help ? "\u2715" : "?"))
                    .id(String.valueOf(HELP_KEY))
                    .attr("data-key", String.valueOf(HELP_KEY))
                    .attr("title", (help ? "Close the help" : "Help") + " (" + HELP_KEY + ")")
                    .listen("click")
                    .clazz("help-button"));
            if (help) {
                content.add(HELP);
                return HTML.DIV().children(content).clazz("sedi");
            }
            synchronized (lock) {
                if (error != null) {
                    content.add(HTML.DIV(error).clazz("message", "failure"));
                }
                if (last == null) {
                    content.add(HTML.DIV(error == null ? "Analyzing ..." : "").clazz("message"));
                } else {
                    HTML.Tag report = view.report(last);
                    content.add(report.children.isEmpty() ? HTML.DIV("No changes.").clazz("message")
                            // The report as wide as its longest line, so are the files in it, scrolled sideways.
                            : HTML.DIV(withFoldComponents(forPage(report, view.only() != null), "",
                                    new HashMap<>())).clazz("scroller"));
                }
            }
            return HTML.DIV().children(content).clazz("sedi");
        }

        /**
         * Handles the buttons. While the help is shown the tool bar listens too: a click anywhere on it brings the
         * report back, on a button also with what the button does.
         */
        @Override
        public void onEvent(WebEvent event) {
            String id = event.targetId();
            if (id == null) {
                return;
            }
            if (id.equals(String.valueOf(HELP_KEY))) {
                help = !help;
                return;
            }
            help = false;
            if (id.length() == 1) {
                View next = view.key(id.charAt(0));
                if (next != null) {
                    view = next;
                }
            }
        }

        private HTML.Tag toolbar() {
            ReportOptions options = view.reporter().options();
            ChangeLevel only = view.only();
            List<HTML> items = new ArrayList<>();
            items.add(button('l', "All", "All the changes by file, under each file its API, implementation, cosmetic "
                    + "and import changes", view.byFile()));
            items.add(separator());
            items.add(button('a', "API", "Only the API changes", only == ChangeLevel.API));
            items.add(button('i', "Implementation", "Only the implementation changes",
                    only == ChangeLevel.IMPLEMENTATION));
            items.add(button('c', "Cosmetic", "Only the cosmetic changes", only == ChangeLevel.COSMETIC));
            items.add(button('m', "Imports", "Only the changed imports", only == ChangeLevel.IMPORTS));
            items.add(separator());
            items.add(button('d', "Javadoc: " + javadoc(options.javadocContext()),
                    "Javadoc: none, lines around the changes, whole", options.javadocContext() != ReportOptions.NO_JAVADOC));
            items.add(button('+', "Additions", "The whole source of the added types and members",
                    options.showAdditions()));
            items.add(button('-', "Deletions", "The whole source of the removed types and members",
                    options.showDeletions()));
            items.add(button('t', "Tests", "The changes of the tests", options.showTests()));
            items.add(HTML.SPAN(title).clazz("title"));
            // The second line of the bar: where in the report the page is scrolled to, see sedi.js.
            items.add(new HTML.Tag(SPY_COMPONENT));
            return HTML.DIV().children(items).clazz(TOOLBAR);
        }

        /**
         * A button of the tool bar pressed also by the key; its id is the key, see {@link #onEvent}. The key is
         * underlined in the label, where it first appears, or put in front of it.
         */
        private static HTML.Tag button(char key, String label, String hint, boolean active) {
            int at = label.toLowerCase(Locale.ROOT).indexOf(Character.toLowerCase(key));
            if (at < 0) {
                label = key + " " + label;
                at = 0;
            }
            HTML.Tag tag = HTML.Tag("button", HTML.Text(label.substring(0, at)),
                            HTML.Tag("u", HTML.Text(label.substring(at, at + 1))), HTML.Text(label.substring(at + 1)))
                    .id(String.valueOf(key))
                    .attr("data-key", String.valueOf(key))
                    .attr("title", hint + " (" + key + ")")
                    .listen("click");
            return active ? tag.clazz("active") : tag;
        }

        private static HTML.Tag separator() {
            return HTML.SPAN().clazz("separator");
        }

        private static String javadoc(int context) {
            return switch (context) {
                case ReportOptions.NO_JAVADOC -> "none";
                case ReportOptions.WHOLE_JAVADOC -> "whole";
                default -> context + " lines";
            };
        }
    }
}
