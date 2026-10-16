package com.telesphorepo.client;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A CI identity token (OIDC): the GitHub Actions token of the job, else the token in the
 * environment variable that the build names. The audience of the GitHub Actions token is the
 * canonical URL of the server.
 */
final class CiToken {
    /** A token and the words for where it came from, for example "GitHub Actions". */
    record Found(String token, String source) {
    }

    private CiToken() {
    }

    /** True when {@link #find} has a token without a request. */
    static boolean available(Map<String, String> env, String oidcTokenEnv) {
        return githubActions(env) || fromEnv(env, oidcTokenEnv) != null;
    }

    /** Returns the CI identity token, or null when the build has none. */
    static Found find(Map<String, String> env, String oidcTokenEnv, Http http) {
        if (githubActions(env)) {
            String reqUrl = env.get("ACTIONS_ID_TOKEN_REQUEST_URL");
            URI url = URI.create(reqUrl + (reqUrl.contains("?") ? "&" : "?") + "audience="
                    + URLEncoder.encode(http.server().base(), StandardCharsets.UTF_8));
            Map<String, Object> answer = http.getExternal("GitHub Actions identity token request", url,
                    env.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN"));
            if (!(answer.get("value") instanceof String token) || token.isEmpty()) {
                throw new ClientException("GitHub Actions returned no identity token");
            }
            return new Found(token, "GitHub Actions");
        }
        String token = fromEnv(env, oidcTokenEnv);
        return token == null ? null : new Found(token, "the CI identity token in " + oidcTokenEnv);
    }

    private static boolean githubActions(Map<String, String> env) {
        return Environment.set(env.get("ACTIONS_ID_TOKEN_REQUEST_URL")) && Environment.set(env.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN"));
    }

    /**
     * The token in the variable that oidcTokenEnv names, or null when the variable is unset or
     * empty. An empty variable leaves the device key as the proof, so a build can name the
     * variable for its CI and still publish from a developer's computer.
     */
    private static String fromEnv(Map<String, String> env, String oidcTokenEnv) {
        if (!Environment.set(oidcTokenEnv)) {
            return null;
        }
        String token = env.get(oidcTokenEnv);
        return Environment.set(token) ? token.trim() : null;
    }
}
