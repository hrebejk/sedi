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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Unit test for MavenProject.
 */
public class MavenProjectTest extends TestCase {

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

            List<String> names = MavenProject.load(helper.getRepositoryPath()).getFirst().classpath().stream()
                    .map(Path::getFileName).map(Path::toString).toList();

            assertTrue(names.contains("junit-4.13.2.jar"));
            assertTrue(names.contains("hamcrest-core-1.3.jar"));
        }
    }
    private static final String JUNIT = """
                <dependency>
                  <groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version>
                </dependency>
            """;

    /** A POM of a module of the reactor, with {@code t:root:1} as its parent, and the given dependencies. */
    private static String modulePom(String artifactId, String dependencies) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>t</groupId><artifactId>root</artifactId><version>1</version></parent>
                  <artifactId>%s</artifactId>
                  <dependencies>
                %s
                  </dependencies>
                </project>
                """.formatted(artifactId, dependencies);
    }

    /** An aggregator POM with the given modules. */
    private static String aggregatorPom(String groupId, String artifactId, String... modules) {
        StringBuilder list = new StringBuilder();
        for (String module : modules) {
            list.append("<module>").append(module).append("</module>");
        }
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>1</version>
                  <packaging>pom</packaging>
                  <modules>%s</modules>
                </project>
                """.formatted(groupId, artifactId, list);
    }

    /** A dependency on a module of the reactor, with extra elements such as a type. */
    private static String sibling(String artifactId, String extra) {
        return "<dependency><groupId>t</groupId><artifactId>" + artifactId
                + "</artifactId><version>${project.version}</version>" + extra + "</dependency>";
    }

    private static MavenProject.Module module(List<MavenProject.Module> modules, String dir) {
        return modules.stream().filter(m -> m.dir().equals(dir)).findFirst().orElseThrow();
    }

    /** Writes the reactor of {@code core}, depending on junit, and {@code app}, depending on core as given. */
    private static void writeReactor(GitTestHelper helper, String coreDependency) throws Exception {
        helper.writeFile("pom.xml", aggregatorPom("t", "root", "core", "app"));
        helper.writeFile("core/pom.xml", modulePom("core", JUNIT));
        helper.writeFile("core/src/main/java/c/Core.java", "package c;\npublic class Core {}\n");
        helper.writeFile("core/src/test/java/c/CoreTest.java", "package c;\npublic class CoreTest {}\n");
        helper.writeFile("app/pom.xml", modulePom("app", coreDependency));
        helper.writeFile("app/src/main/java/a/App.java", "package a;\npublic class App {}\n");
    }

    /**
     * The modules of a reactor: the aggregator without sources is left out, a module depending on another has
     * its main sources on its source path, and the external dependencies, also those it gets through the other
     * module, on its classpath.
     */
    public void testReactor() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            writeReactor(helper, sibling("core", ""));

            List<MavenProject.Module> modules = MavenProject.load(helper.getRepositoryPath());

            assertEquals(List.of("core", "app"), modules.stream().map(MavenProject.Module::dir).toList());
            MavenProject.Module core = module(modules, "core");
            MavenProject.Module app = module(modules, "app");
            assertEquals(List.of("core/src/main/java", "core/src/test/java"), core.roots());
            assertEquals(List.of("app/src/main/java"), app.roots());
            assertTrue(app.sourceRoots().toString(), app.sourceRoots().contains("app/src/main/java"));
            assertTrue(app.sourceRoots().toString(), app.sourceRoots().contains("core/src/main/java"));
            assertFalse(app.sourceRoots().toString(), app.sourceRoots().contains("core/src/test/java"));
            assertTrue(core.sourceRoots().toString(), core.sourceRoots().stream().noneMatch(r -> r.startsWith("app")));

            List<String> names = app.classpath().stream().map(Path::getFileName).map(Path::toString).toList();
            assertTrue(names.toString(), names.contains("junit-4.13.2.jar"));
            assertTrue(names.toString(), names.contains("hamcrest-core-1.3.jar"));
            Path repo = helper.getRepositoryPath().toRealPath();
            for (MavenProject.Module module : modules) {
                for (Path entry : module.classpath()) {
                    assertFalse(entry.toString(), Files.isDirectory(entry));
                    assertFalse(entry.toString(), entry.toRealPath().startsWith(repo));
                }
            }
        }
    }

    /** A dependency on the tests of another module puts its test sources on the source path too. */
    public void testTestJarDependency() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            writeReactor(helper, sibling("core", "<type>test-jar</type><scope>test</scope>"));

            MavenProject.Module app = module(MavenProject.load(helper.getRepositoryPath()), "app");

            assertTrue(app.sourceRoots().toString(), app.sourceRoots().contains("core/src/test/java"));
        }
    }

    /** A dependency on the {@code tests} classifier of another module puts its test sources on the source path. */
    public void testTestsClassifierDependency() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            writeReactor(helper, sibling("core", "<classifier>tests</classifier>"));

            MavenProject.Module app = module(MavenProject.load(helper.getRepositoryPath()), "app");

            assertTrue(app.sourceRoots().toString(), app.sourceRoots().contains("core/src/test/java"));
        }
    }

    /** The modules of an aggregator which is itself a module are found. */
    public void testNestedAggregator() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("pom.xml", aggregatorPom("t", "root", "modules/a"));
            helper.writeFile("modules/a/pom.xml", """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>t</groupId><artifactId>root</artifactId><version>1</version>
                        <relativePath>../../pom.xml</relativePath>
                      </parent>
                      <artifactId>a</artifactId>
                      <packaging>pom</packaging>
                      <modules><module>x</module></modules>
                    </project>
                    """);
            helper.writeFile("modules/a/x/pom.xml", """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent><groupId>t</groupId><artifactId>a</artifactId><version>1</version></parent>
                      <artifactId>x</artifactId>
                    </project>
                    """);
            helper.writeFile("modules/a/x/src/main/java/x/X.java", "package x;\npublic class X {}\n");

            List<MavenProject.Module> modules = MavenProject.load(helper.getRepositoryPath());

            assertEquals(1, modules.size());
            assertEquals("modules/a/x", modules.getFirst().dir());
            assertEquals(List.of("modules/a/x/src/main/java"), modules.getFirst().roots());
        }
    }

    /** A project without source directories is one module whose source root is the project directory. */
    public void testNoSourceDirectories() throws Exception {
        try (GitTestHelper helper = new GitTestHelper()) {
            helper.writeFile("pom.xml", aggregatorPom("t", "root"));
            helper.writeFile("X.java", "public class X {}\n");

            List<MavenProject.Module> modules = MavenProject.load(helper.getRepositoryPath());

            assertEquals(1, modules.size());
            assertEquals("", modules.getFirst().dir());
            assertEquals(List.of(""), modules.getFirst().roots());
            assertEquals(List.of(""), modules.getFirst().sourceRoots());
        }
    }
}
