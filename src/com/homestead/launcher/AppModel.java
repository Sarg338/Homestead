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
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

    static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
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

        /** Stable across a re-sort; the handoff carries this, never an index. */
        public String key() {
            if (type == TYPE_INPUT) return "in:" + inputId;
            if (type == TYPE_AMAZON_HOME) return "amz";
            if (type == TYPE_SETTINGS) return "sys:settings";
            if (type == TYPE_HEALTH) return "sys:health";
            return "app:" + pkg;
        }
    }

    private static final List<Item> ITEMS = new ArrayList<Item>();
    private static boolean loaded = false;
    private static int cellW = 0, cellH = 0;

    private AppModel() { }

    /** Everything, including tiles the user has hidden. Settings needs this. */
    public static List<Item> allItems() { return ITEMS; }

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
        metrics(c);
        ITEMS.clear();

        loadInputs(c);

        Item amazon = new Item();
        amazon.type = TYPE_AMAZON_HOME;
        amazon.label = "Amazon Home";
        amazon.icon = toBitmap(c, appIcon(c, AMAZON_LAUNCHER), ICON_PX, ICON_PX, false);
        ITEMS.add(amazon);

        Item settings = new Item();
        settings.type = TYPE_SETTINGS;
        settings.label = "Settings";
        settings.icon = toBitmap(c, drawable(c, android.R.drawable.ic_menu_manage), ICON_PX, ICON_PX, false);
        ITEMS.add(settings);

        Item health = new Item();
        health.type = TYPE_HEALTH;
        health.label = "TV Health";
        health.icon = toBitmap(c, drawable(c, android.R.drawable.ic_menu_info_details), ICON_PX, ICON_PX, false);
        ITEMS.add(health);

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
                // Icons are only shown when there is no banner, so only decode them then.
                if (a.banner == null) {
                    Drawable ic = null;
                    try { ic = ri.loadIcon(pm); } catch (Throwable t) { ic = null; }
                    a.icon = toBitmap(c, ic, ICON_PX, ICON_PX, false);
                    ic = null;
                }
                ITEMS.add(a);
            }
        }
        // Let the per-package Resources opened by loadBanner/loadIcon be reclaimed.
        System.gc();
        loaded = true;
        resort(c);
        Log.i(TAG, "model loaded: " + ITEMS.size() + " items in "
                + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
    }

    private static void loadInputs(Context c) {
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
                ITEMS.add(it);
            }
        } catch (Throwable t) { }
    }

    private static Drawable drawable(Context c, int id) {
        try { return c.getResources().getDrawable(id); } catch (Throwable t) { return null; }
    }

    private static Drawable appIcon(Context c, String pkg) {
        try { return c.getPackageManager().getApplicationIcon(pkg); }
        catch (Throwable t) { return null; }
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
                if (t == TYPE_INPUT) return 0;
                if (t == TYPE_AMAZON_HOME || t == TYPE_SETTINGS || t == TYPE_HEALTH) return 1;
                return 2;
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
