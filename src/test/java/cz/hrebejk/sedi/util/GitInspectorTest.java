/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.util;

import cz.hrebejk.sedi.GitTestHelper;
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

    /**
     * Tests that the working copy is compared with HEAD whether the changes are staged, unstaged or untracked.
     */
    public void testWorkingCopyIgnoresStaging() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("A.java", "public class A {}");
            helper.writeFile("B.java", "public class B {}");
            helper.commit("Initial commit");

            helper.writeFile("A.java", "public class A { void staged() {} }");
            try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(helper.getRepositoryPath().toFile())) {
                git.add().addFilepattern("A.java").call();
            }
            helper.writeFile("B.java", "public class B { void unstaged() {} }");
            helper.writeFile("Untracked.java", "public class Untracked {}");

            List<DiffEntry> changes = new GitInspector(helper.getRepositoryPath()).getChanges();
            // A new file on disk builds, so it is a change even if git does not track it yet.
            assertEquals(List.of("A.java", "B.java", "Untracked.java"), changes.stream().map(DiffEntry::getNewPath).sorted().toList());
        }
    }

    /**
     * Tests that a file renamed and edited in the working copy is a rename, also when it is just similar enough
     * for the git command line (50%), as is a small class whose name changed.
     */
    public void testWorkingCopyRenameWithChanges() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("ClassV1.java", "package cz.hrebejk.misc;\n\npublic class ClassV1 {\n}\n");
            helper.commit("Initial commit");

            java.nio.file.Files.delete(helper.getRepositoryPath().resolve("ClassV1.java"));
            helper.writeFile("ClassV2.java", "package cz.hrebejk.misc;\n\npublic class ClassV2 {\n}\n");

            List<DiffEntry> changes = new GitInspector(helper.getRepositoryPath()).getChanges();
            assertEquals(1, changes.size());
            assertEquals(DiffEntry.ChangeType.RENAME, changes.get(0).getChangeType());
            assertEquals("ClassV1.java", changes.get(0).getOldPath());
            assertEquals("ClassV2.java", changes.get(0).getNewPath());

            // The files are 54% similar, too little for a higher score.
            List<DiffEntry> strict = new GitInspector(helper.getRepositoryPath(), 60).getChanges();
            assertEquals(List.of(DiffEntry.ChangeType.DELETE, DiffEntry.ChangeType.ADD),
                    strict.stream().map(DiffEntry::getChangeType).toList());
        }
    }

    /**
     * Tests that a rename score out of 0 - 100 is refused.
     */
    public void testInvalidRenameScore() {
        for (int score : new int[]{-1, 101}) {
            try {
                new GitInspector(Path.of("."), score);
                fail("Accepted score " + score);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }
}
