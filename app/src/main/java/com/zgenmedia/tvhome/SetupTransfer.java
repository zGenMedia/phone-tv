package com.zgenmedia.tvhome;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Moves a setup (Library and settings) between two devices on the same network,
 * with nothing to install and no file to carry: the way a launcher should do it.
 *
 * The device that has the setup offers it: it opens a socket, announces itself on
 * the local network (the same discovery printers and Chromecasts use) and shows a
 * six-digit code. The new device lists who is offering, the user picks one and
 * types the code, and the setup comes across in one request.
 *
 * Plain sockets rather than HTTP: Android refuses unencrypted HTTP by default,
 * and a socket on the local network needs no exceptions to that. The code is the
 * key: a wrong code gets nothing, five wrong codes end the offer, and an offer
 * closes by itself after ten minutes or once something has been sent.
 */
final class SetupTransfer {
    static final String SERVICE_TYPE = "_phonetv-setup._tcp.";
    private static final String HELLO = "PHONETV-SETUP 1";
    /** Asked in place of a code: "which Phone TV are you?" Lets a device be found without discovery. */
    private static final String WHO = "WHO";
    /**
     * The port an offer listens on when it's free. A known port means the other device only
     * needs this one's address, and can look for it across the network when the router
     * doesn't pass discovery between Wi-Fi and cable (many don't).
     */
    static final int PORT = 41500;
    private static final int MAX_WRONG = 5;
    private static final long OFFER_MS = 10 * 60_000L;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    interface Offering {
        /** Someone took the setup. Called on the network thread. */
        void sent(String toAddress);
        /** The offer ended without sending: timed out, too many wrong codes, or failed. */
        void ended(String why);
    }

    interface Finding {
        /** A device offering its setup appeared. Called on a system thread. */
        void found(String name, String host, int port);
        void lost(String name);
    }

    private final Context context;
    private ServerSocket server;
    private NsdManager.RegistrationListener registered;
    private NsdManager.DiscoveryListener discovering;
    private WifiManager.MulticastLock multicast;

    SetupTransfer(Context context) { this.context = context.getApplicationContext(); }

    static String newCode() { return String.format(Locale.US, "%06d", new SecureRandom().nextInt(1_000_000)); }

    // ================================================================== offering

