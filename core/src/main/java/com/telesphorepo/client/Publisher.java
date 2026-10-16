package com.telesphorepo.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mints one publish session per repository on first use, mints it again when it has less than a
 * minute left, and closes every session it minted at the end of the build. The proof is, in this
 * order: the GitHub Actions identity token, the identity token in the variable that
 * {@code oidcTokenEnv} names, the device key of this computer. Outside CI, a build with no device
 * that the server accepts enrolls one and waits for the approval. In CI, it fails with what the
 * job must add.
 */
public final class Publisher {
    /** A session is minted again when the cached one has less than this left. */
    private static final long MIN_REMAINING_SECONDS = 60;

    private final Http http;
    private final Environment.Tool tool;
    private final String oidcTokenEnv;
    private final Map<String, String> env;
    private final Logger log;
    private final Map<String, Protocol.PublishSession> current = new HashMap<>();
    private final List<Protocol.PublishSession> minted = new ArrayList<>();
    private DeviceFile device;

    /**
     * @param oidcTokenEnv the name of the variable that holds a CI identity token, or null
     * @param env          the environment of the build
     */
    public Publisher(ServerIdentity server, Environment.Tool tool, String oidcTokenEnv, Map<String, String> env, Logger log) {
        this.http = new Http(server, tool.client());
        this.tool = tool;
        this.oidcTokenEnv = Environment.set(oidcTokenEnv) ? oidcTokenEnv.trim() : null;
        this.env = env;
        this.log = log;
    }

    public ServerIdentity server() {
        return http.server();
    }

    /**
     * Makes sure that a proof is ready before the publish starts. Without a CI token, it checks
     * the saved device and, outside CI, enrolls one when the server accepts none.
     */
    public synchronized void ensureProof() {
        if (!CiToken.available(env, oidcTokenEnv)) {
            device();
        }
    }

    /** Returns an open session for repo, minting one when there is none or it ends within a minute. */
    public synchronized Protocol.PublishSession session(String repo) {
        Protocol.PublishSession s = current.get(repo);
        if (s != null && s.expiresAt().getEpochSecond() - Instant.now().getEpochSecond() > MIN_REMAINING_SECONDS) {
            return s;
        }
        s = mint(repo);
        current.put(repo, s);
        minted.add(s);
        return s;
    }

    /** Closes every session this publisher minted. A failure is a warning: the session expires anyway. */
    public synchronized void closeAll() {
        for (Protocol.PublishSession s : minted) {
            try {
                http.delete(Protocol.sessionRoute(s.id()), s.token());
            } catch (ApiException e) {
                // The session already ended (expired or revoked): nothing is left to close.
                if (e.status() != 401 && e.status() != 404) {
                    log.warn("cannot close " + s + ": " + e.getMessage());
                }
            } catch (ClientException e) {
                log.warn("cannot close " + s + ": " + e.getMessage());
            }
        }
        minted.clear();
        current.clear();
    }

    private Protocol.PublishSession mint(String repo) {
        CiToken.Found ci = CiToken.find(env, oidcTokenEnv, http);
        Protocol.PublishSession s;
        String via;
        if (ci != null) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("token", ci.token());
            req.put("repo", repo);
            s = post(Protocol.OIDC_SESSION, req, repo, null);
            via = ci.source();
        } else {
            DeviceFile d = device();
            DeviceKey key = d.key();
            String nonce;
            try {
                nonce = Enrollment.challenge(http, key);
            } catch (ApiException e) {
                throw e.status() == 401 ? refused(d, e) : e;
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("device_id", key.deviceId());
            req.put("repo", repo);
            req.put("nonce", nonce);
            req.put("signature", key.signPublish(nonce, repo));
            s = post(Protocol.DEVICE_SESSION, req, repo, d);
            via = "device " + d.deviceId();
        }
        log.info(s + " (" + via + ")");
        return s;
    }

    private Protocol.PublishSession post(String route, Map<String, Object> req, String repo, DeviceFile d) {
        Map<String, Object> answer;
        try {
            answer = http.post(route, req);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw new ClientException("the repository \"" + repo + "\" does not exist on " + server() + ". "
                        + tool.repoHint(), e);
            }
            throw d != null && e.status() == 401 ? refused(d, e) : e;
        }
        return Protocol.PublishSession.parse("POST " + route, repo, answer);
    }

    /** This computer's device, checked once per build. */
    private DeviceFile device() {
        if (device != null) {
            return device;
        }
        DeviceFile d = Enrollment.accepted(http, log);
        if (d == null) {
            if (Environment.ci(env)) {
                throw new ClientException(Environment.noToken(env, oidcTokenEnv, server(), tool));
            }
            d = Enrollment.enroll(http, tool.client(), Enrollment.defaultName(), log);
        }
        device = d;
        return d;
    }

    private ClientException refused(DeviceFile d, ApiException e) {
        return new ClientException("the server refused device " + d.deviceId() + " (" + e.error() + "). Delete "
                + DeviceFile.path(server()) + " and " + tool.verb() + " again to approve this computer again.", e);
    }
}
