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
import android.provider.Settings;
import android.view.View;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Fallback page: the adb commands to run from a computer for whatever is still missing on
 * this TV. Needed because Fire OS 7 refuses adb connections that originate on the device
 * itself (see AdbConnection.FAIL_SELF_REFUSED).
 */
public final class CommandsPanel {

    private static final int OK = 0xFFA7D28F;
    private static final int WARN = 0xFFE8C468;

    private CommandsPanel() { }

    public static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, "Commands for a computer",
                "Only the steps this TV will not let the app do itself");

        String pkg = c.getPackageName();
        String ip = localAddress();

        p.addHeader("Connecting");
        p.addRow("This TV", ip == null ? "no network" : ip, null);
        p.addNote("Switch on Settings, My Fire TV, Developer options, ADB debugging. Then "
                + "from a computer with Android platform-tools installed:\n\n"
                + "adb connect " + (ip == null ? "<this TV>" : ip) + "\n\n"
                + "The TV will ask you to allow the connection.");

        // ------------------------------------------------------------------ what is missing
        boolean svc = SetupPanel.serviceEnabled(c);
        boolean wss = HealthPanel.canWriteSettings(c);
        boolean logs = HomeKeyWatcher.permitted(c);

        p.addHeader("Still missing");

        Panel.Row a = p.addRow("Home-button redirect", svc ? "done" : "needed", null);
        a.setValueColor(svc ? OK : WARN);
        if (!svc) {
            p.addNote("Fire OS has no menu for this, so it is switched on over ADB. "
                    + "\"Finish setup automatically\" in Setup does it from the TV, or "
                    + "run:\n\n"
                    + "adb shell settings put secure enabled_accessibility_services "
                    + pkg + "/" + pkg + ".HomeRedirectService\n"
                    + "adb shell settings put secure accessibility_enabled 1");
        }

        Panel.Row w = p.addRow("Settings-tier tuning", wss ? "done" : "needed", null);
        w.setValueColor(wss ? OK : WARN);
        if (!wss) {
            p.addNote("One command makes every row on the TV Health page changeable from "
                    + "the remote, permanently:\n\n"
                    + "adb shell pm grant " + pkg
                    + " android.permission.WRITE_SECURE_SETTINGS");
        }

        Panel.Row h = p.addRow("Catch Home presses early", logs ? "done" : "needed", null);
        h.setValueColor(logs ? OK : WARN);
        if (!logs) {
            p.addNote("Without this, Amazon's home screen shows for a moment on each Home press, "
                    + "and Home does nothing while Amazon's home screen is already showing. "
                    + "Homestead uses it only to spot the Home press in the system log:\n\n"
                    + "adb shell pm grant " + pkg + " android.permission.READ_LOGS");
        }

        // ------------------------------------------------------------------ packages
        StringBuilder off = new StringBuilder();
        int count = 0;
        for (int i = 0; i < Debloat.CATALOGUE.length; i++) {
            Debloat.Entry e = Debloat.CATALOGUE[i];
            if (e.caution) continue;                       // opt-in, not a blanket suggestion
            if (Debloat.isProtected(c, e.pkg)) continue;
            if (Debloat.stateOf(c, e.pkg) != Debloat.ENABLED) continue;
            off.append("adb shell pm disable-user --user 0 ").append(e.pkg).append('\n');
            count++;
        }

        p.addHeader("Advertising & telemetry");
        Panel.Row pkgs = p.addRow("Still running", count == 0 ? "none" : String.valueOf(count), null);
        pkgs.setValueColor(count == 0 ? OK : WARN);
        if (count > 0) {
            p.addNote(off.toString()
                    + "\nEvery one of these is undone with:\n"
                    + "adb shell pm enable <package>\n\n"
                    + "Nothing is uninstalled and no data is deleted. The Debloat page "
                    + "lists what each package does. Alexa and everything else marked caution "
                    + "are left out here because they change how the TV behaves - choose those "
                    + "individually.");
        } else {
            p.addNote("None of the tracked advertising or telemetry packages are running.");
        }

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    /** The address a computer on the same network would use to reach this TV. */
    private static String localAddress() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni == null || ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress addr = as.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
