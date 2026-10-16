package com.telesphorepo.client;

import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The device of this computer: the saved one when the server still accepts it, else a new one
 * enrolled with the device flow. The server gives a user code, the person approves it in a
 * browser, and the client polls until the server answers with a device ID. The approved key goes
 * to the {@link DeviceFile}.
 */
public final class Enrollment {
    private Enrollment() {
    }

    /** Asks the server for a nonce that the device signs. A device that the server does not accept gets HTTP 401. */
    static String challenge(Http http, DeviceKey device) {
        return Protocol.Challenge.parse("POST " + Protocol.CHALLENGE,
                http.post(Protocol.CHALLENGE, Map.of("device_id", device.deviceId()))).nonce();
    }

    /**
     * Returns the saved device of this computer when the server accepts it, or null when there is
     * none, the file is not valid, or the server refuses it.
     */
    public static DeviceFile accepted(Http http, Logger log) {
        DeviceFile device = DeviceFile.load(http.server(), log);
        if (device == null) {
            return null;
        }
        try {
            challenge(http, device.key());
            return device;
        } catch (ApiException e) {
            if (e.status() != 401) {
                throw e;
            }
            log.prompt("The server no longer accepts device " + device.deviceId() + " (" + e.error() + ").");
            return null;
        }
    }

    /**
     * Enrolls this computer: it makes an Ed25519 key, asks the server for a code, prints the
     * approval link, opens it in the browser when it can, waits until the person approves the
     * code, saves the device file and returns it.
     *
     * @param client the publisher client ID, for example {@code gradle}
     * @param name   the device name that the approval page and the device list show
     */
    public static DeviceFile enroll(Http http, String client, String name, Logger log) {
        ServerIdentity server = http.server();
        KeyPair pair = DeviceKey.generate();
        String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(DeviceKey.rawPublicKey(pair.getPublic()));
        Protocol.Enrollment e = Protocol.Enrollment.parse("POST " + Protocol.ENROLL,
                http.post(Protocol.ENROLL, new Protocol.EnrollRequest(name, client, publicKey).json()));

        String link = e.verificationUrl() + "?code=" + e.userCode();
        log.prompt("Approve this computer to publish to " + server + ".");
        log.prompt("Open " + link + " and approve code " + e.userCode());
        if (openBrowser(link)) {
            log.prompt("Telesphorepo opened the link in your browser.");
        }
        log.prompt("Waiting for the approval. The code works for " + Math.max(1, e.expiresIn() / 60) + " minutes.");

        String expired = "the code " + e.userCode() + " expired before anyone approved it. Run the build again.";
        long deadline = System.currentTimeMillis() + e.expiresIn() * 1000;
        long interval = e.interval();
        Map<String, Object> poll = Map.of("device_code", e.deviceCode());
        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                throw new ClientException(expired);
            }
            sleep(interval);
            try {
                long deviceId = Protocol.PollResult.parse("POST " + Protocol.ENROLL_POLL,
                        http.post(Protocol.ENROLL_POLL, poll)).deviceId();
                DeviceFile device = DeviceFile.create(server, deviceId, client, pair);
                log.prompt("This computer is device " + deviceId + ". Its key is in " + device.save());
                return device;
            } catch (ApiException ex) {
                switch (ex.error()) {
                    case "authorization_pending" -> {
                    }
                    case "slow_down" -> interval += 5;
                    case "expired_token" -> throw new ClientException(expired);
                    default -> throw ex;
                }
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
            if (Environment.set(v)) {
                return v.trim();
            }
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
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
        } else if (Environment.set(System.getenv("DISPLAY")) || Environment.set(System.getenv("WAYLAND_DISPLAY"))) {
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

    private static void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("the device approval was interrupted", e);
        }
    }
}
