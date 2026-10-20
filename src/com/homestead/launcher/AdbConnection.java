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
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * A minimal ADB client, speaking to the adbd running on this same television.
 *
 * adbd is a TCP server the user turns on via Settings (Developer options, ADB debugging).
 * Connecting to it gives a uid 2000 (shell) context, which is what `pm disable-user`,
 * `settings put secure` and `appops set` need - the settings the app cannot change in its
 * own process. adbd authenticates with an RSA key (A_AUTH): the first time, the TV shows
 * its own approval dialog and remembers the key. That approval is the platform's boundary.
 *
 * Deliberately request/response and single-threaded: one short command at a time, so there
 * is no reader thread or stream multiplexer; messages for other streams are drained.
 */
final class AdbConnection implements Closeable {

    private static final String TAG = "Homestead";

    static final String HOST = "127.0.0.1";
    /** Fire OS pins network adb here via persist.adb.tcp.port. */
    static final int PORT = 5555;

    // Wire commands, little-endian ASCII (system/core/adb/adb.h).
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_AUTH = 0x48545541;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    /** Protocol version without TLS. Higher versions add adb-over-TLS, which Fire OS 7 has not. */
    private static final int VERSION = 0x01000000;
    private static final int MAXDATA = 256 * 1024;
    private static final int HEADER = 24;
    /** Sanity bound on a declared payload length, so a desynced stream cannot allocate wildly. */
    private static final int MAX_FRAME = 1024 * 1024;
    /** Stop accumulating command output past this; nothing we run is anywhere near it. */
    private static final int MAX_OUTPUT = 512 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int IO_TIMEOUT_MS = 60000;
    /** The user has to find the remote and read a dialog. Be generous. */
    private static final int APPROVAL_TIMEOUT_MS = 180000;

    /** Appended to every command so the exit status comes back with the output. */
    private static final String RC = "__FTVH_RC=";

    /**
     * Handshake tracing. Off for release: it prints message types and the socket addressing
     * to logcat, which is noise at best and a small information leak at worst.
     */
    private static final boolean DEBUG = false;

    // ---------------------------------------------------------------- failure reasons

    static final int FAIL_NO_DAEMON = 1;   // nothing listening: ADB debugging is switched off
    static final int FAIL_BLOCKED = 2;     // the socket itself was refused by policy (SELinux)
    static final int FAIL_DENIED = 3;      // reached adbd, but the key was not authorised
    static final int FAIL_IO = 4;          // anything else
    /**
     * The TCP connection was accepted and then dropped before adbd replied. On recent Fire
     * OS builds adbd refuses connections that originate on the device itself, so a direct
     * loopback or LAN-address connection fails here; the local tunnel is the way around it.
     * The client is unchanged and still works where the platform allows a direct connection.
     */
    static final int FAIL_SELF_REFUSED = 5;

    static final class AdbException extends IOException {
        final int reason;
        AdbException(int reason, String msg) { super(msg); this.reason = reason; }
        AdbException(int reason, String msg, Throwable cause) {
            super(msg + ": " + cause);
            this.reason = reason;
        }
    }

    /** Called once, from the worker thread, when the TV is showing its approval dialog. */
    interface ApprovalListener { void onAwaitingApproval(String fingerprint); }

    // ---------------------------------------------------------------- state

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String banner;
    private int nextLocalId = 1;
    /** Set once adbd has sent us anything at all. Distinguishes a refusal from a protocol fault. */
    private boolean sawReply = false;

    private AdbConnection(Socket s, InputStream in, OutputStream out, String banner) {
        this.socket = s;
        this.in = in;
        this.out = out;
        this.banner = banner;
    }

    String banner() { return banner; }

    boolean isAlive() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    // ---------------------------------------------------------------- connect

