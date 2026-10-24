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
 * The home screen's background: a preset, or a photo in the app's private storage, put
 * there through BackgroundProvider (from a computer or from PhotoPanel). Each look also sets
 * the header and focus-ring colours, since a dark ring vanishes on a dark background and
 * white text on a light one.
 *
 * Up to 1.7.1 a photo was also read from the app's folder on shared storage. Any app with
 * the storage permission can write there, so that folder is no longer read; a photo already
 * in it is copied into private storage once (adoptSharedPhotoOnce).
 */
final class Backgrounds {

    private static final String TAG = "Homestead";

    static final class Preset {
        /** What the setting saves. Never change or reuse one: users' choices are stored as it. */
        final String id;
        final String name;
        final int from, to;      // bottom-left to top-right; equal for a solid colour
        final int text;          // section headers and the clock
        final int ring;          // the focus ring around a tile
        /** A faint edge on every tile, so dark banners do not merge into a dark background. 0 for none. */
        final int outline;
        /** A soft glow in the middle fading to the edges, instead of the diagonal gradient. */
        final boolean radial;
        Preset(String id, String name, int from, int to, int text, int ring, int outline) {
            this(id, name, from, to, text, ring, outline, false);
        }
        Preset(String id, String name, int from, int to, int text, int ring, int outline,
                boolean radial) {
            this.id = id; this.name = name; this.from = from; this.to = to; this.text = text; this.ring = ring;
            this.outline = outline;
            this.radial = radial;
        }
    }

    private static final int EDGE = 0x3DFFFFFF;

    /**
     * In the order the Settings page lists them. Index 0 is the original look and the default.
     * The setting is saved by id, so presets can be added or moved without changing anyone's.
     */
    static final Preset[] PRESETS = {
            new Preset("sky",      "Sky",      0xFF6991C7, 0xFFA3BDED, 0xFFFFFFFF, 0xFF2C2C2C, 0),
            new Preset("midnight", "Midnight", 0xFF0B1F38, 0xFF1D4E86, 0xFFFFFFFF, 0xFFE6EEF8, EDGE),
            new Preset("charcoal", "Charcoal", 0xFF1A1B1F, 0xFF3A3C44, 0xFFFFFFFF, 0xFFE6E6E6, EDGE),
            new Preset("dusk",     "Dusk",     0xFF2A1B3D, 0xFF6B3E7C, 0xFFFFFFFF, 0xFFF0E6F5, EDGE),
            new Preset("black",    "Black",    0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFE6E6E6, EDGE),
            // Dark, with a faint glow in the middle rather than a flat black.
            new Preset("ember",    "Ember",    0xFF35101F, 0xFF000000, 0xFFFFFFFF, 0xFFF0E6EA, EDGE, true),
            new Preset("deepsea",  "Deep sea", 0xFF08303A, 0xFF000000, 0xFFFFFFFF, 0xFFE6F0F2, EDGE, true),
            // A flat dark navy, softer than black.
            new Preset("night",    "Night",    0xFF0E1726, 0xFF0E1726, 0xFFE8EEF6, 0xFFFFFFFF, EDGE),
    };

    /** The setting value for the photo. */
    static final String PHOTO_ID = "photo";
    /** The photo's text and ring colours, and its look until the photo has loaded. */
    static final Preset PHOTO_LOOK =
            new Preset(PHOTO_ID, "Photo", 0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFE6E6E6, EDGE);

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

    /**
     * Up to 1.6.4 the setting was a position in this list, the presets then the photo.
     * Fixed for good: it is how a value saved then is read, whatever PRESETS holds now.
     */
    private static final String[] BY_OLD_POSITION =
            { "sky", "midnight", "charcoal", "dusk", "black", PHOTO_ID };

    /** The chosen look's id. A choice from before ids is read through its old position. */
    static String currentId(Context c) {
        String id = Prefs.backgroundId(c);
        if (id != null) return id;
        int i = Prefs.backgroundPosition(c);
        // Out of range showed the default then too.
        return (i >= 0 && i < BY_OLD_POSITION.length) ? BY_OLD_POSITION[i] : PRESETS[0].id;
    }

