package com.telesphorepo.client;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

/** The Ed25519 key of an enrolled device, and the signature of a publish session request. */
public final class DeviceKey {
    /** The prefix of the signed text, see {@link #signPublish}. */
    public static final String SIGN_PREFIX = "telesphorepo publish v1\n";

    private final long deviceId;
    private final PrivateKey privateKey;

    DeviceKey(long deviceId, PrivateKey privateKey) {
        this.deviceId = deviceId;
        this.privateKey = privateKey;
    }

    /** Makes a new Ed25519 key pair. */
    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new ClientException("this JDK cannot make Ed25519 keys", e);
        }
    }

    /** The raw 32-byte public key: the last 32 bytes of its X.509 encoding. */
    public static byte[] rawPublicKey(PublicKey key) {
        byte[] x509 = key.getEncoded();
        return Arrays.copyOfRange(x509, x509.length - 32, x509.length);
    }

    public long deviceId() {
        return deviceId;
    }

    /**
     * Signs a publish session request: Ed25519 over
     * {@code "telesphorepo publish v1\n" + nonce + "\n" + device_id + "\n" + repo}, as unpadded
     * base64url.
     */
    public String signPublish(String nonce, String repo) {
        String text = SIGN_PREFIX + nonce + "\n" + deviceId + "\n" + repo;
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new ClientException("signing with the device key failed", e);
        }
    }
}
