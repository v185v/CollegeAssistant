package xyz.v185v.collegeassistant.ingestion.crawl;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

/** 所有远程连接器共用的规范 HTTP URL 与来源处理。 */
public final class UrlCanonicalizer {

    private UrlCanonicalizer() {
    }

    public static URI canonicalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("URL must not be blank");
        }
        return canonicalize(URI.create(value.strip()));
    }

    public static URI canonicalize(URI value) {
        if (value == null || !value.isAbsolute() || value.getUserInfo() != null) {
            throw new IllegalArgumentException("URL must be an absolute HTTP(S) URL without user info");
        }
        String scheme = value.getScheme() == null
                ? "" : value.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("URL must use HTTP or HTTPS");
        }
        String host = normalizeHostname(value.getHost());
        int port = value.getPort();
        if (port < -1 || port == 0 || port > 65535) {
            throw new IllegalArgumentException("URL port is invalid");
        }
        if (port == effectivePort(scheme, -1)) {
            port = -1;
        }

        String path = normalizePercentEncoding(value.getRawPath());
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = normalizePercentEncoding(value.getRawQuery());
        String authorityHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        StringBuilder canonical = new StringBuilder(scheme)
                .append("://").append(authorityHost);
        if (port >= 0) {
            canonical.append(':').append(port);
        }
        canonical.append(path);
        if (query != null) {
            canonical.append('?').append(query);
        }
        return URI.create(canonical.toString()).normalize();
    }

    public static boolean sameOrigin(URI left, URI right) {
        URI canonicalLeft = canonicalize(left);
        URI canonicalRight = canonicalize(right);
        return canonicalLeft.getScheme().equals(canonicalRight.getScheme())
                && canonicalLeft.getHost().equalsIgnoreCase(canonicalRight.getHost())
                && effectivePort(canonicalLeft) == effectivePort(canonicalRight);
    }

/** 返回精确规范化的协议/主机名/生效端口部署键。 */
    public static String origin(URI value) {
        URI canonical = canonicalize(value);
        String host = canonical.getHost();
        String authorityHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return canonical.getScheme() + "://" + authorityHost + ':' + effectivePort(canonical);
    }

    public static String normalizedPath(URI value) {
        return canonicalize(value).getPath();
    }

    public static int effectivePort(URI value) {
        URI canonical = canonicalize(value);
        return effectivePort(canonical.getScheme(), canonical.getPort());
    }

    private static int effectivePort(String scheme, int explicitPort) {
        if (explicitPort >= 0) {
            return explicitPort;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static String normalizeHostname(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("URL host is required");
        }
        String host = value.strip();
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isBlank() || host.indexOf('*') >= 0) {
            throw new IllegalArgumentException("URL host is invalid");
        }
        if (host.indexOf(':') >= 0) {
            return host.toLowerCase(Locale.ROOT);
        }
        try {
            return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("URL host is invalid", exception);
        }
    }

    private static String normalizePercentEncoding(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '%') {
                result.append(character);
                continue;
            }
            if (index + 2 >= value.length()) {
                throw new IllegalArgumentException("URL contains an invalid percent escape");
            }
            int high = Character.digit(value.charAt(index + 1), 16);
            int low = Character.digit(value.charAt(index + 2), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("URL contains an invalid percent escape");
            }
            char decoded = (char) ((high << 4) + low);
            if (isUnreserved(decoded)) {
                result.append(decoded);
            } else {
                result.append('%')
                        .append(Character.toUpperCase(value.charAt(index + 1)))
                        .append(Character.toUpperCase(value.charAt(index + 2)));
            }
            index += 2;
        }
        return result.toString();
    }

    private static boolean isUnreserved(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '-' || value == '.' || value == '_' || value == '~';
    }
}
