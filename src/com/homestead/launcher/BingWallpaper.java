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
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Downloads Bing's current image into a separate private cache when the user chooses it. */
final class BingWallpaper {
    static final String ID = "bing";

    private static final String TAG = "Homestead";
    private static final String API =
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US";
    private static final String PREFS = "bing_wallpaper";
    private static final String FILE = "bing-wallpaper.jpg";
    private static final int MAX_JSON = 256 * 1024;
    private static final int MAX_IMAGE = 16 * 1024 * 1024;
    private static final long RETRY_MS = 60L * 60L * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Accessed from the main thread: HomeGrid and settings both invoke refresh from there.
    private static boolean busy;
    private static final List<Runnable> done = new ArrayList<Runnable>();

    private BingWallpaper() { }

    static boolean chosen(Context c) { return ID.equals(Prefs.backgroundId(c)); }

    static File file(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /**
     * Fetch at most once on a local calendar day. A transient failure keeps yesterday's
     * private cache on screen and is retried after an hour; no timer wakes the television.
     */
    static void refresh(Context context, Runnable onReady) {
        final Context c = context.getApplicationContext();
        if (!chosen(c)) return;
        if (busy) {
            if (onReady != null) done.add(onReady);
            return;
        }
        final SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String today = day();
        long now = System.currentTimeMillis();
        if (today.equals(p.getString("day", "")) && file(c) != null) return;
        if (now - p.getLong("attempt", 0L) < RETRY_MS) return;
        if (onReady != null) done.add(onReady);
        busy = true;
        p.edit().putLong("attempt", now).apply();
        new Thread(new Runnable() {
            public void run() {
                boolean ready = false;
                String reason = null;
                try {
                    JSONObject page = new JSONObject(new String(fetch(API, MAX_JSON, false), "UTF-8"));
                    JSONArray images = page.getJSONArray("images");
                    JSONObject image = images.getJSONObject(0);
                    if (!image.optBoolean("wp", false)) throw new IOException("Bing did not offer an image");
                    URL imageUrl = new URL(new URL(API), image.getString("url"));
                    byte[] bytes = fetch(imageUrl.toString(), MAX_IMAGE, true);
                    verifyImage(bytes);
                    replace(c, bytes);
                    p.edit().putString("day", today).apply();
                    ready = true;
                } catch (Throwable t) {
                    reason = t instanceof IOException ? t.getMessage() : "could not read Bing's image";
                    Log.w(TAG, "Bing wallpaper not updated: " + t);
                }
                final boolean updated = ready;
                final String error = reason;
                MAIN.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (updated) {
                            for (Runnable r : done) {
                                try { r.run(); } catch (Throwable ignored) { }
                            }
                        }
                        done.clear();
                        if (updated) Log.i(TAG, "Bing wallpaper cached for " + today);
                        else Log.i(TAG, "Bing wallpaper kept previous image: " + error);
                    }
                });
            }
        }, "homestead-bing-wallpaper").start();
    }

    private static String day() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** HTTPS to Bing only; image redirects are held to the same origin boundary. */
    private static URL allowed(String address) throws IOException {
        URL u = new URL(address);
        String host = u.getHost().toLowerCase(Locale.US);
        boolean bing = host.equals("bing.com") || host.endsWith(".bing.com");
        if (!"https".equals(u.getProtocol()) || !bing || u.getUserInfo() != null
                || (u.getPort() != -1 && u.getPort() != 443)) {
            throw new IOException("Bing returned an unsupported address");
        }
        return u;
    }

    private static byte[] fetch(String address, int limit, boolean image) throws IOException {
        URL url = allowed(address);
        for (int redirects = 0; redirects < 5; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "Homestead/2 Bing wallpaper");
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Bing sent an invalid redirect");
                    url = allowed(new URL(url, location).toString());
                    continue;
                }
                if (code != 200) throw new IOException("Bing is unavailable (HTTP " + code + ")");
                String type = connection.getContentType();
                if (image && (type == null || !type.toLowerCase(Locale.US).startsWith("image/"))) {
                    throw new IOException("Bing returned a page instead of an image");
                }
                if (connection.getContentLengthLong() > limit) throw new IOException("Bing image is too large");
                InputStream in = connection.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try {
                    byte[] buffer = new byte[8192];
                    int n, total = 0;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > limit) throw new IOException("Bing image is too large");
                        out.write(buffer, 0, n);
                    }
                    return out.toByteArray();
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Bing redirected too many times");
    }

    private static void verifyImage(byte[] bytes) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth < 320 || o.outHeight < 180 || o.outWidth > 30000 || o.outHeight > 30000) {
            throw new IOException("Bing did not return a usable wallpaper");
        }
    }

    /** Write and validate before replacing the cache, so a bad response cannot blank it. */
    private static void replace(Context c, byte[] bytes) throws IOException {
        File dest = new File(c.getFilesDir(), FILE);
        File part = new File(c.getFilesDir(), FILE + ".part");
        FileOutputStream out = null;
        boolean saved = false;
        try {
            out = new FileOutputStream(part);
            out.write(bytes);
            out.getFD().sync();
            out.close();
            out = null;
            saved = part.renameTo(dest);
            if (!saved) throw new IOException("could not save Bing wallpaper");
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) { }
            if (!saved) part.delete();
        }
    }
}
