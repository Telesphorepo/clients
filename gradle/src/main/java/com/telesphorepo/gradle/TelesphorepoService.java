package com.telesphorepo.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mints one publish session per repository on first use, mints it again when it has less than a
 * minute left, and closes every session it minted when the build ends. The proof is, in order: a GitHub Actions identity token, the identity token in
 * the environment variable named by {@code telesphorepo.oidcTokenEnv}, the device key of this
 * computer. Outside CI, a build with no accepted device enrolls one and waits for the approval.
 */
public abstract class TelesphorepoService implements BuildService<TelesphorepoService.Params>, AutoCloseable {
    /** The HTTP Basic username sent with a session token. The server ignores it. */
    static final String SESSION_USER = "telesphorepo";

    private static final Logger LOG = Logging.getLogger(TelesphorepoService.class);

    public interface Params extends BuildServiceParameters {
        Property<String> getUrl();

        Property<String> getOidcTokenEnv();
    }

    /** A session is minted again when the cached one has less than this left. */
    private static final long MIN_REMAINING_SECONDS = 60;

    private static final class Session {
        final String id;
        final String token;
        final String repo;
        final long expiresAt;

        Session(String id, String token, String repo, long expiresAt) {
            this.id = id;
            this.token = token;
            this.repo = repo;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Session> current = new HashMap<>();
    private final List<Session> minted = new ArrayList<>();
    private volatile Api api;
    private DeviceKey device;

    private Api api() {
        Api a = api;
        if (a == null) {
            synchronized (this) {
                if (api == null) {
                    api = new Api(getParameters().getUrl().get());
                }
                a = api;
            }
        }
        return a;
    }

    /**
     * Returns the repository name when repo is a Maven repository on the configured server
     * ({@code <url>/maven/<name>/...}), or null for any other URL. The scheme is not compared.
     */
    String repoName(URI repo) {
        if (!getParameters().getUrl().isPresent()) {
            return null;
        }
        return repoName(api().base, repo);
    }

    /** Returns the repository name when repo is {@code <base>/maven/<name>/...}, or null. */
    static String repoName(URI base, URI repo) {
        if (repo == null || repo.getHost() == null || repo.getPath() == null) {
            return null;
        }
        if (!repo.getHost().equalsIgnoreCase(base.getHost()) || port(repo) != port(base)) {
            return null;
        }
        String prefix = (base.getPath() == null ? "" : base.getPath()) + "/maven/";
        if (!repo.getPath().startsWith(prefix)) {
            return null;
        }
        String rest = repo.getPath().substring(prefix.length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        return name.isEmpty() ? null : name;
    }

    private static int port(URI u) {
        if (u.getPort() != -1) {
            return u.getPort();
        }
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    /** Returns the session token for repo, minting a session when there is none or it ends soon. */
    synchronized String token(String repo) {
        Session s = current.get(repo);
        if (s != null && s.expiresAt - Instant.now().getEpochSecond() > MIN_REMAINING_SECONDS) {
            return s.token;
        }
        s = mint(repo);
        current.put(repo, s);
        minted.add(s);
        return s.token;
    }

    private Session mint(String repo) {
        Api a = api();
        Map<String, Object> resp;
        String via;
        String oidc = ciToken(a);
        if (oidc != null) {
            via = "a CI identity token";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("token", oidc);
            body.put("repo", repo);
            try {
                resp = a.post("/api/publish/sessions/oidc", body);
            } catch (Api.ApiException e) {
                throw e.status == 404 ? noRepo(a, repo, e) : e;
            }
        } else {
            DeviceKey device = device(a);
            via = "device " + device.deviceId;
            resp = mintWithDevice(a, device, repo);
        }
        String id = Json.text(resp, "id");
        String token = Json.string(resp, "token");
        if (id == null || token == null) {
            throw new GradleException("The server's publish session answer has no id or token");
        }
        LOG.lifecycle("Telesphorepo: publish session {} for {} ({})", id, repo, via);
        return new Session(id, token, repo, epochSeconds(resp.get("expires_at")));
    }

    /** Reads expires_at as unix seconds, unix milliseconds or an RFC 3339 time. */
    private static long epochSeconds(Object v) {
        if (v instanceof Number) {
            long n = ((Number) v).longValue();
            return n > 100_000_000_000L ? n / 1000 : n;
        }
        if (v instanceof String) {
            try {
                return OffsetDateTime.parse((String) v).toEpochSecond();
            } catch (DateTimeParseException e) {
                // Fall through to the minimum lifetime below.
            }
        }
        // An unreadable expiry: reuse the session for at most two minutes.
        return Instant.now().getEpochSecond() + MIN_REMAINING_SECONDS * 2;
    }

    /**
     * Returns this computer's device, once per build. When the server accepts no saved device, a
     * build on a developer's computer enrolls one now and waits for the approval. A CI build
     * fails with what the job must add.
     */
    private synchronized DeviceKey device(Api a) {
        if (device != null) {
            return device;
        }
        DeviceKey d = DeviceLogin.accepted(a, LOG);
        if (d == null) {
            Map<String, String> env = System.getenv();
            if (Ci.detected(env)) {
                throw new GradleException(Ci.noToken(env, getParameters().getOidcTokenEnv().getOrNull(), a.base));
            }
            d = DeviceLogin.enroll(a, LOG);
        }
        device = d;
        return d;
    }

    private static Map<String, Object> mintWithDevice(Api a, DeviceKey device, String repo) {
        try {
            String nonce = DeviceLogin.challenge(a, device);
            String msg = "telesphorepo publish v1\n" + nonce + "\n" + device.deviceId + "\n" + repo;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("device_id", device.deviceId);
            body.put("repo", repo);
            body.put("nonce", nonce);
            body.put("signature", device.sign(msg));
            return a.post("/api/publish/sessions", body);
        } catch (Api.ApiException e) {
            if (e.status == 404) {
                throw noRepo(a, repo, e);
            }
            if (e.status == 401) {
                throw new GradleException("The server refused device " + device.deviceId + " (" + e.error + "). Delete "
                    + DeviceKey.file(a.base) + " and publish again to approve this computer again.", e);
            }
            throw e;
        }
    }

    private static GradleException noRepo(Api a, String repo, Api.ApiException e) {
        return new GradleException("The repository \"" + repo + "\" does not exist on " + a.base
            + ". Check the repository name in settings.gradle(.kts), or the URL of the publishing repository.", e);
    }

    /** Returns a CI identity token from GitHub Actions or from oidcTokenEnv, or null when there is none. */
    private String ciToken(Api a) {
        String reqUrl = System.getenv("ACTIONS_ID_TOKEN_REQUEST_URL");
        if (reqUrl != null && !reqUrl.isEmpty()) {
            String reqToken = System.getenv("ACTIONS_ID_TOKEN_REQUEST_TOKEN");
            if (reqToken == null || reqToken.isEmpty()) {
                throw new GradleException("ACTIONS_ID_TOKEN_REQUEST_URL is set but ACTIONS_ID_TOKEN_REQUEST_TOKEN is not");
            }
            String audience = URLEncoder.encode(a.base.toString(), StandardCharsets.UTF_8);
            URI url = URI.create(reqUrl + (reqUrl.contains("?") ? "&" : "?") + "audience=" + audience);
            String token = Json.string(a.getBearer("GitHub Actions identity token request", url, reqToken), "value");
            if (token == null || token.isEmpty()) {
                throw new GradleException("GitHub Actions returned no identity token");
            }
            return token;
        }
        String env = getParameters().getOidcTokenEnv().getOrNull();
        if (env != null && !env.isEmpty()) {
            String token = System.getenv(env);
            if (token != null && !token.trim().isEmpty()) {
                return token.trim();
            }
        }
        return null;
    }

    /** Ends every session this build minted. A failure is a warning: the session expires anyway. */
    @Override
    public synchronized void close() {
        for (Session s : minted) {
            try {
                api().delete("/api/publish/sessions/" + URLEncoder.encode(s.id, StandardCharsets.UTF_8), SESSION_USER, s.token);
            } catch (Api.ApiException ex) {
                // The session already ended (expired or revoked): nothing is left to close.
                if (ex.status != 401 && ex.status != 404) {
                    LOG.warn("Telesphorepo: could not close publish session {} for {}: {}", s.id, s.repo, ex.getMessage());
                }
            } catch (RuntimeException ex) {
                LOG.warn("Telesphorepo: could not close publish session {} for {}: {}", s.id, s.repo, ex.getMessage());
            }
        }
        minted.clear();
        current.clear();
    }
}