    static Preset current(Context c) {
        String id = currentId(c);
        if (PHOTO_ID.equals(id)) return PHOTO_LOOK;
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].id.equals(id)) return PRESETS[i];
        }
        return PRESETS[0];
    }

    static boolean photoChosen(Context c) { return PHOTO_ID.equals(currentId(c)); }

    /**
     * Save a choice. Its old position is saved too where it had one, so going back to a
     * version from before ids keeps it.
     */
    static void choose(Context c, String id) {
        int old = -1;
        for (int i = 0; i < BY_OLD_POSITION.length; i++) {
            if (BY_OLD_POSITION[i].equals(id)) old = i;
        }
        Prefs.setBackground(c, id, old);
    }

    /** A look's name in the app's language: string bg_<id>, else its English name. */
    static String name(Context c, Preset p) {
        String n = displayName(c, p.id);
        return n.equals(p.id) ? p.name : n;
    }

    /** String bg_<key> in the app's language, or the key itself when there is none. */
    private static String displayName(Context c, String key) {
        int id = c.getResources().getIdentifier("bg_" + key.toLowerCase(java.util.Locale.US),
                "string", c.getPackageName());
        return id == 0 ? key : c.getString(id);
    }

    /** The value shown on the Settings row. */
    static String label(Context c) {
        if (!photoChosen(c)) return name(c, current(c));
        return c.getString(photoFile(c) != null ? R.string.bg_photo : R.string.bg_photo_none);
    }

    static int dimAlpha(Context c) {
        int d = Prefs.photoDim(c);
        return DIM[(d < 0 || d >= DIM.length) ? 1 : d];
    }

    static String dimLabel(Context c) {
        int d = Prefs.photoDim(c);
        return displayName(c, "dim_" + DIM_NAMES[(d < 0 || d >= DIM.length) ? 1 : d]);
    }

    /** The photo received through BackgroundProvider, or null. Private storage only. */
    static File photoFile(Context c) {
        try {
            File own = BackgroundProvider.photoFile(c);
            if (own.isFile() && own.length() > 0) return own;
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Once per install: a photo that 1.7.1 and earlier read from the app's folder on shared
     * storage is copied into private storage, so nobody loses one, and that folder is never
     * read again. A photo already in private storage is kept as it is.
     */
    static void adoptSharedPhotoOnce(Context c) {
        if (Prefs.sharedPhotoAdopted(c)) return;
        try {
            File dest = BackgroundProvider.photoFile(c);
            if (!(dest.isFile() && dest.length() > 0)) {
                File dir = c.getExternalFilesDir(null);
                for (int i = 0; dir != null && i < FILES.length; i++) {
                    File f = new File(dir, FILES[i]);
                    if (f.isFile() && f.length() > 0 && f.length() <= MAX_FILE) {
                        copy(f, dest);
                        Log.i(TAG, "background photo moved into private storage");
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "shared-storage photo not adopted: " + t);
        }
        Prefs.setSharedPhotoAdopted(c, true);
    }

    /** Through a temporary file, so a failed copy leaves no half photo. */
    private static void copy(File src, File dest) throws java.io.IOException {
        File part = new File(dest.getPath() + ".part");
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(part);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
            try { out.close(); } catch (Throwable ignored) { }
        }
        if (!part.renameTo(dest)) {
            part.delete();
            throw new java.io.IOException("cannot rename " + part);
        }
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

    static GradientDrawable drawable(Preset p) {
        if (p.radial) {
            // The glow sits left of centre, behind the first tile; its reach follows the screen.
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{ p.from, p.to }) {
                @Override protected void onBoundsChange(android.graphics.Rect r) {
                    super.onBoundsChange(r);
                    setGradientRadius(Math.max(r.width(), r.height()) * 0.78f);
                }
            };
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            g.setGradientCenter(0.36f, 0.42f);
            return g;
        }
        return new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                new int[]{ p.from, p.to });
    }
}
