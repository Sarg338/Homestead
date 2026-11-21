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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * For live weather: renders a fixed photorealistic 1080p landscape paired with subtle,
 * natural animated weather elements (rain, snow, lightning, twinkling stars, mist).
 * For video wallpapers: provides the static photorealistic fallback while the MP4 caches.
 */
final class AnimatedBackgroundDrawable extends Drawable {

    static final String ID_WEATHER = WeatherService.ID;
    static final String ID_AURORA = "anim_aurora";
    static final String ID_STARFIELD = "anim_starfield";
    static final String ID_WAVES = "anim_waves";

    private static final String TAG = "Homestead";
    private static final long FRAME_MS = 33L;
    private static final Map<String, Bitmap> CACHE = new HashMap<String, Bitmap>();
    private static final Map<String, Boolean> LOADING = new HashMap<String, Boolean>();

    private final Context ctx;
    private final String id;
    private final boolean weatherMode;
    private final long startMs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hudRect = new RectF();
    private final Matrix drawMatrix = new Matrix();
    private final Path boltPath = new Path();

    private int weatherVersion = -1;
    private WeatherService.State weather;

    private static final int STAR_COUNT = 70;
    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starR = new float[STAR_COUNT];
    private final float[] starPhase = new float[STAR_COUNT];
    private final float[] starSpeed = new float[STAR_COUNT];

