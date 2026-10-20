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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide owner of the connection to this TV's own adbd.
 *
 * Socket work, key generation and commands run on one background thread; state changes
 * and results are posted to the main thread. The connection is opened on demand and
 * dropped after a short idle period.
 *
 * Nothing here runs unless the user has turned the ADB link on in Setup, because the
 * first connection makes the TV show an authorisation dialog.
 */
public final class AdbBridge {

    private static final String TAG = "Homestead";

    public static final int IDLE = 0;
    public static final int CONNECTING = 1;
    /** The TV is showing its "Allow debugging?" dialog and waiting on the user. */
    public static final int AWAITING_APPROVAL = 2;
    public static final int CONNECTED = 3;
    /** Nothing is listening: ADB debugging is switched off in Fire TV settings. */
    public static final int NO_DAEMON = 4;
    /** The platform refused the socket outright. */
    public static final int BLOCKED = 5;
    /** Reached adbd, but the key was not authorised. */
    public static final int DENIED = 6;
    public static final int ERROR = 7;
    /** adbd accepted the socket and closed it silently; Fire OS 7 refuses on-device clients. */
    public static final int SELF_REFUSED = 8;

    /**
     * Idle time before the connection and tunnel are dropped. Short because adbd is
     * reachable by other apps while the tunnel is up; long enough for a burst of commands.
     */
    private static final long IDLE_DISCONNECT_MS = 25000L;

    public interface Listener {
        void onAdbState(int state, String detail);
    }

    public interface Callback {
        /** @param output combined stdout+stderr of the last command, never null. */
        void onDone(boolean ok, String output);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<Listener>();

    private static Context app;
    private static Handler worker;
    private static AdbConnection conn;          // worker thread only
    private static AdbCrypto crypto;            // worker thread only
    private static volatile int state = IDLE;
    private static volatile String detail = "";
    private static volatile String fingerprint;

    private AdbBridge() { }

    // ---------------------------------------------------------------- setup

    /** Safe to call repeatedly; the first call wins. */
    public static synchronized void init(Context c) {
        if (app == null && c != null) app = c.getApplicationContext();
        if (worker == null) {
            HandlerThread t = new HandlerThread("homestead-adb");
            t.start();
            worker = new Handler(t.getLooper());
        }
    }

    public static int state() { return state; }
    public static String detail() { return detail; }
    public static boolean isConnected() { return state == CONNECTED; }

    /**
     * True if a command can run now: connected, or the tunnel is enabled and will be
     * brought up on demand. Keeps the UI from greying actions out after an idle teardown.
     */
    public static boolean canRun(Context c) {
        return isConnected() || (c != null && Prefs.tunnelEnabled(c));
    }

    /** True once we know this device will never accept a connection from itself. */
    public static boolean selfRefused() { return state == SELF_REFUSED; }
    public static boolean isBusy() { return state == CONNECTING || state == AWAITING_APPROVAL; }

    /** Null until the key has been generated (first connect). */
    public static String fingerprint() { return fingerprint; }

    public static synchronized void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static synchronized void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** An idle link that the tunnel re-opens on demand reads "ready", not "off". */
    public static boolean isReady(Context c) {
        return state == CONNECTED || (state == IDLE && Prefs.adbEnabled(c)
                && Prefs.tunnelEnabled(c) && AdbCrypto.hasKey(c));
    }

    /** The link's state for a status row. */
    public static String statusLabel(Context c) {
        if (!Prefs.adbEnabled(c)) return "off";
        if (state == IDLE && isReady(c)) return "ready";
        return describe(state);
    }

    public static String describe(int s) {
        switch (s) {
            case CONNECTING: return "connecting";
            case AWAITING_APPROVAL: return "waiting for you";
            case CONNECTED: return "connected";
            case NO_DAEMON: return "ADB debugging is off";
            case BLOCKED: return "blocked by the system";
            case DENIED: return "not authorised";
            case SELF_REFUSED: return "not allowed on this TV";
            case ERROR: return "failed";
            default: return "off";
        }
    }

    // ---------------------------------------------------------------- public actions

    public static void connect() {
        init(app);
        if (worker == null) return;
        worker.removeCallbacksAndMessages(null);
        worker.post(new Runnable() {
            public void run() {
                ensureConnected();
                // Arm the idle teardown even without a command, or the tunnel would stay up.
                armIdleDisconnect();
            }
        });
    }

    public static void disconnect() {
        if (worker == null) return;
        worker.post(new Runnable() {
            public void run() {
                dropConnection();
                stopTunnelIfActive();
                publish(IDLE, "");
            }
        });
    }

