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

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * TV Health: the TV's current state and what can be done about it.
 *
 * Settings rows need WRITE_SECURE_SETTINGS, a development permission grantable with
 * `pm grant`; once held they are applied in-process. Disabling packages needs
 * CHANGE_COMPONENT_ENABLED_STATE, which a sideloaded app can never hold, so it always goes
 * through the ADB shell (see DebloatPanel). When the ADB link is connected, settings are
 * written through the shell and WRITE_SECURE_SETTINGS is granted at the same time.
 */
public final class HealthPanel {

    private static final String PERM_WSS = "android.permission.WRITE_SECURE_SETTINGS";

    private static final int OK = 0xFFA7D28F;
    private static final int WARN = 0xFFE8C468;
    private static final int BAD = 0xFFE89A8A;

    private HealthPanel() { }

    public static boolean canWriteSettings(Context c) {
        try {
            return c.getPackageManager().checkPermission(PERM_WSS, c.getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    /** True if the page can change anything at all, by either route. */
    private static boolean canApply(Context c) {
        return canWriteSettings(c) || AdbBridge.canRun(c);
    }

    public static View build(final Context c, final SettingsPanel.Host host) {
        AdbBridge.init(c);
        final boolean writable = canApply(c);
        final Panel p = new Panel(c, "TV Health",
                writable ? "Detected state - fixes can be applied here"
                         : "Detected state - read-only until Setup is finished");

        // ---------------------------------------------------------- launcher itself
        p.addHeader("Homestead");

        boolean svc = SetupPanel.serviceEnabled(c);
        Panel.Row r1 = p.addRow("Home-button redirect", svc ? "active" : "NOT ACTIVE", null);
        r1.setValueColor(svc ? OK : BAD);

        String ovlType = HomeRedirectService.overlayTypeLabel();
        Panel.Row r2 = p.addRow("Instant home screen",
                ovlType == null ? (svc ? "ready" : "waiting") : ovlType, null);
        r2.setValueColor(ovlType == null ? (svc ? OK : WARN)
                : (HomeRedirectService.overlayIsFree() ? OK : WARN));

        Panel.Row r3 = p.addRow("App ordering",
                UsageTracker.size(c) + " apps tracked", null);
        r3.setValueColor(OK);

        Panel.Row r4 = p.addRow("Configure this TV",
                Prefs.adbEnabled(c) ? AdbBridge.describe(AdbBridge.state()) : "off", null);
        r4.setValueColor(AdbBridge.isConnected() ? OK : WARN);

        p.addRow("Open Setup", "", new Runnable() {
            public void run() { host.showPanel(SetupPanel.build(c, host)); }
        });

        if (!svc) {
            p.addNote("Without the accessibility service the Home button goes to Amazon's "
                    + "launcher. Setup explains how to switch it on, or can do it for you.");
        }

        // ---------------------------------------------------------- settings-tier fixes
        p.addHeader("Speed");

        final List<Panel.Row> settingRows = new ArrayList<Panel.Row>();
        final List<Check> checks = new ArrayList<Check>();

        checks.add(Check.global("window_animation_scale", "0", "Window animation"));
        checks.add(Check.global("transition_animation_scale", "0", "Transition animation"));
        checks.add(Check.global("animator_duration_scale", "0.5", "Animator duration"));
        checks.add(Check.global("com.amazon.tv.settings.featured_item_video_enabled", "0",
                "Home-screen video autoplay"));
        checks.add(Check.secure("amz_limit_ad_tracking", "1", "Limit ad tracking"));
        checks.add(Check.secure("USAGE_METRICS_UPLOAD_ENABLED", "0", "Usage metrics upload"));
        // The switches under Settings > Preferences > Privacy Settings, as Fire OS stores them.
        checks.add(Check.secure("settingsApp:deviceUsageData:value", "0", "Device usage data"));
        checks.add(Check.secure("settingsApp:appUsageData:value", "0", "Collect app usage data"));
        checks.add(Check.secure("advertisingIdApp:interestBasedAds:value", "0", "Interest-based ads"));
        checks.add(Check.secure("usage_metrics_marketing_enabled", "0", "Marketing metrics"));
        checks.add(Check.secure("pact_usage_collection_enabled", "0", "PACT usage collection"));
        checks.add(Check.secure("limit_ad_tracking", "1", "Limit ad tracking (platform)"));
        checks.add(Check.secure("non_amz_limit_ad_tracking", "1", "Limit ad tracking (3rd party)"));
        checks.add(Check.secure("screensaver_components", "com.android.dreams.basic/.Colors",
                "Screensaver"));

        for (int i = 0; i < checks.size(); i++) {
            if (i == 3) p.addHeader("Ads & privacy");
            final Check ck = checks.get(i);
            final Panel.Row row = p.addRow(ck.label, "", null);
            settingRows.add(row);
            paint(c, ck, row);
            row.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!canApply(c)) return;
                    apply(c, new Check[]{ck}, new Runnable() {
                        public void run() { paint(c, ck, row); }
                    });
                    row.setValue("working...");
                }
            });
        }

