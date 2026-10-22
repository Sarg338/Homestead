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
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The launcher UI, built against a plain Context so the same screen can be hosted by an
 * Activity or a WindowManager overlay.
 *
 * Sections (headers, scrolling card rows, a banner-tile grid) are plain focusable views in
 * a ScrollView rather than a GridView: no recycling, so each view is its item and focus
 * survives a re-sort by key. Data and bitmaps come from the process-wide AppModel.
 */
public class HomeGrid implements SettingsPanel.Host {

    public interface Listener {
        /** Called before the item is acted on, so an overlay host can dismiss itself. */
        void onPick(AppModel.Item it);
    }

    /**
     * Set as a panel root's tag to handle Back itself: return true to consume it, false to
     * let the panel close.
     */
    public interface BackHandler { boolean onBack(); }

    // Layout metrics.
    private static final int MARGIN_DP = 32;
    private static final int GAP_DP = 16;
    private static final int CARD_W_DP = 167;   // wide card, non-TV row
    private static final int CARD_H_DP = 94;
    private static final int HEADER_SP = 21;
    private static final int HEADER_TOP_DP = 28;
    private static final int HEADER_BOTTOM_DP = 12;
    /** AppModel.Item.key() of the Live TV tile. */
    private static final String LIVETV_KEY = "sys:livetv";

    private final Context ctx;
    private final Listener listener;

    public final FrameLayout root;
    private final ScrollView scroll;
    private final LinearLayout column;
    private final TextView clock;

    private final Map<String, View> cellsByKey = new HashMap<String, View>();
    /** The horizontally scrolling section rows, in build order. */
    private final List<HorizontalScrollView> rows = new ArrayList<HorizontalScrollView>();
    private View focusedCell;
    private String focusedKey;
    /** AppModel.order() as of the last build, to spot a re-sort done by the other grid. */
    private String builtOrder;
    /** Settings / TV Health render inside this window; Back pops one level. */
    private final List<View> panels = new ArrayList<View>();
    /** For each open panel, the view that had focus when the next one opened over it. */
    private final List<View> panelFocus = new ArrayList<View>();
    /** The last key released here, logged when a panel opens from a tile. */
    private int lastKey;
    private int lastKeyDevice;
    private int lastKeySource;
    private long lastKeyAt;
    /** Shared by the overlay and activity grids, so presses across the handoff still count. */
    private static final MenuTaps menuTaps = new MenuTaps();

    /** Simple mode's way into Settings: Menu pressed five times, each within 2 s of the last. */
    static final class MenuTaps {
        static final int COUNT = 5;
        private int n;
        private long last;

        /** @return true on the press that completes the sequence. */
        boolean tap() {
            long now = android.os.SystemClock.uptimeMillis();
            n = (now - last <= 2000L) ? n + 1 : 1;
            last = now;
            if (n < COUNT) return false;
            n = 0;
            return true;
        }
    }

