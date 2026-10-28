package xyz.v185v.collegeassistant.ingestion.crawl;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Objects;

/** 抓取目标主机地址校验策略，用于阻止访问非公网地址。 */
@FunctionalInterface
public interface HostAddressPolicy {

    void validate(URI uri) throws IOException;

    static HostAddressPolicy production() {
        return uri -> validateTarget(uri, null);
    }

    static HostAddressPolicy strict(HostAddressResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        return uri -> validateTarget(uri, resolver);
    }

    /**
* 仅对精确配置来源允许非公开解析的部署策略。
* 每个请求都会调用解析器，以便调用方固定同一地址快照。
     */
    static HostAddressPolicy deployment(
            SourceNetworkPolicyProperties properties,
            HostAddressResolver resolver
    ) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(resolver, "resolver");
        return uri -> validateDeploymentTarget(uri, properties, resolver);
    }

    private static void validateTarget(URI uri, HostAddressResolver resolver) throws IOException {
        if (uri == null || uri.getUserInfo() != null) {
            throw new IOException("Source URI is invalid");
        }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IOException("Source URI scheme is invalid");
        }
        String normalizedHost = normalizeHost(uri.getHost());
        InetAddress literalAddress = parseIpLiteral(normalizedHost);
        if (literalAddress != null) {
            requirePublic(literalAddress);
            return;
        }
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new IOException("Cleartext source hosts must be IP literals");
        }
        if (resolver != null) {
            InetAddress[] addresses = resolver.resolve(normalizedHost);
            if (addresses == null || addresses.length == 0) {
                throw new IOException("Source host did not resolve");
            }
            for (InetAddress address : addresses) {
                requirePublic(address);
            }
        }
    }

    private static void validateDeploymentTarget(
            URI uri,
            SourceNetworkPolicyProperties properties,
            HostAddressResolver resolver
    ) throws IOException {
        requireHttpTarget(uri);
        String normalizedHost = normalizeHost(uri.getHost());
        boolean privateAllowed;
        try {
            privateAllowed = properties.permitsPrivate(uri);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Private origin allowlist is invalid", exception);
        }
        InetAddress literalAddress = parseIpLiteral(normalizedHost);
        if (literalAddress != null) {
            if (!privateAllowed) {
                requirePublic(literalAddress);
            }
            return;
        }
        InetAddress[] addresses = resolver.resolve(normalizedHost);
        if (addresses == null || addresses.length == 0) {
            throw new IOException("Source host did not resolve");
        }
        if (!privateAllowed) {
            for (InetAddress address : addresses) {
                requirePublic(address);
            }
        }
    }

    private static void requireHttpTarget(URI uri) throws IOException {
        if (uri == null || uri.getUserInfo() != null) {
            throw new IOException("Source URI is invalid");
        }
        try {
            UrlCanonicalizer.canonicalize(uri);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Source URI is invalid", exception);
        }
    }

    static String normalizeHost(String host) throws IOException {
        if (host == null || host.isBlank()) {
            throw new IOException("Source host is missing");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1).toLowerCase(java.util.Locale.ROOT);
        }
        return host.toLowerCase(java.util.Locale.ROOT);
    }

    private static InetAddress parseIpLiteral(String host) throws IOException {
        if (host.indexOf(':') >= 0) {
            try {
                InetAddress address = InetAddress.getByName(host);
                if (address instanceof Inet6Address) {
                    return address;
                }
            } catch (RuntimeException exception) {
                throw new IOException("Invalid IPv6 literal", exception);
            }
            throw new IOException("Invalid IPv6 literal");
        }

        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            if (parts[index].isEmpty() || !parts[index].chars().allMatch(Character::isDigit)) {
                return null;
            }
            try {
                int value = Integer.parseInt(parts[index]);
                if (value < 0 || value > 255) {
                    return null;
                }
                bytes[index] = (byte) value;
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return InetAddress.getByAddress(bytes);
    }

    static void requirePublic(InetAddress address) throws IOException {
        if (address == null || !isPublic(address)) {
            throw new IOException("Source host resolved to a non-public address");
        }
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        if (bytes.length != 16) {
            return false;
        }
        if (isIpv4Mapped(bytes)) {
            return isPublicIpv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        }

// 当前全球可路由 IPv6 地址分配位于 2000::/3。
        if (!hasPrefix(bytes, new byte[]{0x20}, 3)) {
            return false;
        }
        return !hasPrefix(bytes, new byte[]{0x20, 0x01, 0x00}, 23)
                && !hasPrefix(bytes, new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8}, 32)
                && !hasPrefix(bytes, new byte[]{0x20, 0x02}, 16)
                && !hasPrefix(bytes, new byte[]{0x3f, (byte) 0xff, 0x00}, 20);
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        if (first == 0
                || first == 10
                || first == 127
                || first >= 224) {
            return false;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        if (first == 169 && second == 254) {
            return false;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192) {
            return !(second == 168
                    || (second == 0 && (third == 0 || third == 2))
                    || (second == 88 && third == 99));
        }
        if (first == 198) {
            return !(second == 18
                    || second == 19
                    || (second == 51 && third == 100));
        }
        return !(first == 203 && second == 0 && third == 113);
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean hasPrefix(byte[] address, byte[] prefix, int prefixBits) {
        int wholeBytes = prefixBits / 8;
        for (int index = 0; index < wholeBytes; index++) {
            if (address[index] != prefix[index]) {
                return false;
            }
        }
        int remainingBits = prefixBits % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return (address[wholeBytes] & mask) == (prefix[wholeBytes] & mask);
    }
}