        final Panel.Row applyAll = p.addRow("Apply all of the above", "", null);
        applyAll.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!canApply(c)) return;
                applyAll.setValue("working...");
                final Check[] all = checks.toArray(new Check[checks.size()]);
                apply(c, all, new Runnable() {
                    public void run() {
                        int good = 0;
                        for (int i = 0; i < all.length; i++) {
                            paint(c, all[i], settingRows.get(i));
                            if (all[i].isGood(all[i].read(c))) good++;
                        }
                        applyAll.setValue(good + " of " + all.length + " set");
                        applyAll.setValueColor(good == all.length ? OK : WARN);
                    }
                });
            }
        });

        if (!writable) {
            applyAll.cell.setEnabled(false);
            applyAll.setValue("needs Setup");
            p.addNote("These rows are read-only right now. One permission makes all of them "
                    + "changeable from the remote, permanently - see \"Commands for a "
                    + "computer\" below.");
        } else if (!canWriteSettings(c)) {
            p.addNote("Applied through the ADB link. Setup also grants this app "
                    + "WRITE_SECURE_SETTINGS, after which these rows keep working with the "
                    + "link switched off.");
        }

        // ---------------------------------------------------------- packages
        p.addHeader("Ad & telemetry packages");

        int stillOn = 0, off = 0, absent = 0;
        for (int i = 0; i < Debloat.CATALOGUE.length; i++) {
            int st = Debloat.stateOf(c, Debloat.CATALOGUE[i].pkg);
            if (st == Debloat.ENABLED) stillOn++;
            else if (st == Debloat.DISABLED) off++;
            else absent++;
        }
        Panel.Row pk = p.addRow("Still running", stillOn + " of "
                + (Debloat.CATALOGUE.length - absent), null);
        pk.setValueColor(stillOn == 0 ? OK : WARN);
        p.addRow("Already switched off", String.valueOf(off), null);

        p.addRow("Open Debloat", "", new Runnable() {
            public void run() { host.showPanel(DebloatPanel.build(c, host)); }
        });

        p.addNote("A few telemetry packages are protected by Fire OS and cannot be "
                + "switched off by any app, even with the privacy switches above off.");

        p.addRow("Commands for a computer", "", new Runnable() {
            public void run() { host.showPanel(CommandsPanel.build(c, host)); }
        });

        p.addNote(stillOn == 0
                ? "None of the tracked advertising or telemetry packages are running."
                : stillOn + " of the tracked packages are still running. Debloat can switch "
                        + "them off one at a time or all at once, and put them back the "
                        + "same way.");

        // ---------------------------------------------------------- resources
        p.addHeader("Resources");
        long[] mem = memory(c);
        p.addRow("Free memory", mb(mem[0]) + " of " + mb(mem[1]), null);
        long[] disk = storage();
        p.addRow("Free storage", mb(disk[0]) + " of " + mb(disk[1]), null);

        int disabledTotal = -1;
        try {
            disabledTotal = c.getPackageManager()
                    .getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS).size()
                    - c.getPackageManager().getInstalledApplications(0).size();
        } catch (Throwable t) { disabledTotal = -1; }
        p.addRow("Packages disabled on this TV",
                disabledTotal >= 0 ? String.valueOf(disabledTotal) : "?", null);
        p.addRow("Tiles shown", AppModel.visibleItems(c).size()
                + " of " + AppModel.allItems().size(), null);

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        p.addNote("Press Back to close.");
        return p.root;
    }

    // ---------------------------------------------------------------- applying

    /**
     * Writes the settings in-process if WRITE_SECURE_SETTINGS is held, otherwise through
     * the ADB link (which also grants it). Calls back on the main thread.
     */
    private static void apply(final Context c, final Check[] checks, final Runnable after) {
        if (canWriteSettings(c)) {
            for (int i = 0; i < checks.length; i++) checks[i].applyDirect(c);
            after.run();
            return;
        }
        if (!AdbBridge.canRun(c)) { after.run(); return; }
        String[] cmds = new String[checks.length + 1];
        for (int i = 0; i < checks.length; i++) cmds[i] = checks[i].command();
        cmds[checks.length] = "pm grant " + c.getPackageName() + " " + PERM_WSS;
        AdbBridge.run(cmds, new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) { after.run(); }
        });
    }

    private static void paint(Context c, Check ck, Panel.Row row) {
        String cur = ck.read(c);
        row.setValue(describe(ck, cur));
        row.setValueColor(ck.isGood(cur) ? OK : WARN);
    }

    private static String describe(Check ck, String cur) {
        if (cur == null) return "unset";
        if (ck.isGood(cur)) return ck.shortWant();
        return ck.abbreviate(cur) + " -> " + ck.shortWant();
    }

    // ---------------------------------------------------------------- detection

    private static long[] memory(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return new long[]{mi.availMem, mi.totalMem};
        } catch (Throwable t) { return new long[]{0, 0}; }
    }

    private static long[] storage() {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long b = fs.getBlockSizeLong();
            return new long[]{fs.getAvailableBlocksLong() * b, fs.getBlockCountLong() * b};
        } catch (Throwable t) { return new long[]{0, 0}; }
    }

    private static String mb(long bytes) {
        if (bytes <= 0) return "?";
        long m = bytes / (1024L * 1024L);
        if (m >= 1024) return String.format(java.util.Locale.US, "%.1f GB", m / 1024f);
        return m + " MB";
    }

    // ---------------------------------------------------------------- a setting to check

    private static class Check {
        final boolean global;
        final String key, want, label;

        private Check(boolean g, String k, String w, String l) {
            global = g; key = k; want = w; label = l;
        }
        static Check global(String k, String w, String l) { return new Check(true, k, w, l); }
        static Check secure(String k, String w, String l) { return new Check(false, k, w, l); }

        String read(Context c) {
            try {
                return global
                        ? Settings.Global.getString(c.getContentResolver(), key)
                        : Settings.Secure.getString(c.getContentResolver(), key);
            } catch (Throwable t) { return null; }
        }

        boolean isGood(String cur) {
            if (cur == null) return false;
            if (want.equals(cur)) return true;
            // Animation scales come back as "0.0" against a wanted "0".
            try { return Float.parseFloat(cur) == Float.parseFloat(want); }
            catch (Throwable t) { return false; }
        }

        void applyDirect(Context c) {
            try {
                if (global) Settings.Global.putString(c.getContentResolver(), key, want);
                else Settings.Secure.putString(c.getContentResolver(), key, want);
            } catch (Throwable t) { /* not granted; the ADB route handles it */ }
        }

        String command() {
            return "settings put " + (global ? "global " : "secure ") + key + " '" + want + "'";
        }

        /** A component name is far too wide for the right-hand column. */
        String shortWant() { return abbreviate(want); }

        String abbreviate(String v) {
            if (v == null) return "unset";
            int slash = v.indexOf('/');
            if (slash > 0) {
                String pkg = v.substring(0, slash);
                int dot = pkg.lastIndexOf('.');
                return (dot > 0 ? pkg.substring(dot + 1) : pkg);
            }
            return v.length() > 18 ? v.substring(0, 17) + "…" : v;
        }
    }
}
