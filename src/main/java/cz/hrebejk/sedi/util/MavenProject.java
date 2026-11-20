/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi.util;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the modules of a Maven project, a simple one or one with {@code <modules>}, using the Maven model builder
 * and the Maven Resolver API. No Maven installation is needed. Parent POMs are only found through
 * {@code relativePath}. The modules of the project (its reactor) are never looked for in a repository: a module
 * another one depends on contributes its source roots instead of a jar, so nothing has to be built or installed.
 */
public final class MavenProject {

    private static final String POM = "pom.xml";

    /**
     * A module of the project, with what is needed to compile its sources. Paths of directories in the project
     * are {@code /} separated and relative to the project directory, {@code ""} standing for the directory itself.
     *
     * @param dir         the directory of the module
     * @param roots       the source roots of the module itself: its main and test source directories which exist
     * @param sourceRoots the roots the sources of the module are compiled with: its own, then those of the modules
     *                    of the project it depends on
     * @param classpath   the jars of its dependencies outside the project, of all scopes
     */
    public record Module(String dir, List<String> roots, List<String> sourceRoots, List<Path> classpath) {

        /** Makes the lists unmodifiable. */
        public Module {
            roots = List.copyOf(roots);
            sourceRoots = List.copyOf(sourceRoots);
            classpath = List.copyOf(classpath);
        }
    }

    /** A POM of the project with its effective model. */
    private record Pom(Path dir, Model model) {
    }

    private MavenProject() {
    }

    /**
     * Reads the modules of the Maven project in the given directory, recursively, and resolves their
     * dependencies (including provided and test scope ones). Missing artifacts are downloaded into the local
     * repository. Modules without sources, such as aggregators, are left out. If no module has sources, the
     * result is the project directory itself as the only source root, with the dependencies of its POM.
     *
     * @param projectDir directory containing {@code pom.xml}
     * @return the modules with sources, in the order of the POMs
     * @throws IOException if a POM cannot be read or the dependencies cannot be resolved
     */
    public static List<Module> load(Path projectDir) throws IOException {
        Path base = projectDir.toAbsolutePath().normalize();
        Map<String, Pom> reactor = new LinkedHashMap<>();
        collect(base, reactor);
        Resolver resolver = new Resolver(reactor);

        List<Module> modules = new ArrayList<>();
        for (Pom pom : reactor.values()) {
            List<String> roots = roots(base, pom, true);
            if (roots.isEmpty()) {
                continue;
            }
            modules.add(module(base, pom, roots, resolver));
        }
        if (modules.isEmpty()) {
            Pom root = reactor.values().iterator().next();
            Module module = module(base, root, List.of(""), resolver);
            modules.add(new Module(relative(base, root.dir()), List.of(""), List.of(""), module.classpath()));
        }
        return modules;
    }

    /** Resolves the module, putting the modules of the project it depends on on its source path. */
    private static Module module(Path base, Pom pom, List<String> roots, Resolver resolver) throws IOException {
        Set<String> sourceRoots = new LinkedHashSet<>(roots);
        List<Path> classpath = new ArrayList<>();
        for (Artifact artifact : resolver.resolve(pom)) {
            Pom sibling = resolver.reactor.get(key(artifact));
            if (sibling == null) {
                classpath.add(artifact.getFile().toPath());
            } else {
                sourceRoots.addAll(roots(base, sibling, "tests".equals(artifact.getClassifier())));
            }
        }
        return new Module(relative(base, pom.dir()), roots, List.copyOf(sourceRoots), classpath);
    }

    /** Adds the POM in the directory and, recursively, those of its modules. */
    private static void collect(Path dir, Map<String, Pom> reactor) throws IOException {
        Model model = buildEffectiveModel(dir.resolve(POM));
        reactor.putIfAbsent(model.getGroupId() + ":" + model.getArtifactId(), new Pom(dir, model));
        for (String module : model.getModules()) {
            Path moduleDir = dir.resolve(module).normalize();
            // A module may name its POM file instead of its directory.
            collect(Files.isRegularFile(moduleDir) ? moduleDir.getParent() : moduleDir, reactor);
        }
    }

    /**
     * The existing source roots of the POM relative to the base: the main one and, if asked for, the test one.
     */
    private static List<String> roots(Path base, Pom pom, boolean tests) {
        List<String> roots = new ArrayList<>();
        var build = pom.model().getBuild();
        if (build == null) {
            return roots;
        }
        List<String> directories = new ArrayList<>();
        directories.add(build.getSourceDirectory());
        if (tests) {
            directories.add(build.getTestSourceDirectory());
        }
        for (String directory : directories) {
            if (directory == null) {
                continue;
            }
            Path root = pom.dir().resolve(directory).normalize();
            if (Files.isDirectory(root) && root.startsWith(base)) {
                roots.add(relative(base, root));
            }
        }
        return roots;
    }

