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
import android.provider.Settings;
import android.view.View;

/**
 * Setup: what the app needs and why.
 *
 * The one required step is enabling the accessibility service, which an app cannot do for
 * itself. On Fire OS, ACTION_ACCESSIBILITY_SETTINGS resolves to a CTS stub and the real
 * screen needs a privileged permission, so the instructions are written out by hand.
 * Alternatively the ADB link (see AdbConnection) runs the setup commands on this TV once
 * the user approves the connection on screen. WRITE_SECURE_SETTINGS is optional (TV Health).
 */
public final class SetupPanel {

    private static final int OK = 0xFFA7D28F;
    private static final int WARN = 0xFFE8C468;
    private static final int BAD = 0xFFE89A8A;

    private SetupPanel() { }

    public static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, "Setup", "What this launcher needs, and how to switch it on");

        AdbBridge.init(c);

        // ------------------------------------------------------------------ status
        p.addHeader("Status");

        final Panel.Row svcRow = p.addRow("Home-button redirect", "", null);
        final Panel.Row ovlRow = p.addRow("Instant home screen", "", null);
        final Panel.Row useRow = p.addRow("App ordering", "", null);
        final Panel.Row adbRow = p.addRow("Configure this TV", "", null);

        // ------------------------------------------------------------------ step one
        p.addHeader("1. Turn on the Home button redirect");

        p.addNote("On your remote: press Home, open Settings, then Accessibility, then "
                + "Homestead, and switch it on. That is the whole setup - the launcher, "
                + "the instant home screen and last-used ordering all work from there with "
                + "nothing else granted.\n\n"
                + "Fire OS does not let apps open that screen for you, so this step is done "
                + "by hand.\n\n"
                + "Note that installing an update to this app switches the service off "
                + "again - Android does that to every accessibility service on reinstall - "
                + "so come back here after updating.");

        p.addRow("Open Fire TV Settings", "", new Runnable() {
            public void run() { openSettings(c); }
        });

        // ------------------------------------------------------------------ step two
        p.addHeader("2. Optional: let this app configure the TV");

        p.addNote("Everything above works without this. What follows is only for the "
                + "optional tuning: switching off Amazon's advertising and telemetry "
                + "packages, and turning off the animations.\n\n"
                + "Fire OS runs its debugging service on the television itself, so with "
                + "your permission this app can connect to it and gain the same access a "
                + "computer would have. Nothing leaves the TV, and this app makes no other "
                + "network requests of any kind. The TELEVISION, not this app, asks you to "
                + "approve a key - check that the fingerprint it shows matches the one "
                + "below.\n\n"
                + "A direct connection will be refused, and not because anything is wrong: "
                + "recent Fire OS builds drop any ADB connection that starts on the TV "
                + "itself.\n\n"
                + "\"On-device tunnel\" below gets around that block from inside the app - no "
                + "computer needed. If you would rather not, \"Commands for a computer\" "
                + "lists exactly what to run instead.");

        p.addRow("Open \"My Fire TV\" (ADB debugging lives here)", "", new Runnable() {
            public void run() { openDeviceSettings(c); }
        });

        final Panel.Row connectRow = p.addRow("ADB link", "", null);
        final Panel.Row fpRow = p.addRow("Key fingerprint", "", null);
        final Panel.Row applyRow = p.addRow("Finish setup automatically", "", null);
        final Panel.Row bootRow = p.addRow("Re-apply after reboot",
                Prefs.reapplyOnBoot(c) ? "on" : "off", null);
        final Panel.Row forgetRow = p.addRow("Forget our key", "", null);
        final Panel.Row tunnelRow = p.addRow("On-device tunnel (advanced)", "", null);
        final Panel.Row logRow = p.addRow("Last result", "", null);

        // ------------------------------------------------------------------ behaviour

        final Runnable refresh = new Runnable() {
            public void run() {
                boolean svc = serviceEnabled(c);
                svcRow.setValue(svc ? "on" : "off");
                svcRow.setValueColor(svc ? OK : BAD);

                String type = HomeRedirectService.overlayTypeLabel();
                if (!svc) {
                    ovlRow.setValue("waiting");
                    ovlRow.setValueColor(WARN);
                } else if (type == null) {
                    ovlRow.setValue("ready");
                    ovlRow.setValueColor(OK);
                } else {
                    ovlRow.setValue(type);
                    ovlRow.setValueColor(HomeRedirectService.overlayIsFree() ? OK : WARN);
                }

                int seen = UsageTracker.size(c);
                useRow.setValue(seen == 0 ? "learning" : seen + " apps tracked");
                useRow.setValueColor(OK);

                int st = AdbBridge.state();
                boolean on = Prefs.adbEnabled(c);
                String label = on ? AdbBridge.describe(st) : "off";
                adbRow.setValue(label);
                adbRow.setValueColor(st == AdbBridge.CONNECTED ? OK
                        : (st == AdbBridge.BLOCKED || st == AdbBridge.DENIED) ? BAD : WARN);

                connectRow.setLabel(connectLabel(st, on));
                connectRow.setValue(label);
                connectRow.setValueColor(st == AdbBridge.CONNECTED ? OK : WARN);

                String fp = AdbBridge.fingerprint();
                fpRow.setValue(fp == null ? "not generated yet" : shortFingerprint(fp));

                // Also actionable when the tunnel is enabled but down: it re-opens on demand.
                boolean usable = (st == AdbBridge.CONNECTED) || Prefs.tunnelEnabled(c);
                applyRow.setValue(usable ? (svc ? "re-run" : "run it") : "needs the link");
                applyRow.setValueColor(usable ? OK : WARN);
                applyRow.cell.setEnabled(usable);

                bootRow.setValue(Prefs.reapplyOnBoot(c) ? "on" : "off");
                forgetRow.setValue(AdbCrypto.hasKey(c) ? "delete" : "none stored");

                // "ready" = enabled but down, "active" = up right now.
                boolean pref = Prefs.tunnelEnabled(c);
                boolean tun = TunnelVpnService.isActive();
                tunnelRow.setValue(!pref ? "off" : (tun ? "active" : "ready"));
                tunnelRow.setValueColor(!pref ? WARN : OK);
            }
        };

        connectRow.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (AdbBridge.isConnected()) {
                    AdbBridge.disconnect();
                } else {
                    Prefs.setAdbEnabled(c, true);
                    if (Prefs.tunnelEnabled(c) && !TunnelVpnService.isActive()) {
                        // Tunnel preferred but down: start it; it connects once up.
                        logRow.setValue("starting tunnel...");
                        startTunnel(c);
                    } else {
                        // Direct, or through an already-active tunnel (override is set).
                        logRow.setValue("connecting...");
                        AdbBridge.connect();
                    }
                }
                refresh.run();
            }
        });

        tunnelRow.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (TunnelVpnService.isActive()) {
                    Prefs.setTunnelEnabled(c, false);
                    TunnelVpnService.stop(c);
                    logRow.setValue("tunnel off");
                    logRow.setValueColor(WARN);
                } else {
                    logRow.setValue("approve the VPN prompt on screen");
                    logRow.setValueColor(WARN);
                    startTunnel(c);
                }
                refresh.run();
            }
        });

        applyRow.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // run() brings the tunnel up on demand if it is enabled but down.
                if (!AdbBridge.isConnected() && !Prefs.tunnelEnabled(c)) return;
                logRow.setValue("working...");
                String current = null;
                try {
                    current = Settings.Secure.getString(c.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                } catch (Throwable ignored) { }
                AdbBridge.run(AdbBridge.selfSetupCommands(c, current), new AdbBridge.Callback() {
                    public void onDone(boolean ok, String out) {
                        logRow.setValue(ok ? "done" : "partly failed");
                        logRow.setValueColor(ok ? OK : WARN);
                        refresh.run();
                    }
                });
            }
        });

        bootRow.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setReapplyOnBoot(c, !Prefs.reapplyOnBoot(c));
                refresh.run();
            }
        });

        forgetRow.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AdbBridge.forgetKey();
                Prefs.setAdbEnabled(c, false);
                logRow.setValue("key deleted - revoke it on the TV under "
                        + "My Fire TV, Developer options");
                refresh.run();
            }
        });

        p.addNote("Deleting our key here stops this app using the link. The TV keeps its "
                + "own copy until you use \"Revoke ADB authorisations\" under My Fire TV, "
                + "Developer options.");

        p.addNote("On-device tunnel (advanced): Fire OS refuses ADB connections that start "
                + "on the TV, so the app reaches its own debugging service the way a "
                + "computer would - through a local VPN that presents an outside-looking "
                + "address. It routes only its own private subnet, stays entirely on this "
                + "TV, and makes no internet connection. Turning it on shows the system's "
                + "VPN prompt once and a key icon in the status bar; the first connect asks "
                + "you to approve the key on the TV.\n\n"
                + "It only comes up while the app is actively configuring the TV and shuts "
                + "off again within seconds of finishing (\"ready\" means enabled but down, "
                + "\"active\" means up right now). While it is up, the TV's own ADB is "
                + "reachable to apps on this device again - they would still each need you "
                + "to approve their key on screen, so approve ONLY prompts you expected. "
                + "Needs \"ADB debugging\" left on under My Fire TV; turn that off when you "
                + "are done for the tightest setup. Reversible any time, and Amazon may "
                + "re-block it in a future update.");

        p.addRow("Commands for a computer", "", new Runnable() {
            public void run() { host.showPanel(CommandsPanel.build(c, host)); }
        });

        p.addRow("Close", "", new Runnable() {
            public void run() { host.closePanel(); }
        });

        // Refresh while on screen: the ADB handshake and user approval finish asynchronously.
        final AdbBridge.Listener listener = new AdbBridge.Listener() {
            public void onAdbState(int state, String detail) {
                refresh.run();
                if (state == AdbBridge.AWAITING_APPROVAL) {
                    logRow.setValue("approve it on screen");
                    logRow.setValueColor(WARN);
                } else if (state == AdbBridge.NO_DAEMON) {
                    logRow.setValue("turn on ADB debugging first");
                    logRow.setValueColor(BAD);
                } else if (state == AdbBridge.BLOCKED) {
                    logRow.setValue("this TV will not allow it");
                    logRow.setValueColor(BAD);
                } else if (state == AdbBridge.SELF_REFUSED) {
                    logRow.setValue(TunnelVpnService.isActive()
                            ? "tunnel could not reach adbd"
                            : "blocked - turn on the on-device tunnel below");
                    logRow.setValueColor(WARN);
                } else if (state == AdbBridge.DENIED) {
                    logRow.setValue("the TV did not accept our key");
                    logRow.setValueColor(BAD);
                } else if (state == AdbBridge.CONNECTED) {
                    logRow.setValue("connected");
                    logRow.setValueColor(OK);
                }
            }
        };
        p.root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { AdbBridge.addListener(listener); }
            public void onViewDetachedFromWindow(View v) { AdbBridge.removeListener(listener); }
        });

        Prefs.setSetupSeen(c, true);
        refresh.run();
        return p.root;
    }

    // ---------------------------------------------------------------- helpers

    private static String connectLabel(int state, boolean enabled) {
        if (state == AdbBridge.CONNECTED) return "Disconnect the ADB link";
        if (state == AdbBridge.AWAITING_APPROVAL) return "Waiting for you to approve it";
        if (state == AdbBridge.CONNECTING) return "Connecting...";
        if (state == AdbBridge.SELF_REFUSED) return "Refused - use the on-device tunnel below";
        if (state == AdbBridge.NO_DAEMON) return "Turn on ADB debugging, then try again";
        return enabled ? "Try connecting again" : "Turn on the ADB link";
    }

    /**
     * Bring the tunnel up via VpnSetupActivity (one-time VPN consent), which starts
     * TunnelVpnService; that sets the ADB override and connects through the tunnel.
     */
    static void startTunnel(Context c) {
        Prefs.setAdbEnabled(c, true);
        Prefs.setTunnelEnabled(c, true);
        Intent i = new Intent(c, VpnSetupActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Leaving for the VPN consent screen must not bounce the user back to the launcher.
        HomeRedirectService.suspend(120000L);
        HomeRedirectService.hideOverlay();
        try { c.startActivity(i); } catch (Throwable ignored) { }
    }

    /** The dialog shows all 16 groups; the first four are enough to compare at a glance. */
    private static String shortFingerprint(String fp) {
        String[] parts = fp.split(":");
        if (parts.length <= 4) return fp;
        return parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3] + ":...";
    }

    static boolean serviceEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName());
        } catch (Throwable t) { return false; }
    }

    /** The Fire TV settings root. Accessibility is a few rows down from here. */
    static void openSettings(Context c) {
        if (start(c, new Intent(Settings.ACTION_SETTINGS))) return;
        openDeviceSettings(c);
    }

    /**
     * "My Fire TV" (Developer options, ADB debugging). Unlike the accessibility screen it
     * needs no permission, so this deep link works.
     */
    private static void openDeviceSettings(Context c) {
        if (start(c, new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"))) return;
        Intent explicit = new Intent();
        explicit.setClassName("com.amazon.tv.settings.v2",
                "com.amazon.tv.settings.v2.tv.device.DeviceActivity");
        if (start(c, explicit)) return;
        start(c, new Intent(Settings.ACTION_SETTINGS));
    }

    private static boolean start(Context c, Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Leaving for a settings screen must not bounce the user straight back here.
            HomeRedirectService.suspend(120000L);
            HomeRedirectService.hideOverlay();
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
