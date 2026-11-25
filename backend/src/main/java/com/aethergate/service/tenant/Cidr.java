package com.aethergate.service.tenant;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/** An IPv4 / IPv6 network in CIDR notation ({@code 203.0.113.0/24}, {@code 2001:db8::/32}); a bare IP is a /32 or /128. */
public record Cidr(String text, byte[] network, int prefix) {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

    public static Cidr parse(String raw) {
        String s = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        int slash = s.indexOf('/');
        String ip = slash < 0 ? s : s.substring(0, slash);
        byte[] addr = literal(ip);
        if (addr == null) {
            throw new IllegalArgumentException("'" + raw + "' is not an IP address or CIDR range");
        }
        int max = addr.length * 8;
        int prefix;
        try {
            prefix = slash < 0 ? max : Integer.parseInt(s.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' has an invalid prefix length");
        }
        if (prefix < 0 || prefix > max) {
            throw new IllegalArgumentException("'" + raw + "' has an invalid prefix length");
        }
        return new Cidr(slash < 0 ? ip + "/" + max : s, mask(addr, prefix), prefix);
    }

    /** Literal addresses only: {@code InetAddress.getByName} would otherwise do a DNS lookup on a host name. */
    static byte[] literal(String ip) {
        if (!IPV4.matcher(ip).matches() && !IPV6.matcher(ip).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public boolean contains(String ip) {
        byte[] addr = ip == null ? null : literal(ip.strip());
        if (addr == null || addr.length != network.length) {
            return false;
        }
        return java.util.Arrays.equals(mask(addr, prefix), network);
    }

    private static byte[] mask(byte[] addr, int prefix) {
        byte[] out = addr.clone();
        for (int i = 0; i < out.length; i++) {
            int bits = Math.clamp(prefix - i * 8L, 0, 8);
            out[i] = (byte) (out[i] & (bits == 0 ? 0 : (0xFF << (8 - bits))));
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cidr c && c.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
