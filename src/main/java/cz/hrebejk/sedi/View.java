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
 * category, {@code l} all the changes by file and category, {@code d} cycles the javadoc shown, {@code +} and
 * {@code -} show or hide the whole source of the added and the removed types and members and {@code t} shows or
 * hides the tests. At first the changes are shown as the options of the command line say.
 *
 * @param reporter the reporter with the options of the view
 * @param only     the only level shown, {@code null} for all of them
 * @param byFile   whether all the changes are shown by file and category, see {@link Reporter#reportByFile}
 */
record View(Reporter reporter, ChangeLevel only, boolean byFile) {

    /** The unchanged javadoc lines shown around a change after {@code d} switched from no javadoc. */
    private static final int JAVADOC_CONTEXT = 3;

    /** The view of all the changes with the options of the reporter. */
    View(Reporter reporter) {
        this(reporter, null, false);
    }

    /** The report of the analysis. */
    HTML.Tag report(DiffAnalysis analysis) {
        return byFile ? reporter.reportByFile(analysis)
                : only == null ? reporter.report(analysis) : reporter.reportOnly(analysis, only);
    }

    /** The view with another reporter, showing the same. */
    private View with(Reporter other) {
        return new View(other, only, byFile);
    }

    private View only(ChangeLevel level) {
        return new View(reporter, level, false);
    }

    /** The view after the key is pressed, {@code null} if the key does not change the view. */
    View key(int key) {
        ReportOptions options = reporter.options();
        return switch (key) {
            case 'a' -> only(ChangeLevel.API);
            case 'i' -> only(ChangeLevel.IMPLEMENTATION);
            case 'c' -> only(ChangeLevel.COSMETIC);
            case 'm' -> only(ChangeLevel.IMPORTS);
            case 'l' -> new View(reporter, null, true);
            case 'd' -> with(reporter.withJavadocContext(switch (options.javadocContext()) {
                case ReportOptions.NO_JAVADOC -> JAVADOC_CONTEXT;
                case ReportOptions.WHOLE_JAVADOC -> ReportOptions.NO_JAVADOC;
                default -> ReportOptions.WHOLE_JAVADOC;
            }));
            case '+' -> with(reporter.withAdditions(!options.showAdditions()));
            case '-' -> with(reporter.withDeletions(!options.showDeletions()));
            case 't' -> with(reporter.withTests(!options.showTests()));
            default -> null;
        };
    }
}
