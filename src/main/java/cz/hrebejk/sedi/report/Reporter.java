/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.report;

import cz.hrebejk.sedi.analysis.ApiChanges;
import cz.hrebejk.sedi.analysis.DiffAnalysis;
import cz.hrebejk.sedi.analysis.ElementPair;
import cz.hrebejk.sedi.analysis.FileAnalysis;
import org.eclipse.jgit.diff.DiffEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Renders a {@link DiffAnalysis} as text: for every changed file a heading and then, for every changed type,
 * the list of added, removed and changed members, one line each, rendered by a {@link SignatureDiffPrinter}.
 */
public final class Reporter {

    private final SignatureDiffPrinter printer;

    /**
     * Creates a reporter.
     *
     * @param ansi {@code true} to use ANSI escape codes for strike through
     */
    public Reporter(boolean ansi) {
        this.printer = new SignatureDiffPrinter(ansi);
    }

    /**
     * Reports the API changes of all the files of the diff. A file is left out if its API did not change,
     * unless git added, deleted or renamed it.
     *
     * @param analysis the analyzed diff
     * @return the headings and the indented lines under them
     */
    public List<String> report(DiffAnalysis analysis) {
        List<String> result = new ArrayList<>();
        for (FileAnalysis file : analysis.files()) {
            List<String> lines = report(file);
            if (!lines.isEmpty() || file.isStructural()) {
                result.add(title(file));
                result.addAll(lines);
            }
        }
        return result;
    }

    /**
     * Reports the API changes of a file.
     *
     * @param file the analyzed file
     * @return indented lines, empty if the API did not change
     */
    public List<String> report(FileAnalysis file) {
        List<String> lines = new ArrayList<>();
        if (file.oldPrimary() != null) {
            lines.add("  " + printer.format(new ElementPair(file.oldPrimary(), file.newPrimary(), Set.of()), "")
                    .stripLeading());
            render(file.primaryChanges(), 1, lines);
        }
        render(file.rootChanges(), 0, lines);
        return lines;
    }

    /**
     * Returns the heading of a changed file: the simple name of its top level class, a space and its package,
     * e.g. {@code Foo com.acme}. Added files are prefixed with {@code +}, deleted ones with {@code -}, and
     * a renamed class or moved package shows the old value struck through.
     *
     * @param file the analyzed file
     * @return the heading line
     */
    public String title(FileAnalysis file) {
        DiffEntry.ChangeType type = file.diff().getChangeType();
        String oldName = type == DiffEntry.ChangeType.ADD ? "" : file.oldClassName();
        String newName = type == DiffEntry.ChangeType.DELETE ? "" : file.newClassName();
        String oldPackage = type == DiffEntry.ChangeType.ADD ? "" : file.oldPackage();
        String newPackage = file.newPackage();
        boolean deleted = type == DiffEntry.ChangeType.DELETE;
        String name = deleted ? oldName : printer.slot(oldName, newName);
        String pkg = deleted ? oldPackage : printer.slot(oldPackage, newPackage);
        String prefix = type == DiffEntry.ChangeType.ADD ? "+ " : type == DiffEntry.ChangeType.DELETE ? "- " : "";
        return prefix + name + (pkg.isEmpty() ? "" : " " + pkg);
    }

    /** Renders the members of a container, then the types nested in it which have changes. */
    private void render(ApiChanges changes, int depth, List<String> lines) {
        for (ElementPair pair : changes.pairs()) {
            lines.add("  ".repeat(depth + 1) + printer.format(pair, changes.typeName()));
        }
        for (ApiChanges type : changes.nested()) {
            List<String> nested = new ArrayList<>();
            render(type, depth + 1, nested);
            if (!nested.isEmpty()) {
                lines.add("  ".repeat(depth + 1) + type.typeName());
                lines.addAll(nested);
            }
        }
    }
}
