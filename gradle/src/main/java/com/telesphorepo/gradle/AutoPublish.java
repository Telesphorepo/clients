package com.telesphorepo.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.plugins.PluginManager;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;

import java.net.URI;

/**
 * Publishes each Java project to {@code telesphorepo.repository} with no publishing block in the
 * build file. A project with the {@code java} or {@code java-platform} plugin gets
 * {@code maven-publish}. After Gradle evaluates every project, each project with
 * {@code maven-publish} gets:
 * <ul>
 *   <li>the repository {@code telesphorepo} at {@code <url>/maven/<repository>}, unless the
 *       build file declares a repository on the server itself;</li>
 *   <li>the publication {@code maven} of the {@code java} or {@code javaPlatform} component,
 *       unless the project has a publication of its own. {@code java-gradle-plugin} makes its
 *       own publications, so a Gradle plugin project gets none from here.</li>
 * </ul>
 */
final class AutoPublish {
    static final String NAME = "telesphorepo";

    private AutoPublish() {
    }

    static void apply(Project project, TelesphorepoExtension ext) {
        String url = ext.getUrl().getOrNull();
        String repository = ext.getRepository().getOrNull();
        if (url == null || repository == null || repository.isBlank()) {
            return;
        }
        URI base = Api.normalize(url);
        URI target = URI.create(base + "/maven/" + repository.trim());
        PluginManager plugins = project.getPluginManager();
        plugins.withPlugin("java", p -> plugins.apply("maven-publish"));
        plugins.withPlugin("java-platform", p -> plugins.apply("maven-publish"));
        // Decide after the afterEvaluate callbacks of every project, so a publication or
        // repository that a build file adds in afterEvaluate counts. maven-publish adds the
        // publish tasks of each publication and repository from live callbacks on the publishing
        // containers, so the tasks exist before the task graph is ready.
        project.getGradle().projectsEvaluated(g ->
            plugins.withPlugin("maven-publish", p -> configure(project, base, target)));
    }

    private static void configure(Project project, URI base, URI target) {
        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        boolean declared = publishing.getRepositories().withType(MavenArtifactRepository.class).stream()
            .anyMatch(r -> TelesphorepoService.repoName(base, r.getUrl()) != null);
        if (!declared) {
            publishing.getRepositories().maven(r -> {
                r.setName(NAME);
                r.setUrl(target);
                // The URL comes from the settings script, so an http server is a deliberate choice.
                r.setAllowInsecureProtocol("http".equalsIgnoreCase(target.getScheme()));
            });
        }
        if (!publishing.getPublications().isEmpty() || project.getPluginManager().hasPlugin("java-gradle-plugin")) {
            return;
        }
        SoftwareComponent component = project.getComponents().findByName("java");
        if (component == null) {
            component = project.getComponents().findByName("javaPlatform");
        }
        if (component != null) {
            SoftwareComponent c = component;
            publishing.getPublications().create("maven", MavenPublication.class, pub -> pub.from(c));
        }
    }
}
