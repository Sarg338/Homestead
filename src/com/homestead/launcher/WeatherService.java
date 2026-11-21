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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fetches current weather from Open-Meteo for the live weather background.
 * Location resolution order:
 * 1) Primary city (if configured by the user)
 * 2) Automatic IP geolocation via geojs.io (if enabled)
 * 3) Fallback city (if auto-detection fails or is disabled)
 */
final class WeatherService {
    static final String ID = "anim_weather";

    private static final String TAG = "Homestead";
    private static final String PREFS = "weather_cache";
    private static final long CACHE_MS = 30L * 60L * 1000L;
    private static final long RETRY_MS = 5L * 60L * 1000L;
    private static final int MAX_JSON = 128 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class State {
        final boolean valid;
        final double tempC;
        final int weatherCode;
        final boolean isDay;
        final String city;
        final String source;
        final long updatedAt;

        State(boolean valid, double tempC, int weatherCode, boolean isDay,
                String city, String source, long updatedAt) {
            this.valid = valid;
            this.tempC = tempC;
            this.weatherCode = weatherCode;
            this.isDay = isDay;
            this.city = city;
            this.source = source;
            this.updatedAt = updatedAt;
        }
    }

    static final class CityMatch {
        final String name;
        final String country;
        final String admin1;
        final double lat;
        final double lon;

        CityMatch(String name, String country, String admin1, double lat, double lon) {
            this.name = name;
            this.country = country;
            this.admin1 = admin1;
            this.lat = lat;
            this.lon = lon;
        }

        String label() {
            StringBuilder sb = new StringBuilder(name);
            if (admin1 != null && !admin1.isEmpty() && !admin1.equals(name)) {
                sb.append(", ").append(admin1);
            }
            if (country != null && !country.isEmpty()) {
                sb.append(" (").append(country).append(")");
            }
            return sb.toString();
        }
    }

    private static boolean busy;
    private static String lastError = "";
    private static volatile int version = 1;
    private static final List<Runnable> done = new ArrayList<Runnable>();

    private WeatherService() { }

    static boolean chosen(Context c) { return ID.equals(Prefs.backgroundId(c)); }
    static boolean busy() { return busy; }
    static String lastError() { return lastError; }
    static int version() { return version; }

    static State current(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean valid = p.getBoolean("valid", false);
        double temp = Double.longBitsToDouble(p.getLong("temp", Double.doubleToLongBits(18.0)));
        int code = p.getInt("code", 0);
        boolean day = p.getBoolean("is_day", true);
        String city = p.getString("city", "");
        String source = p.getString("source", "");
        long updated = p.getLong("updated", 0L);
        return new State(valid, temp, code, day, city, source, updated);
    }

    private static String configKey(Context c) {
        return Prefs.weatherAuto(c) + "|" + Prefs.weatherPrimaryCity(c).trim()
                + "|" + Prefs.weatherFallbackCity(c).trim();
    }

