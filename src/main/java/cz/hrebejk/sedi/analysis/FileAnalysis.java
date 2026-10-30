/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import cz.hrebejk.sedi.compilation.CompilationUnitInfo;
import org.eclipse.jgit.diff.DiffEntry;

/**
 * The analysis of one changed file: its two versions, their hashed element trees and the API changes.
 *
 * @param diff           the Git DiffEntry
 * @param oldUnit        compilation unit of the old version, {@code null} if the file was added
 * @param newUnit        compilation unit of the new version, {@code null} if the file was deleted
 * @param oldRoot        hashed elements of the old version; an empty root if the file was added
 * @param newRoot        hashed elements of the new version, hashed with the renames of the whole diff
 * @param oldPrimary     the top level type renamed together with the file by git, else {@code null}
 * @param newPrimary     the new name of {@code oldPrimary}, else {@code null}
 * @param oldClassName   name of the main top level class of the old version
 * @param newClassName   name of the main top level class of the new version
 * @param oldPackage     package of the old version, empty if none
 * @param newPackage     package of the new version, empty if none
 * @param primaryChanges changes of the members of the renamed top level type, {@code null} if there is none
 * @param rootChanges    changes of the top level types, the renamed one excluded
 */
public record FileAnalysis(
        DiffEntry diff,
        CompilationUnitInfo oldUnit,
        CompilationUnitInfo newUnit,
        ElementInfo oldRoot,
        ElementInfo newRoot,
        ElementInfo oldPrimary,
        ElementInfo newPrimary,
        String oldClassName,
        String newClassName,
        String oldPackage,
        String newPackage,
        ApiChanges primaryChanges,
        ApiChanges rootChanges
) {

    /**
     * Returns whether the change of the file is worth reporting even if no API change describes it: files
     * that were added, deleted or renamed by git.
     *
     * @return {@code true} for an added, deleted or renamed file
     */
    public boolean isStructural() {
        DiffEntry.ChangeType type = diff.getChangeType();
        return type == DiffEntry.ChangeType.ADD || type == DiffEntry.ChangeType.DELETE
                || type == DiffEntry.ChangeType.RENAME;
    }

    /**
     * Returns whether there is an API change to report.
     *
     * @return {@code true} if the API of the file changed
     */
    public boolean hasApiChanges() {
        return oldPrimary != null || !rootChanges.isEmpty();
    }
}