    private static final int PRECIP_COUNT = 115;
    private final float[] dropX = new float[PRECIP_COUNT];
    private final float[] dropY = new float[PRECIP_COUNT];
    private final float[] dropSpeed = new float[PRECIP_COUNT];
    private final float[] dropDepth = new float[PRECIP_COUNT];

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running || getCallback() == null) {
                running = false;
                return;
            }
            invalidateSelf();
            handler.removeCallbacks(this);
            handler.postDelayed(this, FRAME_MS);
        }
    };

    AnimatedBackgroundDrawable(Context c, String id) {
        this.ctx = c.getApplicationContext();
        this.id = id;
        this.weatherMode = ID_WEATHER.equals(id);
        this.startMs = SystemClock.uptimeMillis();
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subTextPaint.setColor(0xFFD6E2F0);
        if (weatherMode) seedParticles();
    }

    private void seedParticles() {
        int s = 0x5F3759DF;
        for (int i = 0; i < STAR_COUNT; i++) {
            s = nextRand(s); starX[i] = unit(s);
            s = nextRand(s); starY[i] = unit(s) * 0.62f;
            s = nextRand(s); float m = unit(s);
            starR[i] = 0.7f + m * m * 1.6f;
            s = nextRand(s); starPhase[i] = unit(s) * 6.283f;
            s = nextRand(s); starSpeed[i] = 0.9f + unit(s) * 1.7f;
        }
        for (int i = 0; i < PRECIP_COUNT; i++) {
            s = nextRand(s); dropX[i] = unit(s);
            s = nextRand(s); dropY[i] = unit(s);
            s = nextRand(s); float d = unit(s);
            dropDepth[i] = 0.45f + d * 0.80f;
            dropSpeed[i] = 0.50f + d * 0.60f;
        }
    }

    private static int nextRand(int x) {
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        return x;
    }

    private static float unit(int x) {
        return (x & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!weatherMode) return changed;
        if (visible) {
            if (!running) {
                running = true;
                handler.removeCallbacks(tick);
                handler.postDelayed(tick, FRAME_MS);
            }
        } else {
            running = false;
            handler.removeCallbacks(tick);
        }
        return changed;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;

        if (weatherMode) {
            if (!running && isVisible() && getCallback() != null) {
                running = true;
                handler.removeCallbacks(tick);
                handler.postDelayed(tick, FRAME_MS);
            }
            int ver = WeatherService.version();
            if (weather == null || ver != weatherVersion) {
                weatherVersion = ver;
                weather = WeatherService.current(ctx);
            }
        }

        // Fixed, steady photorealistic landscape (no pan/zoom).
        Bitmap bmp = getOrLoad(assetForState());
        if (bmp != null) {
            float bw = bmp.getWidth();
            float bh = bmp.getHeight();
            float s = Math.max(w / bw, h / bh);
            drawMatrix.reset();
            drawMatrix.setScale(s, s);
            drawMatrix.postTranslate((w - bw * s) * 0.5f, (h - bh * s) * 0.5f);
            canvas.drawBitmap(bmp, drawMatrix, bmpPaint);
        } else {
            canvas.drawColor(0xFF060E1E);
        }

        if (weatherMode) {
            drawWeatherEffects(canvas, w, h, (SystemClock.uptimeMillis() - startMs) / 1000.0f);
        } else {
            canvas.drawColor(0x28000000);
        }
    }

    private String assetForState() {
        if (ID_AURORA.equals(id)) return "anim_bg_aurora.jpg";
        if (ID_STARFIELD.equals(id)) return "anim_bg_starfield.jpg";
        if (ID_WAVES.equals(id)) return "anim_bg_waves.jpg";
        int code = weather.weatherCode;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "anim_bg_weather_snow.jpg";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)
                || code >= 95 || code == 45 || code == 48) return "anim_bg_weather_rain.jpg";
        if (!weather.isDay) return "anim_bg_starfield.jpg";
        return "anim_bg_weather_day.jpg";
    }

    private void drawWeatherEffects(Canvas canvas, int w, int h, float t) {
        int code = weather.weatherCode;
        boolean day = weather.isDay;
        boolean clear = code == 0;
        boolean cloudy = code >= 1 && code <= 3;
        boolean fog = code == 45 || code == 48;
        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
        boolean storm = code >= 95;

        if (!day && (clear || cloudy)) {
            drawStars(canvas, w, h, t, clear ? 0.75f : 0.35f);
            drawShootingStar(canvas, w, h, t);
        }
        if (fog) {
            for (int i = 0; i < 3; i++) {
                float y = h * (0.35f + i * 0.18f);
                float off = (float) Math.sin(t * (0.16f + i * 0.05f) + i) * w * 0.08f;
                linePaint.setColor(0x22E2EAF2);
                linePaint.setStrokeWidth(h * 0.14f);
                canvas.drawLine(-w * 0.1f + off, y, w * 1.1f + off, y, linePaint);
            }
        }
        if (rain || storm) {
            drawRain(canvas, w, h, t, storm ? 1.35f : 1.0f);
        }
        if (snow) {
            drawSnow(canvas, w, h, t);
        }
        if (storm) {
            drawLightning(canvas, w, h, t);
        }
        canvas.drawColor(day ? 0x34000000 : 0x22000000);
    }

    private void drawStars(Canvas canvas, int w, int h, float t, float strength) {
        for (int i = 0; i < STAR_COUNT; i++) {
            float twinkle = 0.30f + 0.70f * (float) Math.sin(t * starSpeed[i] + starPhase[i]);
            int a = (int) (210 * strength * twinkle);
            if (a < 10) continue;
            shapePaint.setColor(Color.argb(a, 240, 246, 255));
            canvas.drawCircle(starX[i] * w, starY[i] * h, starR[i], shapePaint);
        }
    }

    private void drawShootingStar(Canvas canvas, int w, int h, float t) {
        float period = 7.0f;
        float local = t % period;
        if (local > 0.70f) return;
        int idx = (int) (t / period);
        float p = local / 0.70f;
        float sx = w * (0.18f + ((idx * 37) % 55) / 100f);
        float sy = h * (0.08f + ((idx * 23) % 24) / 100f);
        float dx = w * 0.24f;
        float dy = h * 0.14f;
        float hx = sx + dx * p;
        float hy = sy + dy * p;
        float tx = hx - dx * 0.30f * (1f - p * 0.4f);
        float ty = hy - dy * 0.30f * (1f - p * 0.4f);
        int a = (int) (215 * Math.sin(p * Math.PI));
        linePaint.setColor(Color.argb(a, 238, 246, 255));
        linePaint.setStrokeWidth(2.0f);
        canvas.drawLine(tx, ty, hx, hy, linePaint);
    }

    private void drawRain(Canvas canvas, int w, int h, float t, float mult) {
        float slant = w * 0.015f;
        float baseLen = h * 0.036f;
        for (int i = 0; i < PRECIP_COUNT; i++) {
            float d = dropDepth[i];
            float y = wrap(dropY[i] + dropSpeed[i] * mult * t * 1.1f) * (h + baseLen) - baseLen;
            float x = wrap(dropX[i] - dropSpeed[i] * t * 0.08f) * w;
            int a = (int) (75 + 90 * d);
            linePaint.setColor(Color.argb(a, 200, 224, 248));
            linePaint.setStrokeWidth(1.2f + d * 1.1f);
            canvas.drawLine(x, y, x - slant * d, y + baseLen * d, linePaint);
        }
    }

    private void drawSnow(Canvas canvas, int w, int h, float t) {
        for (int i = 0; i < PRECIP_COUNT; i++) {
            float d = dropDepth[i];
            float y = wrap(dropY[i] + dropSpeed[i] * t * 0.16f) * h;
            float sway = (float) Math.sin(t * (1.1f + d * 0.6f) + i) * w * (0.012f + d * 0.008f);
            float x = wrap(dropX[i] + t * 0.008f * d) * w + sway;
            int a = (int) (145 + 95 * d);
            shapePaint.setColor(Color.argb(a, 246, 250, 255));
            canvas.drawCircle(x, y, 2.0f + d * 2.8f, shapePaint);
        }
    }

    private void drawLightning(Canvas canvas, int w, int h, float t) {
        float period = 6.5f;
        float cycle = t % period;
        boolean f1 = cycle < 0.11f;
        boolean f2 = cycle > 0.21f && cycle < 0.32f;
        if (!f1 && !f2) return;
        canvas.drawColor(f1 ? 0x3ADCE8FF : 0x24DCE8FF);
        int idx = (int) (t / period);
        float bx = w * (0.22f + ((idx * 41) % 56) / 100f);
        float by = h * 0.12f;
        boltPath.reset();
        boltPath.moveTo(bx, by);
        boltPath.lineTo(bx - w * 0.022f, by + h * 0.14f);
        boltPath.lineTo(bx + w * 0.014f, by + h * 0.26f);
        boltPath.lineTo(bx - w * 0.028f, by + h * 0.42f);
        boltPath.lineTo(bx + w * 0.010f, by + h * 0.56f);
        linePaint.setColor(0x55A8CCFF);
        linePaint.setStrokeWidth(6.0f);
        canvas.drawPath(boltPath, linePaint);
        linePaint.setColor(0xEEFFFFFF);
        linePaint.setStrokeWidth(2.2f);
        canvas.drawPath(boltPath, linePaint);
    }

    private Bitmap getOrLoad(final String asset) {
        Bitmap b = CACHE.get(asset);
        if (b != null) return b;
        if (Boolean.TRUE.equals(LOADING.get(asset))) return null;
        LOADING.put(asset, Boolean.TRUE);
        new Thread(new Runnable() {
            public void run() {
                Bitmap decoded = null;
                try {
                    DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                    int maxW = Math.max(dm.widthPixels, 1920);
                    int maxH = Math.max(dm.heightPixels, 1080);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    InputStream in1 = ctx.getAssets().open(asset);
                    try { BitmapFactory.decodeStream(in1, null, o); } finally { in1.close(); }
                    int sample = 1;
                    while (o.outWidth / (sample * 2) >= maxW && o.outHeight / (sample * 2) >= maxH) {
                        sample *= 2;
                    }
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    InputStream in2 = ctx.getAssets().open(asset);
                    try { decoded = BitmapFactory.decodeStream(in2, null, o2); } finally { in2.close(); }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to load " + asset + ": " + t);
                }
                final Bitmap result = decoded;
                handler.post(new Runnable() {
                    public void run() {
                        LOADING.remove(asset);
                        if (result != null) CACHE.put(asset, result);
                        invalidateSelf();
                    }
                });
            }
        }, "homestead-anim-bg").start();
        return null;
    }

    private static float wrap(float v) {
        v = v % 1.0f;
        return v < 0 ? v + 1.0f : v;
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter colorFilter) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