    /** Deletes our key. The TV forgets it separately, under Developer options. */
    public static void forgetKey() {
        if (worker == null) return;
        worker.post(new Runnable() {
            public void run() {
                dropConnection();
                stopTunnelIfActive();
                crypto = null;
                fingerprint = null;
                if (app != null) AdbCrypto.forget(app);
                publish(IDLE, "key deleted");
            }
        });
    }

    /** Runs commands in order, stopping at the first failure. Connects first if needed. */
    public static void run(final String[] commands, final Callback cb) {
        init(app);
        if (worker == null) { finish(cb, false, "ADB is not available"); return; }
        worker.removeCallbacksAndMessages(null);
        worker.post(new Runnable() {
            public void run() {
                if (!ensureConnected()) {
                    finish(cb, false, detail);
                    armIdleDisconnect();
                    return;
                }
                StringBuilder log = new StringBuilder();
                boolean ok = true;
                // A refused command (e.g. a package Fire OS protects) must not skip the rest
                // of the batch; only a broken connection stops it.
                for (int i = 0; i < commands.length; i++) {
                    try {
                        AdbConnection.Result r = conn.shell(commands[i]);
                        log.append("$ ").append(commands[i]).append('\n');
                        if (r.output.length() > 0) log.append(r.output).append('\n');
                        if (!r.ok()) {
                            ok = false;
                            log.append("(exit ").append(r.code).append(")\n");
                        }
                    } catch (Throwable t) {
                        ok = false;
                        log.append("! ").append(t).append('\n');
                        dropConnection();
                        publish(ERROR, String.valueOf(t.getMessage()));
                        break;
                    }
                }
                finish(cb, ok, log.toString());
                armIdleDisconnect();
            }
        });
    }

    /** False when a batch never started because there was no connection. */
    public static boolean ran(String output) {
        return output != null && output.startsWith("$ ");
    }

    public static void run(String command, Callback cb) {
        run(new String[]{command}, cb);
    }

    /**
     * Installs an APK by streaming it to `pm install` (see AdbConnection.installStreamed).
     * Replacing the running app kills this process, so a successful self-update usually
     * shows up as a restart rather than as the callback firing.
     */
    public static void installApk(final java.io.File apk, final Callback cb) {
        init(app);
        if (worker == null) { finish(cb, false, "ADB is not available"); return; }
        worker.removeCallbacksAndMessages(null);
        worker.post(new Runnable() {
            public void run() {
                if (!ensureConnected()) { finish(cb, false, detail); armIdleDisconnect(); return; }
                java.io.InputStream in = null;
                try {
                    in = new java.io.FileInputStream(apk);
                    AdbConnection.Result r = conn.installStreamed(in, apk.length());
                    finish(cb, r.ok(), r.output);
                } catch (Throwable t) {
                    dropConnection();
                    publish(ERROR, String.valueOf(t.getMessage()));
                    finish(cb, false, String.valueOf(t));
                } finally {
                    if (in != null) try { in.close(); } catch (Throwable ignored) { }
                }
                armIdleDisconnect();
            }
        });
    }

    // ---------------------------------------------------------------- command helpers

    /**
     * Commands that fully enable the launcher from the TV itself. Our service is appended
     * to enabled_accessibility_services so other enabled services stay on.
     */
    public static String[] selfSetupCommands(Context c, String currentServices) {
        String pkg = c.getPackageName();
        String comp = pkg + "/" + pkg + ".HomeRedirectService";
        String services;
        if (currentServices == null || currentServices.trim().length() == 0) {
            services = comp;
        } else if (currentServices.contains(comp)) {
            services = currentServices;
        } else {
            services = currentServices + ":" + comp;
        }
        return new String[]{
                "settings put secure enabled_accessibility_services '" + services + "'",
                "settings put secure accessibility_enabled 1",
                // Optional: only the fallback overlay and usage paths need these.
                "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
                "appops set " + pkg + " GET_USAGE_STATS allow",
                "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS",
                // Lets HomeKeyWatcher catch Home presses before Amazon's home draws.
                "pm grant " + pkg + " android.permission.READ_LOGS",
        };
    }

    /** Reversible by design: disable-user keeps the APK and its data, unlike uninstall. */
    public static String disableCommand(String pkg) {
        return "pm disable-user --user 0 " + pkg;
    }

    public static String enableCommand(String pkg) {
        return "pm enable " + pkg;
    }