    /** Starts offering {@code setup}. Returns the port; the caller shows the code. */
    int offer(String deviceName, String code, String setup, Offering listener) throws IOException {
        stopOffering();
        final ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        try { socket.bind(new InetSocketAddress(PORT)); }
        catch (IOException taken) { socket.bind(new InetSocketAddress(0)); } // something else has it: any free port
        socket.setSoTimeout(1_000); // wakes up each second to check the clock
        server = socket;
        final byte[] payload = setup.getBytes(StandardCharsets.UTF_8);
        final long until = System.currentTimeMillis() + OFFER_MS;
        new Thread(() -> {
            int wrong = 0;
            String result = null;
            try {
                while (server == socket && System.currentTimeMillis() < until) {
                    Socket peer;
                    try { peer = socket.accept(); } catch (java.net.SocketTimeoutException tick) { continue; }
                    try (Socket s = peer) {
                        s.setSoTimeout(15_000);
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        String hello = in.readLine(), given = in.readLine();
                        OutputStream out = s.getOutputStream();
                        if (HELLO.equals(hello) && WHO.equals(given == null ? "" : given.trim())) {
                            // A device looking for senders. It learns a name, nothing else, and still needs the code.
                            out.write(("NAME " + deviceName + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            continue;
                        }
                        if (!HELLO.equals(hello) || !code.equals(given == null ? "" : given.trim())) {
                            out.write("NO\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            if (HELLO.equals(hello) && ++wrong >= MAX_WRONG) { result = "wrong"; break; }
                            continue;
                        }
                        out.write(("OK\n" + payload.length + "\n").getBytes(StandardCharsets.UTF_8));
                        out.write(payload);
                        out.flush();
                        String to = s.getInetAddress() == null ? "" : s.getInetAddress().getHostAddress();
                        listener.sent(to);
                        result = "";
                        break;
                    } catch (IOException oneVisitor) {
                        // A visitor that hung up halfway doesn't end the offer.
                    }
                }
            } catch (IOException e) {
                if (server == socket) result = e.getMessage();
            } finally {
                boolean mine = server == socket;
                if (mine) stopOffering();
                if (mine && !"".equals(result)) listener.ended(result == null ? "timeout" : result);
            }
        }, "phonetv-setup-offer").start();

        // Announce it. Discovery failing is not fatal: the screen also shows the address to type.
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(deviceName);
            info.setServiceType(SERVICE_TYPE);
            info.setPort(socket.getLocalPort());
            registered = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo s, int error) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int error) {}
                @Override public void onServiceRegistered(NsdServiceInfo s) {}
                @Override public void onServiceUnregistered(NsdServiceInfo s) {}
            };
            nsd().registerService(info, NsdManager.PROTOCOL_DNS_SD, registered);
        } catch (RuntimeException e) {
            registered = null;
        }
        return socket.getLocalPort();
    }

    void stopOffering() {
        ServerSocket s = server;
        server = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { /* already closed */ }
        NsdManager.RegistrationListener r = registered;
        registered = null;
        if (r != null) try { nsd().unregisterService(r); } catch (RuntimeException ignored) { /* never registered */ }
    }

    // =================================================================== finding

    void find(Finding listener) {
        stopFinding();
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("phonetv-setup");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
        } catch (RuntimeException ignored) { /* discovery may still work without it */ }
        final NsdManager nsd = nsd();
        discovering = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int error) {}
            @Override public void onStopDiscoveryFailed(String type, int error) {}
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onServiceLost(NsdServiceInfo s) { listener.lost(s.getServiceName()); }
            @Override public void onServiceFound(NsdServiceInfo s) {
                try {
                    //noinspection deprecation (the replacement needs API 34; this works on every version)
                    nsd.resolveService(s, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo i, int error) {}
                        @Override public void onServiceResolved(NsdServiceInfo i) {
                            InetAddress host = i.getHost();
                            if (host != null) listener.found(i.getServiceName(), host.getHostAddress(), i.getPort());
                        }
                    });
                } catch (RuntimeException ignored) { /* a resolve already running; the next sighting retries */ }
            }
        };
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovering); }
        catch (RuntimeException e) { discovering = null; }
        sweep(listener);
    }

    private java.util.concurrent.ExecutorService sweeping;

    /**
     * Discovery's understudy: knocks on the offer port of every address beside this one's
     * (the usual home network has 254) and reports whoever answers as Phone TV. This finds
     * a sender that discovery misses, which is common between Wi-Fi and wired devices.
     * It can't see out of an emulator's private network; there the address is typed in.
     */
    private void sweep(final Finding listener) {
        final String mine = localAddress();
        int lastDot = mine.lastIndexOf('.');
        if (lastDot < 0 || behindEmulator(mine)) return;
        final String prefix = mine.substring(0, lastDot + 1);
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(24);
        sweeping = pool;
        for (int i = 1; i < 255; i++) {
            final String host = prefix + i;
            if (host.equals(mine)) continue;
            pool.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, PORT), 450);
                    s.setSoTimeout(2_000);
                    s.getOutputStream().write((HELLO + "\n" + WHO + "\n").getBytes(StandardCharsets.UTF_8));
                    s.getOutputStream().flush();
                    String reply = line(s.getInputStream());
                    if (reply.startsWith("NAME ") && sweeping == pool) listener.found(reply.substring(5).trim(), host, PORT);
                } catch (IOException | RuntimeException nobody) { /* the usual answer */ }
            });
        }
        pool.shutdown();
    }

    /** Android emulators (BlueStacks among them) sit on a private 10.0.2.x or 10.0.3.x network of their own. */
    static boolean behindEmulator(String address) {
        return address.startsWith("10.0.2.") || address.startsWith("10.0.3.");
    }

    void stopFinding() {
        java.util.concurrent.ExecutorService sweep = sweeping;
        sweeping = null;
        if (sweep != null) sweep.shutdownNow();
        NsdManager.DiscoveryListener d = discovering;
        discovering = null;
        if (d != null) try { nsd().stopServiceDiscovery(d); } catch (RuntimeException ignored) { /* not started */ }
        if (multicast != null) try { multicast.release(); } catch (RuntimeException ignored) { /* not held */ }
        multicast = null;
    }

    /**
     * Asks a device for its setup. Blocks.
     * @throws WrongCode when the code doesn't match.
     */
    static String fetch(String host, int port, String code) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 8_000);
            s.setSoTimeout(20_000);
            OutputStream out = s.getOutputStream();
            out.write((HELLO + "\n" + code.trim() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = s.getInputStream();
            String answer = line(in);
            if (!"OK".equals(answer)) throw new WrongCode();
            int length;
            try { length = Integer.parseInt(line(in)); } catch (NumberFormatException e) { throw new IOException("bad reply"); }
            if (length < 0 || length > MAX_BYTES) throw new IOException("bad size");
            ByteArrayOutputStream body = new ByteArrayOutputStream(length);
            byte[] buf = new byte[16 * 1024];
            while (body.size() < length) {
                int n = in.read(buf, 0, Math.min(buf.length, length - body.size()));
                if (n < 0) throw new IOException("cut off");
                body.write(buf, 0, n);
            }
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static final class WrongCode extends IOException {
        WrongCode() { super("wrong code"); }
    }

    /** One line, read byte by byte so nothing after it is swallowed by a buffer. */
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1 && c != '\n'; ) { b.write(c); if (b.size() > 96) break; }
        return b.toString("UTF-8").trim();
    }

    /** This device's address on the local network, for typing in when discovery is blocked. */
    static String localAddress() {
        try {
            for (java.net.NetworkInterface nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(nic.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (java.net.SocketException | RuntimeException ignored) { /* no network */ }
        return "";
    }

    /** A name the other device can show: the one set in Android, else the model. */
    static String deviceName(Context c) {
        String name = null;
        try { name = android.provider.Settings.Global.getString(c.getContentResolver(), "device_name"); }
        catch (RuntimeException ignored) { /* not readable on this device */ }
        if (name == null || name.trim().isEmpty()) name = Build.MODEL;
        return "Phone TV on " + name.trim();
    }

    private NsdManager nsd() { return (NsdManager) context.getSystemService(Context.NSD_SERVICE); }
}
