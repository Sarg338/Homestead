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

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.util.DisplayMetrics;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The launcher's data, owned once per process so the overlay's and the activity's grids
 * share one set of artwork. Artwork is held as Bitmaps, which are safe to share across
 * ImageViews; Drawables are not (they carry per-instance state such as bounds).
 *
 * Everything here runs on the main thread.
 */
public final class AppModel {

    private static final String TAG = "Homestead";

    public static final int TYPE_APP = 0;
    public static final int TYPE_INPUT = 1;
    public static final int TYPE_AMAZON_HOME = 2;
    public static final int TYPE_SETTINGS = 3;
    public static final int TYPE_HEALTH = 4;
    /** Fire TV Live TV (antenna/cable tuner), started through Amazon's public TuneService. */
    public static final int TYPE_LIVETV = 5;

    static final String LIVETV_PKG = "com.amazon.tv.livetv";
    static final String LIVETV_SVC = "com.amazon.tv.livetv.TuneService";
    /** Item.key() of the Live TV tile; also how it is stored when hidden. */
    static final String LIVETV_KEY = "sys:livetv";
    static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    static final String AMAZON_SETTINGS = "com.amazon.tv.settings.v2";
    static final String AMAZON_HOME_CLS = "com.amazon.tv.launcher.ui.HomeActivity_vNext";

    /** Grid metrics: 32dp margins, 16dp gaps, 6 columns. */
    public static int columns(Context c) { return Prefs.columns(c); }

    /** Cell size depends on the column count, so a change must invalidate it. */
    public static void invalidateMetrics() { cellW = 0; cellH = 0; }
    private static final int MARGIN_DP = 32;
    private static final int GAP_DP = 16;
    private static final int ICON_PX = 96;
    private static final int CORNER_DP = 8;

    public static class Item {
        public int type;
        public String pkg;
        public String cls;
        public String inputId;
        public String label;
        /**
         * Declares LEANBACK_LAUNCHER or has banner art. Many Fire OS apps declare only
         * LAUNCHER plus android:banner, and still belong on the banner grid.
         */
        public boolean tvApp;
        public Bitmap banner;
        public Bitmap icon;
        public long lastUsed;
        /** Inputs and Live TV: line icon (res/drawable), tinted in code; 0 if none. */
        public int glyph;
        /** Inputs: the TV reports a device on this input. */
        public boolean connected;
        /** The banner's own dominant colour, for the home screen's ambient glow; 0 without one. */
        public int accent;

        /** Stable across a re-sort; the handoff carries this, never an index. */
        public String key() {
            if (type == TYPE_INPUT) return "in:" + inputId;
            if (type == TYPE_LIVETV) return LIVETV_KEY;
            if (type == TYPE_AMAZON_HOME) return "amz";
            if (type == TYPE_SETTINGS) return "sys:settings";
            if (type == TYPE_HEALTH) return "sys:health";
            return "app:" + pkg;
        }
    }

    private static final List<Item> ITEMS = new ArrayList<Item>();
    private static boolean loaded = false;
    private static String lastSignature = "";
    private static int cellW = 0, cellH = 0;

    private AppModel() { }

    /** Everything, including tiles the user has hidden. Settings needs this. */
    public static List<Item> allItems() { return ITEMS; }

    /** Whether the model has the Live TV tile, hidden or not. */
    public static boolean hasLiveTv() {
        for (int i = 0; i < ITEMS.size(); i++) {
            if (ITEMS.get(i).type == TYPE_LIVETV) return true;
        }
        return false;
    }

    /** What the grid should draw: everything except tiles the user hid. */
    public static List<Item> visibleItems(Context c) {
        java.util.Set<String> hidden = Prefs.hidden(c);
        List<Item> out = new ArrayList<Item>(ITEMS.size());
        for (int i = 0; i < ITEMS.size(); i++) {
            Item it = ITEMS.get(i);
            // Settings and TV Health are never hidden; they are the way to unhide.
            if (it.type == TYPE_SETTINGS || it.type == TYPE_HEALTH) { out.add(it); continue; }
            if (!hidden.contains(it.key())) out.add(it);
        }
        return out;
    }

