package com.telesphorepo.gradle;

import org.gradle.api.provider.Property;

/**
 * The {@code telesphorepo { }} block in the settings script.
 *
 * <pre>
 * telesphorepo {
 *     url = "https://repo.acme.com"
 *     repository = "releases"     // optional: every Java project publishes to this repository
 *     oidcTokenEnv = "OIDC_TOKEN" // optional: a CI identity token, e.g. from Jenkins
 * }
 * </pre>
 */
public abstract class TelesphorepoExtension {
    /** The server's base URL. Maven repositories under {@code <url>/maven/} get publish sessions. */
    public abstract Property<String> getUrl();

    /**
     * The repository on the server that each Java project publishes to. Optional. When it is
     * set, the plugin applies {@code maven-publish} to each Java project, adds this repository,
     * and adds a publication to a project that has none of its own.
     */
    public abstract Property<String> getRepository();

    /** The name of an environment variable that holds a CI identity token (OIDC). Optional. */
    public abstract Property<String> getOidcTokenEnv();
}
