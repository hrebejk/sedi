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
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit test for GitTestHelper.
 */
public class GitTestHelperTest extends TestCase {

    /**
     * Tests repository creation, file writing, and committing.
     */
    public void testGitTestHelperLifecycle() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path repoPath = helper.getRepositoryPath();
            assertNotNull(repoPath);
            assertTrue(Files.exists(repoPath.resolve(".git")));

            Path filePath = helper.writeFile("Test.java", "public class Test {}");
            assertTrue(Files.exists(filePath));

            var commit = helper.commit("Initial commit");
            assertNotNull(commit);
        }
    }
}
