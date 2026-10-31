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
 * @param bodyLines      the most lines shown of the changes of one body, {@code 0} to show none
 * @param javadocContext the unchanged javadoc lines shown around a change of a javadoc, {@link #NO_JAVADOC} to show
 *                       no javadoc, {@link #WHOLE_JAVADOC} to show the whole javadoc
 */
public record ReportOptions(ChangeLevel maxLevel, Grouping grouping, int bodyLines, int javadocContext) {

    /** The default of {@link #bodyLines()}. */
    public static final int DEFAULT_BODY_LINES = 12;

    /** The {@link #javadocContext()} showing no javadoc at all. */
    public static final int NO_JAVADOC = -2;

    /** The {@link #javadocContext()} showing the whole javadoc of an element whose javadoc changed. */
    public static final int WHOLE_JAVADOC = -1;

    /** The default of {@link #javadocContext()}: no javadoc. */
    public static final int DEFAULT_JAVADOC_CONTEXT = NO_JAVADOC;

    /**
     * Creates options showing no javadoc.
     *
     * @param maxLevel  the least important level of changes to show
     * @param grouping  how the changes are grouped
     * @param bodyLines the most lines shown of the changes of one body
     */
    public ReportOptions(ChangeLevel maxLevel, Grouping grouping, int bodyLines) {
        this(maxLevel, grouping, bodyLines, DEFAULT_JAVADOC_CONTEXT);
    }

    /**
     * Creates options showing at most {@value #DEFAULT_BODY_LINES} lines of the changes of a body.
     *
     * @param maxLevel the least important level of changes to show
     * @param grouping how the changes are grouped
     */
    public ReportOptions(ChangeLevel maxLevel, Grouping grouping) {
        this(maxLevel, grouping, DEFAULT_BODY_LINES);
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
