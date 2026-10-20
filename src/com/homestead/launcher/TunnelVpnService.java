/*
 * Homestead - a replacement home screen for Fire TV.
 * Copyright (C) 2026 Homestead contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. It is distributed in
 * the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details. You should have received a copy of the GNU General
 * Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.homestead.launcher;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * A local VpnService used only to let the app's ADB client reach this TV's own adbd.
 *
 * It establishes a TUN carrying a single private /24 and rewrites only the IP addresses of
 * packets on that subnet; both kernels still do the TCP. Only that subnet is routed into
 * the VPN, so all other traffic is untouched and nothing else is inspected.
 *
 * Any app can reach adbd while the tunnel is up, but each still faces adbd's own RSA-key
 * approval and this app's key is app-private. The tunnel is off until the user enables it
 * (VpnSetupActivity is not exported); after that it comes up only for ADB work, including
 * boot re-apply, and is torn down as soon as that work goes idle.
 */
public class TunnelVpnService extends VpnService {

    private static final String TAG = "Homestead";

    /** Per-packet tracing. Off for release - it would print handshake bytes to logcat. */
    private static final boolean TRACE = false;

    static final String TUN_ADDR = "10.113.77.1";   // the tunnel interface (a local address)
    static final String FAKE_SRC = "10.113.77.7";    // the peer adbd sees: bound to no interface
    static final String VIRT_ADBD = "10.113.77.8";   // the address the app dials
    private static final String SUBNET = "10.113.77.0";
    private static final int PREFIX = 24;
    private static final int MTU = 1500;

    private ParcelFileDescriptor tun;
    private Thread relay;
    private volatile boolean running;

    /** True while the tunnel is up, so the UI and AdbBridge know the transport exists. */
    private static volatile boolean active;
    public static boolean isActive() { return active; }

    /** The live service, so a stop request can close the tun fd directly. */
    private static volatile TunnelVpnService instance;

    /**
     * Tear the tunnel down. The framework keeps the service alive while the VPN interface
     * is up, so close the tun fd directly here to bring the interface down and let the
     * service stop.
     */
    public static void stop(Context c) {
        TunnelVpnService s = instance;
        if (s != null) s.teardown();
        try { c.stopService(new Intent(c, TunnelVpnService.class)); } catch (Throwable ignored) { }
    }

    static InetAddress virtualHost() {
        try { return InetAddress.getByName(VIRT_ADBD); } catch (Throwable t) { return null; }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        start();
        return START_NOT_STICKY;
    }

    private synchronized void start() {
        if (running) {
            connectThroughTunnel();     // already up - just (re)connect through it
            return;
        }
        final String real = discoverRealAddr();
        if (real == null) {
            Log.w(TAG, "tunnel: no Wi-Fi address to reach adbd on");
            stopSelf();
            return;
        }
        if (subnetInUse()) {
            // Our tunnel subnet collides with a real network on this device. Routing it
            // would hijack that traffic, so refuse rather than cause harm.
            Log.w(TAG, "tunnel: subnet " + SUBNET + "/" + PREFIX + " is in use; refusing");
            stopSelf();
            return;
        }
        try {
            Builder b = new Builder();
            b.setSession("Homestead");
            b.addAddress(TUN_ADDR, PREFIX);
            b.addRoute(SUBNET, PREFIX);   // ONLY this subnet is diverted; real traffic is untouched
            b.setMtu(MTU);
            b.setBlocking(true);
            tun = b.establish();
        } catch (Throwable t) {
            Log.w(TAG, "tunnel: establish failed: " + t);
            stopSelf();
            return;
        }
        if (tun == null) {
            Log.w(TAG, "tunnel: establish returned null (no VPN consent?)");
            stopSelf();
            return;
        }
        running = true;
        instance = this;
        // A caller waiting on isActive() connects at once, so the client must already point
        // at the tunnel and the relay must be running before "active" is set.
        AdbConnection.setOverrideHost(virtualHost());
        relay = new Thread(new Runnable() {
            public void run() { relayLoop(real); }
        }, "homestead-tun");
        relay.start();
        active = true;
        Log.i(TAG, "tunnel up");

        connectThroughTunnel();
    }

    /** Connect through the tunnel. Instant once the key is authorised. */
    private void connectThroughTunnel() {
        AdbBridge.init(getApplicationContext());
        AdbBridge.connect();
    }

    // ---------------------------------------------------------------- the relay

    /**
     * The app's own local address, keyed by its TCP port. The return path delivers to
     * whatever source address the client used, learned from each egress packet; ports are
     * preserved end to end, so the port is a stable key.
     */
    private final java.util.concurrent.ConcurrentHashMap<Integer, byte[]> appAddr =
            new java.util.concurrent.ConcurrentHashMap<Integer, byte[]>();

