package com.telesphorepo.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The device file {@code ~/.telesphorepo/<dir>/device.json} ({@link ServerIdentity#deviceDir}).
 * Only its owner can read it: mode 600 in a 700 folder on POSIX systems, an ACL with the owner as
 * the only entry elsewhere. The Gradle plugin and the Maven extension share it.
 *
 * <pre>{"server": "https://repo.acme.com", "device_id": 7, "client": "gradle",
 *  "private_key": "&lt;base64 PKCS#8&gt;", "public_key": "&lt;base64 raw 32 bytes&gt;",
 *  "created_at": "2026-10-15T10:00:00Z"}</pre>
 *
 * All fields are required, and {@code server} must equal the canonical URL of the server.
 */
public final class DeviceFile {
    private static final Pattern CLIENT_ID = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    private final ServerIdentity server;
    private final long deviceId;
    private final String client;
    private final PrivateKey privateKey;
    private final byte[] publicKey;
    private final Instant createdAt;

    private DeviceFile(ServerIdentity server, long deviceId, String client, PrivateKey privateKey, byte[] publicKey,
            Instant createdAt) {
        this.server = server;
        this.deviceId = deviceId;
        this.client = client;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.createdAt = createdAt;
    }

    /** The device file of a key pair that the server approved as device {@code deviceId}. */
    static DeviceFile create(ServerIdentity server, long deviceId, String client, KeyPair pair) {
        return new DeviceFile(server, deviceId, client, pair.getPrivate(), DeviceKey.rawPublicKey(pair.getPublic()),
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /** The path of the device file for server. */
    public static Path path(ServerIdentity server) {
        return Path.of(System.getProperty("user.home"), ".telesphorepo", server.deviceDir(), "device.json");
    }

    /**
     * Reads the device file for server. Returns null when there is none, and also when the file
     * is not valid; then {@code log} says why, and the caller enrolls the computer again. A file
     * that its group or other users can read on a POSIX system stops the build.
     */
    public static DeviceFile load(ServerIdentity server, Logger log) {
        Path f = path(server);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        String text;
        try {
            if (posix(f)) {
                for (PosixFilePermission p : Files.getPosixFilePermissions(f)) {
                    if (p.name().startsWith("GROUP") || p.name().startsWith("OTHERS")) {
                        throw new ClientException(f + " must be readable by its owner only. Run: chmod 600 " + f);
                    }
                }
            }
            text = Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ClientException("cannot read " + f + ": " + e.getMessage(), e);
        }
        try {
            return parse(server, text);
        } catch (IllegalArgumentException e) {
            log.prompt(f + " is not a valid device file (" + e.getMessage() + "), so Telesphorepo does not use it.");
            return null;
        }
    }

    private static DeviceFile parse(ServerIdentity server, String text) {
        Map<String, Object> m = Json.parseObject(text);
        if (!server.base().equals(m.get("server"))) {
            throw new IllegalArgumentException("\"server\" is not " + server.base());
        }
        if (!(m.get("device_id") instanceof Long id) || id <= 0) {
            throw new IllegalArgumentException("no valid \"device_id\"");
        }
        if (!(m.get("client") instanceof String client) || !CLIENT_ID.matcher(client).matches()) {
            throw new IllegalArgumentException("no valid \"client\"");
        }
        PrivateKey key;
        try {
            String p = (String) m.get("private_key");
            key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(p)));
        } catch (ClassCastException | NullPointerException | IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalArgumentException("no valid \"private_key\"");
        }
        byte[] pub;
        try {
            pub = Base64.getDecoder().decode((String) m.get("public_key"));
        } catch (ClassCastException | NullPointerException | IllegalArgumentException e) {
            throw new IllegalArgumentException("no valid \"public_key\"");
        }
        if (pub.length != 32) {
            throw new IllegalArgumentException("no valid \"public_key\"");
        }
        Instant created;
        try {
            created = OffsetDateTime.parse((String) m.get("created_at")).toInstant();
        } catch (ClassCastException | NullPointerException | DateTimeParseException e) {
            throw new IllegalArgumentException("no valid \"created_at\"");
        }
        return new DeviceFile(server, id, client, key, pub, created);
    }

    /**
     * Writes the device file at once (a temporary file, then a rename). Only the owner can read
     * it and its folder. A file system that supports neither POSIX permissions nor ACLs fails the
     * save. Returns the path.
     */
    public Path save() {
        Path f = path(server);
        Path dir = f.getParent();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("server", server.base());
        m.put("device_id", deviceId);
        m.put("client", client);
        m.put("private_key", Base64.getEncoder().encodeToString(privateKey.getEncoded()));
        m.put("public_key", Base64.getEncoder().encodeToString(publicKey));
        m.put("created_at", createdAt.toString());
        Path tmp = dir.resolve("device.json.tmp");
        try {
            if (posix(f)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
                Files.deleteIfExists(tmp);
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createDirectories(dir);
                ownerOnly(dir);
                Files.deleteIfExists(tmp);
                Files.createFile(tmp);
                ownerOnly(tmp);
            }
            Files.writeString(tmp, Json.write(m) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ClientException("cannot write " + f + ": " + e.getMessage(), e);
        }
        return f;
    }

    public long deviceId() {
        return deviceId;
    }

    /** The publisher client ID that enrolled the device, for example {@code gradle}. */
    public String client() {
        return client;
    }

    /** The signing key of the device. */
    public DeviceKey key() {
        return new DeviceKey(deviceId, privateKey);
    }

    private static boolean posix(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** Replaces the ACL of p with one entry that gives its owner full control. */
    private static void ownerOnly(Path p) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(p, AclFileAttributeView.class);
        if (view == null) {
            throw new IOException("the file system supports neither POSIX permissions nor ACLs, so "
                    + p + " cannot be made readable by its owner only");
        }
        AclEntry entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(view.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(List.of(entry));
    }
}
