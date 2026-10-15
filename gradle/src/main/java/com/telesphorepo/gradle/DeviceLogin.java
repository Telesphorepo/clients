package com.telesphorepo.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The device of this computer: the saved one when the server still accepts it, else a new one
 * that the developer approves in a browser. The key is in {@code ~/.telesphorepo/<host>/device.json}.
 */
final class DeviceLogin {
    private DeviceLogin() {
    }

    /** Asks the server for a nonce that device signs. A device the server does not accept gets HTTP 401. */
    static String challenge(Api api, DeviceKey device) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", device.deviceId);
        String nonce = Json.string(api.post("/api/publish/challenge", body), "nonce");
        if (nonce == null) {
            throw new GradleException("The server's challenge answer has no nonce");
        }
        return nonce;
    }

    /** Returns the saved device when the server accepts it, or null when there is none or the server refuses it. */
    static DeviceKey accepted(Api api, Logger log) {
        DeviceKey device = DeviceKey.load(api.base);
        if (device == null) {
            return null;
        }
        try {
            challenge(api, device);
            return device;
        } catch (Api.ApiException e) {
            if (e.status != 401) {
                throw e;
            }
            log.quiet("Telesphorepo: the server no longer accepts device {} ({}).", device.deviceId, e.error);
            return null;
        }
    }

    /**
     * Enrolls this computer: it generates an Ed25519 key, asks the server for a code, prints the
     * approval link, opens it in the browser when it can, waits until the developer approves the
     * code, and saves the key.
     */
    static DeviceKey enroll(Api api, Logger log) {
        KeyPair keys = DeviceKey.generate();
        Map<String, Object> enroll = new LinkedHashMap<>();
        enroll.put("name", deviceName());
        enroll.put("kind", "gradle");
        enroll.put("public_key", DeviceKey.publicKey(keys.getPublic()));
        Map<String, Object> resp = api.post("/api/devices/enroll", enroll);
        String deviceCode = Json.string(resp, "device_code");
        String userCode = Json.string(resp, "user_code");
        String verificationUrl = Json.string(resp, "verification_url");
        if (deviceCode == null || userCode == null || verificationUrl == null) {
            throw new GradleException("The server's enrollment answer is incomplete");
        }
        long interval = Math.max(1, Json.number(resp, "interval", 5));
        long expiresIn = Json.number(resp, "expires_in", 600);
        long deadline = System.currentTimeMillis() + 1000 * expiresIn;

        String link = verificationUrl + "?code=" + userCode;
        log.quiet("Telesphorepo: approve this computer to publish to {}.", api.base);
        log.quiet("Open {} and approve code {}", link, userCode);
        if (openBrowser(link)) {
            log.quiet("Telesphorepo opened the link in your browser.");
        }
        log.quiet("Waiting for the approval. The code works for {} minutes.", Math.max(1, expiresIn / 60));

        Map<String, Object> poll = new LinkedHashMap<>();
        poll.put("device_code", deviceCode);
        String expired = "The code " + userCode + " expired before anyone approved it. Run the publish again.";
        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                throw new GradleException(expired);
            }
            sleep(interval);
            try {
                long deviceId = Json.number(api.post("/api/devices/enroll/poll", poll), "device_id", 0);
                if (deviceId <= 0) {
                    throw new GradleException("The server's poll answer has no device_id");
                }
                DeviceKey device = DeviceKey.save(api.base, deviceId, keys.getPrivate());
                log.quiet("Telesphorepo: this computer is device {}. Its key is in {}", deviceId, DeviceKey.file(api.base));
                return device;
            } catch (Api.ApiException e) {
                switch (e.error) {
                    case "authorization_pending":
                        break;
                    case "slow_down":
                        interval += 5;
                        break;
                    case "expired_token":
                        throw new GradleException(expired);
                    default:
                        throw e;
                }
            }
        }
    }

    /** Opens url in the default browser. Returns false when this computer has no browser to open. */
    private static boolean openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd;
        if (os.contains("win")) {
            cmd = List.of("rundll32", "url.dll,FileProtocolHandler", url);
        } else if (os.contains("mac")) {
            cmd = List.of("open", url);
        } else if (set(System.getenv("DISPLAY")) || set(System.getenv("WAYLAND_DISPLAY"))) {
            cmd = List.of("xdg-open", url);
        } else {
            return false;
        }
        try {
            new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean set(String s) {
        return s != null && !s.isBlank();
    }

    private static void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("The device approval was interrupted", e);
        }
    }

    /** "user@host", the device name that the approval page and the device list show. */
    private static String deviceName() {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isEmpty()) {
            host = System.getenv("HOSTNAME");
        }
        if (host == null || host.isEmpty()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "unknown";
            }
        }
        return System.getProperty("user.name") + "@" + host;
    }
}
