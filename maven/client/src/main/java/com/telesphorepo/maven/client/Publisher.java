package com.telesphorepo.maven.client;

import java.io.IOException;
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
import java.util.function.Consumer;

/**
 * Mints one publish session per Telesphorepo repository and closes them all at the end of the
 * build. The proof of identity is, in this order: the GitHub Actions identity token (when
 * {@code ACTIONS_ID_TOKEN_REQUEST_URL} and {@code ACTIONS_ID_TOKEN_REQUEST_TOKEN} are set), the
 * OIDC token in the environment variable named by {@code oidcTokenEnv}, and the device key of
 * this computer in {@code ~/.telesphorepo/<host>/device.json}. Outside CI, a build with no
 * device that the server accepts enrolls one and waits for the approval.
 */
public final class Publisher {
    /** A session is minted again when the cached one has less than this left. */
    private static final long MIN_REMAINING_SECONDS = 60;

    private final ServerUrl server;
    private final String oidcTokenEnv;
    private final Map<String, String> env;
    private final Consumer<String> out;
    private final Api api;
    private final Map<String, Session> current = new HashMap<>();
    private final List<Session> minted = new ArrayList<>();
    private DeviceKey device;

    /**
     * @param oidcTokenEnv the name of the variable that holds a CI's OIDC token, or null
     * @param out          receives the lines of a device enrollment
     */
    public Publisher(ServerUrl server, String oidcTokenEnv, Map<String, String> env, Consumer<String> out) {
        this.server = server;
        this.oidcTokenEnv = oidcTokenEnv == null || oidcTokenEnv.isBlank() ? null : oidcTokenEnv.trim();
        this.env = env;
        this.out = out;
        this.api = new Api(server);
    }

    public ServerUrl server() {
        return server;
    }

    /** An open publish session. {@code token} is the HTTP Basic password for the publish. */
    public static final class Session {
        public final String id;
        public final String token;
        public final String repo;
        public final long expiresAt;
        /** The proof that minted the session, for example "device 7". */
        public final String via;

        Session(String id, String token, String repo, long expiresAt, String via) {
            this.id = id;
            this.token = token;
            this.repo = repo;
            this.expiresAt = expiresAt;
            this.via = via;
        }

        @Override
        public String toString() {
            return "publish session " + id + " for " + repo + " (" + via + ")";
        }
    }

    /**
     * Makes sure that a proof of identity is available. Without a CI token, it checks the saved
     * device and, outside CI, enrolls one when the server accepts none. In CI with no token, it
     * fails with what the job must add.
     */
    public synchronized void ensureProof() throws IOException {
        if (!githubAvailable() && envToken() == null) {
            device();
        }
    }

    /** Returns an open session for {@code repo}, minting one when there is none or it ends soon. */
    public synchronized Session session(String repo) throws IOException {
        Session s = current.get(repo);
        long now = Instant.now().getEpochSecond();
        if (s != null && s.expiresAt - now > MIN_REMAINING_SECONDS) {
            return s;
        }
        s = mint(repo);
        current.put(repo, s);
        minted.add(s);
        return s;
    }

    /** Closes every session this publisher minted. {@code warn} receives failures. */
    public synchronized void closeAll(Consumer<String> warn) {
        for (Session s : minted) {
            try {
                api.delete("/api/publish/sessions/" + URLEncoder.encode(s.id, StandardCharsets.UTF_8), s.token);
            } catch (ApiException e) {
                // The session already ended (expired or revoked): nothing is left to close.
                if (e.status() != 401 && e.status() != 404) {
                    warn.accept("cannot close " + s + ": " + e.getMessage());
                }
            } catch (IOException e) {
                warn.accept("cannot close " + s + ": " + e.getMessage());
            }
        }
        minted.clear();
        current.clear();
    }

    /** This computer's device, checked once per build; see {@link #ensureProof}. */
    private DeviceKey device() throws IOException {
        if (device != null) {
            return device;
        }
        DeviceKey d = DeviceLogin.accepted(api, out);
        if (d == null) {
            if (Ci.detected(env)) {
                throw new IOException(Ci.noToken(env, oidcTokenEnv, server));
            }
            d = DeviceLogin.enroll(api, DeviceLogin.defaultName(), "maven", out);
        }
        device = d;
        return d;
    }

    private Session mint(String repo) throws IOException {
        if (githubAvailable()) {
            String token = api.githubIdToken(env.get("ACTIONS_ID_TOKEN_REQUEST_URL"),
                    env.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN"), server.base());
            return oidc(token, repo, "GitHub Actions");
        }
        String envToken = envToken();
        if (envToken != null) {
            return oidc(envToken, repo, "the CI identity token in " + oidcTokenEnv);
        }
        DeviceKey d = device();
        String nonce;
        try {
            nonce = DeviceLogin.challenge(api, d);
        } catch (ApiException e) {
            throw e.status() == 401 ? refused(d, e) : e;
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("device_id", d.deviceId());
        req.put("repo", repo);
        req.put("nonce", nonce);
        req.put("signature", d.signPublish(nonce, repo));
        try {
            return session(api.post("/api/publish/sessions", req, null), repo, "device " + d.deviceId());
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw noRepo(repo, e);
            }
            throw e.status() == 401 ? refused(d, e) : e;
        }
    }

    private IOException refused(DeviceKey d, ApiException e) {
        return new IOException("the server refused device " + d.deviceId() + " (" + e.error() + "). Delete "
                + DeviceKey.file(server) + " and deploy again to approve this computer again.", e);
    }

    private IOException noRepo(String repo, ApiException e) {
        return new IOException("the repository \"" + repo + "\" does not exist on " + server
                + ". Check the repository URL in <distributionManagement>.", e);
    }

    /**
     * The token in the variable named by oidcTokenEnv, or null when the variable is unset or
     * empty. An empty variable falls back to the device key, so a project can name the variable
     * for its CI and still publish from a developer computer.
     */
    private String envToken() {
        if (oidcTokenEnv == null) {
            return null;
        }
        String token = env.get(oidcTokenEnv);
        return token == null || token.isBlank() ? null : token.trim();
    }

    private Session oidc(String token, String repo, String via) throws IOException {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("token", token);
        req.put("repo", repo);
        try {
            return session(api.post("/api/publish/sessions/oidc", req, null), repo, via);
        } catch (ApiException e) {
            throw e.status() == 404 ? noRepo(repo, e) : e;
        }
    }

    private static Session session(Map<String, Object> m, String repo, String via) throws IOException {
        Object id = m.get("id");
        Object token = m.get("token");
        if (id == null || !(token instanceof String t) || t.isEmpty()) {
            throw new IOException("the publish session answer has no id or token");
        }
        return new Session(String.valueOf(id), t, repo, epochSeconds(m.get("expires_at")), via);
    }

    /** Reads expires_at as unix seconds, unix milliseconds or an RFC 3339 time. */
    private static long epochSeconds(Object v) {
        if (v instanceof Long n) {
            return n > 100_000_000_000L ? n / 1000 : n;
        }
        if (v instanceof String s) {
            try {
                return OffsetDateTime.parse(s).toEpochSecond();
            } catch (DateTimeParseException e) {
                // Fall through to the minimum lifetime below.
            }
        }
        // An unreadable expiry: reuse the session for at most two minutes.
        return Instant.now().getEpochSecond() + MIN_REMAINING_SECONDS * 2;
    }

    private boolean githubAvailable() {
        String url = env.get("ACTIONS_ID_TOKEN_REQUEST_URL");
        String token = env.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN");
        return url != null && !url.isBlank() && token != null && !token.isBlank();
    }
}