    public HomeGrid(Context c, Listener l) {
        this.ctx = c;
        this.listener = l;

        // Overridden so Back and Menu reach us before the focused child consumes them; a
        // key listener on the root would never fire.
        root = new FrameLayout(c) {
            @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
                if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    lastKey = e.getKeyCode();
                    lastKeyDevice = e.getDeviceId();
                    lastKeySource = e.getSource();
                    lastKeyAt = android.os.SystemClock.uptimeMillis();
                }
                if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK && !panels.isEmpty()) {
                    if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                        View top = panels.get(panels.size() - 1);
                        Object t = top.getTag();
                        if (t instanceof BackHandler && ((BackHandler) t).onBack()) return true;
                        closePanel();
                    }
                    return true;
                }
                // Menu hides the focused tile, matching the stock Fire TV launcher's
                // convention (long-press also works). In Simple mode it hides nothing, and
                // five presses in a row open Settings instead.
                if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_MENU && panels.isEmpty()) {
                    if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                        if (!Prefs.simpleMode(ctx)) hideFocused();
                        else if (menuTaps.tap()) {
                            logOpen("Settings (Menu x" + MenuTaps.COUNT + ")");
                            showPanel(SettingsPanel.build(ctx, HomeGrid.this));
                        }
                    }
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        root.setClipChildren(false);

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(dp(MARGIN_DP), dp(46), dp(MARGIN_DP), dp(MARGIN_DP));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        clock = new TextView(c);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        clp.topMargin = dp(14);
        clp.rightMargin = dp(33);
        root.addView(clock, clp);

        AppModel.ensureLoaded(c);
        Backgrounds.ensureFolder(c);
        applyBackground();
        build();
        updateClock();
    }

    /** The chosen look; the overlay and the activity re-read it when they come up. */
    private Backgrounds.Preset look;
    /** What is painted now, so an unchanged setting costs only a file stat. */
    private String painted;

    /**
     * Read the background setting and repaint what depends on it. Cheap; no rebuild. A
     * photo is decoded off the main thread, and this runs again once it has loaded.
     */
    public void applyBackground() {
        Backgrounds.Preset p = Backgrounds.current(ctx);
        Bitmap photo = null;
        String state = p.name;
        if (!Backgrounds.photoChosen(ctx)) {
            Backgrounds.drop();
        } else {
            java.io.File f = Backgrounds.photoFile(ctx);
            if (f == null) Backgrounds.drop();
            String key = Backgrounds.keyOf(f);
            photo = Backgrounds.photoFor(key);
            if (f != null && photo == null) {
                Backgrounds.load(ctx, f, new Runnable() {
                    public void run() { applyBackground(); }
                });
                // Keep the previous photo up until the replaced one has loaded.
                photo = Backgrounds.lastPhoto();
                key = "loading";
            }
            state = "photo|" + (photo == null ? "none" : key) + "|" + Backgrounds.dimAlpha(ctx);
        }
        if (state.equals(painted)) return;
        painted = state;
        look = p;
        root.setBackground(photo != null ? Backgrounds.photoDrawable(ctx, photo)
                : Backgrounds.drawable(p));
        paintText(clock);
        for (int i = 0; i < column.getChildCount(); i++) {
            View v = column.getChildAt(i);
            if (v instanceof TextView) paintText((TextView) v);
        }
        // The ring and the outline are per look, so every cell is repainted.
        for (View cell : cellsByKey.values()) style(cell, cell == focusedCell);
    }

    /** Header and clock colour; on a photo, a soft shadow keeps them readable. */
    private void paintText(TextView t) {
        t.setTextColor(look.text);
        if (look == Backgrounds.PHOTO_LOOK) t.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        else t.setShadowLayer(0, 0, 0, 0);
    }

    private int dp(int v) { return AppModel.dp(ctx, v); }

    // ---------------------------------------------------------------- public API

    public void updateClock() {
        int mode = Prefs.clockMode(ctx);
        if (mode == Prefs.CLOCK_OFF) { clock.setText(""); return; }
        String fmt = (mode == Prefs.CLOCK_12H) ? "h:mm a" : "HH:mm";
        clock.setText(new SimpleDateFormat(fmt, Locale.getDefault()).format(new Date()));
    }

    /** Focus for a fresh Home press: Live TV when it comes first, else as takeFocus(). */
    public void takeStartFocus() {
        if (Prefs.liveTvFirst(ctx) && selectKey(LIVETV_KEY)) {
            scroll.scrollTo(0, 0);
            return;
        }
        takeFocus();
    }

    public void takeFocus() {
        if (focusedKey != null && selectKey(focusedKey)) return;
        List<AppModel.Item> items = AppModel.visibleItems(ctx);
        if (!items.isEmpty()) selectKey(items.get(0).key());
    }

    // ------------------------------------------------------------ SettingsPanel.Host

    public boolean isPanelOpen() { return !panels.isEmpty(); }

    /**
     * Hide whatever tile currently has focus.
     * @return true if something was hidden.
     */
    public boolean hideFocused() {
        if (focusedKey == null) return false;
        AppModel.Item target = null;
        List<AppModel.Item> all = AppModel.allItems();
        for (int i = 0; i < all.size(); i++) {
            if (focusedKey.equals(all.get(i).key())) { target = all.get(i); break; }
        }
        if (target == null) return false;
        // Settings and TV Health stay reachable so hidden tiles can always be restored.
        if (target.type == AppModel.TYPE_SETTINGS || target.type == AppModel.TYPE_HEALTH) {
            return false;
        }
        Prefs.setHidden(ctx, target.key(), true);
        try {
            android.widget.Toast.makeText(ctx,
                    target.label + " hidden - restore it in Settings",
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) { }
        rebuild();
        return true;
    }

    public void showPanel(final View v) {
        // Stop the grid, and any panel underneath, from stealing D-pad focus.
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        View under = panels.isEmpty() ? null : panels.get(panels.size() - 1);
        // Taken first: blocking and hiding the page below can clear its focus.
        if (under != null) panelFocus.set(panelFocus.size() - 1, under.findFocus());
        if (under instanceof ViewGroup) {
            ((ViewGroup) under).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        // Only the top page is drawn, so a sheet over a sheet does not dim the grid twice.
        if (under != null) under.setVisibility(View.INVISIBLE);
        root.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panels.add(v);
        panelFocus.add(null);
        v.post(new Runnable() { public void run() { v.requestFocus(); } });
    }

    public void closePanel() {
        if (panels.isEmpty()) return;
        View top = panels.remove(panels.size() - 1);
        panelFocus.remove(panelFocus.size() - 1);
        root.removeView(top);
        if (panels.isEmpty()) {
            scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            takeFocus();
        } else {
            final View below = panels.get(panels.size() - 1);
            below.setVisibility(View.VISIBLE);
            if (below instanceof ViewGroup) {
                ((ViewGroup) below).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            }
            // Back on the row that opened the closed page, if it is still there.
            final View was = panelFocus.get(panelFocus.size() - 1);
            below.post(new Runnable() {
                public void run() {
                    if (was == null || !was.isAttachedToWindow() || !was.requestFocus()) {
                        below.requestFocus();
                    }
                }
            });
        }
    }

    /**
     * Back to the plain grid. A Home press lands here, not on a page left open earlier.
     * The first-run wizard stays, since losing it halfway through setup is worse.
     */
    public void closePanels() {
        while (!panels.isEmpty()) {
            if (!Prefs.onboarded(ctx)
                    && panels.get(panels.size() - 1).getTag() instanceof BackHandler) {
                return;
            }
            closePanel();
        }
    }

    public void rebuildGrid() {
        AppModel.invalidateMetrics();
        applyBackground();
        rebuild();
    }

    /** Carried across the overlay -> activity handoff so the highlight does not jump. */
    public String getSelectedKey() { return focusedKey; }

    /** Also carried across the handoff; restoring focus alone does not restore scroll. */
    public int getScrollY() { return scroll.getScrollY(); }

    /** Horizontal offset of each card row. */
    public int[] getRowScrollX() {
        int[] out = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) out[i] = rows.get(i).getScrollX();
        return out;
    }

    /**
     * Apply scroll offsets after layout: scrollTo() before layout is clamped to 0. The
     * second post covers a late re-layout caused by restoring focus.
     */
    public void applyScroll(final int y, final int[] rowX) {
        if (y <= 0 && rowX == null) return;
        Runnable apply = new Runnable() {
            public void run() {
                if (y > 0) scroll.scrollTo(0, y);
                if (rowX != null) {
                    for (int i = 0; i < rows.size() && i < rowX.length; i++) {
                        if (rowX[i] > 0) rows.get(i).scrollTo(rowX[i], 0);
                    }
                }
            }
        };
        final Runnable again = apply;
        scroll.post(new Runnable() {
            public void run() { again.run(); scroll.post(again); }
        });
    }

    /** Re-select by identity. */
    public boolean selectKey(String key) {
        if (key == null) return false;
        View v = cellsByKey.get(key);
        if (v == null) return false;
        v.requestFocus();
        return true;
    }

    /** Re-sort; rebuilds only if the order changed. @return true if rebuilt. */
    public boolean resort() {
        String keep = focusedKey;
        int y = scroll.getScrollY();
        int[] rx = getRowScrollX();
        if (!AppModel.resort(ctx)) return false;
        build();
        if (panels.isEmpty() && keep != null) selectKey(keep);
        applyScroll(y, rx);
        return true;
    }

    /**
     * The overlay and the activity each have a grid over one shared model, so a re-sort by
     * one leaves the other stale. Rebuild if that happened. @return true if rebuilt.
     */
    public boolean syncOrder() {
        if (AppModel.order().equals(builtOrder)) return false;
        rebuild();
        return true;
    }

    /** Rebuild the views over the current model; nothing is re-decoded. */
    public void rebuild() {
        String keep = focusedKey;
        int y = scroll.getScrollY();
        int[] rx = getRowScrollX();
        build();
        // Leave focus alone while a panel is open, or the grid would take it from the panel.
        if (panels.isEmpty()) {
            if (keep == null || !selectKey(keep)) takeFocus();
        }
        applyScroll(y, rx);
    }

    // ---------------------------------------------------------------- layout

    /**
     * Layout reads metadata from other packages, so one malformed package must not leave a
     * blank home screen. On any failure, draw a minimal row with Settings and TV Health.
     */
    private void build() {
        builtOrder = AppModel.order();
        try {
            buildSections();
        } catch (Throwable t) {
            android.util.Log.e("Homestead", "grid build failed, drawing fallback", t);
            try { buildFallback(); } catch (Throwable ignored) { }
        }
    }

    /** Enough to get to Settings, using nothing that can throw. */
    private void buildFallback() {
        column.removeAllViews();
        cellsByKey.clear();
        rows.clear();
        focusedCell = null;

        List<AppModel.Item> safe = new ArrayList<AppModel.Item>();
        List<AppModel.Item> all = AppModel.allItems();
        for (int i = 0; i < all.size(); i++) {
            int t = all.get(i).type;
            if (t == AppModel.TYPE_SETTINGS || t == AppModel.TYPE_HEALTH
                    || t == AppModel.TYPE_AMAZON_HOME) {
                safe.add(all.get(i));
            }
        }
        addHeader("Homestead", true);
        if (!safe.isEmpty()) addCardRow(safe);
    }

    private void buildSections() {
        column.removeAllViews();
        cellsByKey.clear();
        rows.clear();
        focusedCell = null;

        List<AppModel.Item> inputs = new ArrayList<AppModel.Item>();
        List<AppModel.Item> nonTv = new ArrayList<AppModel.Item>();
        List<AppModel.Item> tv = new ArrayList<AppModel.Item>();

        // Live TV first takes the Live TV tile out of its section and puts it above them all.
        boolean tvFirst = Prefs.liveTvFirst(ctx);
        AppModel.Item liveTv = null;

        List<AppModel.Item> all = AppModel.visibleItems(ctx);
        for (int i = 0; i < all.size(); i++) {
            AppModel.Item it = all.get(i);
            if (tvFirst && it.type == AppModel.TYPE_LIVETV) {
                liveTv = it;
            } else if (it.type == AppModel.TYPE_INPUT || it.type == AppModel.TYPE_LIVETV
                    || it.type == AppModel.TYPE_AMAZON_HOME
                    || it.type == AppModel.TYPE_SETTINGS || it.type == AppModel.TYPE_HEALTH) {
                inputs.add(it);
            } else if (it.tvApp && it.banner != null) {
                tv.add(it);
            } else {
                nonTv.add(it);
            }
        }

        // Settings and TV Health survive the section being switched off.
        if (!Prefs.showInputs(ctx)) {
            List<AppModel.Item> keep = new ArrayList<AppModel.Item>();
            for (int i = 0; i < inputs.size(); i++) {
                int t = inputs.get(i).type;
                if (t == AppModel.TYPE_SETTINGS || t == AppModel.TYPE_HEALTH) keep.add(inputs.get(i));
            }
            inputs = keep;
        }
        if (!Prefs.showNonTv(ctx)) nonTv.clear();

        // Simple mode drops them too, unless nothing else is left: an empty grid has
        // nothing to focus, so the Menu presses that open Settings may not reach us.
        if (Prefs.simpleMode(ctx)) {
            List<AppModel.Item> rest = new ArrayList<AppModel.Item>();
            for (int i = 0; i < inputs.size(); i++) {
                int t = inputs.get(i).type;
                if (t != AppModel.TYPE_SETTINGS && t != AppModel.TYPE_HEALTH) rest.add(inputs.get(i));
            }
            if (!rest.isEmpty() || !nonTv.isEmpty() || !tv.isEmpty() || liveTv != null) {
                inputs = rest;
            }
        }

        String inputsTitle = ctx.getString(R.string.home_sec_inputs);
        String nonTvTitle = ctx.getString(R.string.home_sec_nontv);
        String tvTitle = ctx.getString(R.string.home_sec_tv);
        boolean first = true;
        if (tvFirst) {
            if (liveTv != null)    { addLiveTvTile(liveTv); first = false; }
            if (!tv.isEmpty())     { addHeader(tvTitle, first); first = false; addTileGrid(tv); }
            if (!inputs.isEmpty()) { addHeader(inputsTitle, first); first = false; addCardRow(inputs); }
            if (!nonTv.isEmpty())  { addHeader(nonTvTitle, first); addCardRow(nonTv); }
            return;
        }
        if (!inputs.isEmpty()) { addHeader(inputsTitle, first); first = false; addCardRow(inputs); }
        if (!nonTv.isEmpty())  { addHeader(nonTvTitle, first); first = false; addCardRow(nonTv); }
        if (!tv.isEmpty())     { addHeader(tvTitle, first); addTileGrid(tv); }
    }

    /** Live TV first: one large tile, two banner cells wide, on its own row. */
    private void addLiveTvTile(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        GradientDrawable card = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF168494, 0xFF0A3642 });
        card.setCornerRadius(dp(8));
        cell.setBackground(card);

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(dp(24), dp(12), dp(24), dp(12));

        TextView title = new TextView(ctx);
        title.setText(it.label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.addView(title);

        TextView hint = new TextView(ctx);
        hint.setText(ctx.getString(R.string.home_livetv_hint));
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.addView(hint);

        cell.addView(text, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int w = AppModel.cellW(ctx) * 2 + dp(GAP_DP);
        int h = AppModel.cellH(ctx) * 3 / 2;
        column.addView(cell, new LinearLayout.LayoutParams(w, h));
    }

    private void addHeader(String text, boolean first) {
        TextView h = new TextView(ctx);
        h.setText(text);
        paintText(h);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_SP);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = first ? 0 : dp(HEADER_TOP_DP);
        lp.bottomMargin = dp(HEADER_BOTTOM_DP);
        column.addView(h, lp);
    }

    /** Wide cards in a horizontally scrolling row. */
    private void addCardRow(List<AppModel.Item> items) {
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        rows.add(hs);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (int i = 0; i < items.size(); i++) {
            View cell = buildCard(items.get(i));
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP));
            if (i > 0) lp.leftMargin = dp(GAP_DP);
            row.addView(cell, lp);
        }
        hs.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 6-column grid of banner tiles, built as plain rows. */
    private void addTileGrid(List<AppModel.Item> items) {
        int w = AppModel.cellW(ctx);
        int h = AppModel.cellH(ctx);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % AppModel.columns(ctx) == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(GAP_DP);
                column.addView(row, rlp);
            }
            View cell = buildTile(items.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i % AppModel.columns(ctx) != 0) lp.leftMargin = dp(GAP_DP);
            row.addView(cell, lp);
        }
    }

    // ---------------------------------------------------------------- cells

    private FrameLayout newCell(final AppModel.Item it) {
        final FrameLayout cell = new FrameLayout(ctx);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setClipChildren(false);
        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has) {
                    focusedCell = v;
                    focusedKey = it.key();
                    style(v, true);
                } else {
                    style(v, false);
                }
            }
        });
        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Settings and TV Health open a panel in this window, so they are not
                // reported as a pick; that would make an overlay host dismiss itself.
                boolean leaves = it.type != AppModel.TYPE_SETTINGS
                        && it.type != AppModel.TYPE_HEALTH;
                if (listener != null && leaves) listener.onPick(it);
                act(it);
            }
        });
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            // In Simple mode a held Select falls through to a normal click.
            public boolean onLongClick(View v) {
                return !Prefs.simpleMode(ctx) && hideFocused();
            }
        });
        cellsByKey.put(it.key(), cell);
        style(cell, false);
        return cell;
    }

    /** The preset's edge around an unfocused cell, or null when the preset has none. */
    private Drawable outline() {
        if (look == null || look.outline == 0) return null;
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        d.setStroke(dp(1), look.outline);
        d.setColor(Color.TRANSPARENT);
        return d;
    }

    /**
     * Focus treatment: 1.1x scale and a ring in the preset's colour (dark on the light
     * default, since white is invisible on white banners; light on the dark presets). Set
     * directly rather than via a selector so it survives the window losing focus during
     * the overlay -> activity handoff.
     */
    private void style(View v, boolean focused) {
        if (!(v instanceof FrameLayout)) return;
        FrameLayout f = (FrameLayout) v;
        if (focused) {
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(8));
            ring.setStroke(dp(3), look.ring);
            ring.setColor(Color.TRANSPARENT);
            f.setForeground(ring);
            f.setScaleX(1.10f);
            f.setScaleY(1.10f);
            f.setTranslationZ(dp(8));
        } else {
            f.setForeground(outline());
            f.setScaleX(1f);
            f.setScaleY(1f);
            f.setTranslationZ(0f);
        }
    }

    /** A TV app: full-bleed banner, already rounded in the bitmap. */
    private View buildTile(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        img.setImageBitmap(it.banner);
        cell.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return cell;
    }

    /** An input, Amazon Home, or a non-TV app: icon + label on a card. */
    private View buildCard(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(dp(8));
        boolean textTile = it.type == AppModel.TYPE_INPUT || it.type == AppModel.TYPE_LIVETV;
        // Live TV first: inputs go a quiet grey so only the Live TV tile stands out, and
        // app labels get larger and brighter.
        boolean tvFirst = Prefs.liveTvFirst(ctx);
        if (textTile) card.setColor(tvFirst ? 0xFF2A333D : 0xFF0E5A63);
        else card.setColor(0xFF033058);
        cell.setBackground(card);

        if (textTile) {
            TextView tv = new TextView(ctx);
            tv.setText(it.label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tv.setGravity(Gravity.CENTER);
            cell.addView(tv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return cell;
        }

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        ImageView ic = new ImageView(ctx);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (it.icon != null) ic.setImageBitmap(it.icon);
        int isz = dp(CARD_H_DP) * 62 / 100;
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(isz, isz);
        ilp.rightMargin = dp(9);
        row.addView(ic, ilp);

        TextView tv = new TextView(ctx);
        tv.setText(it.label);
        tv.setTextColor(tvFirst ? 0xFFD5DDE5 : 0xFFA2AFBB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, tvFirst ? 14 : 12);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        tv.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        row.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cell.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return cell;
    }

    // ---------------------------------------------------------------- launching

    /**
     * Says what opened a panel. Device -1 means the key was injected rather than sent by a
     * remote; HDMI-CEC keys also carry the HDMI source.
     */
    private void logOpen(String what) {
        String how = (lastKeyAt == 0L) ? "no key seen"
                : "key " + android.view.KeyEvent.keyCodeToString(lastKey) + " from device "
                        + lastKeyDevice + ", source 0x" + Integer.toHexString(lastKeySource)
                        + ((lastKeySource & android.view.InputDevice.SOURCE_HDMI)
                                == android.view.InputDevice.SOURCE_HDMI ? " (HDMI-CEC)" : "")
                        + ", " + (android.os.SystemClock.uptimeMillis() - lastKeyAt) + "ms ago";
        android.util.Log.i("Homestead", "opening " + what + " (" + how + ")");
    }

    private void act(AppModel.Item it) {
        if (it.type == AppModel.TYPE_SETTINGS) {
            logOpen(it.label);
            showPanel(SettingsPanel.build(ctx, this));
            return;
        }
        if (it.type == AppModel.TYPE_HEALTH) {
            logOpen(it.label);
            showPanel(HealthPanel.build(ctx, this));
            return;
        }
        if (it.type == AppModel.TYPE_LIVETV) {
            logOpen(it.label);
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("livetv://showLiveTVButtonContent/"));
                i.setComponent(new ComponentName(AppModel.LIVETV_PKG, AppModel.LIVETV_SVC));
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                else ctx.startService(i);
            } catch (Throwable t) {
                Toast.makeText(ctx, R.string.home_livetv_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (it.type == AppModel.TYPE_INPUT) {
            Intent i = new Intent(ctx, HdmiActivity.class);
            i.putExtra(HdmiActivity.EXTRA_INPUT_ID, it.inputId);
            i.putExtra(HdmiActivity.EXTRA_LABEL, it.label);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); } catch (Throwable t) { }
            return;
        }
        if (it.type == AppModel.TYPE_AMAZON_HOME) {
            HomeRedirectService.suspend(30000L);
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setClassName(AppModel.AMAZON_LAUNCHER, AppModel.AMAZON_HOME_CLS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable t) { }
            return;
        }
        AppModel.noteLaunched(ctx, it);
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(it.pkg, it.cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
            return;
        } catch (Throwable t) { }
        try {
            Intent f = ctx.getPackageManager().getLaunchIntentForPackage(it.pkg);
            if (f != null) { f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(f); }
        } catch (Throwable t) { }
    }
}
