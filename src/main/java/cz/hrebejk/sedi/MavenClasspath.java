/*
 * Copyright (c) 2026 Petr Hrebejk
 * SPDX-License-Identifier: MIT
 *
 * Part of sedi, an experimental demonstrator written entirely by AI under the
 * direction of the author. Provided "as is", without warranty of any kind; use
 * at your own risk. See the LICENSE file for details.
 */
package cz.hrebejk.sedi;

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
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the classpath of a simple Maven project using the Maven model builder and the Maven Resolver API.
 * No Maven installation is needed. Parent POMs are only found through {@code relativePath}.
 */
public class MavenClasspath {

    private MavenClasspath() {
    }

    /**
     * Resolves the classpath (including provided and test scope dependencies) of the Maven project
     * in the given directory. Missing artifacts are downloaded into the local repository.
     *
     * @param projectDir directory containing {@code pom.xml}
     * @return the classpath entries
     * @throws IOException if the POM cannot be read or the dependencies cannot be resolved
     */
    public static List<Path> resolve(Path projectDir) throws IOException {
        Model model = buildEffectiveModel(projectDir.resolve("pom.xml"));

        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        RepositorySystem system = locator.getService(RepositorySystem.class);

        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        Path localRepoDir = Path.of(System.getProperty("user.home"), ".m2", "repository");
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(localRepoDir.toFile())));

        session.setSystemProperties(System.getProperties());
        CollectRequest collect = new CollectRequest();
        collect.setRepositories(repositories(model));
        for (var d : model.getDependencies()) {
            collect.addDependency(toDependency(d, session));
        }
        if (model.getDependencyManagement() != null) {
            for (var d : model.getDependencyManagement().getDependencies()) {
                collect.addManagedDependency(toDependency(d, session));
            }
        }

        List<Path> classpath = new ArrayList<>();
        try {
            for (ArtifactResult r : system.resolveDependencies(session, new DependencyRequest(collect, null))
                    .getArtifactResults()) {
                classpath.add(r.getArtifact().getFile().toPath());
            }
        } catch (DependencyResolutionException e) {
            throw new IOException("Cannot resolve dependencies of " + projectDir.resolve("pom.xml"), e);
        }
        return classpath;
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

    private static List<RemoteRepository> repositories(Model model) {
        List<RemoteRepository> repos = new ArrayList<>();
        for (var r : model.getRepositories()) {
            repos.add(new RemoteRepository.Builder(r.getId(), "default", r.getUrl()).build());
        }
        if (repos.stream().noneMatch(r -> r.getId().equals("central"))) {
            repos.add(new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/")
                    .build());
        }
        return repos;
    }

    private static Dependency toDependency(org.apache.maven.model.Dependency d,
                                           DefaultRepositorySystemSession session) {
        ArtifactType type = session.getArtifactTypeRegistry().get(d.getType());
        Artifact artifact = new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(),
                type != null ? type.getExtension() : d.getType(), d.getVersion(), type);
        List<Exclusion> exclusions = d.getExclusions().stream()
                .map(e -> new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"))
                .toList();
        return new Dependency(artifact, d.getScope() == null ? "compile" : d.getScope(), d.isOptional(), exclusions);
    }
}
