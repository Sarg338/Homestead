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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Downloads an update APK over HTTPS, checks it is signed with the same certificate as the
 * running app (otherwise it is deleted and refused), and installs it.
 *
 * With the on-device tunnel the install is a silent `pm install -r` as shell; replacing the
 * running app ends this process, so success usually shows up as a restart rather than a
 * callback. Otherwise it falls back to the system installer via UpdateFileProvider.
 */
public final class UpdateInstaller {

    private static final String TAG = "Homestead";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Progress {
        /** 0..100 during download; may be called with -1 when the total size is unknown. */
        void onProgress(int pct);
        /** A short status line (e.g. "Installing through the tunnel"). */
        void onStatus(String status);
        void onDone(boolean ok, String message);
    }

    private UpdateInstaller() { }

    public static void downloadAndInstall(final Context c, final UpdateChecker.Update u,
                                          final Progress p) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                File apk = null;
                try {
                    status(p, "Downloading");
                    apk = download(app, u, p);
                    if (apk == null || apk.length() == 0) { done(p, false, "Download failed."); return; }

                    status(p, "Verifying");
                    if (!signatureMatches(app, apk)) {
                        apk.delete();
                        done(p, false, "Update refused: its signature does not match this app.");
                        return;
                    }
                    install(app, apk, p);
                } catch (Throwable t) {
                    Log.w(TAG, "update install failed: " + t);
                    if (apk != null) try { apk.delete(); } catch (Throwable ignored) { }
                    done(p, false, friendly(t));
                }
            }
        }, "homestead-update-install").start();
    }

    // ---------------------------------------------------------------- download

    private static File download(Context c, UpdateChecker.Update u, Progress p) throws Exception {
        // Internal cache is enough: the tunnel streams the bytes to pm and the intent path
        // serves them via UpdateFileProvider, so nothing needs world-readable storage.
        File dir = c.getCacheDir();
        if (dir == null) dir = c.getExternalCacheDir();
        if (dir != null) dir.mkdirs();
        File out = new File(dir, "homestead-update.apk");
        if (out.exists()) out.delete();

        HttpURLConnection conn = (HttpURLConnection) new URL(u.apkUrl).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Homestead-Updater");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code + " while downloading the update");
            long total = u.apkSize > 0 ? u.apkSize : conn.getContentLength();
            InputStream in = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[65536];
                long got = 0;
                int n;
                int lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        int pct = (int) (got * 100L / total);
                        if (pct != lastPct) { progress(p, pct); lastPct = pct; }
                    } else {
                        progress(p, -1);
                    }
                }
                fos.flush();
            } finally {
                try { fos.close(); } catch (Throwable ignored) { }
                try { in.close(); } catch (Throwable ignored) { }
            }
        } finally {
            conn.disconnect();
        }
        return out;
    }

    // ---------------------------------------------------------------- signature check

    /** True only if the downloaded APK is signed by the same certificate(s) as this app. */
    static boolean signatureMatches(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo cur = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
            PackageInfo dl = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNATURES);
            if (cur == null || dl == null) return false;
            Set<String> mine = digests(cur.signatures);
            Set<String> theirs = digests(dl.signatures);
            return !mine.isEmpty() && mine.equals(theirs);
        } catch (Throwable t) {
            Log.w(TAG, "signature check failed: " + t);
            return false;
        }
    }

    private static Set<String> digests(Signature[] sigs) throws Exception {
        Set<String> out = new HashSet<String>();
        if (sigs == null) return out;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < sigs.length; i++) {
            byte[] d = md.digest(sigs[i].toByteArray());
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (int j = 0; j < d.length; j++) {
                sb.append(Character.forDigit((d[j] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[j] & 0xF, 16));
            }
            out.add(sb.toString());
        }
        return out;
    }

    // ---------------------------------------------------------------- install

    private static void install(final Context c, final File apk, final Progress p) {
        // Replacing the app ends this process; BootReceiver reopens it if this was us.
        Prefs.setUpdateStarted(c, System.currentTimeMillis());
        if (AdbBridge.canRun(c)) {
            status(p, "Installing through the tunnel");
            AdbBridge.installApk(apk, new AdbBridge.Callback() {
                public void onDone(boolean ok, String output) {
                    // Reaching here means the replace has not stopped us yet, or it failed.
                    boolean success = ok && output != null && output.contains("Success");
                    if (success) {
                        done(p, true, "Installed. Homestead will restart on the new version.");
                    } else {
                        Log.w(TAG, "tunnel install did not report success: " + output);
                        installViaIntent(c, apk, p, output);
                    }
                }
            });
        } else {
            installViaIntent(c, apk, p, null);
        }
    }

    private static void installViaIntent(Context c, File apk, Progress p, String prevErr) {
        try {
            Uri uri = UpdateFileProvider.uriFor(c, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            done(p, true, "Opening the system installer to finish.");
        } catch (Throwable t) {
            String base = prevErr != null && prevErr.trim().length() > 0
                    ? "Install failed: " + firstLine(prevErr)
                    : "Could not open the installer.";
            done(p, false, base + " Set up the on-device tunnel for a one-tap install.");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return (nl > 0 ? s.substring(0, nl) : s).trim();
    }

    private static String friendly(Throwable t) {
        if (t instanceof java.net.UnknownHostException) return "No internet connection.";
        if (t instanceof java.net.SocketTimeoutException) return "The download timed out.";
        return "Update failed: " + t.getMessage();
    }

    private static void progress(final Progress p, final int pct) {
        if (p == null) return;
        MAIN.post(new Runnable() { public void run() { try { p.onProgress(pct); } catch (Throwable ignored) { } } });
    }

    private static void status(final Progress p, final String s) {
        if (p == null) return;
        MAIN.post(new Runnable() { public void run() { try { p.onStatus(s); } catch (Throwable ignored) { } } });
    }

    private static void done(final Progress p, final boolean ok, final String msg) {
        if (p == null) return;
        MAIN.post(new Runnable() { public void run() { try { p.onDone(ok, msg); } catch (Throwable ignored) { } } });
    }
}
