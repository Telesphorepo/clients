package com.telesphorepo.maven.extension;

import com.telesphorepo.client.ClientException;
import com.telesphorepo.client.Environment;
import com.telesphorepo.client.Logger;
import com.telesphorepo.client.Publisher;
import com.telesphorepo.client.ServerIdentity;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Named;
import javax.inject.Singleton;
import org.slf4j.LoggerFactory;

/**
 * The state of one Maven build: the configured server, the sessions minted so far, and whether
 * the current thread runs a deploy goal. The lifecycle participant, the event spy and the
 * authentication selector share it.
 */
@Named
@Singleton
public class PublishState {
    /** What the client messages say for Maven. */
    static final Environment.Tool TOOL = new Environment.Tool("maven", "deploy",
            "-Dtelesphorepo.oidcTokenEnv=%s in .mvn/maven.config",
            "mvn -B deploy",
            "Check the repository URL in <distributionManagement>.");

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(PublishState.class);

    /** Where a repository URL points: a repository of another server, or one on the configured server. */
    sealed interface Target permits Unrelated, Telesphorepo {
    }

    /** A repository of another server. Maven handles it unchanged. */
    record Unrelated() implements Target {
    }

    /** A Maven repository on the configured server. */
    record Telesphorepo(String repo) implements Target {
    }

    private static final Target UNRELATED = new Unrelated();

    private final ThreadLocal<Boolean> deploying = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Map<String, ClientException> failed = new ConcurrentHashMap<>();
    private volatile Publisher publisher;

    void configure(ServerIdentity server, String oidcTokenEnv) {
        publisher = new Publisher(server, TOOL, oidcTokenEnv, System.getenv(), new Logger() {
            @Override
            public void info(String line) {
                log.info("Telesphorepo: {}", line);
            }

            @Override
            public void warn(String line) {
                log.warn("Telesphorepo: {}", line);
            }

            @Override
            public void prompt(String line) {
                // Straight to stderr, so that the approval link and code show also with -q.
                System.err.println("Telesphorepo: " + line);
                System.err.flush();
            }
        });
    }

    /** Classifies a repository URL. Without a configured server, every URL is unrelated. */
    Target classify(String url) {
        Publisher p = publisher;
        String repo = p == null ? null : p.server().repoOf(url, "maven");
        return repo == null ? UNRELATED : new Telesphorepo(repo);
    }

    /**
     * Makes sure that a proof of identity is available. On a developer's computer, this can
     * enroll the computer and wait for the approval in the browser.
     */
    void ensureProof() {
        Publisher p = publisher;
        if (p != null) {
            p.ensureProof();
        }
    }

    void deployStarted() {
        deploying.set(Boolean.TRUE);
    }

    void deployEnded() {
        deploying.remove();
    }

    boolean deploying() {
        return deploying.get();
    }

    /**
     * Returns a publish session token for the repository, minting a session when needed. A failed
     * mint throws, and every later call for the same repository throws the same failure, so the
     * deploy stops with the server's message and never uses other credentials.
     */
    String token(Telesphorepo target) {
        ClientException earlier = failed.get(target.repo());
        if (earlier != null) {
            throw earlier;
        }
        try {
            return publisher.session(target.repo()).token();
        } catch (ClientException e) {
            ClientException stop = new ClientException("Telesphorepo: cannot start a publish session for repository "
                    + target.repo() + ": " + e.getMessage(), e);
            failed.put(target.repo(), stop);
            log.error(stop.getMessage());
            throw stop;
        }
    }

    /** Closes every session of this build. */
    void closeAll() {
        Publisher p = publisher;
        if (p != null) {
            p.closeAll();
        }
    }
}
