package com.telesphorepo.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * JSON calls to one Telesphorepo server. Each route goes to the canonical URL of the
 * {@link ServerIdentity}, so a route call never leaves that server. Request and answer bodies are never
 * logged, and error messages never contain a token, a key or a code.
 */
public final class Http {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final ServerIdentity server;
    private final String userAgent;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** @param client the publisher client ID, for example {@code gradle}; it names the User-Agent */
    public Http(ServerIdentity server, String client) {
        this.server = server;
        this.userAgent = "telesphorepo-" + client;
    }

    public ServerIdentity server() {
        return server;
    }

    /** POSTs body as JSON to the route and returns the answer object. */
    public Map<String, Object> post(String route, Map<String, ?> body) {
        HttpRequest req = request(route)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        return send("POST " + route, req);
    }

    /** DELETEs the route with a publish session token as the HTTP Basic password. */
    public void delete(String route, String sessionToken) {
        String basic = Base64.getEncoder().encodeToString(
                (Protocol.SESSION_USER + ":" + sessionToken).getBytes(StandardCharsets.UTF_8));
        send("DELETE " + route, request(route).header("Authorization", "Basic " + basic).DELETE().build());
    }

    /**
     * GETs an absolute URL of another service with a bearer token and returns the answer object.
     * The CI token request of {@link CiToken} uses it.
     */
    Map<String, Object> getExternal(String what, URI url, String bearer) {
        HttpRequest req = HttpRequest.newBuilder(url)
                .timeout(TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Authorization", "bearer " + bearer)
                .GET()
                .build();
        return send(what, req);
    }

    private HttpRequest.Builder request(String route) {
        return HttpRequest.newBuilder(URI.create(server.base() + route))
                .timeout(TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");
    }

    private Map<String, Object> send(String what, HttpRequest req) {
        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ClientException(what + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException(what + " was interrupted", e);
        }
        String body = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() / 100 != 2) {
            throw new ApiException(what, resp.statusCode(), Protocol.ApiError.parse(body));
        }
        if (body.isEmpty()) {
            return Map.of();
        }
        try {
            return Json.parseObject(body);
        } catch (IllegalArgumentException e) {
            throw new ClientException(what + ": the answer is not a JSON object", e);
        }
    }
}
