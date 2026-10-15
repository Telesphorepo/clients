package com.telesphorepo.maven.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.security.KeyPair;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The device of this computer: the saved one when the server still accepts it, else a new one
 * enrolled with the device flow. The server gives a user code, the developer approves it in a
 * browser, and the client polls until the server answers with a device id. The approved key is
 * saved with {@link DeviceKey#save}.
 */
public final class DeviceLogin {
    private DeviceLogin() {
    }

    /** Asks the server for a nonce that device signs. A device the server does not accept gets HTTP 401. */
    static String challenge(Api api, DeviceKey device) throws IOException {
        Map<String, Object> answer = api.post("/api/publish/challenge", Map.of("device_id", device.deviceId()), null);
        if (!(answer.get("nonce") instanceof String nonce) || nonce.isEmpty()) {
            throw new IOException("the challenge answer has no nonce");
        }
        return nonce;
    }

    /**
     * Returns the saved device for the server of {@code api} when the server accepts it, or null
     * when there is none or the server refuses it.
     */
    public static DeviceKey accepted(Api api, Consumer<String> out) throws IOException {
        DeviceKey device;
        try {
            device = DeviceKey.load(api.server());
        } catch (IOException | IllegalArgumentException e) {
            throw new IOException(e.getMessage() + ". Delete " + DeviceKey.file(api.server())
                    + " and deploy again to approve this computer again.", e);
        }
        if (device == null) {
            return null;
        }
        try {
            challenge(api, device);
            return device;
        } catch (ApiException e) {
            if (e.status() != 401) {
                throw e;
            }
            out.accept("The server no longer accepts device " + device.deviceId() + " (" + e.error() + ").");
            return null;
        }
    }

    /**
     * Enrolls this computer: it prints the approval link, opens it in the browser when it can,
     * waits until the developer approves the code, saves the key and returns the new device.
     *
     * @param name the device name that the approval page and the device list show
     * @param kind "maven" or "gradle"
     * @param out  receives the lines to show the developer
     */
    public static DeviceKey enroll(Api api, String name, String kind, Consumer<String> out) throws IOException {
        ServerUrl server = api.server();
        KeyPair pair = DeviceKey.generate();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", name);
        req.put("kind", kind);
        req.put("public_key", Base64.getUrlEncoder().withoutPadding().encodeToString(DeviceKey.rawPublicKey(pair)));
        Map<String, Object> enroll = api.post("/api/devices/enroll", req, null);

        String deviceCode = string(enroll, "device_code");
        String userCode = string(enroll, "user_code");
        String verificationUrl = string(enroll, "verification_url");
        long interval = number(enroll, "interval", 5);
        long expiresIn = number(enroll, "expires_in", 600);

        String link = verificationUrl + "?code=" + userCode;
        out.accept("Approve this computer to publish to " + server + ".");
        out.accept("Open " + link + " and approve code " + userCode);
        if (openBrowser(link)) {
            out.accept("Telesphorepo opened the link in your browser.");
        }
        out.accept("Waiting for the approval. The code works for " + Math.max(1, expiresIn / 60) + " minutes.");

        String expired = "the code " + userCode + " expired before anyone approved it; run the deploy again";
        long deadline = System.currentTimeMillis() + expiresIn * 1000;
        Map<String, Object> poll = Map.of("device_code", deviceCode);
        while (true) {
            sleep(interval);
            try {
                Map<String, Object> answer = api.post("/api/devices/enroll/poll", poll, null);
                if (!(answer.get("device_id") instanceof Long deviceId)) {
                    throw new IOException("the enrollment answer has no device_id");
                }
                DeviceKey device = DeviceKey.of(server, deviceId, pair);
                out.accept("This computer is device " + deviceId + ". Its key is in " + device.save(server));
                return device;
            } catch (ApiException e) {
                switch (e.error()) {
                    case "authorization_pending" -> {
                    }
                    case "slow_down" -> interval += 5;
                    case "expired_token" -> throw new IOException(expired);
                    default -> throw e;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IOException(expired);
            }
        }
    }

    /** "user@host", the default device name. */
    public static String defaultName() {
        return System.getProperty("user.name") + "@" + hostname();
    }

    private static String hostname() {
        for (String var : new String[] {"COMPUTERNAME", "HOSTNAME"}) {
            String v = System.getenv(var);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown host";
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

    private static void sleep(long seconds) throws InterruptedIOException {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("enrollment interrupted");
        }
    }

    private static String string(Map<String, Object> m, String key) throws IOException {
        if (m.get(key) instanceof String s && !s.isEmpty()) {
            return s;
        }
        throw new IOException("the enrollment answer has no " + key);
    }

    private static long number(Map<String, Object> m, String key, long fallback) {
        return m.get(key) instanceof Long n && n > 0 ? n : fallback;
    }
}
