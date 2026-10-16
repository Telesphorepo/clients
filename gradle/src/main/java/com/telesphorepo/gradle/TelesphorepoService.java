package com.telesphorepo.gradle;

import com.telesphorepo.client.ClientException;
import com.telesphorepo.client.Environment;
import com.telesphorepo.client.Publisher;
import com.telesphorepo.client.ServerIdentity;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import java.net.URI;

/**
 * The publish sessions of one build, through the shared {@link Publisher}: one session per
 * repository, minted when a task first publishes to it and closed when the build ends.
 */
public abstract class TelesphorepoService implements BuildService<TelesphorepoService.Params>, AutoCloseable {
    /** What the client messages say for Gradle. */
    static final Environment.Tool TOOL = new Environment.Tool("gradle", "publish",
            "oidcTokenEnv = \"%s\" in the telesphorepo block of settings.gradle(.kts)",
            "./gradlew publish",
            "Check the repository name in settings.gradle(.kts), or the URL of the publishing repository.");

    public interface Params extends BuildServiceParameters {
        Property<String> getUrl();

        Property<String> getOidcTokenEnv();
    }

    private Publisher publisher;

    /** Parses the server URL of the settings script. A bad URL fails the build. */
    static ServerIdentity server(String url) {
        try {
            return ServerIdentity.of(url);
        } catch (ClientException e) {
            throw new GradleException(e.getMessage(), e);
        }
    }

    private synchronized Publisher publisher() {
        if (publisher == null) {
            publisher = new Publisher(server(getParameters().getUrl().get()), TOOL,
                    getParameters().getOidcTokenEnv().getOrNull(), System.getenv(),
                    new GradleLog(Logging.getLogger(TelesphorepoService.class)));
        }
        return publisher;
    }

    /**
     * Returns the repository name when repo is a Maven repository on the configured server
     * ({@code <url>/maven/<name>/...}, with the same scheme, host and port), or null.
     */
    String repoName(URI repo) {
        if (!getParameters().getUrl().isPresent() || repo == null) {
            return null;
        }
        return publisher().server().repoOf(repo.toString(), "maven");
    }

    /** Returns the session token for repo, minting a session when there is none or it ends soon. */
    String token(String repo) {
        try {
            return publisher().session(repo).token();
        } catch (ClientException e) {
            throw new GradleException("Telesphorepo: cannot start a publish session for " + repo + ": " + e.getMessage(), e);
        }
    }

    /** Ends every session this build minted. */
    @Override
    public synchronized void close() {
        if (publisher != null) {
            publisher.closeAll();
        }
    }
}
