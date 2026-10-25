/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.Trees;
import org.eclipse.jgit.diff.DiffEntry;

/**
 * Record holding diff and attributed AST information for an old and new version of a changed file.
 *
 * @param diff                the Git DiffEntry
 * @param oldCompilationUnit  AST compilation unit of the old version (or null)
 * @param newCompilationUnit  AST compilation unit of the new version (or null)
 * @param oldTrees            Trees utility for old version (or null)
 * @param newTrees            Trees utility for new version (or null)
 * @param oldDocTrees         DocTrees utility for old version (or null)
 * @param newDocTrees         DocTrees utility for new version (or null)
 */
public record FileDiffAnalysis(
        DiffEntry diff,
        CompilationUnitTree oldCompilationUnit,
        CompilationUnitTree newCompilationUnit,
        Trees oldTrees,
        Trees newTrees,
        DocTrees oldDocTrees,
        DocTrees newDocTrees
) {

}
