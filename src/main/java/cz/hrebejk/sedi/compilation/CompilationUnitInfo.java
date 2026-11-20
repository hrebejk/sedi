/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.compilation;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.Trees;

import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Container for a compilation unit and the utilities of the javac task it comes from.
 *
 * @param compilationUnit the parsed CompilationUnitTree
 * @param trees             the attributed Trees instance
 * @param docTrees          the attributed DocTrees instance
 * @param elements          the elements of the task, e.g. to find inherited members
 * @param types             the types of the task, e.g. to find supertypes
 */
public record CompilationUnitInfo(
        CompilationUnitTree compilationUnit,
        Trees trees,
        DocTrees docTrees,
        Elements elements,
        Types types
) {
}