    // ---------------------------------------------------------------- worker internals

    /** Worker thread only. */
    private static boolean ensureConnected() {
        if (conn != null && conn.isAlive()) return true;
        if (app == null) { publish(ERROR, "no context"); return false; }
        dropConnection();
        publish(CONNECTING, "");
        try {
            // Load (or generate) the key first: ensureTunnelIfEnabled requires it on disk,
            // so on a first connect the tunnel would otherwise be skipped.
            if (crypto == null) {
                crypto = AdbCrypto.load(app);
                fingerprint = crypto.fingerprint();
            }
            ensureTunnelIfEnabled();
            final boolean[] prompted = { false };
            conn = AdbConnection.open(app, crypto, new AdbConnection.ApprovalListener() {
                public void onAwaitingApproval(String fp) {
                    fingerprint = fp;
                    prompted[0] = true;
                    // Get the overlay out of the way of the TV's approval dialog.
                    MAIN.post(new Runnable() {
                        public void run() {
                            HomeRedirectService.suspend(180000L);
                            HomeRedirectService.hideOverlay();
                        }
                    });
                    publish(AWAITING_APPROVAL, fp);
                }
            });
            // Approved: lift the pause set for the dialog.
            if (prompted[0]) {
                MAIN.post(new Runnable() {
                    public void run() { HomeRedirectService.endSuspend(); }
                });
            }
            publish(CONNECTED, conn.banner());
            return true;
        } catch (AdbConnection.AdbException e) {
            Log.w(TAG, "adb connect failed (" + e.reason + "): " + e.getMessage());
            publish(mapReason(e.reason), e.getMessage());
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "adb connect failed: " + t);
            publish(ERROR, String.valueOf(t.getMessage()));
            return false;
        }
    }

    private static int mapReason(int reason) {
        switch (reason) {
            case AdbConnection.FAIL_NO_DAEMON: return NO_DAEMON;
            case AdbConnection.FAIL_BLOCKED: return BLOCKED;
            case AdbConnection.FAIL_DENIED: return DENIED;
            case AdbConnection.FAIL_SELF_REFUSED: return SELF_REFUSED;
            default: return ERROR;
        }
    }

    private static void dropConnection() {
        if (conn != null) {
            conn.closeQuietly();
            conn = null;
        }
    }

    private static void armIdleDisconnect() {
        if (worker == null) return;
        worker.postDelayed(new Runnable() {
            public void run() {
                if (conn == null) return;
                Log.i(TAG, "adb idle, closing");
                dropConnection();
                stopTunnelIfActive();
                if (state == CONNECTED) publish(IDLE, "idle");
            }
        }, IDLE_DISCONNECT_MS);
    }

    /**
     * Bring the tunnel down once the connection is gone. Only called on idle, disconnect
     * and forget-key, never from pre-connect cleanup.
     */
    private static void stopTunnelIfActive() {
        if (app != null && TunnelVpnService.isActive()) {
            TunnelVpnService.stop(app);
        }
    }

    /**
     * Worker thread. If the tunnel is enabled but down, start it and wait for isActive().
     * VPN consent was already granted via VpnSetupActivity, so this is silent; the service
     * comes up on the main thread, so waiting here cannot deadlock.
     */
    private static void ensureTunnelIfEnabled() {
        if (app == null || !Prefs.tunnelEnabled(app)) return;
        if (TunnelVpnService.isActive()) return;
        if (!AdbCrypto.hasKey(app)) return;
        try {
            app.startService(new Intent(app, TunnelVpnService.class));
        } catch (Throwable t) {
            Log.w(TAG, "could not start tunnel: " + t);
            return;
        }
        for (int i = 0; i < 40 && !TunnelVpnService.isActive(); i++) {
            try { Thread.sleep(75); } catch (InterruptedException ignored) { }
        }
    }

    private static void publish(final int s, final String d) {
        state = s;
        detail = (d == null) ? "" : d;
        MAIN.post(new Runnable() {
            public void run() {
                List<Listener> copy;
                synchronized (AdbBridge.class) {
                    copy = new ArrayList<Listener>(LISTENERS);
                }
                for (int i = 0; i < copy.size(); i++) {
                    try { copy.get(i).onAdbState(s, detail); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private static void finish(final Callback cb, final boolean ok, final String out) {
        if (cb == null) return;
        MAIN.post(new Runnable() {
            public void run() {
                try { cb.onDone(ok, out == null ? "" : out); } catch (Throwable ignored) { }
            }
        });
    }
}
