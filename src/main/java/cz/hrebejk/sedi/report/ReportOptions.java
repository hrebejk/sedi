/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import cz.hrebejk.sedi.analysis.ChangeLevel;

/**
 * What a {@link Reporter} shows and how it arranges it.
 *
 * @param maxLevel       the least important level of changes to show; all more important ones are shown too
 * @param bodyContext    the most unchanged lines of a body shown in a row between its changes, a longer run of them
 *                       is collapsed to a note with its number of lines; {@link #WHOLE_BODY} to show them all
 * @param javadocContext the unchanged javadoc lines shown around a change of a javadoc, {@link #NO_JAVADOC} to show
 *                       no javadoc, {@link #WHOLE_JAVADOC} to show the whole javadoc
 * @param lineNumbers    whether to show the numbers of the lines of the source on the left
 * @param showAdditions  whether the implementation changes show the whole source of the added types and members
 * @param showDeletions  whether the implementation changes show the whole source of the removed types and members
 * @param showTests      whether the changes of the test sources, the files under {@code src/test/}, are shown
 * @param foldContext    whether the unchanged lines of a body collapsed to a note are kept too, in a
 *                       {@linkplain Css#ELIDED fold} behind the note, for a page which can expand them
 */
public record ReportOptions(ChangeLevel maxLevel, int bodyContext, int javadocContext,
                            boolean lineNumbers, boolean showAdditions, boolean showDeletions, boolean showTests,
                            boolean foldContext) {

    /** The {@link #bodyContext()} showing all the unchanged lines of a changed body. */
    public static final int WHOLE_BODY = -1;

    /** The default of {@link #bodyContext()}. */
    public static final int DEFAULT_BODY_CONTEXT = 3;

    /** The {@link #javadocContext()} showing no javadoc at all. */
    public static final int NO_JAVADOC = -2;

    /** The {@link #javadocContext()} showing the whole javadoc of an element whose javadoc changed. */
    public static final int WHOLE_JAVADOC = -1;

    /** The default of {@link #javadocContext()}: no javadoc. */
    public static final int DEFAULT_JAVADOC_CONTEXT = NO_JAVADOC;

    /**
     * Creates options which collapse a long run of unchanged lines to a note alone.
     *
     * @param maxLevel       the least important level of changes to show
     * @param bodyContext    the most unchanged lines of a body shown in a row
     * @param javadocContext the unchanged javadoc lines shown around a change of a javadoc
     * @param lineNumbers    whether to show the numbers of the lines of the source on the left
     * @param showAdditions  whether the implementation changes show the whole source of the added types and members
     * @param showDeletions  whether the implementation changes show the whole source of the removed types and members
     * @param showTests      whether the changes of the test sources are shown
     */
    public ReportOptions(ChangeLevel maxLevel, int bodyContext, int javadocContext, boolean lineNumbers,
                         boolean showAdditions, boolean showDeletions, boolean showTests) {
        this(maxLevel, bodyContext, javadocContext, lineNumbers, showAdditions, showDeletions, showTests, false);
    }

    /**
     * Creates options showing the whole source of the added types and members, not of the removed ones, and showing
     * the tests.
     *
     * @param maxLevel       the least important level of changes to show
     * @param bodyContext    the most unchanged lines of a body shown in a row
     * @param javadocContext the unchanged javadoc lines shown around a change of a javadoc
     * @param lineNumbers    whether to show the numbers of the lines of the source on the left
     */
    public ReportOptions(ChangeLevel maxLevel, int bodyContext, int javadocContext,
                         boolean lineNumbers) {
        this(maxLevel, bodyContext, javadocContext, lineNumbers, true, false, true);
    }

    /**
     * Creates options showing no line numbers.
     *
     * @param maxLevel       the least important level of changes to show
     * @param bodyContext    the most unchanged lines of a body shown in a row
     * @param javadocContext the unchanged javadoc lines shown around a change of a javadoc
     */
    public ReportOptions(ChangeLevel maxLevel, int bodyContext, int javadocContext) {
        this(maxLevel, bodyContext, javadocContext, false);
    }

    /**
     * Returns these options showing the javadoc as given.
     *
     * @param javadocContext the unchanged javadoc lines shown around a change of a javadoc
     * @return the new options
     */
    public ReportOptions withJavadocContext(int javadocContext) {
        return new ReportOptions(maxLevel, bodyContext, javadocContext, lineNumbers, showAdditions, showDeletions, showTests,
                foldContext);
    }

    /**
     * Returns these options showing the whole source of the added types and members, or not.
     *
     * @param show whether to show it
     * @return the new options
     */
    public ReportOptions withAdditions(boolean show) {
        return new ReportOptions(maxLevel, bodyContext, javadocContext, lineNumbers, show, showDeletions, showTests,
                foldContext);
    }

    /**
     * Returns these options showing the whole source of the removed types and members, or not.
     *
     * @param show whether to show it
     * @return the new options
     */
    public ReportOptions withDeletions(boolean show) {
        return new ReportOptions(maxLevel, bodyContext, javadocContext, lineNumbers, showAdditions, show, showTests,
                foldContext);
    }

    /**
     * Returns these options showing the changes of the test sources, or not.
     *
     * @param show whether to show them
     * @return the new options
     */
    public ReportOptions withTests(boolean show) {
        return new ReportOptions(maxLevel, bodyContext, javadocContext, lineNumbers, showAdditions, showDeletions, show,
                foldContext);
    }

    /**
     * Returns these options keeping the unchanged lines collapsed to a note behind it, or not.
     *
     * @param fold whether to keep them
     * @return the new options
     */
    public ReportOptions withFoldContext(boolean fold) {
        return new ReportOptions(maxLevel, bodyContext, javadocContext, lineNumbers, showAdditions, showDeletions,
                showTests, fold);
    }

    /**
     * Creates options showing no javadoc.
     *
     * @param maxLevel  the least important level of changes to show
     * @param bodyContext the most unchanged lines of a body shown in a row
     */
    public ReportOptions(ChangeLevel maxLevel, int bodyContext) {
        this(maxLevel, bodyContext, DEFAULT_JAVADOC_CONTEXT);
    }

    /**
     * Creates options showing at most {@value #DEFAULT_BODY_CONTEXT} lines of the changes of a body.
     *
     * @param maxLevel the least important level of changes to show
     */
    public ReportOptions(ChangeLevel maxLevel) {
        this(maxLevel, DEFAULT_BODY_CONTEXT);
    }

    /** Only the API changes. */
    public static final ReportOptions DEFAULT = new ReportOptions(ChangeLevel.API);
}
