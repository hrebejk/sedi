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
 * @param grouping       how the changes are grouped
 * @param bodyContext    the most unchanged lines of a body shown in a row between its changes, a longer run of them
 *                       is collapsed to a note with its number of lines; {@link #WHOLE_BODY} to show them all
 * @param javadocContext the unchanged javadoc lines shown around a change of a javadoc, {@link #NO_JAVADOC} to show
 *                       no javadoc, {@link #WHOLE_JAVADOC} to show the whole javadoc
 * @param lineNumbers    whether to show the numbers of the lines of the source on the left
 * @param showAdditions  whether the implementation changes show the whole source of the added types and members
 * @param showDeletions  whether the implementation changes show the whole source of the removed types and members
 */
public record ReportOptions(ChangeLevel maxLevel, Grouping grouping, int bodyContext, int javadocContext,
                            boolean lineNumbers, boolean showAdditions, boolean showDeletions) {

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
     * Creates options showing the whole source of the added types and members, not of the removed ones.
     *
     * @param maxLevel       the least important level of changes to show
     * @param grouping       how the changes are grouped
     * @param bodyContext    the most unchanged lines of a body shown in a row
     * @param javadocContext the unchanged javadoc lines shown around a change of a javadoc
     * @param lineNumbers    whether to show the numbers of the lines of the source on the left
     */
    public ReportOptions(ChangeLevel maxLevel, Grouping grouping, int bodyContext, int javadocContext,
                         boolean lineNumbers) {
        this(maxLevel, grouping, bodyContext, javadocContext, lineNumbers, true, false);
    }

    /**
     * Creates options showing no line numbers.
     *
     * @param maxLevel       the least important level of changes to show
     * @param grouping       how the changes are grouped
     * @param bodyContext    the most unchanged lines of a body shown in a row
     * @param javadocContext the unchanged javadoc lines shown around a change of a javadoc
     */
    public ReportOptions(ChangeLevel maxLevel, Grouping grouping, int bodyContext, int javadocContext) {
        this(maxLevel, grouping, bodyContext, javadocContext, false);
    }

    /**
     * Returns these options with the levels up to the given one shown.
     *
     * @param level the least important level to show
     * @return the new options
     */
    public ReportOptions withMaxLevel(ChangeLevel level) {
        return new ReportOptions(level, grouping, bodyContext, javadocContext, lineNumbers, showAdditions, showDeletions);
    }

    /**
     * Returns these options grouping the changes as given.
     *
     * @param grouping how the changes are grouped
     * @return the new options
     */
    public ReportOptions withGrouping(Grouping grouping) {
        return new ReportOptions(maxLevel, grouping, bodyContext, javadocContext, lineNumbers, showAdditions, showDeletions);
    }

    /**
     * Returns these options showing the javadoc as given.
     *
     * @param javadocContext the unchanged javadoc lines shown around a change of a javadoc
     * @return the new options
     */
    public ReportOptions withJavadocContext(int javadocContext) {
        return new ReportOptions(maxLevel, grouping, bodyContext, javadocContext, lineNumbers, showAdditions, showDeletions);
    }

    /**
     * Returns these options showing the whole source of the added types and members, or not.
     *
     * @param show whether to show it
     * @return the new options
     */
    public ReportOptions withAdditions(boolean show) {
        return new ReportOptions(maxLevel, grouping, bodyContext, javadocContext, lineNumbers, show, showDeletions);
    }

    /**
     * Returns these options showing the whole source of the removed types and members, or not.
     *
     * @param show whether to show it
     * @return the new options
     */
    public ReportOptions withDeletions(boolean show) {
        return new ReportOptions(maxLevel, grouping, bodyContext, javadocContext, lineNumbers, showAdditions, show);
    }

    /**
     * Creates options showing no javadoc.
     *
     * @param maxLevel  the least important level of changes to show
     * @param grouping  how the changes are grouped
     * @param bodyContext the most unchanged lines of a body shown in a row
     */
    public ReportOptions(ChangeLevel maxLevel, Grouping grouping, int bodyContext) {
        this(maxLevel, grouping, bodyContext, DEFAULT_JAVADOC_CONTEXT);
    }

    /**
     * Creates options showing at most {@value #DEFAULT_BODY_CONTEXT} lines of the changes of a body.
     *
     * @param maxLevel the least important level of changes to show
     * @param grouping how the changes are grouped
     */
    public ReportOptions(ChangeLevel maxLevel, Grouping grouping) {
        this(maxLevel, grouping, DEFAULT_BODY_CONTEXT);
    }

    /** How the changes are grouped. */
    public enum Grouping {
        /** By the file: package and type, then the changes of its members. */
        BY_TYPE,
        /** By the level of the change, then by package and type. */
        BY_CATEGORY
    }

    /** Only the API changes, grouped by type. */
    public static final ReportOptions DEFAULT = new ReportOptions(ChangeLevel.API, Grouping.BY_TYPE);
}
