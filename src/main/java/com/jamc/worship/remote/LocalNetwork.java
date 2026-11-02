package com.jamc.worship.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Finds the address phones on the same Wi-Fi or LAN can use to reach this PC. */
public final class LocalNetwork {

    private static final Logger log = LoggerFactory.getLogger(LocalNetwork.class);

    /** Adapters that exist on this PC but that a phone on the church network cannot reach. */
    private static final List<String> VIRTUAL_ADAPTERS = List.of("virtual", "vmware", "vbox", "hyper-v", "vethernet", "wsl",
            "docker", "loopback", "bluetooth", "vpn", "tap-", "tun", "hamachi", "tailscale", "zerotier", "wireguard");

    /** A private (LAN) IPv4 address of this PC. */
    public record Address(String ip, String adapter) {
        @Override
        public String toString() {
            return ip + "  (" + adapter + ")";
        }
    }

    record Candidate(String ip, String adapter, boolean virtual) {
    }

    private LocalNetwork() {
    }

    /** Every usable address, best first. Empty when this PC is on no network. */
    public static List<Address> detect() {
        List<Candidate> found = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                String name = nic.getDisplayName() == null ? nic.getName() : nic.getDisplayName();
                boolean virtual = nic.isPointToPoint() || nic.isVirtual() || looksVirtual(name);
                for (InterfaceAddress a : nic.getInterfaceAddresses()) {
                    if (a.getAddress() instanceof Inet4Address ip && ip.isSiteLocalAddress()) {
                        found.add(new Candidate(ip.getHostAddress(), name, virtual));
                    }
                }
            }
        } catch (SocketException e) {
            log.warn("Could not list the network adapters", e);
        }
        return rank(found, routedAddress());
    }

    /** Real adapters before virtual ones, and the adapter Windows routes traffic through before the others. */
    static List<Address> rank(List<Candidate> candidates, String routedIp) {
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort((a, b) -> Integer.compare(score(a, routedIp), score(b, routedIp)));
        return ordered.stream().map(c -> new Address(c.ip(), c.adapter())).toList();
    }

    private static int score(Candidate c, String routedIp) {
        return c.virtual() ? 2 : c.ip().equals(routedIp) ? 0 : 1;
    }

    static boolean looksVirtual(String adapterName) {
        String name = adapterName == null ? "" : adapterName.toLowerCase(Locale.ROOT);
        return VIRTUAL_ADAPTERS.stream().anyMatch(name::contains);
    }

    /** Which local address the system would send from. Connecting a UDP socket only picks a route: nothing is sent. */
    private static String routedAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), 53);
            InetAddress local = socket.getLocalAddress();
            return local == null || local.isAnyLocalAddress() ? null : local.getHostAddress();
        } catch (Exception e) {
            return null; // no route (no internet is fine on a church LAN)
        }
    }
}
