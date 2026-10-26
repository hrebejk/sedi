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
 * Record holding diff and attributed AST information for an old and new version of a changed file.
 *
 * @param diff    the Git DiffEntry
 * @param oldUnit compilation unit of the old version with its Trees and DocTrees (or null if the file was added)
 * @param newUnit compilation unit of the new version with its Trees and DocTrees (or null if the file was deleted)
 */
public record FileDiffAnalysis(
        DiffEntry diff,
        CompilationUnitInfo oldUnit,
        CompilationUnitInfo newUnit
) {

}