    public static void ensureLoaded(Context c) {
        if (!loaded) reload(c);
    }

    /** Banner tile size in px, derived from the real panel width. */
    public static int cellW(Context c) { metrics(c); return cellW; }
    public static int cellH(Context c) { metrics(c); return cellH; }

    private static void metrics(Context c) {
        if (cellW > 0) return;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int margin = dp(c, MARGIN_DP);
        int gap = dp(c, GAP_DP);
        int cols = Prefs.columns(c);
        cellW = (dm.widthPixels - 2 * margin - (cols - 1) * gap) / cols;
        if (cellW <= 0) cellW = dp(c, 136);
        cellH = cellW * 9 / 16;
    }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------------ loading

    public static void reload(Context c) {
        long t0 = android.os.SystemClock.uptimeMillis();
        // Taken before loading: anything that appears during a slow load (HDMI inputs at
        // boot) then differs at the next check instead of matching it.
        String sig = signature(c);
        metrics(c);
        ITEMS.clear();

        loadInputs(c);

        // Fire OS icons are looked up by name, so a TV without one keeps the usual icon.
        boolean fire = Prefs.fireOsIcons(c);
        Drawable d;

        Item amazon = new Item();
        amazon.type = TYPE_AMAZON_HOME;
        amazon.label = "Amazon Home";
        d = fire ? fireOsIcon(c, AMAZON_LAUNCHER, "ic_home") : null;
        amazon.icon = toBitmap(c, d != null ? d : appIcon(c, AMAZON_LAUNCHER), ICON_PX, ICON_PX, false);
        ITEMS.add(amazon);

        Item settings = new Item();
        settings.type = TYPE_SETTINGS;
        settings.label = "TV Settings";
        d = fire ? fireOsIcon(c, AMAZON_LAUNCHER, "ic_settings_default_60dp") : null;
        settings.icon = d != null ? toBitmap(c, d, ICON_PX, ICON_PX, false) : gearIcon(ICON_PX);
        ITEMS.add(settings);

        Item health = new Item();
        health.type = TYPE_HEALTH;
        health.label = "TV Health";
        d = fire ? fireOsIcon(c, AMAZON_SETTINGS, "icon_device_margo") : null;
        health.icon = d != null ? toBitmap(c, d, ICON_PX, ICON_PX, false) : infoIcon(ICON_PX);
        ITEMS.add(health);
        d = null;
        boolean wide = Prefs.nonTvWide(c);

        PackageManager pm = c.getPackageManager();
        Set<String> seen = new HashSet<String>();
        String self = c.getPackageName();

        // LEANBACK first so a package that declares both is classified as a TV app.
        String[] cats = { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER };
        for (int ci = 0; ci < cats.length; ci++) {
            Intent probe = new Intent(Intent.ACTION_MAIN);
            probe.addCategory(cats[ci]);
            List<ResolveInfo> ris = pm.queryIntentActivities(probe, 0);
            if (ris == null) continue;
            for (int k = 0; k < ris.size(); k++) {
                ResolveInfo ri = ris.get(k);
                ActivityInfo ai = ri.activityInfo;
                if (ai == null) continue;
                if (self.equals(ai.packageName)) continue;
                // Amazon's launcher is reached through the "Amazon Home" tile, which also
                // stands the redirect down; listing it directly would just bounce back.
                if (AMAZON_LAUNCHER.equals(ai.packageName)) continue;
                if (!seen.add(ai.packageName)) continue;

                Item a = new Item();
                a.type = TYPE_APP;
                a.pkg = ai.packageName;
                a.cls = ai.name;
                CharSequence lb = ri.loadLabel(pm);
                a.label = (lb == null) ? ai.packageName : lb.toString();

                Drawable raw = null;
                try { raw = ai.loadBanner(pm); } catch (Throwable t) { raw = null; }
                a.banner = toBitmap(c, raw, cellW, cellH, true);
                raw = null;
                a.tvApp = (ci == 0) || a.banner != null;
                if (a.banner != null) a.accent = dominantColor(a.banner);
                // Icons are only shown when there is no banner, so only decode them then.
                if (a.banner == null) {
                    Drawable ic = null;
                    try { ic = ri.loadIcon(pm); } catch (Throwable t) { ic = null; }
                    if (wide) a.banner = iconTile(c, ic, cellW, cellH);
                    else a.icon = toBitmap(c, ic, ICON_PX, ICON_PX, false);
                    ic = null;
                }
                ITEMS.add(a);
            }
        }
        // Let the per-package Resources opened by loadBanner/loadIcon be reclaimed.
        System.gc();
        lastSignature = sig;
        loaded = true;
        resort(c);
        Log.i(TAG, "model loaded: " + ITEMS.size() + " items in "
                + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
    }

    /**
     * Whether changes to these packages could alter the grid: one of them is on it, or the
     * launchable apps or HDMI inputs differ from the last load. Decodes no artwork.
     */
    public static boolean affectsGrid(Context c, Set<String> changed) {
        if (!loaded) return true;
        for (int i = 0; i < ITEMS.size(); i++) {
            String p = ITEMS.get(i).pkg;
            if (p != null && changed.contains(p)) return true;
        }
        return !signature(c).equals(lastSignature);
    }

    /** Launchable activities and HDMI inputs, as a string to compare between loads. */
    private static String signature(Context c) {
        List<String> parts = new ArrayList<String>();
        try {
            PackageManager pm = c.getPackageManager();
            String[] cats = { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER };
            for (int ci = 0; ci < cats.length; ci++) {
                Intent probe = new Intent(Intent.ACTION_MAIN);
                probe.addCategory(cats[ci]);
                List<ResolveInfo> ris = pm.queryIntentActivities(probe, 0);
                if (ris == null) continue;
                for (int k = 0; k < ris.size(); k++) {
                    ActivityInfo ai = ris.get(k).activityInfo;
                    if (ai != null) parts.add(ci + ":" + ai.packageName + "/" + ai.name);
                }
            }
        } catch (Throwable t) {
            parts.add("apps failed");
        }
        try {
            TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            List<TvInputInfo> list = (tim == null) ? null : tim.getTvInputList();
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    TvInputInfo info = list.get(i);
                    if (info == null || !info.isPassthroughInput()) continue;
                    CharSequence lb = null;
                    try { lb = info.loadLabel(c); } catch (Throwable t) { }
                    parts.add("in:" + info.getId() + "=" + lb + "/" + inputConnected(tim, info.getId()));
                }
            }
        } catch (Throwable t) {
            parts.add("inputs failed");
        }
        if (liveTvAvailable(c)) parts.add("livetv");
        Collections.sort(parts);
        return parts.toString();
    }

    /**
     * True when this device has a built-in tuner and Amazon's Live TV TuneService, the same
     * service the remote's TV button starts. It is exported without a permission, so any
     * app can start it; it resumes the last watched channel.
     */
    static boolean liveTvAvailable(Context c) {
        try {
            Intent probe = new Intent();
            probe.setComponent(new ComponentName(LIVETV_PKG, LIVETV_SVC));
            if (c.getPackageManager().resolveService(probe, 0) == null) return false;
            TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            List<TvInputInfo> list = (tim == null) ? null : tim.getTvInputList();
            if (list == null) return false;
            for (int i = 0; i < list.size(); i++) {
                if (isHardwareTuner(list.get(i))) return true;
            }
        } catch (Throwable t) { }
        return false;
    }

    /**
     * A tuner in the TV itself. Channel apps (Tubi, Prime Video, Fire TV Channels...) also
     * register inputs of TYPE_TUNER, so the type alone would match on any device with one
     * of them. Android gives a hardware input the id "<service>/HW<n>"; an app's input id is
     * just its service name.
     */
    static boolean isHardwareTuner(TvInputInfo info) {
        if (info == null || info.isPassthroughInput()
                || info.getType() != TvInputInfo.TYPE_TUNER) return false;
        return isHardwareInputId(info.getId());
    }

    static boolean isHardwareInputId(String id) {
        if (id == null) return false;
        int slash = id.lastIndexOf('/');
        if (slash < 0 || !id.startsWith("HW", slash + 1) || id.length() == slash + 3) return false;
        for (int i = slash + 3; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) return false;
        }
        return true;
    }

    private static void loadInputs(Context c) {
        if (liveTvAvailable(c)) {
            Item tv = new Item();
            tv.type = TYPE_LIVETV;
            tv.label = c.getString(R.string.home_livetv);
            tv.glyph = R.drawable.ic_in_antenna;
            ITEMS.add(tv);
        }
        try {
            TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return;
            List<TvInputInfo> list = tim.getTvInputList();
            if (list == null) return;
            for (int i = 0; i < list.size(); i++) {
                TvInputInfo info = list.get(i);
                if (info == null || !info.isPassthroughInput()) continue;
                Item it = new Item();
                it.type = TYPE_INPUT;
                it.inputId = info.getId();
                CharSequence lb = null;
                try { lb = info.loadLabel(c); } catch (Throwable t) { }
                it.label = (lb == null || lb.length() == 0) ? "Input" : lb.toString();
                it.glyph = it.label.toUpperCase(Locale.US).startsWith("HDMI")
                        ? R.drawable.ic_in_hdmi : R.drawable.ic_in_composite;
                it.connected = inputConnected(tim, it.inputId);
                ITEMS.add(it);
            }
        } catch (Throwable t) { }
    }

    /** INPUT_STATE_CONNECTED only; disconnected, or a state the TV can't tell, reads as not. */
    private static boolean inputConnected(TvInputManager tim, String id) {
        if (android.os.Build.VERSION.SDK_INT < 24) return false;
        try {
            return tim.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Drawable drawable(Context c, int id) {
        try { return c.getResources().getDrawable(id); } catch (Throwable t) { return null; }
    }

    /**
     * A plain gear, for the Settings tile: a ring of evenly-spaced teeth around an open
     * centre, built from ovals and rotated rounded rects rather than a traced icon. Flat and
     * monochrome, matching every other tile's icon style - replaces
     * android.R.drawable.ic_menu_manage, one of the old pre-Material stock icons that read
     * as a system dialog glyph rather than part of this launcher.
     */
    private static Bitmap gearIcon(int px) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        float cx = px / 2f, cy = px / 2f;
        float outer = px * 0.40f, inner = px * 0.24f, hole = px * 0.14f;
        float toothW = px * 0.15f, toothH = px * 0.14f;
        int teeth = 8;

        Path gear = new Path();
        gear.addCircle(cx, cy, outer, Path.Direction.CW);
        RectF tooth = new RectF(cx - toothW / 2, cy - outer - toothH * 0.35f,
                cx + toothW / 2, cy - outer + toothH * 0.65f);
        for (int i = 0; i < teeth; i++) {
            Path t = new Path();
            t.addRoundRect(tooth, px * 0.02f, px * 0.02f, Path.Direction.CW);
            Matrix m = new Matrix();
            m.setRotate(360f / teeth * i, cx, cy);
            t.transform(m);
            gear.op(t, Path.Op.UNION);
        }
        Path ring = new Path();
        ring.addCircle(cx, cy, outer, Path.Direction.CW);
        ring.op(circle(cx, cy, inner), Path.Op.DIFFERENCE);
        gear.op(ring, Path.Op.INTERSECT);
        gear.op(circle(cx, cy, hole), Path.Op.UNION);
        gear.op(circle(cx, cy, hole * 0.4f), Path.Op.DIFFERENCE);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        cv.drawPath(gear, p);
        return bmp;
    }

    /** A circled "i", for the TV Health tile. Replaces android.R.drawable.ic_menu_info_details. */
    private static Bitmap infoIcon(int px) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        float cx = px / 2f, cy = px / 2f;
        float outer = px * 0.40f, ringW = px * 0.045f;

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(ringW);
        ring.setColor(0xFFFFFFFF);
        cv.drawCircle(cx, cy, outer - ringW / 2, ring);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFFFFFFFF);
        float dotR = px * 0.045f;
        cv.drawCircle(cx, cy - outer * 0.42f, dotR, fill);
        RectF stem = new RectF(cx - dotR, cy - outer * 0.12f, cx + dotR, cy + outer * 0.55f);
        cv.drawRoundRect(stem, dotR, dotR, fill);
        return bmp;
    }

    private static Path circle(float cx, float cy, float r) {
        Path p = new Path();
        p.addCircle(cx, cy, r, Path.Direction.CW);
        return p;
    }

    private static Drawable appIcon(Context c, String pkg) {
        try { return c.getPackageManager().getApplicationIcon(pkg); }
        catch (Throwable t) { return null; }
    }

    /**
     * A drawable from another app's resources on this TV, by name; null if it has none.
     * Tinted light: some are coloured by the owning app's theme, which we don't have.
     */
    private static Drawable fireOsIcon(Context c, String pkg, String name) {
        try {
            android.content.res.Resources r = c.getPackageManager().getResourcesForApplication(pkg);
            int id = r.getIdentifier(name, "drawable", pkg);
            if (id == 0) return null;
            Drawable d = r.getDrawable(id).mutate();
            d.setColorFilter(0xFFE6EEF8, android.graphics.PorterDuff.Mode.SRC_IN);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A banner for an app that has none, the way Fire TV's own home draws one: the icon,
     * full height, on a tile filled with the colour at the icon's left edge.
     */
    private static Bitmap iconTile(Context c, Drawable ic, int w, int h) {
        Bitmap icon = toBitmap(c, ic, h, h, false);
        if (icon == null) return null;
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            Path clip = new Path();
            float r = dp(c, CORNER_DP);
            clip.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
            cv.clipPath(clip);
            int edge = icon.getPixel(1, icon.getHeight() / 2);
            // A round or transparent icon has no edge colour; use a neutral card instead.
            cv.drawColor((edge >>> 24) < 0x80 ? 0xFF2A333D : (edge | 0xFF000000));
            cv.drawBitmap(icon, (w - icon.getWidth()) / 2f, (h - icon.getHeight()) / 2f, null);
            return bmp;
        } catch (Throwable t) {
            return null;
        } finally {
            icon.recycle();
        }
    }

    /**
     * Redraw into a display-sized bitmap so full-size art is not retained. Banners are
     * rounded here, once, rather than clipped on every frame.
     */
    private static Bitmap toBitmap(Context c, Drawable src, int maxW, int maxH, boolean round) {
        if (src == null) return null;
        try {
            int w = src.getIntrinsicWidth();
            int h = src.getIntrinsicHeight();
            if (w <= 0 || h <= 0) { w = maxW; h = maxH; }
            float s = Math.min((float) maxW / (float) w, (float) maxH / (float) h);
            if (s > 1f) s = 1f;
            int tw = Math.max(1, Math.round(w * s));
            int th = Math.max(1, Math.round(h * s));
            Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            if (round) {
                Path clip = new Path();
                float r = dp(c, CORNER_DP);
                clip.addRoundRect(new RectF(0, 0, tw, th), r, r, Path.Direction.CW);
                cv.clipPath(clip);
            }
            src.setBounds(0, 0, tw, th);
            src.draw(cv);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A single representative colour for the banner's ambient glow: average a sparse grid
     * of sample points (the banner is already shrunk to the grid cell, so this is cheap),
     * then push saturation and brightness up to a floor - a plain average is often a muddy
     * grey, and the glow needs to read clearly against a black background.
     */
    private static int dominantColor(Bitmap bmp) {
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (w <= 0 || h <= 0) return 0;
            int cols = 6, rows = 6;
            long r = 0, g = 0, b = 0;
            int n = 0;
            for (int yi = 0; yi < rows; yi++) {
                int y = Math.min(h - 1, (int) ((yi + 0.5f) * h / rows));
                for (int xi = 0; xi < cols; xi++) {
                    int x = Math.min(w - 1, (int) ((xi + 0.5f) * w / cols));
                    int p = bmp.getPixel(x, y);
                    if (android.graphics.Color.alpha(p) < 32) continue;
                    r += android.graphics.Color.red(p);
                    g += android.graphics.Color.green(p);
                    b += android.graphics.Color.blue(p);
                    n++;
                }
            }
            if (n == 0) return 0;
            float[] hsv = new float[3];
            android.graphics.Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
            hsv[1] = Math.min(1f, Math.max(hsv[1], 0.55f));
            hsv[2] = Math.min(1f, Math.max(hsv[2], 0.55f));
            return android.graphics.Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ ordering

    /** @return true if the visible order actually changed (i.e. views need rebuilding). */
    public static boolean resort(Context c) {
        String before = order();

        Map<String, UsageStats> stats;
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long end = System.currentTimeMillis();
            stats = usm.queryAndAggregateUsageStats(end - 90L * 24L * 3600L * 1000L, end);
        } catch (Throwable t) {
            stats = null;
        }
        for (int i = 0; i < ITEMS.size(); i++) {
            Item a = ITEMS.get(i);
            if (a.type != TYPE_APP) continue;
            // UsageStatsManager is optional (its appop must be granted from a shell). Fold
            // what it offers into UsageTracker and read from there, so ordering works
            // without it.
            if (stats != null) {
                UsageStats us = stats.get(a.pkg);
                if (us != null) UsageTracker.seed(c, a.pkg, us.getLastTimeUsed());
            }
            a.lastUsed = UsageTracker.lastUsed(c, a.pkg);
        }

        final boolean byLastUsed = (Prefs.sort(c) == Prefs.SORT_LAST_USED);
        Collections.sort(ITEMS, new Comparator<Item>() {
            public int compare(Item x, Item y) {
                if (x.type != y.type) return rank(x.type) - rank(y.type);
                if (x.type == TYPE_INPUT) {
                    boolean xh = x.label.toUpperCase(Locale.US).startsWith("HDMI");
                    boolean yh = y.label.toUpperCase(Locale.US).startsWith("HDMI");
                    if (xh != yh) return xh ? -1 : 1;
                    return x.label.compareToIgnoreCase(y.label);
                }
                if (x.type != TYPE_APP) return 0;
                if (x.tvApp != y.tvApp) return x.tvApp ? -1 : 1;
                if (!byLastUsed) return x.label.compareToIgnoreCase(y.label);
                if (x.lastUsed != y.lastUsed) return (x.lastUsed > y.lastUsed) ? -1 : 1;
                return x.label.compareToIgnoreCase(y.label);
            }
            private int rank(int t) {
                if (t == TYPE_LIVETV) return 0;
                if (t == TYPE_INPUT) return 1;
                if (t == TYPE_AMAZON_HOME || t == TYPE_SETTINGS || t == TYPE_HEALTH) return 2;
                return 3;
            }
        });
        return !before.equals(order());
    }

    static String order() {
        StringBuilder sb = new StringBuilder(ITEMS.size() * 12);
        for (int i = 0; i < ITEMS.size(); i++) sb.append(ITEMS.get(i).key()).append('|');
        return sb.toString();
    }

    public static void noteLaunched(Context c, Item it) {
        if (it == null || it.pkg == null) return;
        UsageTracker.record(c, it.pkg);
    }

    /**
     * True if this package is one of the apps we draw. Filters window events before usage
     * is recorded, so dialogs and system windows are not counted.
     */
    public static boolean hasApp(String pkg) {
        if (pkg == null) return false;
        for (int i = 0; i < ITEMS.size(); i++) {
            Item it = ITEMS.get(i);
            if (it.type == TYPE_APP && pkg.equals(it.pkg)) return true;
        }
        return false;
    }
}
