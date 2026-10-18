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

/**
 * Container for a compilation unit and its associated Trees and DocTrees.
 *
 * @param compilationUnit the parsed CompilationUnitTree
 * @param trees             the attributed Trees instance
 * @param docTrees          the attributed DocTrees instance
 */
public record CompilationAnalysis(
        CompilationUnitTree compilationUnit,
        Trees trees,
        DocTrees docTrees
) {
}
