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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Asks GitHub whether there is a newer Homestead release than the one installed.
 *
 * One unauthenticated GET to the Releases API (no token is shipped in the app), so the
 * releases repo must be public; a 404 is reported as "no public release channel".
 * The release tag ("v1.2" or "1.2") is compared with the installed versionName as dotted
 * numbers ("1.10" > "1.9"). Installing is UpdateInstaller's job.
 */
public final class UpdateChecker {

    private static final String TAG = "Homestead";

    /** owner/repo. One line to change if distribution moves to a dedicated releases repo. */
    static final String REPO = "Sarg338/Homestead";

    private static final String API =
            "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** Don't hit the network more than once a day on the automatic path. */
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A release that is newer than what is installed. */
    public static final class Update {
        /** Cleaned versionName, e.g. "1.2" (no leading v). */
        public final String versionName;
        /** Release notes (the release body markdown), may be empty. */
        public final String notes;
        /** Direct download URL of the .apk asset. */
        public final String apkUrl;
        /** Asset size in bytes, or 0 if the API did not say. */
        public final long apkSize;

        Update(String versionName, String notes, String apkUrl, long apkSize) {
            this.versionName = versionName;
            this.notes = notes == null ? "" : notes;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }
    }

    /** @param update the newer release, or null if none (also null on a throttled skip). */
    public interface Callback {
        void onResult(Update update, String error);
    }

    private UpdateChecker() { }

    /** True if the automatic path is due to check again (older than the interval). */
    static boolean dueForCheck(Context c) {
        long last = Prefs.lastUpdateCheck(c);
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS;
    }

    /**
     * Check in the background. force=false is throttled to once a day (a skipped check calls
     * back with null, null); force=true is the manual action and always hits the network.
     */
    public static void check(final Context c, final boolean force, final Callback cb) {
        if (!force && !dueForCheck(c)) { post(cb, null, null); return; }
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                Update u = null;
                String err = null;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(API).openConnection();
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "Homestead-Updater");
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    int code = conn.getResponseCode();
                    Prefs.setLastUpdateCheck(app, System.currentTimeMillis());
                    if (code == 404 || code == 401) {
                        err = "No release is available yet.";
                    } else if (code == 403) {
                        err = "GitHub rate limit reached. Try again later.";
                    } else if (code != 200) {
                        err = "GitHub returned HTTP " + code + ".";
                    } else {
                        u = parse(app, read(conn.getInputStream()));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "update check failed: " + t);
                    err = friendly(t);
                } finally {
                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
                }
                post(cb, u, err);
            }
        }, "homestead-update-check").start();
    }

    private static Update parse(Context c, String body) {
        try {
            JSONObject o = new JSONObject(body);
            String tag = o.optString("tag_name", o.optString("name", ""));
            String notes = o.optString("body", "");
            String apkUrl = null;
            long size = 0;
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", null);
                        size = a.optLong("size", 0);
                        break;
                    }
                }
            }
            String installed = installedVersionName(c);
            if (tag.length() > 0 && apkUrl != null && isNewer(tag, installed)) {
                return new Update(clean(tag), notes, apkUrl, size);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not parse release JSON: " + t);
        }
        return null;
    }

    // ---------------------------------------------------------------- version compare

    /** True if candidate (e.g. "v1.2") is a higher dotted-number version than installed. */
    static boolean isNewer(String candidate, String installed) {
        int[] a = parts(candidate);
        int[] b = parts(installed);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String v) {
        if (v == null) return new int[0];
        String s = clean(v);
        String[] raw = s.split("[^0-9]+");
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].length() == 0) continue;
            try { out.add(Integer.parseInt(raw[i])); } catch (NumberFormatException ignored) { }
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Strip a leading v/V and surrounding whitespace. */
    static String clean(String v) {
        if (v == null) return "";
        String s = v.trim();
        if (s.length() > 0 && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) s = s.substring(1);
        return s.trim();
    }

    static String installedVersionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String read(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    private static String friendly(Throwable t) {
        String m = String.valueOf(t.getMessage());
        if (t instanceof java.net.UnknownHostException) return "No internet connection.";
        if (t instanceof java.net.SocketTimeoutException) return "The update check timed out.";
        return "Update check failed: " + m;
    }

    private static void post(final Callback cb, final Update u, final String err) {
        if (cb == null) return;
        MAIN.post(new Runnable() {
            public void run() { try { cb.onResult(u, err); } catch (Throwable ignored) { } }
        });
    }
}
