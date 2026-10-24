/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes the API (signature) changes of a file: for every changed type the list of added, removed
 * and changed members, one line each, rendered by a {@link SignatureDiffPrinter}.
 */
public final class ApiDiffReporter {

    private final SignatureDiffPrinter printer;

    /**
     * Creates a reporter.
     *
     * @param ansi {@code true} to use ANSI escape codes for strike through
     */
    public ApiDiffReporter(boolean ansi) {
        this.printer = new SignatureDiffPrinter(ansi);
    }

    /**
     * Reports the API changes between two versions of a compilation unit.
     *
     * @param analysis the old and new version of a changed file
     * @return indented lines, empty if the API did not change
     */
    public List<String> report(FileDiffAnalysis analysis) {
        ElementInfo oldRoot = root(analysis.oldCompilationUnit(), analysis.oldTrees());
        ElementInfo newRoot = root(analysis.newCompilationUnit(), analysis.newTrees());
        List<String> lines = new ArrayList<>();
        diff(oldRoot, newRoot, "", 0, lines);
        return lines;
    }

    private static ElementInfo root(com.sun.source.tree.CompilationUnitTree cut, com.sun.source.util.Trees trees) {
        return cut == null ? new ElementInfo(null, "ROOT", ElementInfo.NodeType.COMPILATION_UNIT)
                : new ElementHashCalculator(trees).calculate(cut);
    }

    /** Diffs the members of two versions of a container, then descends into the types present in both. */
    private void diff(ElementInfo oldContainer, ElementInfo newContainer, String typeName, int depth,
                      List<String> lines) {
        for (ElementPair pair : MemberMatcher.match(oldContainer, newContainer)) {
            lines.add("  ".repeat(depth + 1) + printer.format(pair, typeName));
        }
        Map<String, ElementInfo> oldTypes = types(oldContainer);
        for (ElementInfo newType : types(newContainer).values()) {
            ElementInfo oldType = oldTypes.get(newType.getName());
            if (oldType == null) {
                continue;
            }
            List<String> nested = new ArrayList<>();
            diff(oldType, newType, newType.getName(), depth + 1, nested);
            if (!nested.isEmpty()) {
                lines.add("  ".repeat(depth + 1) + newType.getName());
                lines.addAll(nested);
            }
        }
    }

    private static Map<String, ElementInfo> types(ElementInfo container) {
        Map<String, ElementInfo> types = new LinkedHashMap<>();
        for (ElementInfo child : container.getChildren()) {
            if (child.getNodeType() == ElementInfo.NodeType.TYPE) {
                types.put(child.getName(), child);
            }
        }
        return types;
    }
}
