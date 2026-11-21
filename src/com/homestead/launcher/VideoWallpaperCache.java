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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Downloads and caches 1080p looping MP4 video wallpapers on demand into private storage.
 */
final class VideoWallpaperCache {

    static final String KEY_AURORA = "aurora_v3";
    static final String KEY_STARFIELD = "starfield_v2";
    static final String KEY_WAVES = "waves_v2";

    private static final String TAG = "Homestead";
    private static final long MAX_BYTES = 128L * 1024L * 1024L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Map<String, String> URLS = new HashMap<String, String>();
    private static final Map<String, String> AUDIO_URLS = new HashMap<String, String>();
    static {
        // Verified matching 1080p Mixkit clips.
        URLS.put(KEY_AURORA, "https://assets.mixkit.co/videos/4036/4036-1080.mp4");
        URLS.put(KEY_STARFIELD, "https://assets.mixkit.co/videos/30046/30046-1080.mp4");
        URLS.put(KEY_WAVES, "https://assets.mixkit.co/videos/44482/44482-1080.mp4");

        AUDIO_URLS.put("sfx_aurora_v2", "https://assets.mixkit.co/active_storage/sfx/2097/2097-preview.mp3");
        AUDIO_URLS.put("sfx_starfield_v3", "https://assets.mixkit.co/active_storage/sfx/2465/2465-preview.mp3");
        AUDIO_URLS.put("sfx_night_v2", "https://assets.mixkit.co/active_storage/sfx/1227/1227-preview.mp3");
        AUDIO_URLS.put("sfx_ocean", "https://assets.mixkit.co/active_storage/sfx/1185/1185-preview.mp3");
        AUDIO_URLS.put("sfx_weather_day_v2", "https://assets.mixkit.co/active_storage/sfx/1210/1210-preview.mp3");
        AUDIO_URLS.put("sfx_rain_v2", "https://assets.mixkit.co/active_storage/sfx/1247/1247-preview.mp3");
    }

    private static final Set<String> BUSY = new HashSet<String>();
    private static final Set<String> FAILED = new HashSet<String>();

    private VideoWallpaperCache() { }

    static String keyFor(String bgId) {
        if (AnimatedBackgroundDrawable.ID_AURORA.equals(bgId)) return KEY_AURORA;
        if (AnimatedBackgroundDrawable.ID_STARFIELD.equals(bgId)) return KEY_STARFIELD;
        if (AnimatedBackgroundDrawable.ID_WAVES.equals(bgId)) return KEY_WAVES;
        return null;
    }

    static String audioKeyFor(Context c, String bgId) {
        if (AnimatedBackgroundDrawable.ID_AURORA.equals(bgId)) return "sfx_aurora_v2";
        if (AnimatedBackgroundDrawable.ID_STARFIELD.equals(bgId)) return "sfx_starfield_v3";
        if (AnimatedBackgroundDrawable.ID_WAVES.equals(bgId)) return "sfx_ocean";
        if (WeatherService.ID.equals(bgId)) {
            WeatherService.State ws = WeatherService.current(c);
            int code = ws.weatherCode;
            if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95) {
                return "sfx_rain_v2";
            }
            return ws.isDay ? "sfx_weather_day_v2" : "sfx_night_v2";
        }
        return null;
    }

    static File audioFile(Context c, String key) {
        if (key == null) return null;
        File dir = new File(c.getFilesDir(), "audio");
        File f = new File(dir, key + ".mp3");
        return f.isFile() && f.length() > 0 ? f : null;
    }

    static void ensureAudio(Context context, final String key, final Runnable onReady) {
        final Context c = context.getApplicationContext();
        if (key == null || audioFile(c, key) != null || BUSY.contains(key) || FAILED.contains(key)) return;
        final String urlStr = AUDIO_URLS.get(key);
        if (urlStr == null) return;
        downloadTo(c, "audio", key, ".mp3", urlStr, onReady);
    }

    static File file(Context c, String key) {
        if (key == null) return null;
        File dir = new File(c.getFilesDir(), "videos");
        File f = new File(dir, key + ".mp4");
        return f.isFile() && f.length() > 0 ? f : null;
    }

    static void ensure(Context context, final String key, final Runnable onReady) {
        final Context c = context.getApplicationContext();
        if (key == null || file(c, key) != null || BUSY.contains(key) || FAILED.contains(key)) return;
        final String urlStr = URLS.get(key);
        if (urlStr == null) return;
        downloadTo(c, "videos", key, ".mp4", urlStr, onReady);
    }

    private static void downloadTo(final Context c, final String subDir, final String key,
            final String ext, final String urlStr, final Runnable onReady) {
        BUSY.add(key);
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                File dir = new File(c.getFilesDir(), subDir);
                if (!dir.isDirectory()) dir.mkdirs();
                File dest = new File(dir, key + ext);
                File part = new File(dir, key + ext + ".part");
                try {
                    URL url = allowed(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(45000);
                    conn.setRequestProperty("User-Agent", "Homestead/2 VideoWallpaper");
                    try {
                        int code = conn.getResponseCode();
                        if (code != 200) throw new IOException("HTTP " + code);
                        long len = conn.getContentLength();
                        if (len > MAX_BYTES) throw new IOException("Media too large");
                        InputStream in = conn.getInputStream();
                        FileOutputStream out = new FileOutputStream(part);
                        try {
                            byte[] buf = new byte[16384];
                            int n;
                            long total = 0;
                            while ((n = in.read(buf)) != -1) {
                                total += n;
                                if (total > MAX_BYTES) throw new IOException("Media too large");
                                out.write(buf, 0, n);
                            }
                            out.getFD().sync();
                        } finally {
                            try { in.close(); } catch (Throwable ignored) { }
                            try { out.close(); } catch (Throwable ignored) { }
                        }
                        if (!part.renameTo(dest)) throw new IOException("Rename failed");
                        ok = true;
                        Log.i(TAG, "Media cached: " + key);
                    } finally {
                        conn.disconnect();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Media download failed (" + key + "): " + t);
                    part.delete();
                }
                final boolean success = ok;
                MAIN.post(new Runnable() {
                    public void run() {
                        BUSY.remove(key);
                        if (!success) FAILED.add(key);
                        if (success && onReady != null) onReady.run();
                    }
                });
            }
        }, "homestead-media-dl-" + key).start();
    }

    private static URL allowed(String address) throws IOException {
        URL u = new URL(address);
        String host = u.getHost().toLowerCase(Locale.US);
        if (!"https".equals(u.getProtocol()) || !host.equals("assets.mixkit.co")
                || u.getUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443)) {
            throw new IOException("Unsupported video host");
        }
        return u;
    }
}
