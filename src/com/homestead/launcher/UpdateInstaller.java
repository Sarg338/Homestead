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
import android.os.Build;
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
    /**
     * The server is trusted for what it sends, not for how much of the disk it may fill: the
     * download stops a little past the size the release declared, or at this ceiling when
     * it declared none.
     */
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L;
    private static final long SIZE_SLACK_BYTES = 1024L * 1024L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Progress {
        /** 0..100 during download; may be called with -1 when the total size is unknown. */
        void onProgress(int pct);
        /** A short status line (e.g. "Installing through the tunnel"). */
        void onStatus(String status);
        /**
         * The system installer is open. Its result is not reported: a successful install
         * replaces the app and ends this process.
         */
        void onInstallerOpened();
        /** Fire OS does not allow Homestead to install apps yet, so nothing was opened. */
        void onNotAllowed();
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
                    status(p, app.getString(R.string.upd_downloading));
                    apk = download(app, u, p);
                    if (apk == null || apk.length() == 0) { done(p, false, app.getString(R.string.upd_download_failed)); return; }

                    status(p, app.getString(R.string.upd_verifying));
                    if (!signatureMatches(app, apk)) {
                        apk.delete();
                        done(p, false, app.getString(R.string.upd_bad_signature));
                        return;
                    }
                    install(app, apk, p);
                } catch (Throwable t) {
                    Log.w(TAG, "update install failed: " + t);
                    if (apk != null) try { apk.delete(); } catch (Throwable ignored) { }
                    done(p, false, friendly(app, t));
                }
            }
        }, "homestead-update-install").start();
    }

    // ---------------------------------------------------------------- download

    private static File download(Context c, UpdateChecker.Update u, Progress p) throws Exception {
        // Internal cache only: the tunnel streams the bytes to pm and the intent path serves
        // them via UpdateFileProvider, so nothing needs shared storage. Shared storage is also
        // writable by other apps, which could swap the file after the signature check.
        File dir = c.getCacheDir();
        if (dir == null) throw new Exception(c.getString(R.string.upd_no_cache));
        dir.mkdirs();
        File out = new File(dir, "homestead-update.apk");
        if (out.exists()) out.delete();

        HttpURLConnection conn = (HttpURLConnection) new URL(u.apkUrl).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Homestead-Updater");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        boolean complete = false;
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code + " while downloading the update");
            long total = u.apkSize > 0 ? u.apkSize : conn.getContentLength();
            long limit = u.apkSize > 0 ? u.apkSize + SIZE_SLACK_BYTES : MAX_DOWNLOAD_BYTES;
            InputStream in = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[65536];
                long got = 0;
                int n;
                int lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    got += n;
                    if (got > limit) {
                        throw new Exception(c.getString(R.string.upd_too_large));
                    }
                    fos.write(buf, 0, n);
                    if (total > 0) {
                        int pct = (int) (got * 100L / total);
                        if (pct != lastPct) { progress(p, pct); lastPct = pct; }
                    } else {
                        progress(p, -1);
                    }
                }
                fos.flush();
                complete = true;
            } finally {
                try { fos.close(); } catch (Throwable ignored) { }
                try { in.close(); } catch (Throwable ignored) { }
            }
        } finally {
            conn.disconnect();
            // A cut-off or oversized download is not left behind in the cache.
            if (!complete) try { out.delete(); } catch (Throwable ignored) { }
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
            status(p, c.getString(R.string.upd_installing_tunnel));
            AdbBridge.installApk(apk, new AdbBridge.Callback() {
                public void onDone(boolean ok, String output) {
                    // Reaching here means the replace has not stopped us yet, or it failed.
                    boolean success = ok && output != null && output.contains("Success");
                    if (success) {
                        done(p, true, c.getString(R.string.upd_installed));
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
        // Without "Install unknown apps" for Homestead, Fire OS shows a refusal that does not
        // say where the switch is, so check first and let the panel explain.
        if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
            notAllowed(p);
            return;
        }
        try {
            Uri uri = UpdateFileProvider.uriFor(c, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            // CLEAR_TASK: after an earlier update the installer's "App installed" screen can
            // still be in its task, and without this Android brings that back instead.
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            // Reported first, so the panel is watching before its window loses focus.
            opened(p);
            c.startActivity(i);
        } catch (Throwable t) {
            String base = prevErr != null && prevErr.trim().length() > 0
                    ? c.getString(R.string.upd_install_failed, firstLine(prevErr))
                    : c.getString(R.string.upd_no_installer);
            done(p, false, base + " " + c.getString(R.string.upd_tunnel_hint));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return (nl > 0 ? s.substring(0, nl) : s).trim();
    }

    private static String friendly(Context c, Throwable t) {
        if (t instanceof java.net.UnknownHostException) return c.getString(R.string.upd_no_internet);
        if (t instanceof java.net.SocketTimeoutException) return c.getString(R.string.upd_download_timeout);
        return c.getString(R.string.upd_failed, t.getMessage());
    }

    private static void progress(final Progress p, final int pct) {
        if (p == null) return;
        MAIN.post(new Runnable() { public void run() { try { p.onProgress(pct); } catch (Throwable ignored) { } } });
    }

    private static void status(final Progress p, final String s) {
        if (p == null) return;
        MAIN.post(new Runnable() { public void run() { try { p.onStatus(s); } catch (Throwable ignored) { } } });
    }

    private static void opened(final Progress p) {
        if (p == null) return;
        MAIN.post(new Runnable() { public void run() { try { p.onInstallerOpened(); } catch (Throwable ignored) { } } });
    }

    private static void notAllowed(final Progress p) {
        if (p == null) return;
        MAIN.post(new Runnable() { public void run() { try { p.onNotAllowed(); } catch (Throwable ignored) { } } });
    }

    private static void done(final Progress p, final boolean ok, final String msg) {
        if (p == null) return;
        MAIN.post(new Runnable() { public void run() { try { p.onDone(ok, msg); } catch (Throwable ignored) { } } });
    }
}
