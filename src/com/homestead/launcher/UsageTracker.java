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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Last-used times per package, recorded by the launcher itself from the accessibility
 * service's TYPE_WINDOW_STATE_CHANGED events. This avoids depending on UsageStatsManager,
 * whose PACKAGE_USAGE_STATS appop can only be granted from a shell, and counts launches
 * from any path, not just tile presses.
 *
 * When UsageStatsManager is available it is used to seed launches that predate install;
 * the newer timestamp wins.
 */
final class UsageTracker {

    /** Existing file name, kept so stored timestamps carry over. */
    private static final String FILE = "launchtimes";

    /** Fire OS sends several window events per app switch; repeats inside this are dropped. */
    private static final long COALESCE_MS = 15000L;

    /** Guard against unbounded growth if a package churns window events. */
    private static final int MAX_ENTRIES = 400;

    private static Map<String, Long> cache;

    private UsageTracker() { }

    private static synchronized Map<String, Long> cache(Context c) {
        if (cache == null) {
            cache = new HashMap<String, Long>();
            try {
                Map<String, ?> all = prefs(c).getAll();
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Long) cache.put(e.getKey(), (Long) v);
                }
            } catch (Throwable ignored) { }
        }
        return cache;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Note that a package came to the foreground. Cheap enough for the event callback. */
    static synchronized void record(Context c, String pkg) {
        if (c == null || pkg == null || pkg.length() == 0) return;
        long now = System.currentTimeMillis();
        Map<String, Long> m = cache(c);
        Long prev = m.get(pkg);
        if (prev != null && now - prev < COALESCE_MS) return;
        m.put(pkg, now);
        try {
            prefs(c).edit().putLong(pkg, now).apply();
        } catch (Throwable ignored) { }
        if (m.size() > MAX_ENTRIES) trim(c, m);
    }

    static synchronized long lastUsed(Context c, String pkg) {
        if (pkg == null) return 0L;
        Long v = cache(c).get(pkg);
        return (v == null) ? 0L : v.longValue();
    }

    /** How many packages have been recorded; shown on the Setup page. */
    static synchronized int size(Context c) {
        return cache(c).size();
    }

    /** Fold in a UsageStatsManager timestamp; never overwrites a newer local one. */
    static synchronized void seed(Context c, String pkg, long millis) {
        if (pkg == null || millis <= 0L) return;
        Map<String, Long> m = cache(c);
        Long prev = m.get(pkg);
        if (prev != null && prev.longValue() >= millis) return;
        m.put(pkg, millis);
        try {
            prefs(c).edit().putLong(pkg, millis).apply();
        } catch (Throwable ignored) { }
    }

    /** Drops packages that are no longer launchable. */
    private static void trim(Context c, Map<String, Long> m) {
        try {
            Set<String> keys = new java.util.HashSet<String>(m.keySet());
            SharedPreferences.Editor ed = prefs(c).edit();
            for (String k : keys) {
                if (m.size() <= MAX_ENTRIES) break;
                if (c.getPackageManager().getLaunchIntentForPackage(k) == null) {
                    m.remove(k);
                    ed.remove(k);
                }
            }
            ed.apply();
        } catch (Throwable ignored) { }
    }
}
