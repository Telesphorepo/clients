package com.telesphorepo.client;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The identity of a Telesphorepo server: the scheme ({@code http} or {@code https}), the host in
 * lower case with an internationalized name in its ASCII form, the effective port (80 or 443
 * when the URL names none) and the base path without a trailing slash ({@code ""} for the root).
 * A URL belongs to the server only when its scheme, host and port are equal to these and its path
 * is the base path or starts with the base path and {@code /}. The clients check this before a
 * token, a device signature or a session request goes out.
 */
public final class ServerIdentity {
    private final String scheme;
    private final String host;
    private final int port;
    private final String basePath;

    private ServerIdentity(String scheme, String host, int port, String basePath) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.basePath = basePath;
    }

    /**
     * Parses the server URL, for example {@code https://repo.acme.com}. The URL must be http or
     * https with a host, and must have no user name, query or fragment.
     */
    public static ServerIdentity of(String url) {
        URI u = uri(url == null ? "" : url.trim());
        ServerIdentity id = u == null || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null
                ? null : parse(u);
        if (id == null) {
            throw new ClientException("telesphorepo.url must be an http or https URL with a host and no user name, query "
                    + "or fragment, for example https://repo.example.com (got \"" + url + "\")");
        }
        return id;
    }

    /** The canonical URL: no default port and no trailing slash. API routes are appended to it. */
    public String base() {
        return scheme + "://" + host + (port == defaultPort(scheme) ? "" : ":" + port) + basePath;
    }

    /**
     * Returns the repository name when {@code url} belongs to this server and its path is
     * {@code <base path>/<format>/<name>} or below it, else null.
     */
    public String repoOf(String url, String format) {
        String rest = pathWithin(url);
        String prefix = "/" + format + "/";
        if (rest == null || !rest.startsWith(prefix)) {
            return null;
        }
        rest = rest.substring(prefix.length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        return name.isEmpty() ? null : name;
    }

    /**
     * Returns the raw path of {@code url} after the base path ({@code ""} or a path that starts
     * with {@code /}) when the URL belongs to this server, else null.
     */
    public String pathWithin(String url) {
        URI u = url == null ? null : uri(url.trim());
        ServerIdentity other = u == null ? null : parse(u);
        if (other == null || !scheme.equals(other.scheme) || !host.equals(other.host) || port != other.port) {
            return null;
        }
        String p = u.getRawPath() == null ? "" : u.getRawPath();
        if (p.equals(basePath)) {
            return "";
        }
        return p.startsWith(basePath + "/") ? p.substring(basePath.length()) : null;
    }

    /**
     * The folder name under {@code ~/.telesphorepo}: the host without IPv6 brackets, then
     * {@code _<port>} when the port is not the scheme's default, then {@code _} and the base path
     * with each {@code /} as {@code _} when the base path is not empty. Each character other than
     * A-Z, a-z, 0-9, ".", "_" and "-" becomes "_".
     */
    public String deviceDir() {
        StringBuilder b = new StringBuilder(host.replace("[", "").replace("]", ""));
        if (port != defaultPort(scheme)) {
            b.append('_').append(port);
        }
        if (!basePath.isEmpty()) {
            b.append('_').append(basePath.substring(1).replace('/', '_'));
        }
        return b.toString().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ServerIdentity other && base().equals(other.base());
    }

    @Override
    public int hashCode() {
        return base().hashCode();
    }

    @Override
    public String toString() {
        return base();
    }

    /** Reads the identity parts of u, ignoring user info, query and fragment. Null when u is not http(s) with a host. */
    private static ServerIdentity parse(URI u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        String authority = u.getRawAuthority();
        if (authority == null) {
            return null;
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        String host;
        String rest;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) {
                return null;
            }
            host = authority.substring(0, end + 1);
            rest = authority.substring(end + 1);
        } else {
            int colon = authority.indexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
            rest = colon < 0 ? "" : authority.substring(colon);
        }
        int port = defaultPort(scheme);
        if (!rest.isEmpty()) {
            if (!rest.startsWith(":")) {
                return null;
            }
            String p = rest.substring(1);
            if (!p.isEmpty()) {
                try {
                    port = Integer.parseInt(p);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (port < 1 || port > 65535) {
                    return null;
                }
            }
        }
        if (!host.startsWith("[")) {
            try {
                host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.equals("[]")) {
            return null;
        }
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return new ServerIdentity(scheme, host, port, path);
    }

    private static int defaultPort(String scheme) {
        return scheme.equals("https") ? 443 : 80;
    }

    private static URI uri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
