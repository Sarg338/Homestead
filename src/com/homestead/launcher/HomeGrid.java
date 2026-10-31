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
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
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
    private static final float FOCUS_SCALE = 1.10f;
    private static final long FOCUS_MS = 140L;
    /** The name label under a focused banner. */
    private static final int NAME_H_DP = 20;
    private static final String NAME_TAG = "name";
    /** Below the last row: room for a focused tile's growth and its name, clear of the screen edge. */
    private static final int BOTTOM_DP = MARGIN_DP + NAME_H_DP + 12;

    // The dock design (PR #27): the most-used apps on a translucent band at the bottom of the
    // first screen, one grid below it, a glow in the focused banner's colour, a springier focus.
    private static final int DOCK_COUNT = 5;
    /** Dock tile height per Prefs.dockSize; the tiles are 16:9. */
    private static final int[] DOCK_H_DP = { 88, 112, 136, 160 };
    private static final int DOCK_PAD_DP = 14;
    private static final int DOCK_BOTTOM_DP = 26;
    private static final int DOCK_BAND_COLOR = 0x24FFFFFF;
    private static final int DOCK_CARD_COLOR = 0xFF1B1B1E;
    private static final int GLOW_ALPHA = 0x8A;
    private static final float DOCK_FOCUS_SCALE = 1.12f;
    private static final int DOCK_LIFT_DP = 10;
    private static final android.view.animation.Interpolator DOCK_IN =
            new android.view.animation.OvershootInterpolator(2.2f);
    private static final android.view.animation.Interpolator DOCK_OUT =
            new android.view.animation.DecelerateInterpolator(1.4f);

    private final Context ctx;
    private final Listener listener;

    public final FrameLayout root;
    private final ScrollView scroll;
    private final LinearLayout column;
    private final TextView clock;
    /** Greeting and date at the top left; fixed, so they stay put while the grid scrolls. */
    private final LinearLayout head;
    private final TextView greet;
    private final TextView date;
    private final LinearLayout topBar;
    /** The date beside a large clock, drawn only with the top bar. */
    private final TextView dateText;
    private boolean barShown;
    private View barSpacer, barRule;

    /** The dock design is in use for this build (Settings, Home screen, Design). */
    private boolean dock;
    /** Behind everything; coloured by the focused banner in the dock design, else empty. */
    private final View glow;
    private int glowColor;
    /** The dock's title line, naming the focused dock tile; null without a dock. */
    private TextView dockTitle;
    private final java.util.Set<String> dockKeys = new java.util.LinkedHashSet<String>();

    private final Map<String, View> cellsByKey = new HashMap<String, View>();
    /**
     * The top bar's pills by key, so focus can return to one after a panel, an app or the
     * overlay's handoff. Kept apart from cellsByKey, whose cells get the tile look on restyle.
     */
    private final Map<String, View> pillsByKey = new HashMap<String, View>();
    /** Each shown row's header by Prefs.ROW_* name, for showRow(). */
    private final Map<String, View> rowHeaders = new HashMap<String, View>();
    /** The horizontally scrolling section rows, in build order. */
    private final List<HorizontalScrollView> rows = new ArrayList<HorizontalScrollView>();
    private View focusedCell;
    private String focusedKey;
    /** The grid row lifted so its focused tile's name label draws over the row below. */
    private View raisedRow;
    /** The Live TV first tile, or null when that layout is not in use. */
    private View topTile;
    /** AppModel.order() as of the last build, to spot a re-sort done by the other grid. */
    private String builtOrder;
    private boolean restoreShown;
    /** What holds the restore tile; it slides the tile out when the shared clock runs out. */
    private FrameLayout restoreSlot;
    private final Runnable restoreTimeUp = new Runnable() {
        public void run() { slideOutRestore(); }
    };
    /**
     * Bumped whenever either grid changes a layout setting or hides a tile; each grid builds
     * against the value it saw, so the other one can tell it is out of date.
     */
    private static int layoutGen;
    private int builtGen = -1;
    /** Settings / TV Health render inside this window; Back pops one level. */
    private final List<View> panels = new ArrayList<View>();
    /** For each open panel, the view that had focus when the next one opened over it. */
    private final List<View> panelFocus = new ArrayList<View>();
    /**
     * What the panels opened here call back into. A panel keeps the host it was built with,
     * so when the overlay hands its panels to the activity, this is pointed at the
     * activity's grid and the overlay starts a new one.
     */
    private PanelHost panelHost = new PanelHost(this);
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
                            showPanel(SettingsPanel.build(ctx, panelHost));
                        }
                    }
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        root.setClipChildren(false);

        // Added first, so it draws behind the page: a large soft blob, most of it off the top
        // left of the screen, recoloured (never moved) as the focus moves.
        glow = new View(c);
        int sw = c.getResources().getDisplayMetrics().widthPixels;
        int sh = c.getResources().getDisplayMetrics().heightPixels;
        int gs = Math.round(sh * 1.05f);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(gs, gs);
        glp.leftMargin = Math.round(sw * 0.12f) - gs / 2;
        glp.topMargin = Math.round(sh * 0.42f) - gs / 2;
        root.addView(glow, glp);

        scroll = new ScrollView(c) {
            // Only the top edge fades under the top bar; the bottom row is not dimmed.
            @Override protected float getBottomFadingEdgeStrength() { return 0f; }

            // The dock sits on the bottom fading edge, so the stock math nudged the page up on
            // every move along it and keepAtTop() pulled it back: a jump each time. A tile that
            // is already fully on screen needs no scroll.
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
                if (dock && r.top >= getScrollY() && r.bottom <= getScrollY() + getHeight()) return 0;
                return super.computeScrollDeltaToGetChildRectOnScreen(r);
            }
        };
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
        column.setPadding(dp(MARGIN_DP), dp(46), dp(MARGIN_DP), dp(BOTTOM_DP));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        clock = new TextView(c);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        // The clock, with the settings pills to its left when that shortcut row is chosen.
        dateText = new TextView(c);
        dateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dateText.setLetterSpacing(0.1f);
        dateText.setSingleLine(true);
        topBar = new LinearLayout(c) {
            // The date is the first thing to go when the clock and the pills do not fit side by
            // side (a 12-hour clock is wider): better no date than a pill pushed off the screen.
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, h);
                if (dateText.getVisibility() != View.VISIBLE) return;
                int avail = MeasureSpec.getSize(w) - getPaddingLeft() - getPaddingRight();
                int need = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View v = getChildAt(i);
                    if (v == barSpacer || v.getVisibility() == View.GONE) continue;
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
                    need += v.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                }
                if (need > avail) {
                    dateText.setVisibility(View.GONE);
                    if (barRule != null) barRule.setVisibility(View.GONE);
                    super.onMeasure(w, h);
                }
            }
        };
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setClipChildren(false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        clp.topMargin = dp(6);
        // Room round the pills for the focus ring, which grows past their edge.
        topBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        topBar.setClipToPadding(false);
        clp.leftMargin = dp(MARGIN_DP - 6);
        clp.rightMargin = dp(33 - 6);
        root.addView(topBar, clp);

        head = new LinearLayout(c);
        head.setOrientation(LinearLayout.VERTICAL);
        greet = new TextView(c);
        greet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        greet.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(greet);
        date = new TextView(c);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        date.setAlpha(0.8f);
        head.addView(date);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        hlp.topMargin = dp(14);
        hlp.leftMargin = dp(MARGIN_DP);
        root.addView(head, hlp);

        AppModel.ensureLoaded(c);
        Backgrounds.adoptSharedPhotoOnce(c);
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
        paintText(greet);
        paintText(date);
        paintText(dateText);
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
        updateHead();
        int mode = Prefs.clockMode(ctx);
        if (mode == Prefs.CLOCK_OFF) { clock.setText(""); dateText.setText(""); return; }
        Date now = new Date();
        if (mode == Prefs.CLOCK_12H && barShown) {
            // The large clock keeps AM/PM small, so a 12-hour time is no wider than a 24-hour one.
            String t = new SimpleDateFormat("h:mm", Locale.getDefault()).format(now);
            String ap = new SimpleDateFormat("a", Locale.getDefault()).format(now);
            SpannableString s = new SpannableString(t + " " + ap);
            s.setSpan(new RelativeSizeSpan(0.4f), t.length() + 1, s.length(), 0);
            clock.setText(s);
        } else {
            String fmt = (mode == Prefs.CLOCK_12H) ? "h:mm a" : "HH:mm";
            clock.setText(new SimpleDateFormat(fmt, Locale.getDefault()).format(now));
        }
        dateText.setText(dateString(now, true).toUpperCase(Locale.getDefault()));
    }

    /**
     * The date as the Date setting asks for it; empty when it is off. In the bar the long form
     * is abbreviated, since the full weekday and month do not fit beside the pills.
     */
    private String dateString(Date now, boolean compact) {
        int df = Prefs.dateFormat(ctx);
        if (df == Prefs.DATE_LONG) {
            String pat = android.text.format.DateFormat.getBestDateTimePattern(
                    Locale.getDefault(), compact ? "EEEdMMM" : "EEEEdMMMM");
            return new SimpleDateFormat(pat, Locale.getDefault()).format(now);
        }
        if (df == Prefs.DATE_SHORT) return android.text.format.DateFormat.getDateFormat(ctx).format(now);
        return "";
    }

    /** Greeting and date, each its own setting; the grid moves down while either shows. */
    private void updateHead() {
        // The bar carries the clock and the date at the left, so the greeting steps aside.
        if (barShown) { head.setVisibility(View.GONE); return; }
        Date now = new Date();
        boolean g = Prefs.greeting(ctx);
        int df = Prefs.dateFormat(ctx);
        if (g) {
            int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            greet.setText(h >= 5 && h < 12 ? R.string.home_greet_morning
                    : h >= 12 && h < 18 ? R.string.home_greet_afternoon : R.string.home_greet_evening);
        }
        greet.setVisibility(g ? View.VISIBLE : View.GONE);
        if (df != Prefs.DATE_OFF) date.setText(dateString(now, false));
        date.setVisibility(df != Prefs.DATE_OFF ? View.VISIBLE : View.GONE);
        boolean show = g || df != Prefs.DATE_OFF;
        head.setVisibility(show ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scroll.getLayoutParams();
        // With the header up, the scroll area starts below it and clips, so the grid never
        // draws over it while scrolling; the padding moves the other way, so tiles at rest
        // stay where they were and a focused tile in the first row still has room to grow.
        int top = show ? dp(g && df != Prefs.DATE_OFF ? 76 : 54) : 0;
        if (lp.topMargin != top) {
            lp.topMargin = top;
            scroll.setLayoutParams(lp);
            column.setPadding(column.getPaddingLeft(), show ? dp(14) : dp(46),
                    column.getPaddingRight(), column.getPaddingBottom());
        }
        // Clipping to the outline holds with hardware drawing, where clipChildren alone let
        // the scrolled rows show through over the header.
        scroll.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        scroll.setClipToOutline(show);
    }

    /** Focus for a fresh Home press: Live TV when it comes first, else as takeFocus(). */
    public void takeStartFocus() {
        // The restore tile is the one thing worth seeing before anything else.
        if (AppModel.restorePopupLive() && selectKey(AppModel.RESTORE_KEY)) {
            scroll.scrollTo(0, 0);
            return;
        }
        if (liveTvFirst() && selectKey(AppModel.LIVETV_KEY)) {
            scroll.scrollTo(0, 0);
            return;
        }
        // The dock design starts on the dock, not on the first tile of the grid below it.
        if (dock && !dockKeys.isEmpty() && selectKey(dockKeys.iterator().next())) return;
        takeFocus();
    }

    /**
     * Live TV first is in effect: switched on, and this TV has the tile. Without the tile
     * the switch is not offered in Settings, so a stale "on" must not keep the layout.
     */
    private boolean liveTvFirst() {
        return Prefs.liveTvFirst(ctx) && AppModel.hasLiveTv();
    }

    /**
     * The Live TV first tile has no header above it, so focusing it scrolls the page to the
     * top. Repeated after layout, which would otherwise scroll it flush with the screen edge.
     */
    private void keepAtTop() {
        if (scroll.getScrollY() != 0) scroll.scrollTo(0, 0);
        scroll.post(new Runnable() {
            public void run() { scroll.scrollTo(0, 0); }
        });
    }

    public void takeFocus() {
        if (focusedKey != null && selectKey(focusedKey)) return;
        // The first item that has a cell: some are not drawn (the Settings tile while the top
        // bar is on, a section that is switched off).
        List<AppModel.Item> items = AppModel.visibleItems(ctx);
        for (int i = 0; i < items.size(); i++) {
            if (selectKey(items.get(i).key())) return;
        }
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
        // Settings and TV Health stay reachable so hidden tiles can always be restored. The
        // settings shortcuts come and go with "Choose shortcuts" instead.
        if (target.type == AppModel.TYPE_SETTINGS || target.type == AppModel.TYPE_HEALTH
                || target.type == AppModel.TYPE_RESTORE || target.type == AppModel.TYPE_SHORTCUT) {
            return false;
        }
        Prefs.setHidden(ctx, target.key(), true);
        try {
            android.widget.Toast.makeText(ctx,
                    target.label + " hidden - restore it in Settings",
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) { }
        layoutGen++;
        rebuild();
        return true;
    }

    /**
     * Whether the D-pad can reach the grid: its tiles and the top bar's pills. The top bar sits
     * beside the scroll, not in it, so it is blocked separately. Back on, each gets its default
     * again: the scroll's is AFTER_DESCENDANTS, so the scroll itself never takes focus instead
     * of a tile (Down from a pill landed on it, with no tile lit).
     */
    private void gridFocusable(boolean on) {
        int block = ViewGroup.FOCUS_BLOCK_DESCENDANTS;
        scroll.setDescendantFocusability(on ? ViewGroup.FOCUS_AFTER_DESCENDANTS : block);
        topBar.setDescendantFocusability(on ? ViewGroup.FOCUS_BEFORE_DESCENDANTS : block);
    }

    public void showPanel(final View v) {
        // Stop the grid, and any panel underneath, from stealing D-pad focus.
        gridFocusable(false);
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
            gridFocusable(true);
            takeFocus();
            // The restore tile was under the page; its time on screen starts now.
            if (restoreSlot != null) {
                if (AppModel.restorePopupLive()) armRestore();
                else dropRestore();
            }
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

    /**
     * Take over the panels open in another grid, as they are. The overlay hands them to the
     * activity just before it lifts, so a page opened in the first seconds after Home stays
     * open on the same row. Skipped if a panel is already open here.
     * @return true if panels were moved.
     */
    public boolean adoptPanels(HomeGrid from) {
        if (from == this || from.panels.isEmpty() || !panels.isEmpty()) return false;
        // Settings changed on those pages since this grid last caught up.
        syncOrder();
        applyBackground();
        updateClock();
        View top = from.panels.get(from.panels.size() - 1);
        // Taken first: leaving its window clears the focus.
        View focus = top.findFocus();
        gridFocusable(false);
        for (int i = 0; i < from.panels.size(); i++) {
            View v = from.panels.get(i);
            from.root.removeView(v);
            root.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            panels.add(v);
            panelFocus.add(from.panelFocus.get(i));
        }
        from.panels.clear();
        from.panelFocus.clear();
        from.gridFocusable(true);
        from.panelHost.grid = this;
        from.panelHost = new PanelHost(from);
        if (focus == null || !focus.requestFocus()) top.requestFocus();
        return true;
    }

    /** Passes a panel's calls on to the grid it is in now; see panelHost. */
    private static final class PanelHost implements SettingsPanel.Host {
        HomeGrid grid;
        PanelHost(HomeGrid g) { grid = g; }
        public void rebuildGrid() { grid.rebuildGrid(); }
        public void showRow(String row) { grid.showRow(row); }
        public void showPanel(View v) { grid.showPanel(v); }
        public void closePanel() { grid.closePanel(); }
    }

    /** A setting changed from a panel in this grid; the other grid catches up via syncOrder(). */
    public void rebuildGrid() {
        layoutGen++;
        AppModel.invalidateMetrics();
        applyBackground();
        rebuild();
    }

    public void showRow(final String row) {
        // Two posts: after both of applyScroll's, which put the old scroll position back
        // once a rebuild is laid out.
        scroll.post(new Runnable() {
            public void run() {
                scroll.post(new Runnable() {
                    public void run() {
                        View h = rowHeaders.get(row);
                        if (h != null) {
                            scroll.smoothScrollTo(0, Math.max(0, h.getTop() - dp(HEADER_TOP_DP)));
                        }
                    }
                });
            }
        });
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
        if (v == null) v = pillsByKey.get(key);
        if (v == null) return false;
        v.requestFocus();
        // A tile that already had focus gets no focus change, which is what brings the
        // dock back down to the bottom of the screen.
        if (dock && dockKeys.contains(key)) keepAtTop();
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
     * The overlay and the activity each have a grid over one shared model, so a re-sort or
     * a settings change made in one leaves the other stale. Rebuild if that happened.
     * @return true if rebuilt.
     */
    public boolean syncOrder() {
        if (AppModel.order().equals(builtOrder) && builtGen == layoutGen) return false;
        android.util.Log.i("Homestead", "grid out of date, rebuilding");
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
        // A scroll kept from the other design would leave the dock half off the first screen.
        if (dock && focusedKey != null && dockKeys.contains(focusedKey)) keepAtTop();
        // A restore tile that appears while someone is browsing does not take the focus:
        // a Select meant for the tile under the remote would land on it. It is at the top,
        // and a fresh Home press selects it (takeStartFocus()).
    }

    // ---------------------------------------------------------------- layout

    /**
     * Layout reads metadata from other packages, so one malformed package must not leave a
     * blank home screen. On any failure, draw a minimal row with Settings and TV Health.
     */
    private void build() {
        builtOrder = AppModel.order();
        builtGen = layoutGen;
        // Simple mode keeps its own plain layout whatever the design.
        dock = Prefs.design(ctx) == Prefs.DESIGN_DOCK && !Prefs.simpleMode(ctx);
        dockKeys.clear();
        dockTitle = null;
        if (!dock) setGlow(0);
        updateClock();
        try {
            buildTopBar();
            buildSections();
        } catch (Throwable t) {
            android.util.Log.e("Homestead", "grid build failed, drawing fallback", t);
            try { buildFallback(); } catch (Throwable ignored) { }
        }
    }

    /** Enough to get to Settings, using nothing that can throw. */
    private void buildFallback() {
        dock = false;
        column.removeAllViews();
        cellsByKey.clear();
        rowHeaders.clear();
        rows.clear();
        focusedCell = null;
        raisedRow = null;
        topTile = null;
        restoreSlot = null;

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
        rowHeaders.clear();
        rows.clear();
        focusedCell = null;
        raisedRow = null;
        restoreSlot = null;

        List<AppModel.Item> inputs = new ArrayList<AppModel.Item>();
        List<AppModel.Item> nonTv = new ArrayList<AppModel.Item>();
        List<AppModel.Item> tv = new ArrayList<AppModel.Item>();
        List<AppModel.Item> shortcuts = new ArrayList<AppModel.Item>();

        // Live TV first takes the Live TV tile out of its section and puts it above them all.
        boolean tvFirst = liveTvFirst();
        AppModel.Item liveTv = null;
        AppModel.Item restore = null;
        topTile = null;

        List<AppModel.Item> all = AppModel.visibleItems(ctx);
        restoreShown = false;
        for (int i = 0; i < all.size(); i++) {
            AppModel.Item it = all.get(i);
            if (it.type == AppModel.TYPE_RESTORE) {
                restoreShown = true;
                restore = it;
            } else if (it.type == AppModel.TYPE_SHORTCUT) {
                shortcuts.add(it);
            } else if (barShown && it.type == AppModel.TYPE_SETTINGS) {
                // The top bar has its own Settings pill; the tile would only repeat it.
                continue;
            } else if (tvFirst && it.type == AppModel.TYPE_LIVETV) {
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
        // The shortcuts row is not part of Simple mode, like the top bar.
        if (Prefs.simpleMode(ctx)) shortcuts.clear();

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

        if (dock) {
            addDockLayout(restore, liveTv, tv, inputs, nonTv);
            return;
        }

        String inputsTitle = ctx.getString(R.string.home_sec_inputs);
        String nonTvTitle = ctx.getString(R.string.home_sec_nontv);
        String tvTitle = ctx.getString(R.string.home_sec_tv);
        String shortcutsTitle = ctx.getString(R.string.home_sec_settings);
        if (Prefs.shortcutsPlace(ctx) != Prefs.SHORTCUTS_ROW) shortcuts.clear();
        boolean first = true;
        if (restore != null) { addRestoreTile(restore); first = false; }
        if (liveTv != null)  { addLiveTvTile(liveTv); first = false; }
        // The rows in the order chosen in Settings; an empty or switched-off one is skipped.
        List<String> order = Prefs.rowOrder(ctx);
        for (int i = 0; i < order.size(); i++) {
            String row = order.get(i);
            if (row.equals(Prefs.ROW_SHORTCUTS)) {
                if (shortcuts.isEmpty()) continue;
                rowHeaders.put(row, addHeader(shortcutsTitle, first));
                addCardRow(shortcuts);
            } else if (row.equals(Prefs.ROW_INPUTS)) {
                if (inputs.isEmpty()) continue;
                rowHeaders.put(row, addHeader(inputsTitle, first));
                addCardRow(inputs);
            } else if (row.equals(Prefs.ROW_NONTV)) {
                if (nonTv.isEmpty()) continue;
                rowHeaders.put(row, addHeader(nonTvTitle, first));
                addNonTv(nonTv);
            } else {
                if (tv.isEmpty()) continue;
                rowHeaders.put(row, addHeader(tvTitle, first));
                addTileGrid(tv);
            }
            first = false;
        }
    }

    /**
     * The dock design: Live TV (when it comes first) and the most-used TV apps on a band at
     * the bottom of the first screen, then one grid with everything else, no headers.
     */
    private void addDockLayout(AppModel.Item restore, AppModel.Item liveTv, List<AppModel.Item> tv,
                               List<AppModel.Item> inputs, List<AppModel.Item> nonTv) {
        if (restore != null) addRestoreTile(restore);
        int n = Math.min(Math.max(0, DOCK_COUNT - (liveTv != null ? 1 : 0)), tv.size());
        if (liveTv != null || n > 0) addDock(liveTv, tv.subList(0, n), restore == null);
        List<AppModel.Item> grid = new ArrayList<AppModel.Item>(inputs);
        grid.addAll(tv.subList(n, tv.size()));
        grid.addAll(nonTv);
        if (!grid.isEmpty()) addTileGrid(grid);
    }

    /** The translucent band and its title line; pinned to the bottom when pin is set. */
    private void addDock(AppModel.Item liveTv, List<AppModel.Item> apps, boolean pin) {
        TextView title = new TextView(ctx);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        paintText(title);
        title.setText(liveTv != null ? liveTv.label : apps.get(0).label);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(10);

        int h = dp(DOCK_H_DP[Prefs.dockSize(ctx)]);
        int normalW = h * 16 / 9;
        int featuredW = normalW * 3 / 2;
        // Room inside the band for a focused tile's growth and its lifted shadow, which would
        // otherwise be cut by the band's edge (the band clips, so tiles past its end are cut
        // off cleanly at the larger sizes).
        int widest = liveTv != null ? featuredW : normalW;
        int padH = Math.max(dp(DOCK_PAD_DP), Math.round(widest * (DOCK_FOCUS_SCALE - 1f) / 2f) + dp(2));
        int padV = Math.round(h * (DOCK_FOCUS_SCALE - 1f) / 2f) + dp(DOCK_LIFT_DP) + dp(4);
        int bandH = h + 2 * padV;

        if (pin) {
            // Open space above the dock, so it sits at the bottom of the first screen.
            title.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) scroll.getLayoutParams();
            int spacer = ctx.getResources().getDisplayMetrics().heightPixels - slp.topMargin
                    - column.getPaddingTop() - title.getMeasuredHeight() - tlp.bottomMargin
                    - bandH - dp(DOCK_BOTTOM_DP);
            if (spacer > 0) column.addView(new View(ctx), new LinearLayout.LayoutParams(1, spacer));
        }
        column.addView(title, tlp);
        dockTitle = title;

        HorizontalScrollView hs = new CardRow(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        rows.add(hs);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(padH, padV, padH, padV);
        if (liveTv != null) {
            View cell = buildCard(liveTv);
            if (topTile == null) topTile = cell;
            stopAt(cell, true, apps.isEmpty());
            row.addView(cell, new LinearLayout.LayoutParams(featuredW, h));
            dockKeys.add(liveTv.key());
        }
        for (int i = 0; i < apps.size(); i++) {
            View cell = buildTile(apps.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(normalW, h);
            if (i > 0 || liveTv != null) lp.leftMargin = dp(GAP_DP);
            stopAt(cell, i == 0 && liveTv == null, i == apps.size() - 1);
            row.addView(cell, lp);
            dockKeys.add(apps.get(i).key());
        }
        hs.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout band = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(DOCK_BAND_COLOR);
        band.setBackground(bg);
        band.addView(hs, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(DOCK_BOTTOM_DP);
        column.addView(band, blp);
    }

    /** The glow in the focused banner's colour; none for a tile without art. */
    private void setGlow(int accent) {
        if (accent == glowColor) return;
        glowColor = accent;
        if (accent == 0) { glow.setBackground(null); return; }
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setGradientRadius(glow.getLayoutParams().width / 2f);
        d.setColors(new int[] { (GLOW_ALPHA << 24) | (accent & 0xFFFFFF), accent & 0xFFFFFF });
        glow.setBackground(d);
    }

    /** Scrolls the dock so a focused end tile keeps the band's padding, its zoom uncut. */
    private void revealInDock(final View cell) {
        cell.post(new Runnable() {
            public void run() {
                if (!(cell.getParent() instanceof ViewGroup)) return;
                ViewGroup row = (ViewGroup) cell.getParent();
                if (!(row.getParent() instanceof HorizontalScrollView)) return;
                HorizontalScrollView hs = (HorizontalScrollView) row.getParent();
                int x = hs.getScrollX();
                int left = cell.getLeft() - row.getPaddingLeft();
                int right = cell.getRight() + row.getPaddingRight();
                if (left < x) hs.smoothScrollTo(left, 0);
                else if (right > x + hs.getWidth()) hs.smoothScrollTo(right - hs.getWidth(), 0);
            }
        });
    }

    /** Live TV first: one large tile, two banner cells wide, on its own row. */
    private void addLiveTvTile(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        if (topTile == null) topTile = cell;
        // Alone on its row, so Left or Right would otherwise jump to a tile in another row.
        stopAt(cell, true, true);
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
        // A longer name ("TV en direct") would wrap and push the hint out of the tile: keep it
        // on one line, smaller if it must be. The width is what the icon and paddings leave.
        int w = AppModel.cellW(ctx) * 2 + dp(GAP_DP);
        float room = w - dp(24) - (it.glyph != 0 ? dp(56) : 0) - dp(48);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        for (int sp = 29; sp >= 20 && title.getPaint().measureText(it.label) > room; sp--) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
        text.addView(title);

        TextView hint = new TextView(ctx);
        hint.setText(ctx.getString(R.string.home_livetv_hint));
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.addView(hint);

        // The same line icon as the Live TV card, large, left of the text.
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), 0, 0, 0);
        if (it.glyph != 0) {
            ImageView ic = new ImageView(ctx);
            ic.setImageResource(it.glyph);
            ic.setColorFilter(0xFFBFE9EE, android.graphics.PorterDuff.Mode.SRC_IN);
            row.addView(ic, new LinearLayout.LayoutParams(dp(56), dp(56)));
        }
        row.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cell.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int h = AppModel.cellH(ctx) * 3 / 2;
        column.addView(cell, new LinearLayout.LayoutParams(w, h));
    }

    /** The most pills the top bar takes, More settings and Settings included. */
    private static final int BAR_MAX = 5;

    /**
     * The "top bar" shortcut row: small rounded buttons left of the clock, at the same height
     * so nothing below moves. The chosen screens come first, then More settings (Amazon's
     * full menu) and Settings (this launcher's). Not shown in Simple mode.
     */
    private void buildTopBar() {
        topBar.removeAllViews();
        pillsByKey.clear();
        // The dock design always has the bar: its clock, date and Settings pill.
        boolean bar = (dock || Prefs.shortcutsPlace(ctx) == Prefs.SHORTCUTS_BAR)
                && !Prefs.simpleMode(ctx);
        barShown = bar;
        boolean showClock = Prefs.clockMode(ctx) != Prefs.CLOCK_OFF;

        View spacer = new View(ctx);
        barSpacer = spacer;
        barRule = null;
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, 1, 1f);

        if (!bar) {
            // The plain layout: the clock alone at the right.
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            clock.setTypeface(Typeface.DEFAULT);
            dateText.setVisibility(View.GONE);
            topBar.addView(spacer, slp);
            topBar.addView(clock);
            updateClock();
            // Where the page starts is up to updateHead: below the greeting and date, or at the top.
            scroll.setVerticalFadingEdgeEnabled(false);
            root.setClipChildren(false);
            return;
        }

        // Clock and date at the left, the glass pills at the right.
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        topBar.addView(clock);
        if (showClock && Prefs.dateFormat(ctx) != Prefs.DATE_OFF) {
            View rule = new View(ctx);
            barRule = rule;
            rule.setBackgroundColor(0x55FFFFFF);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(1), dp(30));
            rlp.leftMargin = dp(16);
            rlp.rightMargin = dp(16);
            topBar.addView(rule, rlp);
            dateText.setVisibility(View.VISIBLE);
            dateText.setAlpha(0.8f);
            topBar.addView(dateText);
        } else {
            dateText.setVisibility(View.GONE);
        }
        topBar.addView(spacer, slp);

        List<AppModel.Item> pills = new ArrayList<AppModel.Item>();
        List<AppModel.Item> all = AppModel.visibleItems(ctx);
        for (int i = 0; i < all.size() && pills.size() < BAR_MAX - 2; i++) {
            if (all.get(i).type == AppModel.TYPE_SHORTCUT) pills.add(all.get(i));
        }
        pills.add(barItem(AppModel.SC_MORE, ctx.getString(R.string.home_sc_more), "more"));
        pills.add(barItem(AppModel.SC_SETTINGS, ctx.getString(R.string.home_sc_settings), "settings"));
        for (int i = 0; i < pills.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(PILL_H_DP));
            if (i > 0) lp.leftMargin = dp(12);
            View pill = buildPill(pills.get(i));
            pillsByKey.put(pills.get(i).key(), pill);
            // Nothing above the bar, and no jumping to a tile past either end of it.
            boolean firstPill = i == 0, lastPill = i == pills.size() - 1;
            stopKeys(pill, android.view.KeyEvent.KEYCODE_DPAD_UP,
                    firstPill ? android.view.KeyEvent.KEYCODE_DPAD_LEFT : -1,
                    lastPill ? android.view.KeyEvent.KEYCODE_DPAD_RIGHT : -1);
            topBar.addView(pill, lp);
        }
        // The page scrolls below the bar, never behind it, and fades out as it nears it. The
        // padding keeps the first tile well clear of the pills when the page is at the top.
        updateClock();
        setScrollTop(BAR_H_DP);
        column.setPadding(dp(MARGIN_DP), dp(28), dp(MARGIN_DP), dp(BOTTOM_DP));
    }

    /** The bar's height, which the scrolling page starts under. */
    private static final int BAR_H_DP = 58;

    /** Where the scrolling page starts; with the bar it also fades at that edge. */
    private void setScrollTop(int dpTop) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scroll.getLayoutParams();
        if (lp.topMargin != dp(dpTop)) {
            lp.topMargin = dp(dpTop);
            scroll.setLayoutParams(lp);
        }
        scroll.setVerticalFadingEdgeEnabled(dpTop > 0);
        scroll.setFadingEdgeLength(dp(48));
        // The root normally lets children draw past their own edge so a focused tile can grow; under the bar
        // that would let it show through, so here it is cut at the edge instead.
        root.setClipChildren(dpTop > 0);
    }

    private static final int PILL_H_DP = 40;

    private AppModel.Item barItem(String action, String label, String icon) {
        AppModel.Item it = new AppModel.Item();
        it.type = AppModel.TYPE_SHORTCUT;
        it.label = label;
        it.cls = action;
        it.icon = ShortcutIcons.render(icon, dp(40));
        return it;
    }

    /** Pills are small: the long screen names lose their second half. */
    private String shortLabel(AppModel.Item it) {
        String s = FireTvSettingsPanel.homeLabel(ctx, it.cls, true);
        return s != null ? s : it.label;
    }

    /**
     * A glass pill: a faint white wash that fades down, a hairline light edge, fully rounded.
     * Over a photo or a gradient it picks up what is behind it instead of hiding it. The
     * focus ring follows the same rounded shape and the fill brightens, so the pill reads
     * as lit from within rather than boxed.
     */
    private View buildPill(AppModel.Item it) {
        final FrameLayout cell = newCell(it);
        final int radius = dp(PILL_H_DP / 2);
        final GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x40FFFFFF, 0x14FFFFFF });
        glass.setCornerRadius(radius);
        glass.setStroke(dp(1), 0x59FFFFFF);
        cell.setBackground(glass);
        // newCell draws a tile outline until the first focus change; a pill has none.
        cell.setForeground(null);

        final View.OnFocusChangeListener base = cell.getOnFocusChangeListener();
        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (base != null) base.onFocusChange(v, has);
                GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(radius);
                if (has) {
                    ring.setStroke(dp(2), 0xF2FFFFFF);
                    ring.setColor(0x26FFFFFF);
                    cell.setForeground(ring);
                    cell.setScaleX(1.06f);
                    cell.setScaleY(1.06f);
                } else {
                    cell.setForeground(null);
                    cell.setScaleX(1f);
                    cell.setScaleY(1f);
                }
            }
        });

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(16), 0);

        ImageView ic = new ImageView(ctx);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (it.icon != null) ic.setImageBitmap(it.icon);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        ilp.rightMargin = dp(8);
        row.addView(ic, ilp);

        TextView tv = new TextView(ctx);
        tv.setText(shortLabel(it));
        tv.setTextColor(0xF2FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLetterSpacing(0.03f);
        tv.setSingleLine(true);
        row.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cell.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return cell;
    }

    /** The system undid something Debloat had switched off: one amber tile above everything. */
    private void addRestoreTile(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        topTile = cell;
        stopAt(cell, true, true);
        GradientDrawable card = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF9A6A12, 0xFF4A3208 });
        card.setCornerRadius(dp(8));
        cell.setBackground(card);

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(dp(24), dp(12), dp(24), dp(12));

        TextView title = new TextView(ctx);
        title.setText(it.label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.addView(title);

        List<String> pkgs = AppModel.reverted(ctx);
        TextView hint = new TextView(ctx);
        hint.setText(ctx.getString(pkgs.size() == 1 ? R.string.home_restore_hint_one
                : R.string.home_restore_hint_many, revertedNames(ctx, pkgs)));
        hint.setTextColor(0xE6FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setMaxLines(2);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(hint);

        // Two lines of hint do not fit a banner cell's height at every density, so the tile
        // grows to its text and is never shorter than a cell.
        cell.setMinimumHeight(AppModel.cellH(ctx));
        cell.addView(text, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL));

        // The clock starts when a window really shows the tile, scrolled into view: the
        // activity's window can be built while it is in the background, and the page can
        // be scrolled down when the tile appears.
        final android.view.ViewTreeObserver.OnScrollChangedListener inView =
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    public void onScrollChanged() {
                        if (!AppModel.restoreClockRunning()) armRestore();
                    }
                };
        final FrameLayout slot = new FrameLayout(ctx) {
            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                // Back on screen after its time ran out elsewhere: gone before it is drawn.
                if (!AppModel.restorePopupLive()) { dropRestore(); return; }
                getViewTreeObserver().addOnScrollChangedListener(inView);
                post(new Runnable() { public void run() { armRestore(); } });
            }
            @Override protected void onWindowVisibilityChanged(int v) {
                super.onWindowVisibilityChanged(v);
                if (v != View.VISIBLE) removeCallbacks(restoreTimeUp);
                else if (!AppModel.restorePopupLive()) dropRestore();
                else armRestore();
            }
            @Override protected void onDetachedFromWindow() {
                removeCallbacks(restoreTimeUp);
                getViewTreeObserver().removeOnScrollChangedListener(inView);
                super.onDetachedFromWindow();
            }
        };
        slot.setClipChildren(false);
        slot.addView(cell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        restoreSlot = slot;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                AppModel.cellW(ctx) * 3 + dp(GAP_DP) * 2, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(GAP_DP);
        column.addView(slot, lp);
    }

    /** The tile is on screen here: start the shared clock if this is the first sight, and
     *  slide out when it runs out, at the same moment as the other grid. A page open over
     *  the grid hides it, so the clock waits for that too (closePanel() arms it again). */
    private void armRestore() {
        FrameLayout slot = restoreSlot;
        if (slot == null || slot.getParent() == null) return;
        if (slot.getWindowVisibility() != View.VISIBLE || !slot.isShown()) return;
        if (!panels.isEmpty() && !AppModel.restoreClockRunning()) return;
        // Not counted until most of it is on screen.
        android.graphics.Rect seen = new android.graphics.Rect();
        if (!AppModel.restoreClockRunning()
                && (!slot.getGlobalVisibleRect(seen) || seen.height() < slot.getHeight() / 2)) {
            return;
        }
        long left = AppModel.startRestorePopup();
        android.util.Log.i("Homestead", "restore tile on screen ("
                + ctx.getClass().getSimpleName() + "), " + left + " ms left");
        slot.removeCallbacks(restoreTimeUp);
        slot.postDelayed(restoreTimeUp, Math.max(0L, left));
    }

    /** Its time ran out while this grid was not showing it: take it out with no animation. */
    private void dropRestore() {
        FrameLayout slot = restoreSlot;
        if (slot == null) return;
        restoreSlot = null;
        cellsByKey.remove(AppModel.RESTORE_KEY);
        slot.setVisibility(View.GONE);
        root.post(new Runnable() {
            public void run() { rebuild(); }
        });
    }

    /** Time is up: the tile slides out to the left, then the rows below close the gap. */
    private void slideOutRestore() {
        final FrameLayout slot = restoreSlot;
        if (slot == null || slot.getParent() == null || slot.getChildCount() == 0) return;
        restoreSlot = null;
        android.util.Log.i("Homestead", "restore tile time up, sliding out ("
                + ctx.getClass().getSimpleName() + ")");
        final View tile = slot.getChildAt(0);
        // Out of reach from here on, so Select cannot land on a tile that is leaving.
        cellsByKey.remove(AppModel.RESTORE_KEY);
        boolean had = tile.hasFocus();
        tile.setFocusable(false);
        if (had && panels.isEmpty()) takeStartFocus();
        tile.animate().translationX(-(slot.getWidth() + dp(MARGIN_DP))).alpha(0f)
                .setDuration(350L)
                .withEndAction(new Runnable() {
                    public void run() { collapseRestore(slot); }
                });
    }

    private void collapseRestore(final FrameLayout slot) {
        final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) slot.getLayoutParams();
        final int h = slot.getHeight();
        final int m = lp.bottomMargin;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(1f, 0f);
        a.setDuration(250L);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator va) {
                float f = (Float) va.getAnimatedValue();
                lp.height = Math.round(h * f);
                lp.bottomMargin = Math.round(m * f);
                slot.setLayoutParams(lp);
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                // The model no longer offers the tile, so this drops the empty slot.
                if (slot.getParent() != column) return;
                // Seen at the top of the page, it leaves the page at the top: the rows that
                // moved up would otherwise sit under the edge with their header cut off.
                boolean atTop = scroll.getScrollY() < h;
                rebuild();
                if (atTop && panels.isEmpty()) keepAtTop();
            }
        });
        a.start();
    }

    /** The reverted packages by name, short enough for the tile: "A", "A and B",
     *  "A, B and C", or "A, B and 4 more". */
    private static String revertedNames(Context c, List<String> pkgs) {
        int n = pkgs.size();
        if (n == 0) return "";
        String a = Debloat.titleOf(c, pkgs.get(0));
        if (n == 1) return a;
        String b = Debloat.titleOf(c, pkgs.get(1));
        if (n == 2) return c.getString(R.string.home_names_two, a, b);
        if (n == 3) return c.getString(R.string.home_names_three, a, b, Debloat.titleOf(c, pkgs.get(2)));
        return c.getString(R.string.home_names_more, a, b, n - 2);
    }

    /** Switch off again whatever the system switched back on; open Setup if there is no link. */
    private void restoreReverted() {
        List<String> pkgs = AppModel.reverted(ctx);
        if (pkgs.isEmpty()) return;
        if (!AdbBridge.canRun(ctx)) {
            Toast.makeText(ctx, R.string.home_restore_failed, Toast.LENGTH_LONG).show();
            showPanel(DebloatPanel.build(ctx, panelHost));
            return;
        }
        final String[] list = pkgs.toArray(new String[pkgs.size()]);
        String[] cmds = new String[list.length];
        for (int i = 0; i < list.length; i++) cmds[i] = AdbBridge.disableCommand(list[i]);
        Toast.makeText(ctx, R.string.home_connecting, Toast.LENGTH_SHORT).show();
        AdbBridge.runRetry(cmds, new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) {
                // Never started: nothing changed, so keep the tile and the record of what
                // is still on, and say so. Forgetting them here would hide the problem.
                if (!AdbBridge.ran(out)) {
                    Toast.makeText(ctx, R.string.home_restore_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                StringBuilder kept = new StringBuilder();
                for (int i = 0; i < list.length; i++) {
                    boolean off = Debloat.stateOf(ctx, list[i]) == Debloat.DISABLED;
                    Prefs.noteDisabled(ctx, list[i], off);
                    if (!off) {
                        if (kept.length() > 0) kept.append(", ");
                        kept.append(Debloat.titleOf(ctx, list[i]));
                    }
                }
                // Fire OS refuses some packages; the record is dropped so the tile does not
                // come back for good, but the user is told.
                if (kept.length() > 0) {
                    Toast.makeText(ctx, ctx.getString(R.string.home_restore_refused, kept),
                            Toast.LENGTH_LONG).show();
                }
                AppModel.reload(ctx);
                rebuild();
            }
        });
    }

    private TextView addHeader(String text, boolean first) {
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
        return h;
    }

    /** Cards, or banner tiles made from the icons when "Wide tiles" is on (AppModel builds them). */
    private void addNonTv(List<AppModel.Item> items) {
        // Until the model reloads after the switch, the tiles are not built yet.
        if (Prefs.nonTvWide(ctx) && items.get(0).banner != null) addTileGrid(items);
        else addCardRow(items);
    }

    /**
     * A scrolling row of cards. HorizontalScrollView moves the focus for Left and Right itself
     * and reports the key handled, so the system's navigation click, which every other D-pad
     * move makes, never plays; the row plays it when the focus actually moved.
     */
    private static final class CardRow extends HorizontalScrollView {
        CardRow(Context c) { super(c); }

        @Override public boolean arrowScroll(int direction) {
            View before = findFocus();
            boolean handled = super.arrowScroll(direction);
            View after = findFocus();
            if (handled && after != null && after != before && after != this) {
                playSoundEffect(android.view.SoundEffectConstants
                        .getContantForFocusDirection(direction));
            }
            return handled;
        }
    }

    /** Wide cards in a horizontally scrolling row. */
    private void addCardRow(List<AppModel.Item> items) {
        HorizontalScrollView hs = new CardRow(ctx);
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
            stopAt(cell, i == 0, i == items.size() - 1);
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
            // The dock design's one grid mixes banners with the cards of inputs and the like.
            AppModel.Item it = items.get(i);
            View cell = it.banner != null ? buildTile(it) : buildCard(it);
            boolean rowEnd = i % AppModel.columns(ctx) == AppModel.columns(ctx) - 1
                    || i == items.size() - 1;
            stopAt(cell, i % AppModel.columns(ctx) == 0, rowEnd);
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
        if (dock) {
            // A rounded outline, so the lifted tile's shadow has the tile's shape.
            cell.setOutlineProvider(new android.view.ViewOutlineProvider() {
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(8));
                }
            });
        }
        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has) {
                    focusedCell = v;
                    focusedKey = it.key();
                    style(v, true, true);
                    if (v == topTile) keepAtTop();
                    if (dock) {
                        setGlow(it.accent);
                        if (dockKeys.contains(it.key())) {
                            keepAtTop();
                            revealInDock(v);
                            if (dockTitle != null) dockTitle.setText(it.label);
                        }
                    }
                } else {
                    style(v, false, true);
                }
            }
        });
        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // A tile that opens a panel in this window is not reported as a pick; that
                // would make an overlay host dismiss itself.
                if (listener != null && !opensPanel(it)) listener.onPick(it);
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

    /**
     * Settings, TV Health, the bar's Settings pill, and its More settings pill while Amazon's
     * launcher is off (that one asks first): each opens a panel here instead of leaving.
     */
    private boolean opensPanel(AppModel.Item it) {
        if (it.type == AppModel.TYPE_SETTINGS || it.type == AppModel.TYPE_HEALTH) return true;
        if (it.type != AppModel.TYPE_SHORTCUT) return false;
        if (AppModel.SC_SETTINGS.equals(it.cls)) return true;
        return AppModel.SC_MORE.equals(it.cls) && !AppModel.amazonLauncherOn(ctx);
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
    private void style(View v, boolean focused) { style(v, focused, false); }

    /**
     * @param animate grow or shrink over FOCUS_MS; only for a D-pad move, so a focus
     *        restored during the handoff appears at its final size.
     */
    private void style(View v, boolean focused, boolean animate) {
        if (!(v instanceof FrameLayout)) return;
        // A bar pill draws its own focus (ring, 1.06x); a tile's grow and shadow on top of it
        // ran past the bar's edge, which cuts it.
        if (pillsByKey.containsValue(v)) return;
        FrameLayout f = (FrameLayout) v;
        if (dock) { dockStyle(f, focused, animate); return; }
        float scale = focused ? FOCUS_SCALE : 1f;
        if (focused) {
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(8));
            ring.setStroke(dp(3), look.ring);
            ring.setColor(Color.TRANSPARENT);
            f.setForeground(ring);
            f.setTranslationZ(dp(8));
        } else {
            f.setForeground(outline());
            f.setTranslationZ(0f);
        }
        f.animate().cancel();
        if (animate) {
            f.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_MS).start();
        } else {
            f.setScaleX(scale);
            f.setScaleY(scale);
        }
        // The label hangs over the next grid row, which is drawn later, so the focused tile's
        // row is lifted above it. Only one row is ever lifted, and only a focus gain moves
        // the lift: a loss can't tell whether focus is staying in the row.
        View row = (View) f.getParent();
        if (focused && raisedRow != null && raisedRow != row) {
            raisedRow.setTranslationZ(0f);
            raisedRow = null;
        }
        View name = f.findViewWithTag(NAME_TAG);
        if (name != null) {
            name.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            if (focused && row != null) {
                row.setTranslationZ(dp(8));
                raisedRow = row;
            }
            // Scroll far enough to show the label too, not only the banner.
            if (focused && animate) {
                final FrameLayout cell = f;
                cell.post(new Runnable() {
                    public void run() {
                        cell.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
                                cell.getWidth(), cell.getHeight() + dp(NAME_H_DP + 8)));
                    }
                });
            }
        }
    }

    /**
     * The dock design's focus (PR #27): a white rim, a spring to DOCK_FOCUS_SCALE with a slight
     * overshoot, and a lift that casts a shadow. The grown tile and its shadow are scrolled
     * fully on screen, so neither is cut at the bottom edge.
     */
    private void dockStyle(final FrameLayout f, boolean focused, boolean animate) {
        Drawable rim = outline();
        if (focused) {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(8));
            d.setStroke(dp(3), Color.WHITE);
            d.setColor(Color.TRANSPARENT);
            rim = d;
        }
        f.setForeground(rim);
        float scale = focused ? DOCK_FOCUS_SCALE : 1f;
        float z = focused ? dp(DOCK_LIFT_DP) : 0f;
        f.animate().cancel();
        if (!animate || f.getWidth() == 0) {
            f.setScaleX(scale);
            f.setScaleY(scale);
            f.setTranslationZ(z);
        } else {
            f.animate().scaleX(scale).scaleY(scale).translationZ(z)
                    .setDuration(focused ? 220L : 160L)
                    .setInterpolator(focused ? DOCK_IN : DOCK_OUT).start();
        }
        // Dock tiles are kept in view by keepAtTop() and revealInDock(); scrolling for them
        // here too made the page jump on every move along the dock.
        boolean inDock = f.getParent() != null
                && ((View) f.getParent()).getParent() instanceof HorizontalScrollView;
        if (focused && animate && !inDock) {
            f.post(new Runnable() {
                public void run() {
                    int gx = Math.round(f.getWidth() * (DOCK_FOCUS_SCALE - 1f) / 2f) + dp(4);
                    int gy = Math.round(f.getHeight() * (DOCK_FOCUS_SCALE - 1f) / 2f) + dp(4);
                    f.requestRectangleOnScreen(new android.graphics.Rect(-gx, -gy,
                            f.getWidth() + gx, f.getHeight() + gy + dp(DOCK_LIFT_DP)));
                }
            });
        }
    }

    /**
     * Keep focus on the first or last cell of a row when the D-pad points past it. Otherwise
     * the focus search picks the nearest cell that way anywhere on the page, often in another
     * row (a scrolled card row has cards off the left edge), which reads as a jump.
     */
    private static void stopAt(View cell, final boolean first, final boolean last) {
        if (!first && !last) return;
        cell.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, android.view.KeyEvent e) {
                return (first && code == android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                        || (last && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            }
        });
    }

    /** Swallow the given D-pad keys on a cell, for the bar's edges and its Up. */
    private static void stopKeys(View cell, final int... codes) {
        cell.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, android.view.KeyEvent e) {
                for (int c : codes) if (c == code) return true;
                return false;
            }
        });
    }

    /** A TV app: full-bleed banner, already rounded in the bitmap. */
    private View buildTile(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        img.setImageBitmap(it.banner);
        cell.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The dock design names the focused dock tile in its title line instead.
        if (dock) return cell;

        // The app's name, shown while focused. It hangs below the banner into the gap and
        // over the row beneath (the focused cell draws on top), so rows keep their height.
        TextView name = new TextView(ctx);
        name.setTag(NAME_TAG);
        name.setText(it.label);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(10));
        pill.setColor(0xD9101820);
        name.setBackground(pill);
        name.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(NAME_H_DP),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        name.setTranslationY(dp(NAME_H_DP + 4));
        cell.addView(name, nlp);
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
        boolean tvFirst = liveTvFirst();
        if (dock) card.setColor(look == Backgrounds.PHOTO_LOOK ? 0xA61B1E24 : DOCK_CARD_COLOR);
        else if (textTile) card.setColor(tvFirst ? 0xFF2A333D : 0xFF0E5A63);
        else card.setColor(0xFF033058);
        cell.setBackground(card);

        if (textTile) {
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            if (it.glyph != 0) {
                ImageView ic = new ImageView(ctx);
                ic.setImageResource(it.glyph);
                ic.setColorFilter(tvFirst ? 0xFFB4BECA : 0xFFBFE9EE,
                        android.graphics.PorterDuff.Mode.SRC_IN);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(28), dp(28));
                ilp.bottomMargin = dp(6);
                col.addView(ic, ilp);
            }
            TextView tv = new TextView(ctx);
            tv.setText(it.label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(tv);
            if (it.connected) {
                TextView tag = new TextView(ctx);
                tag.setText(R.string.home_connected);
                tag.setTextColor(0xFF7BD88F);
                tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tag.setGravity(Gravity.CENTER);
                col.addView(tag);
            }
            cell.addView(col, new FrameLayout.LayoutParams(
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
        // A grid cell is smaller than a card in a row, so the icon leaves room for the name.
        int isz = dock ? dp(34) : dp(CARD_H_DP) * 62 / 100;
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(isz, isz);
        ilp.rightMargin = dp(8);
        row.addView(ic, ilp);

        TextView tv = new TextView(ctx);
        tv.setText(it.label);
        tv.setTextColor(tvFirst ? 0xFFD5DDE5 : 0xFFA2AFBB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, tvFirst ? 14 : 12);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        // Both exist from Android 6; Fire OS 5 (Android 5.1) already breaks simply and never
        // hyphenates.
        if (Build.VERSION.SDK_INT >= 23) {
            tv.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
            tv.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
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
            showPanel(SettingsPanel.build(ctx, panelHost));
            return;
        }
        if (it.type == AppModel.TYPE_HEALTH) {
            logOpen(it.label);
            showPanel(HealthPanel.build(ctx, panelHost));
            return;
        }
        if (it.type == AppModel.TYPE_RESTORE) {
            restoreReverted();
            return;
        }
        if (it.type == AppModel.TYPE_SHORTCUT) {
            logOpen(it.label);
            if (AppModel.SC_MORE.equals(it.cls)) {
                FireTvSettingsPanel.openAll(ctx, panelHost);
            } else if (AppModel.SC_SETTINGS.equals(it.cls)) {
                showPanel(SettingsPanel.build(ctx, panelHost));
            } else {
                SetupPanel.start(ctx, FireTvSettingsPanel.intentFor(it.cls));
            }
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
        // Fire OS lets only its own Settings/home open some apps' screens, so a direct launch is
        // refused and the tile did nothing. Open the TV's Settings and point the way with a brief
        // bubble, or (for one with no settings home) just show where to go.
        if (AppModel.AIRPLAY_PKG.equals(it.pkg)) {
            openTvSettings(R.string.home_airplay_hint);
            return;
        }
        if (AppModel.PROFILE_PICKER_PKG.equals(it.pkg)) {
            openTvSettings(R.string.home_profile_hint);
            return;
        }
        if (AppModel.AMAZON_KIDS_PKG.equals(it.pkg)) {
            openTvSettings(R.string.home_kids_hint);
            return;
        }
        if (AppModel.WSIW_PKG.equals(it.pkg)) {
            Toast.makeText(ctx, R.string.home_wsiw_hint, Toast.LENGTH_LONG).show();
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

    /**
     * Open Fire TV's own Settings menu (the standard LEANBACK_SETTINGS entry any launcher may use) and
     * show a brief bubble saying where to go, for apps Fire OS won't let a third-party launcher open
     * directly. The Home redirect stands down for Settings, so the overlay does not cover it.
     */
    private void openTvSettings(int hintRes) {
        HomeRedirectService.suspend(30000L);
        Toast.makeText(ctx, hintRes, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); return; } catch (Throwable t) { }
        // Fallback: the launcher's main Settings activity by name.
        try {
            Intent e = new Intent(Intent.ACTION_MAIN)
                    .setClassName(AppModel.AMAZON_LAUNCHER, "com.amazon.tv.launcher.ui.MainSettingsActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(e); return;
        } catch (Throwable t) { }
        try { ctx.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable t) { }
    }
}
