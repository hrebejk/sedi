/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.analysis;

import cz.hrebejk.sedi.compilation.AstLoader;
import cz.hrebejk.sedi.compilation.CompilationUnitInfo;
import cz.hrebejk.sedi.compilation.SourceFileProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Loads source files from the working directory in tests. */
final class TestSources {

    private TestSources() {
    }

    static List<CompilationUnitInfo> load(AstLoader loader, List<Path> files) throws IOException {
        Path base = files.get(0).toAbsolutePath().getParent();
        return loader.load(List.of(SourceFileProvider.directories(base, List.of(""))), files);
    }
}
