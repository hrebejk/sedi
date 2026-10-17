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
import org.eclipse.jgit.diff.DiffEntry;

import java.nio.file.Path;
import java.util.List;

/**
 * Unit test for GitInspector.
 */
public class GitInspectorTest extends TestCase {

    /**
     * Tests detecting changes in working copy and committed changes.
     */
    public void testGetChanges() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            Path repoPath = helper.getRepositoryPath();
            helper.writeFile("Hello.java", "public class Hello {}");
            helper.commit("Initial commit");

            GitInspector inspector = new GitInspector(repoPath);

            // No changes yet after commit
            List<DiffEntry> changes0 = inspector.getChanges();
            assertTrue(changes0.isEmpty());

            // Modify file in working tree (uncommitted change)
            helper.writeFile("Hello.java", "public class Hello { void m() {} }");
            List<DiffEntry> changes1 = inspector.getChanges();
            assertEquals(1, changes1.size());
            assertEquals("Hello.java", changes1.get(0).getNewPath());

            // Commit change
            helper.commit("Add method");

            // Now no working copy changes, but should detect change from latest commit vs parent
            List<DiffEntry> changes2 = inspector.getChanges();
            assertEquals(1, changes2.size());
            assertEquals("Hello.java", changes2.get(0).getNewPath());
        }
    }
}