    static void refresh(Context context, boolean force, Runnable onDone) {
        final Context c = context.getApplicationContext();
        if (!force && !chosen(c)) return;
        if (busy) {
            if (onDone != null) done.add(onDone);
            return;
        }
        final SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        final String cfg = configKey(c);
        boolean sameCfg = cfg.equals(p.getString("config", ""));
        if (!force && sameCfg) {
            long updated = p.getLong("updated", 0L);
            if (p.getBoolean("valid", false) && now - updated >= 0 && now - updated < CACHE_MS) {
                return;
            }
            long attempt = p.getLong("attempt", 0L);
            if (now - attempt >= 0 && now - attempt < RETRY_MS) return;
        }
        if (onDone != null) done.add(onDone);
        busy = true;
        p.edit().putLong("attempt", now).apply();

        final boolean auto = Prefs.weatherAuto(c);
        final String primary = Prefs.weatherPrimaryCity(c).trim();
        final String fallback = Prefs.weatherFallbackCity(c).trim();
        final String lang = Locale.getDefault().getLanguage();

        new Thread(new Runnable() {
            public void run() {
                String err = "";
                try {
                    double lat = 0, lon = 0;
                    String cityName = "";
                    String source = "";
                    boolean resolved = false;

                    if (!primary.isEmpty()) {
                        CityMatch m = geocodeFirst(primary, lang);
                        if (m != null) {
                            lat = m.lat;
                            lon = m.lon;
                            cityName = m.name;
                            source = "primary";
                            resolved = true;
                        }
                    }
                    if (!resolved && auto) {
                        try {
                            JSONObject geo = new JSONObject(new String(
                                    fetch("https://get.geojs.io/v1/ip/geo.json"), "UTF-8"));
                            String sLat = geo.optString("latitude", "");
                            String sLon = geo.optString("longitude", "");
                            if (!sLat.isEmpty() && !sLon.isEmpty()) {
                                lat = Double.parseDouble(sLat);
                                lon = Double.parseDouble(sLon);
                                cityName = geo.optString("city", "");
                                if (cityName.isEmpty()) cityName = geo.optString("country", "");
                                source = "auto";
                                resolved = true;
                            }
                        } catch (Throwable ignored) { }
                    }
                    if (!resolved && !fallback.isEmpty()) {
                        CityMatch m = geocodeFirst(fallback, lang);
                        if (m != null) {
                            lat = m.lat;
                            lon = m.lon;
                            cityName = m.name;
                            source = "fallback";
                            resolved = true;
                        }
                    }
                    if (!resolved) {
                        throw new IOException("No weather location resolved");
                    }

                    String url = String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day&timezone=auto",
                            lat, lon);
                    JSONObject root = new JSONObject(new String(fetch(url), "UTF-8"));
                    JSONObject cur = root.getJSONObject("current");
                    double temp = cur.getDouble("temperature_2m");
                    int code = cur.getInt("weather_code");
                    boolean isDay = cur.optInt("is_day", 1) == 1;

                    p.edit().putBoolean("valid", true)
                            .putLong("temp", Double.doubleToLongBits(temp))
                            .putInt("code", code)
                            .putBoolean("is_day", isDay)
                            .putString("city", cityName)
                            .putString("source", source)
                            .putString("config", cfg)
                            .putLong("updated", System.currentTimeMillis())
                            .apply();
                    version++;
                    Log.i(TAG, "Weather updated: " + cityName + " (" + source + ") "
                            + temp + "C code=" + code + " day=" + isDay);
                } catch (Throwable t) {
                    err = t.getMessage() != null ? t.getMessage() : "Weather error";
                    Log.w(TAG, "Weather update failed: " + t);
                }
                final String finalErr = err;
                MAIN.post(new Runnable() {
                    public void run() {
                        busy = false;
                        lastError = finalErr;
                        List<Runnable> waiting = new ArrayList<Runnable>(done);
                        done.clear();
                        for (Runnable r : waiting) {
                            try { r.run(); } catch (Throwable ignored) { }
                        }
                    }
                });
            }
        }, "homestead-weather").start();
    }

    static CityMatch geocodeFirst(String query, String lang) throws Exception {
        List<CityMatch> list = searchCities(query, lang, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    static List<CityMatch> searchCities(String query, String lang, int count) throws Exception {
        List<CityMatch> out = new ArrayList<CityMatch>();
        if (query == null || query.trim().isEmpty()) return out;
        String q = URLEncoder.encode(query.trim(), "UTF-8");
        String l = (lang != null && !lang.isEmpty()) ? URLEncoder.encode(lang, "UTF-8") : "en";
        String url = "https://geocoding-api.open-meteo.com/v1/search?name=" + q
                + "&count=" + count + "&language=" + l + "&format=json";
        JSONObject root = new JSONObject(new String(fetch(url), "UTF-8"));
        JSONArray results = root.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null) continue;
            out.add(new CityMatch(
                    o.optString("name", ""),
                    o.optString("country", ""),
                    o.optString("admin1", ""),
                    o.optDouble("latitude", 0),
                    o.optDouble("longitude", 0)));
        }
        return out;
    }

    private static URL allowed(String address) throws IOException {
        URL u = new URL(address);
        String host = u.getHost().toLowerCase(Locale.US);
        boolean ok = host.equals("api.open-meteo.com")
                || host.equals("geocoding-api.open-meteo.com")
                || host.equals("get.geojs.io");
        if (!"https".equals(u.getProtocol()) || !ok || u.getUserInfo() != null
                || (u.getPort() != -1 && u.getPort() != 443)) {
            throw new IOException("Unsupported weather host: " + host);
        }
        return u;
    }

    private static byte[] fetch(String address) throws IOException {
        URL url = allowed(address);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent", "Homestead/2 Weather");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_JSON) throw new IOException("Response too large");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
        } finally {
            conn.disconnect();
        }
    }

    static int conditionLabelRes(int code) {
        if (code == 0) return R.string.weather_cond_clear;
        if (code >= 1 && code <= 3) return R.string.weather_cond_cloudy;
        if (code == 45 || code == 48) return R.string.weather_cond_fog;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.string.weather_cond_rain;
        if ((code >= 71 && code <= 77) || (code == 85 || code == 86)) return R.string.weather_cond_snow;
        if (code >= 95) return R.string.weather_cond_storm;
        return R.string.weather_cond_cloudy;
    }
}
