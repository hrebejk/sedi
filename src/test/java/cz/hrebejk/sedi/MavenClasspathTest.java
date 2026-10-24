/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

import junit.framework.TestCase;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Unit test for MavenClasspath.
 */
public class MavenClasspathTest extends TestCase {

    /**
     * Tests parsing of a classpath string.
     */
    public void testParse() {
        String cp = "/a/b.jar" + File.pathSeparator + "/c/d.jar" + File.pathSeparator + "\n";
        assertEquals(List.of(Path.of("/a/b.jar"), Path.of("/c/d.jar")), MavenClasspath.parse(cp));
        assertTrue(MavenClasspath.parse("").isEmpty());
    }
}
