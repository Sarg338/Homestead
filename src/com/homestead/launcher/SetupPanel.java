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
 * The Home button needs the accessibility service, which an app cannot enable for itself,
 * and Fire OS's Accessibility menu does not list third-party services. So it is enabled
 * over ADB: the app's own link (see AdbConnection) once the user approves it on screen, or
 * a computer. WRITE_SECURE_SETTINGS is optional (TV Health).
 */
public final class SetupPanel {

    private static final int OK = 0xFFA7D28F;
    private static final int WARN = 0xFFE8C468;
    private static final int BAD = 0xFFE89A8A;

    /** Shown with "Turn off the Home button" here and in Settings; Fire OS 6 has its own. */
    static String turnOffHelp(Context c) {
        return c.getString(HomeWatchService.available(c)
                ? R.string.setup_turn_off_help_fo6 : R.string.setup_turn_off_help);
    }

    private SetupPanel() { }

    public static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, c.getString(R.string.set_setup), c.getString(R.string.setup_sub));

        AdbBridge.init(c);

        // ------------------------------------------------------------------ status
        p.addHeader(c.getString(R.string.setup_status));

        final Panel.Row svcRow = p.addRow(c.getString(R.string.setup_svc), "", null);
        svcRow.setHelp(c.getString(HomeWatchService.available(c)
                ? R.string.setup_svc_help_fo6 : R.string.setup_svc_help));
        final Panel.Row ovlRow = p.addRow(c.getString(R.string.setup_ovl), "", null);
        final Panel.Row useRow = p.addRow(c.getString(R.string.setup_use), "", null);
        final Panel.Row adbRow = p.addRow(c.getString(R.string.setup_adb), "", null);

        // ------------------------------------------------------------------ configure
        p.addHeader(c.getString(R.string.setup_configure));

        p.addSectionHelp(c.getString(R.string.setup_configure_help));

        p.addRow(c.getString(R.string.setup_open_mft), "", new Runnable() {
            public void run() { openDeviceSettings(c); }
        }).opens();

        final Panel.Row connectRow = p.addRow(c.getString(R.string.setup_link), "", null);
        final Panel.Row fpRow = p.addRow(c.getString(R.string.setup_fp), "", null);
        final Panel.Row applyRow = p.addRow(c.getString(R.string.setup_apply), "", null);
        final Panel.Row bootRow = p.addRow(c.getString(R.string.setup_boot),
                Prefs.reapplyOnBoot(c) ? "on" : "off", null).asSwitch();
        final Panel.Row forgetRow = p.addRow(c.getString(R.string.setup_forget), "", null);
        forgetRow.setHelp(c.getString(R.string.setup_forget_help));
        final Panel.Row tunnelRow = p.addRow(c.getString(R.string.setup_tunnel), "", null);
        tunnelRow.setHelp(c.getString(R.string.setup_tunnel_help));
        final Panel.Row logRow = p.addStatus(c.getString(R.string.setup_last));

        // ------------------------------------------------------------------ home button
        p.addHeader(c.getString(R.string.setup_home_button));

        // Set once refresh exists, just below.
        final Runnable[] refreshRef = new Runnable[1];
        final Runnable offPaint = addTurnOffRow(c, p, new Runnable() {
            public void run() { if (refreshRef[0] != null) refreshRef[0].run(); }
        });
        p.addNote(turnOffHelp(c));

        // ------------------------------------------------------------------ behaviour

        final Runnable refresh = new Runnable() {
            public void run() {
                boolean svc = serviceEnabled(c);
                if (HomeWatchService.available(c)) {
                    // Fire OS 6: no redirect, the log watcher brings Homestead up instead.
                    boolean watch = HomeWatchService.shouldRun(c);
                    svcRow.setValue(c.getString(watch ? R.string.setup_on_fo6 : R.string.setup_off_select));
                    svcRow.setValueColor(watch ? OK : WARN);
                } else {
                    svcRow.setValue(c.getString(svc ? R.string.v_on : R.string.v_off));
                    svcRow.setValueColor(svc ? OK : BAD);
                }

                String type = HomeRedirectService.overlayTypeLabel();
                if (!svc) {
                    ovlRow.setValue(c.getString(R.string.setup_waiting));
                    ovlRow.setValueColor(WARN);
                } else if (type == null) {
                    ovlRow.setValue(c.getString(R.string.setup_ready));
                    ovlRow.setValueColor(OK);
                } else {
                    ovlRow.setValue(type);
                    ovlRow.setValueColor(HomeRedirectService.overlayIsFree() ? OK : WARN);
                }

                int seen = UsageTracker.size(c);
                useRow.setValue(seen == 0 ? c.getString(R.string.setup_learning) : c.getString(R.string.setup_tracked, seen));
                useRow.setValueColor(OK);

                int st = AdbBridge.state();
                boolean on = Prefs.adbEnabled(c);
                boolean ready = AdbBridge.isReady(c);
                String label = AdbBridge.statusLabel(c);
                adbRow.setValue(label);
                adbRow.setValueColor(ready ? OK
                        : (st == AdbBridge.BLOCKED || st == AdbBridge.DENIED) ? BAD : WARN);

                connectRow.setLabel(connectLabel(c, st, on));
                connectRow.setValue(label);
                connectRow.setValueColor(ready ? OK : WARN);

                String fp = AdbBridge.fingerprint();
                // The fingerprint is read when connecting; a saved key may not be loaded yet.
                fpRow.setValue(fp != null ? shortFingerprint(fp)
                        : c.getString(AdbCrypto.hasKey(c) ? R.string.setup_fp_saved : R.string.setup_fp_none));
                offPaint.run();

                // Also actionable when the tunnel is enabled but down: it re-opens on demand.
                boolean usable = (st == AdbBridge.CONNECTED) || Prefs.tunnelEnabled(c);
                applyRow.setValue(c.getString(usable ? (svc ? R.string.setup_rerun : R.string.setup_run) : R.string.setup_needs_link));
                applyRow.setValueColor(usable ? OK : WARN);
                applyRow.cell.setEnabled(usable);

                bootRow.setValue(Prefs.reapplyOnBoot(c) ? "on" : "off");
                forgetRow.setValue(c.getString(AdbCrypto.hasKey(c) ? R.string.setup_delete : R.string.setup_none_stored));

                // "ready" = enabled but down, "active" = up right now.
                boolean pref = Prefs.tunnelEnabled(c);
                boolean tun = TunnelVpnService.isActive();
                tunnelRow.setValue(c.getString(!pref ? R.string.v_off : (tun ? R.string.setup_active : R.string.setup_ready)));
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
                        logRow.setValue(c.getString(R.string.setup_log_starting));
                        startTunnel(c);
                    } else {
                        // Direct, or through an already-active tunnel (override is set).
                        logRow.setValue(c.getString(R.string.setup_log_connecting));
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
                    logRow.setValue(c.getString(R.string.setup_log_tunnel_off));
                    logRow.setValueColor(WARN);
                } else {
                    logRow.setValue(c.getString(R.string.setup_log_vpn));
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
                logRow.setValue(c.getString(R.string.setup_log_working));
                String current = null;
                try {
                    current = Settings.Secure.getString(c.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                } catch (Throwable ignored) { }
                AdbBridge.run(AdbBridge.selfSetupCommands(c, current), new AdbBridge.Callback() {
                    public void onDone(boolean ok, String out) {
                        HomeRedirectService.startHomeKeyWatch();
                        logRow.setValue(c.getString(ok ? R.string.setup_log_done : R.string.setup_log_partly));
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
                logRow.setValue(c.getString(R.string.setup_log_key_deleted));
                refresh.run();
            }
        });

        p.addHeader(c.getString(R.string.setup_more));
        p.addRow(c.getString(R.string.setup_commands), "", new Runnable() {
            public void run() { host.showPanel(CommandsPanel.build(c, host)); }
        }).opens().setHelp(c.getString(R.string.setup_commands_help));

        p.addRow(c.getString(R.string.set_close), "", new Runnable() {
            public void run() { host.closePanel(); }
        });

        // Refresh while on screen: the ADB handshake and user approval finish asynchronously.
        final AdbBridge.Listener listener = new AdbBridge.Listener() {
            public void onAdbState(int state, String detail) {
                refresh.run();
                if (state == AdbBridge.AWAITING_APPROVAL) {
                    logRow.setValue(c.getString(R.string.setup_log_approve));
                    logRow.setValueColor(WARN);
                } else if (state == AdbBridge.NO_DAEMON) {
                    logRow.setValue(c.getString(R.string.setup_log_no_daemon));
                    logRow.setValueColor(BAD);
                } else if (state == AdbBridge.BLOCKED) {
                    logRow.setValue(c.getString(R.string.setup_log_blocked));
                    logRow.setValueColor(BAD);
                } else if (state == AdbBridge.SELF_REFUSED) {
                    logRow.setValue(TunnelVpnService.isActive()
                            ? c.getString(R.string.setup_log_tunnel_fail)
                            : c.getString(R.string.setup_log_self_refused));
                    logRow.setValueColor(WARN);
                } else if (state == AdbBridge.DENIED) {
                    logRow.setValue(c.getString(R.string.setup_log_denied));
                    logRow.setValueColor(BAD);
                } else if (state == AdbBridge.CONNECTED) {
                    logRow.setValue(c.getString(R.string.setup_log_connected));
                    logRow.setValueColor(OK);
                }
            }
        };
        p.root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { AdbBridge.addListener(listener); }
            public void onViewDetachedFromWindow(View v) { AdbBridge.removeListener(listener); }
        });

        // Fire OS 6: Home turned off here comes back on here, without a computer.
        svcRow.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (HomeWatchService.available(c) && Prefs.homeOffByUser(c)) {
                    HomeWatchService.turnOn(c);
                    refresh.run();
                }
            }
        });

        Prefs.setSetupSeen(c, true);
        refreshRef[0] = refresh;
        refresh.run();
        return p.root;
    }

    // ---------------------------------------------------------------- helpers

    private static String connectLabel(Context c, int state, boolean enabled) {
        if (state == AdbBridge.CONNECTED) return c.getString(R.string.setup_c_disconnect);
        if (state == AdbBridge.AWAITING_APPROVAL) return c.getString(R.string.setup_c_waiting);
        if (state == AdbBridge.CONNECTING) return c.getString(R.string.setup_c_connecting);
        if (state == AdbBridge.SELF_REFUSED) return c.getString(R.string.setup_c_refused);
        if (state == AdbBridge.NO_DAEMON) return c.getString(R.string.setup_c_no_daemon);
        return c.getString(enabled ? R.string.setup_c_retry : R.string.setup_c_on);
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
    static String shortFingerprint(String fp) {
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

    /** The Home button reaches Homestead: the redirect, or Fire OS 6's log watcher. */
    static boolean homeWorks(Context c) {
        return serviceEnabled(c) || HomeWatchService.shouldRun(c);
    }

    /**
     * "Turn off the Home button". Takes two presses within five seconds, since switching it
     * back on needs ADB. after runs once it is off. @return a Runnable that repaints the row.
     */
    static Runnable addTurnOffRow(final Context c, Panel p, final Runnable after) {
        final Panel.Row row = p.addRow(c.getString(R.string.setup_turn_off), "", null);
        final boolean[] armed = { false };
        final Runnable paint = new Runnable() {
            public void run() {
                row.setValueColor(WARN);
                if (HomeWatchService.available(c)) {
                    // Fire OS 6: the log watcher, which this app can turn off and on itself.
                    boolean off = Prefs.homeOffByUser(c);
                    row.setValue(off ? c.getString(R.string.v_off)
                            : armed[0] ? c.getString(R.string.setup_press_again) : "");
                    row.cell.setEnabled(!off);
                } else if (!serviceEnabled(c)) {
                    row.setValue(c.getString(R.string.v_off));
                    row.cell.setEnabled(false);
                } else if (!HomeRedirectService.canTurnOff()) {
                    row.setValue(c.getString(R.string.setup_needs_computer));
                    row.cell.setEnabled(false);
                } else {
                    row.setValue(armed[0] ? c.getString(R.string.setup_press_again) : "");
                    row.cell.setEnabled(true);
                }
            }
        };
        final Runnable disarm = new Runnable() {
            public void run() { armed[0] = false; paint.run(); }
        };
        paint.run();
        row.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!armed[0]) {
                    armed[0] = true;
                    paint.run();
                    row.cell.postDelayed(disarm, 5000L);
                    return;
                }
                row.cell.removeCallbacks(disarm);
                armed[0] = false;
                if (HomeWatchService.available(c)) {
                    HomeWatchService.turnOff(c);
                } else if (!HomeRedirectService.turnOff(c)) {
                    row.setValue(c.getString(R.string.setup_could_not));
                    return;
                }
                // The setting is written asynchronously, so show the result directly.
                row.setValue(c.getString(R.string.v_off));
                row.cell.setEnabled(false);
                // Turning Home off means going back to Amazon's home screen, so its launcher
                // comes back on if Debloat switched it off.
                String launcher = AppModel.AMAZON_LAUNCHER;
                if (Debloat.stateOf(c, launcher) == Debloat.DISABLED
                        && Prefs.disabledByUs(c).contains(launcher) && AdbBridge.canRun(c)) {
                    AdbBridge.runRetry(new String[] { AdbBridge.enableCommand(launcher) },
                            new AdbBridge.Callback() {
                                public void onDone(boolean ok, String out) {
                                    boolean on = Debloat.stateOf(c, AppModel.AMAZON_LAUNCHER)
                                            == Debloat.ENABLED;
                                    if (on) Prefs.noteDisabled(c, AppModel.AMAZON_LAUNCHER, false);
                                    row.setValue(c.getString(on ? R.string.setup_off_amazon_back
                                            : R.string.setup_off_need_launcher));
                                }
                            });
                }
                if (after != null) after.run();
            }
        });
        return paint;
    }

    /** "My Fire TV" (Developer options, ADB debugging). */
    static void openDeviceSettings(Context c) {
        if (start(c, new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"))) return;
        Intent explicit = new Intent();
        explicit.setClassName("com.amazon.tv.settings.v2",
                "com.amazon.tv.settings.v2.tv.device.DeviceActivity");
        if (start(c, explicit)) return;
        start(c, new Intent(Settings.ACTION_SETTINGS));
    }

    static boolean start(Context c, Intent i) {
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
