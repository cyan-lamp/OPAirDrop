package com.oshare.airdrop.core;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Device-side probe for M1: runs the real responder on {@code wonder0} and prints
 * what it hears. Not part of the app — it exists so the discovery layer can be
 * exercised over the kernel link without wiring a Service or an Activity first.
 *
 * <p>Usage: {@code app_process /system/bin com.oshare.airdrop.core.MdnsProbe [iface] [seconds] [instance]}
 */
public final class MdnsProbe {
    private static final String TAG = "mdns-probe";

    /** Placeholder capability bits; the real values are still an open question in M1. */
    private static final String FLAGS = "9e1b";

    public static void main(String[] args) {
        String iface = args.length > 0 ? args[0] : MdnsResponder.DEFAULT_INTERFACE;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 45;
        String instance = args.length > 2 ? args[2] : "OPAirDrop";

        try {
            Identity identity;
            long started = System.currentTimeMillis();
            try {
                identity = Identity.generate(instance);
            } catch (java.security.GeneralSecurityException broken) {
                say("identity failed: " + broken);
                return;
            }
            String hash = identity.certHash();
            say("identity " + hash.substring(0, 16) + "… in " + (System.currentTimeMillis() - started) + " ms");

            List<String> addresses = linkLocalOf(iface);
            if (addresses.isEmpty()) {
                say("no IPv6 link-local on " + iface + " — run awdl-link.sh up first");
                return;
            }
            say(iface + " " + addresses);

            final int[] errors = {0};
            MdnsResponder.Source source = new MdnsResponder.Source() {
                @Override
                public Map<String, String> txt() {
                    Map<String, String> txt = new LinkedHashMap<>();
                    txt.put("h", hash);
                    txt.put("fl", FLAGS);
                    return txt;
                }

                @Override
                public List<String> addresses() {
                    return addresses;
                }
            };

            MdnsResponder responder = new MdnsResponder(instance, hostName(iface), AirDropPeer.DEFAULT_PORT,
                source, new MdnsResponder.Listener() {
                    @Override
                    public void onPeer(AirDropPeer peer) {
                        say("peer " + peer);
                    }

                    @Override
                    public void onError(String message, Throwable error) {
                        if (errors[0]++ < 5) say("error " + message + ": " + error);
                    }
                });

            try {
                responder.start(iface, MdnsResponder.PORT);
            } catch (java.io.IOException failed) {
                say("bind failed on " + iface + ": " + failed);
                return;
            }
            say("listening on " + iface + ":" + MdnsResponder.PORT + " as '" + instance + "'");

            long deadline = System.currentTimeMillis() + seconds * 1000L;
            long nextQuery = System.currentTimeMillis() + 10_000L;
            long nextBeat = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (now >= nextQuery) {
                    nextQuery = now + 10_000L;
                    responder.query();
                    responder.announce();
                    say("sent query + announce");
                }
                if (now >= nextBeat) {
                    nextBeat = now + 5_000L;
                    say("heartbeat peers=" + responder.peers().size() + " errors=" + errors[0]);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException stop) {
                    break;
                }
            }
            // Count before close(): closing clears the peer table on purpose.
            int found = responder.peers().size();
            responder.close();
            say("done peers=" + found + " errors=" + errors[0]);
        } catch (Throwable fatal) {
            say("fatal: " + fatal);
            fatal.printStackTrace();
        }
    }

    /** {@code fe80::…} of the interface, without the {@code %iface} scope suffix. */
    private static List<String> linkLocalOf(String iface) {
        List<String> out = new ArrayList<>();
        try {
            NetworkInterface network = NetworkInterface.getByName(iface);
            if (network == null) return out;
            for (InterfaceAddress address : network.getInterfaceAddresses()) {
                InetAddress plain = address.getAddress();
                if (plain instanceof Inet6Address && plain.isLinkLocalAddress()) {
                    String text = plain.getHostAddress();
                    int scope = text.indexOf('%');
                    out.add(scope < 0 ? text : text.substring(0, scope));
                }
            }
        } catch (java.net.SocketException broken) {
            say("cannot read " + iface + ": " + broken);
        }
        return out;
    }

    /** DNS host name we claim; Apple peers only use it to label records. */
    private static String hostName(String iface) {
        return iface.replaceAll("[^A-Za-z0-9-]", "-") + "-opairdrop.local";
    }

    private static void say(String line) {
        System.out.println(TAG + ": " + line);
        System.out.flush();
    }

    private MdnsProbe() {
    }
}
