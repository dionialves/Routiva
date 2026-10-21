package br.com.scriptping;

import java.net.InetAddress;

/** Validates numeric IP targets without resolving hostnames through DNS. */
public final class IpAddress {
    private IpAddress() {}

    public static boolean isValid(String ip) {
        if (ip == null || ip.isBlank()) return false;
        if (ip.contains(":")) {
            if (!ip.matches("[0-9a-fA-F:.]+")) return false;
            try {
                return !InetAddress.getByName(ip).isAnyLocalAddress();
            } catch (Exception ex) {
                return false;
            }
        }
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) return false;
        boolean nonzero = false;
        for (String octet : octets) {
            if (!octet.matches("[0-9]{1,3}")) return false;
            int value = Integer.parseInt(octet);
            if (value > 255) return false;
            nonzero |= value != 0;
        }
        return nonzero;
    }
}
