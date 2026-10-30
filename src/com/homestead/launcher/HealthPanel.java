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

    /** The screensaver in use: Android's basic colours, or unset for the TV's default. */
    private static final String SAVER_KEY = "screensaver_components";
    private static final String SAVER_BASIC = "com.android.dreams.basic/.Colors";
    /** Amazon's screensaver, the TV's default: its picture collection, or the user's own album. */
    private static final String SAVER_PKG = "com.amazon.ftv.screensaver";

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
        final Panel p = new Panel(c, c.getString(R.string.set_health),
                c.getString(writable ? R.string.health_sub_rw
                         : R.string.health_sub_ro));

        // ---------------------------------------------------------- launcher itself
        p.addHeader("Homestead");

        boolean svc = SetupPanel.serviceEnabled(c);
        boolean home = SetupPanel.homeWorks(c);
        Panel.Row r1 = p.addRow(c.getString(R.string.setup_svc), c.getString(home ? R.string.setup_active : R.string.health_not_active), null);
        r1.setValueColor(home ? OK : BAD);

        String ovlType = HomeRedirectService.overlayTypeLabel();
        Panel.Row r2 = p.addRow(c.getString(R.string.setup_ovl),
                ovlType == null ? c.getString(svc ? R.string.setup_ready : R.string.setup_waiting) : ovlType, null);
        r2.setValueColor(ovlType == null ? (svc ? OK : WARN)
                : (HomeRedirectService.overlayIsFree() ? OK : WARN));

        Panel.Row r3 = p.addRow(c.getString(R.string.setup_use),
                c.getString(R.string.setup_tracked, UsageTracker.size(c)), null);
        r3.setValueColor(OK);

        Panel.Row r4 = p.addRow(c.getString(R.string.setup_adb), AdbBridge.statusLabel(c), null);
        r4.setValueColor(AdbBridge.isReady(c) ? OK : WARN);

        p.addRow(c.getString(R.string.health_open_setup), "", new Runnable() {
            public void run() { host.showPanel(SetupPanel.build(c, host)); }
        }).opens();

        if (!svc) {
            p.addNote(c.getString(R.string.health_no_svc));
        }

        // ---------------------------------------------------------- settings-tier fixes
        p.addHeader(c.getString(R.string.health_speed));

        final List<Panel.Row> settingRows = new ArrayList<Panel.Row>();
        final List<Check> checks = new ArrayList<Check>();

        checks.add(Check.global("window_animation_scale", "0", R.string.health_c_window));
        checks.add(Check.global("transition_animation_scale", "0", R.string.health_c_transition));
        // Below 1, the Amazon home menu's Settings and Inputs do nothing on Fire OS 8.
        checks.add(Check.global("animator_duration_scale", "1", R.string.health_c_animator));
        checks.add(Check.global("com.amazon.tv.settings.featured_item_video_enabled", "0",
                R.string.health_c_autoplay));
        checks.add(Check.secure("amz_limit_ad_tracking", "1", R.string.health_c_limit_ads));
        checks.add(Check.secure("USAGE_METRICS_UPLOAD_ENABLED", "0", R.string.health_c_metrics));
        // The switches under Settings > Preferences > Privacy Settings, as Fire OS stores them.
        checks.add(Check.secure("settingsApp:deviceUsageData:value", "0", R.string.health_c_device_usage));
        checks.add(Check.secure("settingsApp:appUsageData:value", "0", R.string.health_c_app_usage));
        checks.add(Check.secure("advertisingIdApp:interestBasedAds:value", "0", R.string.health_c_interest_ads));
        checks.add(Check.secure("usage_metrics_marketing_enabled", "0", R.string.health_c_marketing));
        checks.add(Check.secure("pact_usage_collection_enabled", "0", R.string.health_c_pact));
        checks.add(Check.secure("limit_ad_tracking", "1", R.string.health_c_limit_platform));
        checks.add(Check.secure("non_amz_limit_ad_tracking", "1", R.string.health_c_limit_3p));

        for (int i = 0; i < checks.size(); i++) {
            if (i == 3) p.addHeader(c.getString(R.string.health_ads_privacy));
            final Check ck = checks.get(i);
            final Panel.Row row = p.addRow(c.getString(ck.label), "", null);
            settingRows.add(row);
            paint(c, ck, row);
            row.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!canApply(c)) return;
                    // Before apply: with the permission held it finishes at once and paints
                    // the result, which a later "working..." would cover for good.
                    row.setValue(c.getString(R.string.setup_log_working));
                    apply(c, new Check[]{ck}, new Runnable() {
                        public void run() { paint(c, ck, row); }
                    });
                }
            });
        }

        final Panel.Row applyAll = p.addRow(c.getString(R.string.health_apply_all), "", null);
        applyAll.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!canApply(c)) return;
                applyAll.setValue(c.getString(R.string.setup_log_working));
                final Check[] all = checks.toArray(new Check[checks.size()]);
                apply(c, all, new Runnable() {
                    public void run() {
                        int good = 0;
                        for (int i = 0; i < all.length; i++) {
                            paint(c, all[i], settingRows.get(i));
                            if (all[i].isGood(all[i].read(c))) good++;
                        }
                        applyAll.setValue(c.getString(R.string.health_n_set, good, all.length));
                        applyAll.setValueColor(good == all.length ? OK : WARN);
                    }
                });
            }
        });

        if (!writable) {
            applyAll.cell.setEnabled(false);
            applyAll.setValue(c.getString(R.string.health_needs_setup));
            p.addNote(c.getString(R.string.health_ro_note));
        } else if (!canWriteSettings(c)) {
            p.addNote(c.getString(R.string.health_adb_note));
        }

        // ---------------------------------------------------------- screensaver
        // A choice, not a fix: Amazon's screensaver shows sponsored pictures, but also the
        // user's own Amazon Photos album, so it is not part of "Apply all". Before 1.8.1 the
        // row set the basic colours and then offered no way back.
        p.addHeader(c.getString(R.string.health_saver_hdr));
        final Panel.Row saver = p.addRow(c.getString(R.string.health_saver_row), "", null);
        paintSaver(c, saver);
        saver.setHelp(c.getString(R.string.health_saver_help));
        saver.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!canApply(c)) return;
                // Unset puts the TV back on its default screensaver.
                final String next = SAVER_BASIC.equals(readSecure(c, SAVER_KEY)) ? null : SAVER_BASIC;
                saver.setValue(c.getString(R.string.setup_log_working));
                writeSecure(c, SAVER_KEY, next, new Runnable() {
                    public void run() { paintSaver(c, saver); }
                });
            }
        });

        // ---------------------------------------------------------- packages
        p.addHeader(c.getString(R.string.health_packages));

        // Every package still on falls into one reason, so the user can tell "done" from
        // "not done yet". Only the last one is work left.
        java.util.Set<String> refused = Prefs.refusedByFireOs(c);
        int off = 0, fireOs = 0, locked = 0, choice = 0, stillOn = 0;
        for (int i = 0; i < Debloat.CATALOGUE.length; i++) {
            Debloat.Entry e = Debloat.CATALOGUE[i];
            int st = Debloat.stateOf(c, e.pkg);
            if (st == Debloat.DISABLED) off++;
            else if (st != Debloat.ENABLED) continue;
            else if (refused.contains(e.pkg)) fireOs++;
            else if (Debloat.isProtected(c, e.pkg)) locked++;
            else if (e.caution) choice++;
            else stillOn++;
        }
        p.addRow(c.getString(R.string.health_p_off), String.valueOf(off), null).setValueColor(OK);
        Panel.Row left = p.addRow(c.getString(R.string.health_p_left), String.valueOf(stillOn), null);
        left.setValueColor(stillOn == 0 ? OK : WARN);
        p.addRow(c.getString(R.string.health_p_fireos), String.valueOf(fireOs), null);
        p.addRow(c.getString(R.string.health_p_locked), String.valueOf(locked), null);
        p.addRow(c.getString(R.string.health_p_choice), String.valueOf(choice), null);

        p.addRow(c.getString(R.string.health_open_debloat), "", new Runnable() {
            public void run() { host.showPanel(DebloatPanel.build(c, host)); }
        }).opens();

        p.addRow(c.getString(R.string.setup_commands), "", new Runnable() {
            public void run() { host.showPanel(CommandsPanel.build(c, host)); }
        }).opens();

        p.addSectionHelp((stillOn == 0
                ? c.getString(R.string.health_p_all_off)
                : c.getResources().getQuantityString(R.plurals.health_p_some_left, stillOn, stillOn))
                + "\n\n" + c.getString(R.string.health_p_protected_note));

        // ---------------------------------------------------------- resources
        p.addHeader(c.getString(R.string.health_resources));
        long[] mem = memory(c);
        p.addRow(c.getString(R.string.health_mem), c.getString(R.string.health_x_of_y, mb(mem[0]), mb(mem[1])), null);
        long[] disk = storage();
        p.addRow(c.getString(R.string.health_storage), c.getString(R.string.health_x_of_y, mb(disk[0]), mb(disk[1])), null);

        int disabledTotal = -1;
        try {
            // Disabled apps are listed either way; count the ones flagged as disabled.
            List<android.content.pm.ApplicationInfo> apps = c.getPackageManager()
                    .getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
            disabledTotal = 0;
            for (int i = 0; i < apps.size(); i++) if (!apps.get(i).enabled) disabledTotal++;
        } catch (Throwable t) { disabledTotal = -1; }
        p.addRow(c.getString(R.string.health_disabled_total),
                disabledTotal >= 0 ? String.valueOf(disabledTotal) : "?", null);
        p.addRow(c.getString(R.string.health_tiles), c.getString(R.string.health_x_of_y, String.valueOf(AppModel.visibleItems(c).size()),
                String.valueOf(AppModel.allItems().size())), null);

        p.addRow(c.getString(R.string.set_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- applying

    /**
     * Writes the settings in-process if WRITE_SECURE_SETTINGS is held, otherwise through
     * the ADB link (which also grants it). Calls back on the main thread.
     */
    private static void apply(final Context c, final Check[] checks, final Runnable after) {
        // Only what is not already fine.
        if (canWriteSettings(c)) {
            for (int i = 0; i < checks.length; i++) {
                if (!checks[i].isGood(checks[i].read(c))) checks[i].applyDirect(c);
            }
            after.run();
            return;
        }
        if (!AdbBridge.canRun(c)) { after.run(); return; }
        List<String> cmds = new ArrayList<String>();
        for (int i = 0; i < checks.length; i++) {
            if (!checks[i].isGood(checks[i].read(c))) cmds.add(checks[i].command());
        }
        cmds.add("pm grant " + c.getPackageName() + " " + PERM_WSS);
        AdbBridge.run(cmds.toArray(new String[cmds.size()]), new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) { after.run(); }
        });
    }

    private static void paint(Context c, Check ck, Panel.Row row) {
        String cur = ck.read(c);
        row.setValue(describe(c, ck, cur));
        row.setValueColor(ck.isGood(cur) ? OK : WARN);
    }

    private static String describe(Context c, Check ck, String cur) {
        cur = ck.effective(cur);
        if (cur == null || cur.isEmpty()) return c.getString(R.string.health_unset);
        // What is set, not the target.
        if (ck.isGood(cur)) return ck.abbreviate(c, cur);
        return ck.abbreviate(c, cur) + " -> " + ck.shortWant(c);
    }

    // ---------------------------------------------------------------- screensaver

    private static String readSecure(Context c, String key) {
        try { return Settings.Secure.getString(c.getContentResolver(), key); }
        catch (Throwable t) { return null; }
    }

    /** What the TV uses now. Both choices are fine; only a default whose package is off is flagged. */
    private static void paintSaver(Context c, Panel.Row row) {
        String cur = readSecure(c, SAVER_KEY);
        if (SAVER_BASIC.equals(cur)) {
            row.setValue(c.getString(R.string.health_saver_basic));
            row.setValueColor(OK);
        } else if (cur == null || cur.isEmpty() || cur.contains(SAVER_PKG)) {
            boolean off = Debloat.stateOf(c, SAVER_PKG) == Debloat.DISABLED;
            row.setValue(c.getString(off ? R.string.health_saver_default_off : R.string.health_saver_default));
            row.setValueColor(off ? WARN : OK);
        } else {
            // Another screensaver the user picked: named by its package, left alone until pressed.
            int slash = cur.indexOf('/');
            String pkg = slash > 0 ? cur.substring(0, slash) : cur;
            int dot = pkg.lastIndexOf('.');
            row.setValue(dot > 0 ? pkg.substring(dot + 1) : pkg);
            row.setValueColor(OK);
        }
    }

    /**
     * One secure setting, in-process when WRITE_SECURE_SETTINGS is held, else through the
     * ADB link (which also grants it). A null value removes the setting.
     */
    private static void writeSecure(final Context c, String key, String value, final Runnable after) {
        if (canWriteSettings(c)) {
            try { Settings.Secure.putString(c.getContentResolver(), key, value); }
            catch (Throwable t) { /* not granted after all; nothing to do */ }
            after.run();
            return;
        }
        if (!AdbBridge.canRun(c)) { after.run(); return; }
        String cmd = value == null ? "settings delete secure " + key
                : "settings put secure " + key + " '" + value + "'";
        AdbBridge.run(new String[]{ cmd, "pm grant " + c.getPackageName() + " " + PERM_WSS },
                new AdbBridge.Callback() {
                    public void onDone(boolean ok, String out) { after.run(); }
                });
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
        final String key, want;
        final int label;

        private Check(boolean g, String k, String w, int l) {
            global = g; key = k; want = w; label = l;
        }
        static Check global(String k, String w, int l) { return new Check(true, k, w, l); }
        static Check secure(String k, String w, int l) { return new Check(false, k, w, l); }

        String read(Context c) {
            try {
                return global
                        ? Settings.Global.getString(c.getContentResolver(), key)
                        : Settings.Secure.getString(c.getContentResolver(), key);
            } catch (Throwable t) { return null; }
        }

        /** The value the TV goes by: an unset animation scale is Android's default, 1. */
        String effective(String cur) {
            return cur == null && key.endsWith("_scale") ? "1" : cur;
        }

        boolean isGood(String cur) {
            cur = effective(cur);
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
        String shortWant(Context c) { return abbreviate(c, want); }

        String abbreviate(Context c, String v) {
            if (v == null) return c.getString(R.string.health_unset);
            if (key.endsWith("_scale")) {
                try {
                    float f = Float.parseFloat(v);
                    if (f == 0f) return c.getString(R.string.v_off);
                    return (f == (int) f ? String.valueOf((int) f) : String.valueOf(f)) + "x";
                } catch (Throwable ignored) { }
            }
            // Every other numeric row here is a switch.
            if ("0".equals(v)) return c.getString(R.string.v_off);
            if ("1".equals(v)) return c.getString(R.string.v_on);
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
