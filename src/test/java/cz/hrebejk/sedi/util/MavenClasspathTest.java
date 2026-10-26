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

import java.nio.file.Path;
import java.util.List;

/**
 * Unit test for MavenClasspath.
 */
public class MavenClasspathTest extends TestCase {

    /**
     * Tests resolving the classpath of a POM with a property-versioned dependency and its transitive dependency.
     */
    public void testResolve() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("pom.xml", """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>t</groupId><artifactId>t</artifactId><version>1</version>
                      <properties><junit.version>4.13.2</junit.version></properties>
                      <dependencies>
                        <dependency>
                          <groupId>junit</groupId><artifactId>junit</artifactId>
                          <version>${junit.version}</version><scope>test</scope>
                        </dependency>
                      </dependencies>
                    </project>
                    """);

            List<String> names = MavenClasspath.resolve(helper.getRepositoryPath()).stream()
                    .map(Path::getFileName).map(Path::toString).toList();

            assertTrue(names.contains("junit-4.13.2.jar"));
            assertTrue(names.contains("hamcrest-core-1.3.jar"));
        }
    }
}