    private void relayLoop(String realAddr) {
        byte[] real = v4(realAddr);
        byte[] fake = v4(FAKE_SRC);
        byte[] virt = v4(VIRT_ADBD);
        int egress = 0, ret = 0;

        FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
        byte[] p = new byte[MTU + 80];
        try {
            while (running) {
                int n = in.read(p);
                if (n < 20) continue;
                if ((p[0] & 0xF0) != 0x40) continue;          // IPv4 only
                int ihl = (p[0] & 0x0F) * 4;
                if (ihl < 20 || n < ihl) continue;
                // Trust the header's own length, and drop anything truncated or overlong.
                int len = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
                if (len < ihl || len > n) continue;
                if ((p[9] & 0xFF) != 6) continue;             // TCP only
                if (ihl + 14 > len) continue;                 // need the TCP header's flags

                boolean toVirt = eq(p, 16, virt);
                boolean toFake = eq(p, 16, fake);
                if (toVirt) {
                    int appPort = ((p[ihl] & 0xFF) << 8) | (p[ihl + 1] & 0xFF);
                    appAddr.put(appPort, new byte[]{ p[12], p[13], p[14], p[15] });
                    set(p, 12, fake); set(p, 16, real);        // egress: app -> adbd
                    egress++;
                    if (TRACE) trace("->adbd", p, ihl, len);
                } else if (toFake) {
                    int appPort = ((p[ihl + 2] & 0xFF) << 8) | (p[ihl + 3] & 0xFF);
                    byte[] dst = appAddr.get(appPort);
                    if (dst == null) dst = real;               // fallback: the usual source
                    set(p, 12, virt); set(p, 16, dst);         // return: adbd -> app
                    ret++;
                    if (TRACE) trace("->app ", p, ihl, len);
                } else {
                    continue;                                  // not ours
                }
                ipChecksum(p, ihl);
                tcpChecksum(p, ihl, len);
                out.write(p, 0, len);
            }
        } catch (Throwable t) {
            if (running) Log.w(TAG, "tunnel relay ended: " + t);
        }
        Log.i(TAG, "tunnel relay stopped (egress=" + egress + " return=" + ret + ")");
    }

    /** Decode enough of a segment to follow the handshake in logcat. TRACE builds only. */
    private static void trace(String dir, byte[] p, int ihl, int len) {
        int f = p[ihl + 13] & 0xFF;
        StringBuilder fl = new StringBuilder();
        if ((f & 0x02) != 0) fl.append("SYN ");
        if ((f & 0x10) != 0) fl.append("ACK ");
        if ((f & 0x01) != 0) fl.append("FIN ");
        if ((f & 0x04) != 0) fl.append("RST ");
        int payload = len - ihl - ((p[ihl + 12] & 0xF0) >> 4) * 4;
        Log.i(TAG, "tun " + dir + " " + ip(p, 12) + " -> " + ip(p, 16)
                + " [" + fl.toString().trim() + "] len=" + payload);
    }

    // ---------------------------------------------------------------- checksums

    private static void ipChecksum(byte[] p, int ihl) {
        p[10] = 0; p[11] = 0;
        int sum = 0;
        for (int i = 0; i < ihl; i += 2) sum += ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        int cs = (~sum) & 0xFFFF;
        p[10] = (byte) (cs >>> 8); p[11] = (byte) cs;
    }

    private static void tcpChecksum(byte[] p, int ihl, int total) {
        int off = ihl, len = total - ihl;
        p[off + 16] = 0; p[off + 17] = 0;
        long sum = 0;
        for (int i = 12; i < 20; i += 2) sum += ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF); // src+dst
        sum += 6;            // protocol
        sum += len & 0xFFFF; // TCP length
        for (int i = 0; i + 1 < len; i += 2) sum += ((p[off + i] & 0xFF) << 8) | (p[off + i + 1] & 0xFF);
        if ((len & 1) != 0) sum += (p[off + len - 1] & 0xFF) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        int cs = (int) ((~sum) & 0xFFFF);
        p[off + 16] = (byte) (cs >>> 8); p[off + 17] = (byte) cs;
    }

    // ---------------------------------------------------------------- helpers

    private static boolean eq(byte[] p, int off, byte[] a) {
        return p[off] == a[0] && p[off + 1] == a[1] && p[off + 2] == a[2] && p[off + 3] == a[3];
    }

    private static void set(byte[] p, int off, byte[] a) {
        p[off] = a[0]; p[off + 1] = a[1]; p[off + 2] = a[2]; p[off + 3] = a[3];
    }

    private static byte[] v4(String s) {
        String[] q = s.split("\\.");
        return new byte[]{ (byte) Integer.parseInt(q[0]), (byte) Integer.parseInt(q[1]),
                (byte) Integer.parseInt(q[2]), (byte) Integer.parseInt(q[3]) };
    }

    private static String ip(byte[] p, int off) {
        return (p[off] & 0xFF) + "." + (p[off + 1] & 0xFF) + "."
                + (p[off + 2] & 0xFF) + "." + (p[off + 3] & 0xFF);
    }

    /** The Wi-Fi/Ethernet address adbd actually listens behind - never loopback or the tun. */
    private static String discoverRealAddr() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni == null || ni.isLoopback() || !ni.isUp()) continue;
                String name = ni.getName();
                if (name != null && name.startsWith("tun")) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && !a.isLinkLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** True if the tunnel subnet overlaps a real network - routing it would hijack traffic. */
    private static boolean subnetInUse() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni == null || ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address) {
                        byte[] b = a.getAddress();
                        // /24 compare against SUBNET (10.113.77.0)
                        if ((b[0] & 0xFF) == 10 && (b[1] & 0xFF) == 113 && (b[2] & 0xFF) == 77) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Idempotent. Closing the tun fd is what brings tun0 down; do it here, not lazily. */
    private synchronized void teardown() {
        if (tun == null && !running) return;
        running = false;
        active = false;
        if (instance == this) instance = null;
        AdbConnection.setOverrideHost(null);
        // A live connection through the tunnel now points at a dead socket - drop it so the
        // next connect goes direct (or re-opens the tunnel).
        try { AdbBridge.disconnect(); } catch (Throwable ignored) { }
        try { if (tun != null) tun.close(); } catch (Throwable ignored) { }
        tun = null;
        Log.i(TAG, "tunnel torn down");
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }
}
