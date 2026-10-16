package com.telesphorepo.client;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The routes and messages of the publish API that the clients use. Each answer reads into a
 * record and fails with the route and the field when the server's answer does not match.
 */
public final class Protocol {
    public static final String ENROLL = "/api/devices/enroll";
    public static final String ENROLL_POLL = "/api/devices/enroll/poll";
    public static final String CHALLENGE = "/api/publish/challenge";
    public static final String DEVICE_SESSION = "/api/publish/sessions";
    public static final String OIDC_SESSION = "/api/publish/sessions/oidc";

    /** The HTTP Basic username sent with a publish session token. The server reads only the password. */
    public static final String SESSION_USER = "telesphorepo";

    /** The prefix of every publish session token. */
    public static final String SESSION_TOKEN_PREFIX = "trs_";

    private Protocol() {
    }

    /** The route that closes the publish session with this id. */
    public static String sessionRoute(long id) {
        return DEVICE_SESSION + "/" + id;
    }

    /** The body of {@code POST /api/devices/enroll}. {@code client} is a registered publisher client ID. */
    public record EnrollRequest(String name, String client, String publicKey) {
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("client", client);
            m.put("public_key", publicKey);
            return m;
        }
    }

    /** The answer of {@code POST /api/devices/enroll}. */
    public record Enrollment(String deviceCode, String userCode, String verificationUrl, long expiresIn, long interval) {
        static Enrollment parse(String route, Map<String, Object> m) {
            return new Enrollment(text(route, m, "device_code"), text(route, m, "user_code"),
                    text(route, m, "verification_url"), positive(route, m, "expires_in"), positive(route, m, "interval"));
        }
    }

    /** The answer of {@code POST /api/devices/enroll/poll} once someone approved the device. */
    public record PollResult(long deviceId) {
        static PollResult parse(String route, Map<String, Object> m) {
            return new PollResult(positive(route, m, "device_id"));
        }
    }

    /** The answer of {@code POST /api/publish/challenge}: a single-use nonce for the device to sign. */
    public record Challenge(String nonce) {
        static Challenge parse(String route, Map<String, Object> m) {
            return new Challenge(text(route, m, "nonce"));
        }
    }

    /**
     * An open publish session, the answer of the mint routes:
     * {@code {"id": 41, "token": "trs_...", "repo": "releases", "expires_at": "2026-10-15T12:00:00Z"}}.
     */
    public record PublishSession(long id, String token, String repo, Instant expiresAt) {
        /**
         * Reads the answer of a mint route for {@code repo}: {@code id} an integer above 0,
         * {@code token} starting with {@code trs_}, {@code repo} equal to the requested repo and
         * {@code expires_at} an RFC 3339 time.
         */
        static PublishSession parse(String route, String repo, Map<String, Object> m) {
            long id = positive(route, m, "id");
            String token = text(route, m, "token");
            if (!token.startsWith(SESSION_TOKEN_PREFIX) || token.length() == SESSION_TOKEN_PREFIX.length()) {
                throw invalid(route, "token");
            }
            if (!repo.equals(m.get("repo"))) {
                throw invalid(route, "repo");
            }
            Instant expires;
            try {
                expires = OffsetDateTime.parse(text(route, m, "expires_at")).toInstant();
            } catch (DateTimeParseException e) {
                throw invalid(route, "expires_at");
            }
            return new PublishSession(id, token, repo, expires);
        }

        @Override
        public String toString() {
            return "publish session " + id + " for " + repo;
        }
    }

    /** The body of an error answer: {@code {"error": "..."}}. */
    public record ApiError(String error) {
        /** Reads the error field of body. A body that is not a JSON object gives an empty error. */
        static ApiError parse(String body) {
            try {
                Object e = Json.parseObject(body).get("error");
                return new ApiError(e instanceof String s ? s : "");
            } catch (IllegalArgumentException e) {
                return new ApiError("");
            }
        }
    }

    private static String text(String route, Map<String, Object> m, String field) {
        if (m.get(field) instanceof String s && !s.isEmpty()) {
            return s;
        }
        throw invalid(route, field);
    }

    private static long positive(String route, Map<String, Object> m, String field) {
        if (m.get(field) instanceof Long n && n > 0) {
            return n;
        }
        throw invalid(route, field);
    }

    private static ClientException invalid(String route, String field) {
        return new ClientException("the server's answer to " + route + " has no valid \"" + field + "\"");
    }
}
