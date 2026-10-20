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
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * User preferences, read synchronously on the main thread (the values are small).
 *
 * Hidden tiles are keyed by AppModel.Item.key(), so inputs and the Amazon Home tile can be
 * hidden as well as apps.
 */
public final class Prefs {

    private static final String FILE = "settings";

    private static final String K_HIDDEN = "hidden";
    private static final String K_SORT = "sort";
    private static final String K_COLUMNS = "columns";
    private static final String K_SEC_INPUTS = "sec_inputs";
    private static final String K_SEC_NONTV = "sec_nontv";
    private static final String K_CLOCK = "clock";
    private static final String K_ADB = "adb_enabled";
    private static final String K_TUNNEL = "adb_tunnel_enabled";
    private static final String K_REAPPLY = "adb_reapply_on_boot";
    private static final String K_DISABLED = "disabled_by_us";
    private static final String K_SETUP_SEEN = "setup_seen";
    private static final String K_ONBOARDED = "onboarded";
    private static final String K_UPD_LASTCHECK = "update_last_check";
    private static final String K_UPD_SKIP = "update_skipped_version";
    private static final String K_UPD_NOTIFIED = "update_notified_version";
    private static final String K_UPD_AUTO = "update_auto_check";

    public static final int SORT_LAST_USED = 0;
    public static final int SORT_ALPHA = 1;

    public static final int CLOCK_24H = 0;
    public static final int CLOCK_12H = 1;
    public static final int CLOCK_OFF = 2;

    private Prefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- hidden tiles

    /** Returns a copy: a Set from SharedPreferences must not be mutated. */
    public static Set<String> hidden(Context c) {
        Set<String> s = sp(c).getStringSet(K_HIDDEN, Collections.<String>emptySet());
        return new HashSet<String>(s);
    }

    public static boolean isHidden(Context c, String key) {
        if (key == null) return false;
        return sp(c).getStringSet(K_HIDDEN, Collections.<String>emptySet()).contains(key);
    }

    public static void setHidden(Context c, String key, boolean hide) {
        if (key == null) return;
        Set<String> s = hidden(c);
        if (hide) s.add(key); else s.remove(key);
        sp(c).edit().putStringSet(K_HIDDEN, s).apply();
    }

    public static void clearHidden(Context c) {
        sp(c).edit().putStringSet(K_HIDDEN, new HashSet<String>()).apply();
    }

    public static int hiddenCount(Context c) {
        return sp(c).getStringSet(K_HIDDEN, Collections.<String>emptySet()).size();
    }

    // ---------------------------------------------------------------- layout

    public static int sort(Context c) { return sp(c).getInt(K_SORT, SORT_LAST_USED); }
    public static void setSort(Context c, int v) { sp(c).edit().putInt(K_SORT, v).apply(); }

    public static int columns(Context c) {
        int n = sp(c).getInt(K_COLUMNS, 6);
        if (n < 4) n = 4;
        if (n > 8) n = 8;
        return n;
    }
    public static void setColumns(Context c, int v) { sp(c).edit().putInt(K_COLUMNS, v).apply(); }

    public static boolean showInputs(Context c) { return sp(c).getBoolean(K_SEC_INPUTS, true); }
    public static void setShowInputs(Context c, boolean v) { sp(c).edit().putBoolean(K_SEC_INPUTS, v).apply(); }

    public static boolean showNonTv(Context c) { return sp(c).getBoolean(K_SEC_NONTV, true); }
    public static void setShowNonTv(Context c, boolean v) { sp(c).edit().putBoolean(K_SEC_NONTV, v).apply(); }

    public static int clockMode(Context c) { return sp(c).getInt(K_CLOCK, CLOCK_24H); }
    public static void setClockMode(Context c, int v) { sp(c).edit().putInt(K_CLOCK, v).apply(); }

    // ---------------------------------------------------------------- the ADB link

    /** Off by default: the first connection raises the system authorisation dialog. */
    public static boolean adbEnabled(Context c) { return sp(c).getBoolean(K_ADB, false); }
    public static void setAdbEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_ADB, v).apply();
    }

    /**
     * Route the ADB link through the on-device VPN tunnel (TunnelVpnService). Off by
     * default and set only by explicit user action; once set, later connects bring the
     * tunnel up automatically since the system remembers VPN consent.
     */
    public static boolean tunnelEnabled(Context c) { return sp(c).getBoolean(K_TUNNEL, false); }
    public static void setTunnelEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_TUNNEL, v).apply();
    }

    /** Re-apply package choices after reboot; some are reverted by the system. */
    public static boolean reapplyOnBoot(Context c) { return sp(c).getBoolean(K_REAPPLY, true); }
    public static void setReapplyOnBoot(Context c, boolean v) {
        sp(c).edit().putBoolean(K_REAPPLY, v).apply();
    }

    public static boolean setupSeen(Context c) { return sp(c).getBoolean(K_SETUP_SEEN, false); }
    public static void setSetupSeen(Context c, boolean v) {
        sp(c).edit().putBoolean(K_SETUP_SEEN, v).apply();
    }

    /** Whether the first-run wizard has been completed or skipped. */
    public static boolean onboarded(Context c) { return sp(c).getBoolean(K_ONBOARDED, false); }
    public static void setOnboarded(Context c, boolean v) {
        sp(c).edit().putBoolean(K_ONBOARDED, v).apply();
    }

    // ---------------------------------------------------------------- updates

    public static boolean autoUpdateCheck(Context c) { return sp(c).getBoolean(K_UPD_AUTO, true); }
    public static void setAutoUpdateCheck(Context c, boolean v) {
        sp(c).edit().putBoolean(K_UPD_AUTO, v).apply();
    }

    /** Epoch millis of the last automatic check; used to throttle to once a day. */
    public static long lastUpdateCheck(Context c) { return sp(c).getLong(K_UPD_LASTCHECK, 0L); }
    public static void setLastUpdateCheck(Context c, long v) {
        sp(c).edit().putLong(K_UPD_LASTCHECK, v).apply();
    }

    /** A version the user chose to skip. */
    public static String skippedVersion(Context c) { return sp(c).getString(K_UPD_SKIP, ""); }
    public static void setSkippedVersion(Context c, String v) {
        sp(c).edit().putString(K_UPD_SKIP, v == null ? "" : v).apply();
    }

    /** The last version auto-prompted for, so each version prompts only once. */
    public static String notifiedVersion(Context c) { return sp(c).getString(K_UPD_NOTIFIED, ""); }
    public static void setNotifiedVersion(Context c, String v) {
        sp(c).edit().putString(K_UPD_NOTIFIED, v == null ? "" : v).apply();
    }

    // ------------------------------------------------------ packages we disabled

    /** Packages this app disabled, so "undo everything" restores only those. */
    public static Set<String> disabledByUs(Context c) {
        return new HashSet<String>(
                sp(c).getStringSet(K_DISABLED, Collections.<String>emptySet()));
    }

    public static void noteDisabled(Context c, String pkg, boolean disabled) {
        if (pkg == null) return;
        Set<String> s = disabledByUs(c);
        if (disabled) s.add(pkg); else s.remove(pkg);
        sp(c).edit().putStringSet(K_DISABLED, s).apply();
    }

    public static void clearDisabledRecord(Context c) {
        sp(c).edit().putStringSet(K_DISABLED, new HashSet<String>()).apply();
    }
}