    private static String relative(Path base, Path path) {
        return base.relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static String key(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId();
    }

    private static Model buildEffectiveModel(Path pom) throws IOException {
        ModelBuildingRequest request = new DefaultModelBuildingRequest()
                .setPomFile(pom.toFile())
                .setSystemProperties(System.getProperties())
                .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
                .setProcessPlugins(false)
                .setTwoPhaseBuilding(false);
        try {
            return new DefaultModelBuilderFactory().newInstance().build(request).getEffectiveModel();
        } catch (ModelBuildingException e) {
            throw new IOException("Cannot read " + pom + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the dependencies of the modules. The modules of the project are found by a {@link WorkspaceReader}:
     * their POMs where they are, so their own dependencies are resolved as by Maven, and the module directory in
     * place of any other artifact, never put on a classpath.
     */
    private static final class Resolver {

        private final Map<String, Pom> reactor;
        private final RepositorySystem system;
        private final DefaultRepositorySystemSession session;

        Resolver(Map<String, Pom> reactor) {
            this.reactor = reactor;
            DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
            locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
            locator.addService(TransporterFactory.class, FileTransporterFactory.class);
            locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
            system = locator.getService(RepositorySystem.class);

            session = MavenRepositorySystemUtils.newSession();
            Path localRepoDir = Path.of(System.getProperty("user.home"), ".m2", "repository");
            session.setLocalRepositoryManager(
                    system.newLocalRepositoryManager(session, new LocalRepository(localRepoDir.toFile())));
            session.setSystemProperties(System.getProperties());
            session.setWorkspaceReader(new Workspace());
        }

        /** The resolved artifacts the module depends on, transitively, those of the project included. */
        List<Artifact> resolve(Pom pom) throws IOException {
            Model model = pom.model();
            CollectRequest collect = new CollectRequest();
            collect.setRepositories(repositories(model));
            for (var d : model.getDependencies()) {
                collect.addDependency(toDependency(d));
            }
            if (model.getDependencyManagement() != null) {
                for (var d : model.getDependencyManagement().getDependencies()) {
                    collect.addManagedDependency(toDependency(d));
                }
            }
            try {
                return system.resolveDependencies(session, new DependencyRequest(collect, null))
                        .getArtifactResults().stream().map(ArtifactResult::getArtifact).toList();
            } catch (DependencyResolutionException e) {
                throw new IOException("Cannot resolve dependencies of " + pom.dir().resolve(POM), e);
            }
        }

        private Dependency toDependency(org.apache.maven.model.Dependency d) {
            ArtifactType type = session.getArtifactTypeRegistry().get(d.getType());
            Artifact artifact = new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(),
                    type != null ? type.getExtension() : d.getType(), d.getVersion(), type);
            List<Exclusion> exclusions = d.getExclusions().stream()
                    .map(e -> new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"))
                    .toList();
            return new Dependency(artifact, d.getScope() == null ? "compile" : d.getScope(), d.isOptional(),
                    exclusions);
        }

        private static List<RemoteRepository> repositories(Model model) {
            List<RemoteRepository> repos = new ArrayList<>();
            for (var r : model.getRepositories()) {
                repos.add(new RemoteRepository.Builder(r.getId(), "default", r.getUrl()).build());
            }
            if (repos.stream().noneMatch(r -> r.getId().equals("central"))) {
                repos.add(new RemoteRepository.Builder("central", "default",
                        "https://repo.maven.apache.org/maven2/").build());
            }
            return repos;
        }

        /** Finds the modules of the project, whatever version is asked for. */
        private final class Workspace implements WorkspaceReader {

            private final WorkspaceRepository repository = new WorkspaceRepository("reactor");

            @Override
            public WorkspaceRepository getRepository() {
                return repository;
            }

            @Override
            public File findArtifact(Artifact artifact) {
                Pom pom = reactor.get(key(artifact));
                if (pom == null) {
                    return null;
                }
                return "pom".equals(artifact.getExtension()) ? pom.dir().resolve(POM).toFile() : pom.dir().toFile();
            }

            @Override
            public List<String> findVersions(Artifact artifact) {
                Pom pom = reactor.get(key(artifact));
                return pom == null ? List.of() : List.of(pom.model().getVersion());
            }
        }
    }
}
