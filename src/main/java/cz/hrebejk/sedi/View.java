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
import cz.hrebejk.sedi.report.ReportOptions;
import cz.hrebejk.sedi.report.Reporter;
import org.maraboo.fleck.html.HTML;

/**
 * What of an analysis is shown, as the keys change it: the keys {@code a i c m} show only the changes of one
 * category, {@code d} cycles the javadoc shown, {@code +} and {@code -} show or hide the whole source of the added
 * and the removed types and members, {@code t} shows or hides the tests and {@code g} shows all the changes again,
 * switching between grouping them by category and by type.
 *
 * @param reporter the reporter with the options of the view
 * @param only     the only level shown, {@code null} for all of them
 */
record View(Reporter reporter, ChangeLevel only) {

    /** The unchanged javadoc lines shown around a change after {@code d} switched from no javadoc. */
    private static final int JAVADOC_CONTEXT = 3;

    /** The view of all the changes with the options of the reporter. */
    View(Reporter reporter) {
        this(reporter, null);
    }

    /** The report of the analysis. */
    HTML.Tag report(DiffAnalysis analysis) {
        return only == null ? reporter.report(analysis) : reporter.reportOnly(analysis, only);
    }

    /** The view after the key is pressed, {@code null} if the key does not change the view. */
    View key(int key) {
        ReportOptions options = reporter.options();
        return switch (key) {
            case 'a' -> new View(reporter, ChangeLevel.API);
            case 'i' -> new View(reporter, ChangeLevel.IMPLEMENTATION);
            case 'c' -> new View(reporter, ChangeLevel.COSMETIC);
            case 'm' -> new View(reporter, ChangeLevel.IMPORTS);
            case 'd' -> new View(reporter.withJavadocContext(switch (options.javadocContext()) {
                case ReportOptions.NO_JAVADOC -> JAVADOC_CONTEXT;
                case ReportOptions.WHOLE_JAVADOC -> ReportOptions.NO_JAVADOC;
                default -> ReportOptions.WHOLE_JAVADOC;
            }), only);
            case '+' -> new View(reporter.withAdditions(!options.showAdditions()), only);
            case '-' -> new View(reporter.withDeletions(!options.showDeletions()), only);
            case 't' -> new View(reporter.withTests(!options.showTests()), only);
            // One level alone has no grouping, so all the levels are shown again.
            case 'g' -> new View(reporter.withGrouping(reporter.grouping() == ReportOptions.Grouping.BY_TYPE
                    ? ReportOptions.Grouping.BY_CATEGORY : ReportOptions.Grouping.BY_TYPE));
            default -> null;
        };
    }
}