    /**
     * Connects and authenticates, trying each address this device answers on.
     *
     * Loopback is tried first because it is the one address that always exists and never
     * changes. It is not always the one that works, though: on the test TV (Fire OS 7.7.1.5)
     * adbd accepts a connection from 127.0.0.1, reads the CNXN, and then closes without
     * replying - while the byte-for-byte identical handshake over the LAN address of the
     * same device authenticates normally. So the device's own routable addresses are tried
     * next, and the one that worked is remembered for the rest of the process.
     */
    static AdbConnection open(Context ctx, AdbCrypto crypto, ApprovalListener approval)
            throws AdbException {
        List<InetAddress> hosts = candidateHosts();
        AdbException last = null;
        for (int i = 0; i < hosts.size(); i++) {
            InetAddress host = hosts.get(i);
            try {
                AdbConnection c = attempt(host, ctx, crypto, approval);
                preferred = host;
                return c;
            } catch (AdbException e) {
                // A refusal by the user is final - trying another address would only make
                // the TV ask them again.
                if (e.reason == FAIL_DENIED) throw e;
                Log.w(TAG, "adb via " + host.getHostAddress() + " failed: " + e.getMessage());
                last = e;
            }
        }
        throw (last != null) ? last
                : new AdbException(FAIL_NO_DAEMON, "no address answered on port " + PORT);
    }

    private static AdbConnection attempt(InetAddress host, Context ctx, AdbCrypto crypto,
                                         ApprovalListener approval) throws AdbException {
        Socket s = new Socket();
        InputStream in;
        OutputStream out;
        try {
            s.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            s.setSoTimeout(IO_TIMEOUT_MS);
            in = s.getInputStream();
            out = s.getOutputStream();
            if (DEBUG) {
                Log.i(TAG, "adb socket " + s.getLocalAddress() + ":" + s.getLocalPort()
                        + " -> " + s.getRemoteSocketAddress());
            }
        } catch (Throwable t) {
            closeQuietly(s);
            throw new AdbException(classifyConnect(t),
                    "cannot reach adbd on " + host.getHostAddress() + ":" + PORT, t);
        }

        AdbConnection c = new AdbConnection(s, in, out, "");
        try {
            return c.handshake(ctx, crypto, approval);
        } catch (Throwable t) {
            boolean spokeToUs = c.sawReply;
            c.closeQuietly();
            if (t instanceof AdbException && spokeToUs) throw (AdbException) t;
            if (!spokeToUs) {
                throw new AdbException(FAIL_SELF_REFUSED,
                        "adbd accepted the connection and closed it without replying");
            }
            throw new AdbException(FAIL_IO, "adb handshake failed", t);
        }
    }

    /**
     * When set, the client connects only through this address (the local tunnel's virtual
     * adbd) instead of the device's own addresses. Null in the ordinary path.
     */
    private static volatile InetAddress overrideHost;
    static void setOverrideHost(InetAddress a) { overrideHost = a; }

