/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import java.util.List;

/**
 * Everything known about a diff: the analysis of every changed file and the renames found among them.
 *
 * @param files   the changed files
 * @param renames the renamed types, fields and methods of all the files
 */
public record DiffAnalysis(List<FileAnalysis> files, Renames renames) {
}
