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
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;

/**
 * The home screen's background: a preset, or a photo copied into the app's own folder on
 * external storage, which needs no permission. Each look also sets the header and
 * focus-ring colours, since a dark ring vanishes on a dark background and white text on a
 * light one.
 */
final class Backgrounds {

    private static final String TAG = "Homestead";

    static final class Preset {
        final String name;
        final int from, to;      // bottom-left to top-right; equal for a solid colour
        final int text;          // section headers and the clock
        final int ring;          // the focus ring around a tile
        /** A faint edge on every tile, so dark banners do not merge into a dark background. 0 for none. */
        final int outline;
        Preset(String name, int from, int to, int text, int ring, int outline) {
            this.name = name; this.from = from; this.to = to; this.text = text; this.ring = ring;
            this.outline = outline;
        }
    }

    private static final int EDGE = 0x3DFFFFFF;

    /** Index 0 is the original look and the default. */
    static final Preset[] PRESETS = {
            new Preset("Sky",      0xFF6991C7, 0xFFA3BDED, 0xFFFFFFFF, 0xFF2C2C2C, 0),
            new Preset("Midnight", 0xFF0B1F38, 0xFF1D4E86, 0xFFFFFFFF, 0xFFE6EEF8, EDGE),
            new Preset("Charcoal", 0xFF1A1B1F, 0xFF3A3C44, 0xFFFFFFFF, 0xFFE6E6E6, EDGE),
            new Preset("Dusk",     0xFF2A1B3D, 0xFF6B3E7C, 0xFFFFFFFF, 0xFFF0E6F5, EDGE),
            new Preset("Black",    0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFE6E6E6, EDGE),
    };

    /** The setting value for the photo: the slot after the last preset. */
    static final int PHOTO = PRESETS.length;
    /** The photo's text and ring colours, and its look until the photo has loaded. */
    static final Preset PHOTO_LOOK =
            new Preset("Photo", 0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFE6E6E6, EDGE);

    private static final String[] FILES = { "background.jpg", "background.jpeg", "background.png" };
    /** Bigger files are ignored rather than decoded. */
    private static final long MAX_FILE = 25L * 1024L * 1024L;

    /** Alpha of the black layer over the photo, for Off, Light and Dark. */
    private static final int[] DIM = { 0x00, 0x59, 0x99 };
    static final String[] DIM_NAMES = { "Off", "Light", "Dark" };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Main thread only. One copy, shared by the activity's grid and the overlay's.
    private static Bitmap photo;
    private static String photoKey;
    private static boolean loading;

    private Backgrounds() { }

    static Preset current(Context c) {
        int i = Prefs.background(c);
        if (i == PHOTO) return PHOTO_LOOK;
        if (i < 0 || i > PHOTO) i = 0;
        return PRESETS[i];
    }

    static boolean photoChosen(Context c) { return Prefs.background(c) == PHOTO; }

    /** The next choice in the Settings cycle: the presets, then the photo. */
    static int next(Context c) {
        int i = Prefs.background(c);
        return (i >= PHOTO || i < 0) ? 0 : i + 1;
    }

    /** The value shown on the Settings row. */
    static String label(Context c) {
        if (!photoChosen(c)) return current(c).name;
        return photoFile(c) != null ? "Photo" : "Photo (no file yet)";
    }

    static int dimAlpha(Context c) {
        int d = Prefs.photoDim(c);
        return DIM[(d < 0 || d >= DIM.length) ? 1 : d];
    }

    static String dimLabel(Context c) {
        int d = Prefs.photoDim(c);
        return DIM_NAMES[(d < 0 || d >= DIM.length) ? 1 : d];
    }

    /** The photo in the app's folder, or null. */
    static File photoFile(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return null;
            for (int i = 0; i < FILES.length; i++) {
                File f = new File(dir, FILES[i]);
                if (f.isFile() && f.length() > 0) return f;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Identifies one version of the file, so a replaced photo is noticed. */
    static String keyOf(File f) {
        return (f == null) ? null : f.getName() + "|" + f.length() + "|" + f.lastModified();
    }

    /** The decoded photo if it matches this file version, else null. Main thread. */
    static Bitmap photoFor(String key) {
        return (key != null && key.equals(photoKey)) ? photo : null;
    }

    /** Whatever photo was last decoded, shown while a replaced file loads. Main thread. */
    static Bitmap lastPhoto() { return photo; }

    /**
     * Let go of the decoded photo (about 8 MB at 1080p) once it is not shown: another look
     * was chosen or the file was removed. Not recycled, since the other grid may still be
     * drawing it until its next repaint. Main thread.
     */
    static void drop() {
        photo = null;
        photoKey = null;
    }

    /**
     * Decode the photo on a worker and run done on the main thread when it lands. Does
     * nothing if that version is already decoded or a decode is running. Main thread.
     */
    static void load(Context c, final File f, final Runnable done) {
        final String key = keyOf(f);
        if (key == null || key.equals(photoKey) || loading) return;
        loading = true;
        final Context app = c.getApplicationContext();
        final DisplayMetrics dm = c.getResources().getDisplayMetrics();
        new Thread(new Runnable() {
            public void run() {
                final Bitmap b = decode(f, dm.widthPixels, dm.heightPixels);
                MAIN.post(new Runnable() {
                    public void run() {
                        loading = false;
                        // The user moved on while it decoded: do not keep it.
                        if (!photoChosen(app)) return;
                        photo = b;
                        photoKey = key;
                        Log.i(TAG, "background photo " + (b == null ? "could not be read"
                                : "loaded at " + b.getWidth() + "x" + b.getHeight()));
                        if (done != null) done.run();
                    }
                });
            }
        }, "homestead-photo").start();
    }

    /** Scaled and centre-cropped to the screen, so nothing larger stays in memory. */
    private static Bitmap decode(File f, int dw, int dh) {
        try {
            if (f.length() > MAX_FILE) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= dw && o.outHeight / (sample * 2) >= dh) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
            if (src == null) return null;
            float s = Math.max((float) dw / src.getWidth(), (float) dh / src.getHeight());
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate((dw - src.getWidth() * s) / 2f, (dh - src.getHeight() * s) / 2f);
            Bitmap out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "background photo failed: " + t);
            return null;
        }
    }

    /** The photo under a black layer at the chosen dim. */
    static Drawable photoDrawable(Context c, Bitmap b) {
        Drawable img = new BitmapDrawable(c.getResources(), b);
        int a = dimAlpha(c);
        if (a == 0) return img;
        return new LayerDrawable(new Drawable[]{ img, new ColorDrawable(a << 24) });
    }

    /**
     * Create the app's folder on external storage, so a custom background can be copied
     * there with adb push. Android only creates it once something asks for it.
     */
    static void ensureFolder(Context c) {
        try { c.getExternalFilesDir(null); } catch (Throwable ignored) { }
    }

    static Drawable drawable(Preset p) {
        return new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                new int[]{ p.from, p.to });
    }
}