    /** Loopback, then whatever the last successful connection used, then every local address. */
    private static List<InetAddress> candidateHosts() {
        InetAddress ov = overrideHost;
        if (ov != null) {
            List<InetAddress> only = new ArrayList<InetAddress>(1);
            only.add(ov);
            return only;
        }
        List<InetAddress> out = new ArrayList<InetAddress>();
        try { out.add(InetAddress.getByName(HOST)); } catch (Throwable ignored) { }
        if (preferred != null && !out.contains(preferred)) out.add(0, preferred);
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni == null || ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    // Link-local v6 needs a scope id to be usable and never carries adbd.
                    if (a == null || a.isLinkLocalAddress() || a.isAnyLocalAddress()) continue;
                    if (!out.contains(a)) out.add(a);
                }
            }
        } catch (Throwable ignored) { }
        try {
            InetAddress v6 = InetAddress.getByName("::1");
            if (!out.contains(v6)) out.add(v6);
        } catch (Throwable ignored) { }
        return out;
    }

    /** Remembered across connections so a reconnect does not re-walk the list. */
    private static volatile InetAddress preferred;

    /** Which address the live connection is using, for the Setup page. */
    static String preferredHost() {
        InetAddress a = preferred;
        return (a == null) ? null : a.getHostAddress();
    }

    /**
     * A refused connection and a forbidden one mean completely different things to the
     * user - "turn ADB debugging on" versus "this device will not allow it at all" - so
     * they are separated here rather than reported as one failure.
     */
    private static int classifyConnect(Throwable t) {
        String m = String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.US);
        if (m.contains("eacces") || m.contains("permission denied")
                || m.contains("eperm") || m.contains("operation not permitted")) {
            return FAIL_BLOCKED;
        }
        if (t instanceof ConnectException || m.contains("econnrefused")
                || m.contains("connection refused") || m.contains("unreachable")) {
            return FAIL_NO_DAEMON;
        }
        if (t instanceof SocketTimeoutException) return FAIL_NO_DAEMON;
        return FAIL_IO;
    }

    private AdbConnection handshake(Context ctx, AdbCrypto crypto, ApprovalListener approval)
            throws IOException {
        // "host::" is the minimal legal banner: systemtype, serial, banner. No features
        // are advertised, so adbd will not try to frame our shell output with shell v2.
        send(A_CNXN, VERSION, MAXDATA, asciiz("host::"));

        boolean signed = false;
        boolean offeredKey = false;
        while (true) {
            Message m;
            try {
                m = receive();
            } catch (SocketTimeoutException timeout) {
                throw new AdbException(offeredKey ? FAIL_DENIED : FAIL_IO,
                        offeredKey ? "no answer to the authorisation prompt"
                                   : "adbd stopped responding");
            }

            if (m.command == A_CNXN) {
                String b = new String(m.data, "UTF-8").trim();
                Log.i(TAG, "adb connected: " + b);
                return new AdbConnection(socket, in, out, b);
            }

            if (m.command == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (!signed) {
                    // Try the key we already have. If the TV authorised us before, this is
                    // the whole handshake and the user sees nothing at all.
                    signed = true;
                    try {
                        send(A_AUTH, AUTH_SIGNATURE, 0, crypto.signToken(m.data));
                    } catch (Exception e) {
                        throw new AdbException(FAIL_IO, "cannot sign the adb token", e);
                    }
                } else if (!offeredKey) {
                    // The signature was not recognised, so offer the public key. adbd now
                    // puts its approval dialog on the television and blocks until answered.
                    offeredKey = true;
                    socket.setSoTimeout(APPROVAL_TIMEOUT_MS);
                    if (approval != null) {
                        try { approval.onAwaitingApproval(crypto.fingerprint()); }
                        catch (Throwable ignored) { }
                    }
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, crypto.publicKeyPayload(ctx));
                } else {
                    throw new AdbException(FAIL_DENIED, "the TV rejected our key");
                }
                continue;
            }

            if (m.command == A_CLSE) {
                throw new AdbException(offeredKey ? FAIL_DENIED : FAIL_IO,
                        "adbd closed the connection during authentication");
            }
            // Anything else before A_CNXN is not ours to interpret; ignore it.
        }
    }

    // ---------------------------------------------------------------- shell

    static final class Result {
        final String output;
        /** Process exit status, or -1 if the shell never reported one. */
        final int code;
        Result(String output, int code) { this.output = output; this.code = code; }
        boolean ok() { return code == 0; }
        @Override public String toString() { return "rc=" + code + " " + output.trim(); }
    }

    /**
     * Runs one command through the `shell:` service and waits for it to finish.
     *
     * The command is handed to sh, so the exit status is captured by appending an echo
     * rather than by implementing adb shell protocol v2 (which exists only to carry a
     * separate stderr stream and an exit code, neither of which is worth a second framing
     * layer here). stdout and stderr are already merged by adbd for a non-interactive
     * command, so `2>&1` is not needed.
     */
    synchronized Result shell(String command) throws IOException {
        final int localId = nextLocalId++;
        send(A_OPEN, localId, 0, asciiz("shell:" + command + "\n" + "echo " + RC + "$?"));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean opened = false;
        while (true) {
            Message m = receive();

            if (m.command == A_OKAY && m.arg1 == localId) { opened = true; continue; }

            if (m.command == A_WRTE && m.arg1 == localId) {
                if (buf.size() < MAX_OUTPUT) buf.write(m.data, 0, m.data.length);
                // Flow control: adbd will not send the next chunk until this is answered.
                send(A_OKAY, localId, m.arg0, null);
                continue;
            }

            if (m.command == A_CLSE && m.arg1 == localId) {
                // arg0 == 0 means "your OPEN failed", and there is no remote stream to
                // close back. Otherwise the close must be acknowledged.
                if (m.arg0 != 0) send(A_CLSE, localId, m.arg0, null);
                break;
            }

            if (m.command == A_CLSE || m.command == A_WRTE || m.command == A_OKAY) {
                continue;       // some other stream; we only ever run one at a time
            }
        }
        if (!opened && buf.size() == 0) {
            throw new AdbException(FAIL_IO, "adbd refused to open a shell");
        }
        return split(new String(buf.toByteArray(), "UTF-8"));
    }

    /** Peels the trailing exit-status marker back off the output. */
    private static Result split(String raw) {
        String text = raw.replace("\r\n", "\n");
        int at = text.lastIndexOf(RC);
        if (at < 0) return new Result(text, -1);
        int code = -1;
        String tail = text.substring(at + RC.length()).trim();
        try { code = Integer.parseInt(tail); } catch (NumberFormatException ignored) { }
        String body = text.substring(0, at);
        if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
        return new Result(body, code);
    }

    // ---------------------------------------------------------------- streamed install

    private static final int WRITE_CHUNK = 64 * 1024;

    /**
     * Installs an APK by streaming its bytes into `pm install -S <size>` over the raw
     * `exec:` service, as `adb install` does. Streaming over stdin avoids a path-based
     * install, which would need the file readable by system_server; the download can stay
     * in the app's internal cache. exec: (not shell:) gives a raw binary channel so the pty
     * line discipline cannot mangle the APK. There is no exit code, so success is taken from
     * pm printing "Success".
     */
    synchronized Result installStreamed(InputStream data, long size) throws IOException {
        final int localId = nextLocalId++;
        send(A_OPEN, localId, 0, asciiz("exec:pm install -r -S " + size));

        int remoteId = -1;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // The open is acknowledged with an A_OKAY that also carries the remote stream id
        // (our address for outgoing writes) and the first unit of write credit.
        while (remoteId < 0) {
            Message m = receive();
            if (m.command == A_OKAY && m.arg1 == localId) { remoteId = m.arg0; break; }
            if (m.command == A_CLSE && m.arg1 == localId) {
                if (m.arg0 != 0) send(A_CLSE, localId, m.arg0, null);
                throw new AdbException(FAIL_IO, "adbd refused to open the installer stream");
            }
            // frames for other streams: not ours to act on
        }

        byte[] chunk = new byte[WRITE_CHUNK];
        long sent = 0;
        boolean closed = false;

        // Write one credit-limited chunk, then wait for the next A_OKAY before the next -
        // a window of one, which is all adbd promises. Any stdout pm emits meanwhile is
        // drained and acknowledged so the peer never blocks on us.
        while (sent < size && !closed) {
            int want = (int) Math.min(chunk.length, size - sent);
            int n = readFully(data, chunk, want);
            if (n <= 0) throw new AdbException(FAIL_IO, "the update file ended early");
            byte[] payload = (n == chunk.length) ? chunk : java.util.Arrays.copyOf(chunk, n);
            send(A_WRTE, localId, remoteId, payload);
            sent += n;

            boolean acked = false;
            while (!acked) {
                Message m = receive();
                if (m.command == A_OKAY && m.arg1 == localId) {
                    acked = true;
                } else if (m.command == A_WRTE && m.arg1 == localId) {
                    if (buf.size() < MAX_OUTPUT) buf.write(m.data, 0, m.data.length);
                    send(A_OKAY, localId, m.arg0, null);
                } else if (m.command == A_CLSE && m.arg1 == localId) {
                    if (m.arg0 != 0) send(A_CLSE, localId, m.arg0, null);
                    closed = true;
                    acked = true;
                }
            }
        }

        // All bytes delivered: read pm's result until it closes the stream.
        while (!closed) {
            Message m = receive();
            if (m.command == A_WRTE && m.arg1 == localId) {
                if (buf.size() < MAX_OUTPUT) buf.write(m.data, 0, m.data.length);
                send(A_OKAY, localId, m.arg0, null);
            } else if (m.command == A_CLSE && m.arg1 == localId) {
                if (m.arg0 != 0) send(A_CLSE, localId, m.arg0, null);
                closed = true;
            }
        }

        String out = new String(buf.toByteArray(), "UTF-8").trim();
        return new Result(out, out.contains("Success") ? 0 : 1);
    }

    private static int readFully(InputStream in, byte[] b, int want) throws IOException {
        int off = 0;
        while (off < want) {
            int r = in.read(b, off, want - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    // ---------------------------------------------------------------- framing

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    /**
     * Header and payload go out as one write, so that a peer that has already gone away
     * reports the failure on the header rather than only on a later payload write.
     */
    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int len = (data == null) ? 0 : data.length;
        ByteBuffer h = ByteBuffer.allocate(HEADER + len).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(command);
        h.putInt(arg0);
        h.putInt(arg1);
        h.putInt(len);
        h.putInt(checksum(data));
        h.putInt(~command);
        if (len > 0) h.put(data);
        // Stream acknowledgements are per-chunk and would drown the handshake.
        if (DEBUG && command != A_OKAY) {
            Log.i(TAG, "adb -> " + name(command) + " arg0=" + arg0 + " len=" + len);
        }
        out.write(h.array());
        out.flush();
    }

    private static String name(int c) {
        if (c == A_CNXN) return "CNXN";
        if (c == A_AUTH) return "AUTH";
        if (c == A_OPEN) return "OPEN";
        if (c == A_OKAY) return "OKAY";
        if (c == A_WRTE) return "WRTE";
        if (c == A_CLSE) return "CLSE";
        return Integer.toHexString(c);
    }

    private Message receive() throws IOException {
        byte[] head = new byte[HEADER];
        readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        sawReply = true;
        Message m = new Message();
        m.command = b.getInt();
        if (DEBUG && m.command != A_OKAY && m.command != A_WRTE) {
            Log.i(TAG, "adb <- " + name(m.command));
        }
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt();                                     // payload checksum, unused by adbd
        int magic = b.getInt();
        if (magic != ~m.command) {
            throw new AdbException(FAIL_IO, "corrupt adb frame (magic mismatch)");
        }
        if (len < 0 || len > MAX_FRAME) {
            throw new AdbException(FAIL_IO, "implausible adb payload length " + len);
        }
        m.data = new byte[len];
        if (len > 0) readFully(m.data);
        return m;
    }

    private void readFully(byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n < 0) throw new AdbException(FAIL_IO, "adbd closed the connection");
            off += n;
        }
    }

    /**
     * adb calls this a CRC but it is a plain byte sum. Current adbd ignores it entirely for
     * protocol version 0x01000000; it is computed properly anyway so this stays correct
     * against an implementation that does check.
     */
    private static int checksum(byte[] data) {
        if (data == null) return 0;
        int sum = 0;
        for (int i = 0; i < data.length; i++) sum += (data[i] & 0xFF);
        return sum;
    }

    private static byte[] asciiz(String s) {
        byte[] raw = s.getBytes();
        byte[] out = new byte[raw.length + 1];
        System.arraycopy(raw, 0, out, 0, raw.length);
        out[raw.length] = 0;
        return out;
    }

    // ---------------------------------------------------------------- teardown

    @Override public void close() { closeQuietly(); }

    void closeQuietly() {
        try { if (in != null) in.close(); } catch (Throwable ignored) { }
        try { if (out != null) out.close(); } catch (Throwable ignored) { }
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (Throwable ignored) { }
    }
}
