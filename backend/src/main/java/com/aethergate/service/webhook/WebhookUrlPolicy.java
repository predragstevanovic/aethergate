package com.aethergate.service.webhook;

import com.aethergate.config.AetherGateProperties;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * SSRF guard for tenant-supplied webhook URLs. A webhook is a server-side request to a URL chosen by a
 * customer, so without this a tenant could make the gateway call {@code http://169.254.169.254/} (cloud
 * metadata), {@code localhost:8080/actuator} or any host on the private network.
 *
 * <ol>
 *   <li>On save: HTTPS only, no credentials in the URL, no internal host names or private IP literals.</li>
 *   <li>On every send: the host is resolved again and EVERY address must be public (DNS can change after the
 *       check, e.g. DNS rebinding); redirects are never followed by the sender.</li>
 * </ol>
 */
@Component
public class WebhookUrlPolicy {

    private final boolean allowPrivate;
    private final boolean allowHttp;

    public WebhookUrlPolicy(AetherGateProperties properties) {
        this.allowPrivate = properties.webhooks().allowPrivateTargets();
        this.allowHttp = properties.webhooks().allowHttp();
    }

    /** Validates the shape of a URL at save time; returns it normalised. */
    public String validate(String raw) {
        String url = raw == null ? "" : raw.strip();
        if (url.length() > 500) {
            throw new IllegalArgumentException("Webhook URL must be at most 500 characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Webhook URL is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
            throw new IllegalArgumentException("Webhook URL must use https://");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL must include a host name");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Webhook URL must not contain credentials");
        }
        if (!allowPrivate) {
            String h = host.toLowerCase(Locale.ROOT);
            if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")
                    || h.endsWith(".lan") || (!h.contains(".") && !h.contains(":"))) {
                throw new IllegalArgumentException("Webhook URL must point to a public host, not '" + host + "'");
            }
            InetAddress literal = literal(h);
            if (literal != null && isPrivate(literal)) {
                throw new IllegalArgumentException("Webhook URL must not target a private, loopback or link-local address");
            }
        }
        return uri.toString();
    }

    /** Resolves the host at send time; throws if any address it resolves to is not public. */
    public void checkResolved(String url) {
        if (allowPrivate) {
            return;
        }
        String host = URI.create(url).getHost();
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (isPrivate(a)) {
                    throw new WebhookBlockedException("Blocked: " + host + " resolves to non-public address " + a.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw new WebhookBlockedException("DNS lookup failed for " + host);
        }
    }

    private static InetAddress literal(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!h.matches("[0-9.]+") && !h.contains(":")) {
            return null; // a name: never resolved at save time (resolution is checked on every send)
        }
        try {
            return InetAddress.getByName(h);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Loopback, private (RFC 1918), link-local (incl. 169.254.169.254 metadata), CGNAT, ULA, multicast, unspecified. */
    public static boolean isPrivate(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0 || (first == 100 && second >= 64 && second <= 127) // 0/8, CGNAT 100.64/10
                    || (first == 192 && second == 0 && (b[2] & 0xFF) == 0)       // 192.0.0/24
                    || (first == 198 && (second == 18 || second == 19))          // benchmarking 198.18/15
                    || first >= 240;                                             // reserved + broadcast
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xFE) == 0xFC; // fc00::/7 unique local
        }
        return false;
    }
}
