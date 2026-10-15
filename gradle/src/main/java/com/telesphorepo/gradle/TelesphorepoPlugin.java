package com.telesphorepo.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Task;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;

/**
 * Settings plugin {@code com.telesphorepo.publish}. Every Maven repository under
 * {@code <telesphorepo.url>/maven/} gets a publish session as its credentials when a task first
 * publishes to it; the sessions end with the build. With {@code telesphorepo.repository}, each
 * Java project publishes to that repository with no publishing block (see {@link AutoPublish}).
 * The root project gets {@code telesphorepoLogin}, which enrolls this computer as a device.
 */
public class TelesphorepoPlugin implements Plugin<Settings> {
    @Override
    public void apply(Settings settings) {
        TelesphorepoExtension ext = settings.getExtensions().create("telesphorepo", TelesphorepoExtension.class);
        Gradle gradle = settings.getGradle();

        Provider<TelesphorepoService> service = gradle.getSharedServices().registerIfAbsent(
            "telesphorepo", TelesphorepoService.class, spec -> {
                spec.getParameters().getUrl().set(ext.getUrl());
                spec.getParameters().getOidcTokenEnv().set(ext.getOidcTokenEnv());
            });

        gradle.rootProject(project -> project.getTasks().register("telesphorepoLogin", TelesphorepoLoginTask.class, task -> {
            task.setGroup("publishing");
            task.setDescription("Enrolls this computer as a device that can publish to the Telesphorepo server.");
            task.getUrl().set(ext.getUrl());
        }));

        // The settings script has run when the first project exists, so ext holds its values.
        gradle.allprojects(project -> AutoPublish.apply(project, ext));

        // Only the tasks that publish to the server get the credentials action. The check runs on
        // the task graph, because maven-publish sets the repository of a task after the
        // configureEach actions of other plugins. A configuration cache hit rebuilds the repository
        // with credentials from Gradle properties only, so these tasks cannot use the cache. Other
        // publish tasks stay as they are and keep the cache.
        gradle.getTaskGraph().whenReady(graph -> {
            for (Task task : graph.getAllTasks()) {
                if (task instanceof PublishToMavenRepository
                    && publishesToServer(ext, ((PublishToMavenRepository) task).getRepository())) {
                    task.notCompatibleWithConfigurationCache("Telesphorepo sets the repository credentials when the task runs");
                    task.usesService(service);
                    task.doFirst(new PublishCredentials(service));
                }
            }
        });
    }

    /** True when repo is a Maven repository on the configured server. */
    private static boolean publishesToServer(TelesphorepoExtension ext, MavenArtifactRepository repo) {
        String url = ext.getUrl().getOrNull();
        if (url == null || repo == null) {
            return false;
        }
        return TelesphorepoService.repoName(Api.normalize(url), repo.getUrl()) != null;
    }
}
