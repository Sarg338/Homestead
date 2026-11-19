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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The setup guide: a skippable welcome, a choice of features, the setup screens for those
 * features, then the home screen's design and background. A new install opens on it, and
 * Settings opens it again. The launcher works whether or not it is finished.
 *
 * Renders inside the launcher window like the other panels (activity or overlay); Back
 * steps backward via HomeGrid.BackHandler. The steps that wait on the TV (ADB debugging,
 * the Home button, the ADB tunnel) poll the real state and show it live, but never
 * advance by themselves.
 *
 * Two shells. The setup steps fill the screen. The design, background and closing steps
 * are a sheet on the right edge with the user's own home screen beside it, as on the
 * Settings pages, so each choice shows there the moment it is made.
 *
 * Where it stands is saved (Prefs.wizardState) at every step: one step sends the user out
 * to Fire OS's settings, and if the app is restarted meanwhile it comes back on that step.
 */
public final class OnboardingPanel {

    // The app's palette, shared with Panel/HomeGrid.
    private static final int NAVY = 0xFF0B1F38, SHEET_BG = 0xFF0E2542;
    private static final int ROW = 0xFF102C4A, SHEET_ROW = 0x14FFFFFF, FOCUS = 0xFF1D4E86;
    private static final int HEAD = 0xFF7FA8D8, BODY = 0xFF9FB2C6, MUTE = 0xFF7C8FA4;
    private static final int WHITE = 0xFFF3F6FA, OK = 0xFFA7D28F, WARN = 0xFFE8C468, BAD = 0xFFE89A8A;
    private static final int GRAD1 = 0xFF6991C7, GRAD2 = 0xFFA3BDED;
    private static final int GLOW = 0x8CA3BDED, GHOST_LINE = 0x40FFFFFF;

    /** The steps. Saved in Prefs.wizardState, so a number is never changed or reused. */
    private static final int WELCOME = 0, CHOOSE = 1, HOME = 2, ADB = 3, DONE = 4, SIMPLE = 5,
            DEBUG = 6, DESIGN = 7, LOOK = 8;
    private static final int CARD_HOME = 0, CARD_CLEAN = 1, CARD_SIMPLE = 2;
    /** Switching off Debloat's safe list, from the clean-up step or Simple mode's. */
    private static final int CLEAN_IDLE = 0, CLEAN_RUNNING = 1, CLEAN_DONE = 2,
            CLEAN_FAILED = 3, CLEAN_NO_LINK = 4;
    /** A line of a list: something that will happen, has happened, or has not. */
    private static final int ITEM_PLAIN = 0, ITEM_DONE = 1, ITEM_OFF = 2;
    /** Presses are ignored this long after a step appears, so a double press does not act twice. */
    private static final long SETTLE_MS = 300L;
    /** The same for a button that appears by itself, while the user may still be pressing OK in a system prompt. */
    private static final long SURPRISE_MS = 1200L;
    /**
     * And for Back once this window is in front again: the way back from Fire OS's settings
     * is Back, several times, and one press too many must not step the guide back a page.
     */
    private static final long RETURN_MS = 1000L;

    /** One of the two shells a step is drawn in: the full page, or the sheet. */
    private static final class Frame {
        View box;
        /** Everything under the dots; faded in when the step changes. */
        View body;
        LinearLayout dots, head, content, actions;
        ScrollView scroll;
    }

    /** The root's tag: Back steps backward, and marks the view as the guide (isWizard). */
    private final class WizardBack implements HomeGrid.BackHandler {
        public boolean onBack() {
            if (closing || SystemClock.uptimeMillis() < backQuietUntil) return true;
            if (pos > 0) { back(); return true; }
            // On the first step Back closes the guide. A new install shows it again at the
            // next start, from the top.
            poll.removeCallbacks(tick);
            Prefs.setWizardState(c, null);
            return false;
        }
    }

    private final Context c;
    private final SettingsPanel.Host host;
    public final FrameLayout root;
    private final ColorDrawable scrim = new ColorDrawable(NAVY);
    private final Frame page, sheet;
    private final int sheetW;
    private Frame cur;
    private boolean inSheet;
    /** Null until the first step is drawn, which appears without the shells' transition. */
    private Boolean sheetUp;
    private ValueAnimator scrimAnim;
    private int shownStep = -1;
    private boolean forward = true;
    private long busyUntil, backQuietUntil;
    private boolean closing;

    private int pos = 0;                 // index into steps()
    private boolean wantHome, wantClean, wantSimple;
    /** Simple mode was switched on in this run, so unticking its card switches it back off. */
    private boolean simpleByWizard;
    private final Handler poll = new Handler(Looper.getMainLooper());
    private LinearLayout content, actions;
    private TextView chip;               // the status pill of the step shown, else null
    private View focusTarget;            // control to focus after a render
    private View downTarget;             // the button Down reaches from the step's controls
    private boolean wantSelfSetup;       // switch the Home button on once the link is up
    /** What the step shown was drawn for; a change redraws it, with the focus on the next thing to do. */
    private boolean debugOn, homeOn, adbLinked;
    private int cleanState = CLEAN_IDLE;
    private int cleanDone, cleanTotal;
    private TextView menuHint;           // the Menu x5 try-out line on the SIMPLE step
    private boolean menuTried;
    private final HomeGrid.MenuTaps menuTaps = new HomeGrid.MenuTaps();
    /** The language pill to land on after the welcome page is drawn again in that language. */
    private String focusLang;
    /** Landscape thumbnails by asset name, decoded once per guide. */
    private final Map<String, Bitmap> thumbs = new HashMap<String, Bitmap>();

    /** The guide for a new install, or one left open when the app was last closed. */
    public static View build(Context c, SettingsPanel.Host host) {
        return new OnboardingPanel(c, host, false).root;
    }

    /** @param again opened from Settings: starts at the top, whatever was saved. */
    public static View build(Context c, SettingsPanel.Host host, boolean again) {
        return new OnboardingPanel(c, host, again).root;
    }

    /** Whether a panel is the guide, which a Home press leaves open. */
    static boolean isWizard(View panel) {
        return panel != null && panel.getTag() instanceof WizardBack;
    }

    /**
     * What a new install starts with, set before its home screen is first drawn: the kind of
     * clock the TV itself uses. Nobody who already uses the app is changed by it.
     */
    static void firstRunDefaults(Context c) {
        if (!Prefs.clockChosen(c)) {
            Prefs.setClockMode(c, tv24Hour(c) ? Prefs.CLOCK_24H : Prefs.CLOCK_12H);
        }
    }

    /** The TV's own time format: its setting where Fire OS has one, else its language's. */
    private static boolean tv24Hour(Context c) {
        try {
            String v = Settings.System.getString(c.getContentResolver(), Settings.System.TIME_12_24);
            if ("24".equals(v)) return true;
            if ("12".equals(v)) return false;
        } catch (Throwable ignored) { }
        try {
            // The TV's language, not the one chosen for the app.
            Locale tv = android.content.res.Resources.getSystem().getConfiguration().locale;
            java.text.DateFormat f = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, tv);
            if (f instanceof java.text.SimpleDateFormat) {
                String p = ((java.text.SimpleDateFormat) f).toPattern().replaceAll("'[^']*'", "");
                return p.indexOf('H') >= 0 || p.indexOf('k') >= 0;
            }
        } catch (Throwable ignored) { }
        return true;
    }

    private OnboardingPanel(Context c, SettingsPanel.Host host, boolean again) {
        this.c = c;
        this.host = host;
        AdbBridge.init(c);

        // Overridden so Simple mode's step can let the helper try Menu x5 here.
        root = new FrameLayout(c) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && !closing && current() == SIMPLE) {
                    if (e.getAction() == KeyEvent.ACTION_UP && menuTaps.tap()) {
                        menuTried = true;
                        paintMenuHint();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }

            // Whoever asks the guide to take the focus (the grid does, when it shows a
            // page) gets the step's own starting point, not the first thing on the page.
            @Override public boolean requestFocus(int direction, android.graphics.Rect prev) {
                View f = focusTarget;
                if (f != null && f.isAttachedToWindow() && f.requestFocus()) return true;
                return super.requestFocus(direction, prev);
            }

            // Down from the step's own controls goes to the way forward, not to Back, which
            // happens to sit nearer the left edge.
            @Override public View focusSearch(View focused, int direction) {
                View next = super.focusSearch(focused, direction);
                if (direction == View.FOCUS_DOWN && next != null && focused != null
                        && downTarget != null && next != downTarget
                        && next.getParent() == actions && focused.getParent() != actions) {
                    return downTarget;
                }
                return next;
            }

            // Back from a settings screen or a system prompt: the last presses made there
            // may still be on their way.
            @Override public void onWindowFocusChanged(boolean has) {
                super.onWindowFocusChanged(has);
                if (!has) return;
                long now = SystemClock.uptimeMillis();
                backQuietUntil = now + RETURN_MS;
                busyUntil = Math.max(busyUntil, now + SETTLE_MS);
            }
        };
        // Opaque for the setup steps; cleared for the sheet, so the home screen shows.
        root.setBackground(scrim);
        root.setClickable(true);
        // Clickable to swallow touches; that must not make it take the D-pad's focus itself.
        root.setFocusable(false);
        root.setTag(new WizardBack());

        int screen = c.getResources().getDisplayMetrics().widthPixels;
        sheetW = Math.min(dp(400), screen / 2);
        page = buildPage();
        sheet = buildSheet();

        // What is not set up yet starts ticked. Simple mode is never assumed.
        wantHome = !SetupPanel.homeWorks(c);
        wantClean = !linked();
        int step = WELCOME;
        String saved = again ? null : Prefs.wizardState(c);
        if (saved != null) {
            try {
                String[] p = saved.split(",");
                step = Integer.parseInt(p[0]);
                wantHome = p[1].equals("1");
                wantClean = p[2].equals("1");
                wantSimple = p[3].equals("1");
                simpleByWizard = p[4].equals("1") && Prefs.simpleMode(c);
            } catch (Throwable t) {
                step = WELCOME;
            }
        }
        pos = Math.max(0, steps().indexOf(step));

        render();
        // Polls only while it is on screen, in whichever window that is.
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {
                poll.removeCallbacks(tick);
                if (!closing) poll.postDelayed(tick, 1000L);
            }
            public void onViewDetachedFromWindow(View v) { poll.removeCallbacks(tick); }
        });
    }

    /** The full page: the step dots, the step itself (scrolling if it must), the buttons. */
    private Frame buildPage() {
        Frame f = new Frame();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(40), dp(56), dp(40));
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        f.box = col;

        f.dots = dotsRow();
        col.addView(f.dots, wrap());

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = dp(28);
        col.addView(body, blp);
        f.body = body;

        // Scrolls so the three feature cards fit on any screen density; focus moving to
        // a card scrolls it into view.
        f.scroll = stepScroll();
        f.scroll.setVerticalScrollBarEnabled(false);
        f.scroll.setFocusable(false);
        body.addView(f.scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        f.content = new LinearLayout(c);
        f.content.setOrientation(LinearLayout.VERTICAL);
        f.scroll.addView(f.content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // A page's heading scrolls with the rest of it.
        f.head = f.content;

        f.actions = new LinearLayout(c);
        f.actions.setOrientation(LinearLayout.HORIZONTAL);
        f.actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(20);
        body.addView(f.actions, alp);
        return f;
    }

    /** The sheet: the dots and a heading that stay put, the choices (scrolling), the buttons. */
    private Frame buildSheet() {
        Frame f = new Frame();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(26), dp(28), dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET_BG);
        float r = dp(22);
        bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        box.setBackground(bg);
        box.setElevation(dp(16));
        box.setClipToPadding(false);
        box.setClipChildren(false);
        box.setVisibility(View.GONE);
        root.addView(box, new FrameLayout.LayoutParams(
                sheetW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        f.box = box;

        f.dots = dotsRow();
        box.addView(f.dots, wrap());

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = dp(18);
        box.addView(body, blp);
        f.body = body;

        f.head = new LinearLayout(c);
        f.head.setOrientation(LinearLayout.VERTICAL);
        body.addView(f.head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Wider than the text by the room a focused choice needs to grow into.
        f.scroll = stepScroll();
        f.scroll.setVerticalScrollBarEnabled(false);
        f.scroll.setFocusable(false);
        f.scroll.setClipToPadding(false);
        f.scroll.setPadding(dp(8), dp(6), dp(8), dp(10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(8);
        slp.leftMargin = -dp(8);
        slp.rightMargin = -dp(8);
        body.addView(f.scroll, slp);

        f.content = new LinearLayout(c);
        f.content.setOrientation(LinearLayout.VERTICAL);
        f.content.setClipChildren(false);
        f.content.setClipToPadding(false);
        f.scroll.addView(f.content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        f.actions = new LinearLayout(c);
        f.actions.setOrientation(LinearLayout.HORIZONTAL);
        f.actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(10);
        body.addView(f.actions, alp);
        return f;
    }

    /**
     * A ScrollView scrolls on Up or Down when nothing further in it can take the focus, and
     * reports the key handled: one press spent before the focus reaches the buttons. Here a
     * step's text never needs that, so the press goes straight on.
     */
    private ScrollView stepScroll() {
        return new ScrollView(c) {
            @Override public boolean arrowScroll(int direction) {
                View now = findFocus();
                if (now == this) now = null;
                View next = android.view.FocusFinder.getInstance().findNextFocus(this, now, direction);
                return next != null && super.arrowScroll(direction);
            }
        };
    }

    private LinearLayout dotsRow() {
        LinearLayout d = new LinearLayout(c);
        d.setOrientation(LinearLayout.HORIZONTAL);
        d.setGravity(Gravity.CENTER_VERTICAL);
        return d;
    }

    // ---------------------------------------------------------------- flow

    private List<Integer> steps() {
        List<Integer> s = new ArrayList<Integer>();
        s.add(WELCOME);
        s.add(CHOOSE);
        // Both setups go through the TV's debugging service, which has to be on first.
        if (wantHome || wantClean) s.add(DEBUG);
        if (wantHome) s.add(HOME);
        if (wantClean) s.add(ADB);
        if (wantSimple) s.add(SIMPLE);
        // Simple mode keeps its own plain layout, so it has no design to choose. One this
        // run switched on and whose card was then unticked is about to be switched off.
        boolean simple = wantSimple || (Prefs.simpleMode(c) && !simpleByWizard);
        if (!simple) s.add(DESIGN);
        s.add(LOOK);
        s.add(DONE);
        return s;
    }

    private int current() {
        List<Integer> s = steps();
        if (pos >= s.size()) pos = s.size() - 1;
        return s.get(pos);
    }

    private void next() {
        if (current() == CHOOSE) settleSimple();
        if (pos < steps().size() - 1) { pos++; forward = true; render(); }
    }

    private void back() {
        if (pos > 0) { pos--; forward = false; render(); }
    }

    /** Simple mode switched on in this run, its card unticked since: off again. */
    private void settleSimple() {
        if (wantSimple || !simpleByWizard) return;
        simpleByWizard = false;
        Prefs.setSimpleMode(c, false);
        host.rebuildGrid();
    }

    /**
     * Close the guide: finished, skipped, or closed from its first page. Nothing is applied
     * here that a step did not already apply when the user chose it.
     */
    private void finish() {
        if (closing) return;
        closing = true;
        poll.removeCallbacks(tick);
        settleSimple();
        Prefs.setWizardState(c, null);
        Prefs.setOnboarded(c, true);
        // Left without the Home button: the Setup page does not open at every start.
        if (!SetupPanel.homeWorks(c)) Prefs.setHomeDeclined(c, true);
        final Runnable close = new Runnable() {
            public void run() {
                host.closePanel();
                host.focusStart();
            }
        };
        if (!root.isAttachedToWindow()) { close.run(); return; }
        // The sheet slides off and the page fades, onto the home screen behind them.
        if (inSheet) {
            sheet.box.animate().translationX(sheetW).setDuration(200L)
                    .setInterpolator(new AccelerateInterpolator(1.5f)).withEndAction(close).start();
        } else {
            root.animate().alpha(0f).setDuration(180L).withEndAction(close).start();
        }
    }

    /** The ADB link is set up: connected now, or ready to be brought up when needed. */
    private boolean linked() { return AdbBridge.isReady(c); }

    private void save() {
        Prefs.setWizardState(c, current() + "," + bit(wantHome) + "," + bit(wantClean) + ","
                + bit(wantSimple) + "," + bit(simpleByWizard));
    }

    private static String bit(boolean b) { return b ? "1" : "0"; }

    // ---------------------------------------------------------------- render

    private void render() {
        if (closing) return;
        int id = current();
        boolean toSheet = id == DESIGN || id == LOOK || id == DONE;
        cur = toSheet ? sheet : page;
        inSheet = toSheet;
        cur.head.removeAllViews();
        cur.content.removeAllViews();
        cur.actions.removeAllViews();
        content = cur.content;
        actions = cur.actions;
        chip = null;
        menuHint = null;
        focusTarget = null;
        downTarget = null;

        switch (id) {
            case WELCOME: buildWelcome(); break;
            case CHOOSE:  buildChoose();  break;
            case DEBUG:   buildDebug();   break;
            case HOME:    buildHome();    break;
            case ADB:     buildAdb();     break;
            case SIMPLE:  buildSimple();  break;
            case DESIGN:  buildDesign();  break;
            case LOOK:    buildLook();    break;
            case DONE:    buildDone();    break;
        }
        buildDots();
        save();
        // Unless the step named one: its last button, which is the one that goes on.
        if (downTarget == null && actions.getChildCount() > 0) {
            downTarget = actions.getChildAt(actions.getChildCount() - 1);
        }

        boolean first = sheetUp == null;
        boolean swap = first || sheetUp != toSheet;
        if (id != shownStep) {
            shownStep = id;
            cur.scroll.scrollTo(0, 0);
            busyUntil = Math.max(busyUntil, SystemClock.uptimeMillis() + SETTLE_MS);
            // The new step eases in from the side it lies on; the shells have their own move.
            cur.body.animate().cancel();
            if (!first && !swap) {
                cur.body.setAlpha(0f);
                cur.body.setTranslationX(forward ? dp(18) : -dp(18));
                cur.body.animate().alpha(1f).translationX(0f).setDuration(170L)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            } else {
                cur.body.setAlpha(1f);
                cur.body.setTranslationX(0f);
            }
        }
        if (swap) showShell(toSheet, !first);

        final View f = focusTarget;
        if (f != null) {
            f.requestFocus();
            f.post(new Runnable() { public void run() { if (!closing) f.requestFocus(); } });
        }
    }

    /**
     * Bring one shell up and take the other away. To the sheet: the page fades, the navy
     * clears off the home screen, and the sheet slides in from the edge. Back the other way
     * for a step back.
     */
    private void showShell(final boolean toSheet, boolean animate) {
        sheetUp = toSheet;
        final View in = toSheet ? sheet.box : page.box;
        final View out = toSheet ? page.box : sheet.box;
        in.animate().cancel();
        out.animate().cancel();
        if (scrimAnim != null) scrimAnim.cancel();
        // The shell on its way out keeps its buttons for the fade; they must not take a press.
        ((ViewGroup) out).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        ((ViewGroup) in).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        in.setVisibility(View.VISIBLE);
        in.setAlpha(1f);
        in.setTranslationX(0f);
        if (!animate) {
            out.setVisibility(View.GONE);
            scrim.setAlpha(toSheet ? 0 : 255);
            return;
        }
        final Runnable gone = new Runnable() {
            public void run() {
                out.setVisibility(View.GONE);
                out.setAlpha(1f);
                out.setTranslationX(0f);
            }
        };
        if (toSheet) {
            in.setTranslationX(sheetW);
            in.animate().translationX(0f).setDuration(300L)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            out.animate().alpha(0f).setDuration(180L).withEndAction(gone).start();
        } else {
            in.setAlpha(0f);
            in.animate().alpha(1f).setDuration(220L).start();
            out.animate().translationX(sheetW).setDuration(220L)
                    .setInterpolator(new AccelerateInterpolator(1.5f)).withEndAction(gone).start();
        }
        scrimAnim = ValueAnimator.ofInt(scrim.getAlpha(), toSheet ? 0 : 255);
        scrimAnim.setDuration(280L);
        scrimAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                scrim.setAlpha((Integer) a.getAnimatedValue());
            }
        });
        scrimAnim.start();
        busyUntil = Math.max(busyUntil, SystemClock.uptimeMillis() + 320L);
    }

    private void buildDots() {
        LinearLayout dots = cur.dots;
        dots.removeAllViews();
        List<Integer> s = steps();
        for (int i = 0; i < s.size(); i++) {
            View d = new View(c);
            boolean active = (i == pos);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(4));
            g.setColor(active ? HEAD : (i < pos ? 0x66A3BDED : 0x33FFFFFF));
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(active ? 26 : 9), dp(9));
            lp.rightMargin = dp(7);
            dots.addView(d, lp);
        }
    }

    /** The step's heading: fixed at the top of the sheet, the first lines of a page. */
    private void header(String tagTxt, String titleTxt, String bodyTxt) {
        LinearLayout h = cur.head;
        h.addView(tag(tagTxt));
        h.addView(margin(title(titleTxt), dp(6), bodyTxt == null ? 0 : dp(6)));
        if (bodyTxt != null) h.addView(body(bodyTxt));
    }

    private String stepTag() { return s(R.string.onb_step, stepNo(), stepTotal()); }

    // ---------------------------------------------------------------- steps

    private void buildWelcome() {
        LinearLayout brand = new LinearLayout(c);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(glyph("⌂", dp(58)));
        TextView mark = text("Homestead", 40, WHITE, Typeface.BOLD);
        LinearLayout.LayoutParams mlp = wrap();
        mlp.leftMargin = dp(20);
        brand.addView(mark, mlp);
        content.addView(brand, wrap());

        content.addView(margin(title(s(R.string.onb_welcome_title)), dp(26), dp(10)));
        content.addView(body(s(R.string.onb_welcome_body)));

        // Every language at once, each under its own name; the page is drawn again in the
        // one picked, so the rest of the guide reads in it.
        content.addView(margin(tag(s(R.string.set_lang)), dp(26), dp(10)));
        LinearLayout langs = new LinearLayout(c);
        langs.setOrientation(LinearLayout.HORIZONTAL);
        View landOn = null;
        String now = Prefs.language(c);
        for (int i = 0; i < Lang.CODES.length; i++) {
            final String code = Lang.CODES[i];
            View pill = choicePill(Lang.label(c, code), code.equals(now), new Runnable() {
                public void run() {
                    if (code.equals(Prefs.language(c))) return;
                    Prefs.setLanguage(c, code);
                    HomeRedirectService.languageChanged(c);
                    focusLang = code;
                    render();
                }
            });
            LinearLayout.LayoutParams lp = wrap();
            lp.rightMargin = dp(10);
            langs.addView(pill, lp);
            if (code.equals(focusLang)) landOn = pill;
        }
        focusLang = null;
        content.addView(langs, wrap());

        View start = button(s(R.string.onb_start), true, new Runnable() { public void run() { next(); } });
        actions.addView(start);
        actions.addView(gap());
        // Opened again from Settings there is nothing to skip: it just closes.
        actions.addView(button(s(Prefs.onboarded(c) ? R.string.set_close : R.string.onb_skip), false,
                new Runnable() { public void run() { finish(); } }));
        focusTarget = landOn != null ? landOn : start;
        downTarget = start;
    }

    private void buildChoose() {
        header(stepTag(), s(R.string.onb_choose_title), s(R.string.onb_choose_body));

        content.addView(featureCard("⌂", s(R.string.onb_card_home),
                s(R.string.onb_card_home_desc), null,
                SetupPanel.homeWorks(c) ? s(R.string.onb_pill_on) : null, CARD_HOME));
        content.addView(featureCard("✦", s(R.string.onb_card_clean),
                s(R.string.onb_card_clean_desc), s(R.string.onb_card_clean_pill),
                linked() ? s(R.string.onb_pill_linked) : null, CARD_CLEAN));
        content.addView(featureCard("★", s(R.string.set_simple),
                s(R.string.onb_card_simple_desc), s(R.string.onb_card_simple_pill),
                Prefs.simpleMode(c) && !simpleByWizard ? s(R.string.onb_pill_on) : null, CARD_SIMPLE));

        View cont = button(s(R.string.onb_continue), true, new Runnable() { public void run() { next(); } });
        actions.addView(button(s(R.string.set_back), false, new Runnable() { public void run() { back(); } }));
        actions.addView(gap());
        actions.addView(cont);
        focusTarget = cont;
    }

    /** ADB debugging is a Fire OS setting only the user can switch on; this waits for it. */
    private void buildDebug() {
        debugOn = adbDebuggingOn();
        header(stepTag(), s(R.string.onb_dbg_title), s(R.string.onb_dbg_body));
        content.addView(miniStep("1", s(R.string.onb_dbg_step1), debugOn));
        content.addView(miniStep("2", s(R.string.onb_dbg_step2), debugOn));

        chip = pill();
        content.addView(wrapMargin(chip, dp(14), dp(4)));
        content.addView(note(s(R.string.onb_dbg_note)));
        updateChip();

        View open = button(s(R.string.onb_open_mft), !debugOn, new Runnable() {
            public void run() { SetupPanel.openDeviceSettings(c); }
        });
        View cont = button(s(R.string.onb_continue), debugOn, new Runnable() { public void run() { next(); } });
        actions.addView(button(s(R.string.set_back), false, new Runnable() { public void run() { back(); } }));
        actions.addView(gap());
        actions.addView(open);
        actions.addView(space(dp(10)));
        actions.addView(cont);
        focusTarget = debugOn ? cont : open;
    }

    private void buildHome() {
        homeOn = SetupPanel.homeWorks(c);
        header(stepTag(), s(R.string.onb_home_title), s(R.string.onb_home_body));
        content.addView(miniStep("1", s(R.string.onb_home_step1), homeOn));
        content.addView(miniStep("2", s(R.string.onb_home_step2), homeOn));

        chip = pill();
        content.addView(wrapMargin(chip, dp(14), dp(4)));
        content.addView(note(s(R.string.onb_home_note)));
        updateChip();

        View setup = button(s(R.string.onb_set_up), !homeOn, new Runnable() {
            public void run() { setUpHome(); }
        });
        View cont = button(s(R.string.onb_continue), homeOn, new Runnable() { public void run() { next(); } });
        actions.addView(button(s(R.string.set_back), false, new Runnable() { public void run() { back(); } }));
        actions.addView(gap());
        actions.addView(setup);
        actions.addView(space(dp(10)));
        actions.addView(cont);
        focusTarget = homeOn ? cont : setup;
    }

    private boolean adbDebuggingOn() {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable t) { return false; }
    }

    private void setUpHome() {
        wantSelfSetup = true;
        if (AdbBridge.isConnected()) runSelfSetup();
        else SetupPanel.startTunnel(c);
    }

    /** The same commands as Setup's "Finish setup automatically". */
    private void runSelfSetup() {
        wantSelfSetup = false;
        String current = null;
        try {
            current = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Throwable ignored) { }
        AdbBridge.run(AdbBridge.selfSetupCommands(c, current), new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) {
                HomeRedirectService.startHomeKeyWatch();
                if (root.isAttachedToWindow()) live();
            }
        });
    }

    /** The clean-up step: the ADB link, then the offer to switch off Debloat's safe list. */
    private void buildAdb() {
        adbLinked = linked();
        // Ticked off only when the whole list went; a part of it is said in the status line.
        boolean cleaned = cleanState == CLEAN_DONE && cleanDone == cleanTotal;
        header(stepTag(), s(R.string.onb_adb_title), s(R.string.onb_adb_body));
        content.addView(miniStep("1", s(R.string.onb_adb_step), adbLinked));
        content.addView(miniStep("2", s(R.string.onb_adb_step2), cleaned));

        chip = pill();
        content.addView(wrapMargin(chip, dp(14), dp(4)));
        content.addView(note(s(R.string.onb_adb_note)));
        if (cleanState == CLEAN_IDLE) updateChip(); else paintClean();

        actions.addView(button(s(R.string.set_back), false, new Runnable() { public void run() { back(); } }));
        actions.addView(gap());
        View cont = button(s(R.string.onb_continue),
                cleanState == CLEAN_DONE || cleanState == CLEAN_RUNNING,
                new Runnable() { public void run() { next(); } });
        View act = null;
        if (!adbLinked) {
            act = button(s(R.string.onb_set_up), true, new Runnable() {
                public void run() { SetupPanel.startTunnel(c); }
            });
        } else if (cleanState == CLEAN_IDLE || cleanState == CLEAN_NO_LINK
                || cleanState == CLEAN_FAILED) {
            // Nothing is switched off until this is pressed.
            act = button(s(cleanState == CLEAN_FAILED ? R.string.onb_try_again : R.string.onb_clean_now),
                    true, new Runnable() { public void run() { startClean(); render(); } });
        }
        if (act != null) {
            actions.addView(act);
            actions.addView(space(dp(10)));
        }
        actions.addView(cont);
        focusTarget = act != null ? act : cont;
    }

    private void buildSimple() {
        final boolean on = Prefs.simpleMode(c);
        header(stepTag(), s(R.string.set_simple), null);
        int state = on ? ITEM_DONE : ITEM_PLAIN;
        content.addView(checkItem(state, s(R.string.onb_simple_1)));
        content.addView(checkItem(state, s(R.string.onb_simple_2)));
        content.addView(checkItem(state, s(R.string.onb_simple_3)));
        content.addView(checkItem(cleanState == CLEAN_DONE ? ITEM_DONE : ITEM_PLAIN,
                s(R.string.onb_simple_4)));

        if (on) {
            chip = pill();
            content.addView(wrapMargin(chip, dp(14), dp(4)));
            if (cleanState == CLEAN_IDLE) setChip(OK, s(R.string.onb_simple_is_on)); else paintClean();
        }
        menuHint = note("");
        content.addView(menuHint);
        paintMenuHint();

        actions.addView(button(s(R.string.set_back), false, new Runnable() { public void run() { back(); } }));
        actions.addView(gap());
        if (!on) {
            // The confirmation: nothing is hidden or switched off before this press.
            View turnOn = button(s(R.string.onb_simple_turn_on), true, new Runnable() {
                public void run() {
                    Prefs.setSimpleMode(c, true);
                    simpleByWizard = true;
                    host.rebuildGrid();
                    if (cleanState != CLEAN_RUNNING && cleanState != CLEAN_DONE) startClean();
                    render();
                }
            });
            actions.addView(turnOn);
            focusTarget = turnOn;
            return;
        }
        // On already (before this run, or the page was left and reopened): the safe list is
        // offered, not run. Also the way to try again once the connection is there.
        boolean notRun = cleanState == CLEAN_IDLE || cleanState == CLEAN_FAILED
                || cleanState == CLEAN_NO_LINK;
        if (notRun && AdbBridge.canRun(c)) {
            actions.addView(button(s(cleanState == CLEAN_FAILED ? R.string.onb_try_again : R.string.onb_clean_now),
                    false, new Runnable() { public void run() { startClean(); render(); } }));
            actions.addView(space(dp(10)));
        }
        View cont = button(s(R.string.onb_continue), true, new Runnable() { public void run() { next(); } });
        actions.addView(cont);
        focusTarget = cont;
    }

    /** Switch off the Debloat page's safe list except Amazon Photos; never Alexa. */
    private void startClean() {
        if (!AdbBridge.canRun(c)) { cleanState = CLEAN_NO_LINK; return; }
        int n = DebloatPanel.switchOffGroup(c, Debloat.SAFE,
                java.util.Collections.singleton(Debloat.AMAZON_PHOTOS),
                new DebloatPanel.BulkResult() {
            public void onResult(boolean connected, int done, int total) {
                cleanState = connected ? CLEAN_DONE : CLEAN_FAILED;
                cleanDone = done;
                cleanTotal = total;
                if (!root.isAttachedToWindow() || closing) return;
                // A switched-off app's tile leaves the home screen beside the later steps.
                if (connected && done > 0) {
                    AppModel.reload(c);
                    host.rebuildGrid();
                }
                int id = current();
                if (id == ADB || id == SIMPLE || id == DONE) render();
            }
        });
        if (n == 0) { cleanState = CLEAN_DONE; cleanDone = 0; cleanTotal = 0; }
        else cleanState = CLEAN_RUNNING;
    }

    /** What the safe list's run came to, for the status pill and the last page. */
    private String cleanText() {
        if (cleanState == CLEAN_RUNNING) return s(R.string.onb_clean_running);
        if (cleanState == CLEAN_DONE && cleanTotal == 0) return s(R.string.onb_clean_already);
        if (cleanState == CLEAN_DONE && cleanDone == cleanTotal) {
            return c.getResources().getQuantityString(R.plurals.onb_clean_done, cleanDone, cleanDone);
        }
        if (cleanState == CLEAN_DONE) {
            return c.getResources().getQuantityString(R.plurals.onb_clean_partial, cleanDone,
                    cleanDone, cleanTotal);
        }
        if (cleanState == CLEAN_FAILED) return s(R.string.onb_clean_failed);
        return s(R.string.onb_clean_no_link);
    }

    private void paintClean() {
        if (chip == null) return;
        boolean whole = cleanState == CLEAN_DONE && cleanDone == cleanTotal;
        setChip(whole ? OK : cleanState == CLEAN_FAILED ? BAD : WARN, cleanText());
    }

    private void paintMenuHint() {
        if (menuHint == null) return;
        menuHint.setText(s(menuTried ? R.string.onb_menu_done : R.string.onb_menu_try));
        menuHint.setTextColor(menuTried ? OK : MUTE);
    }

    /** Classic or Dock, and the dock's size, tried on the home screen beside the sheet. */
    private void buildDesign() {
        header(stepTag(), s(R.string.onb_design_title), s(R.string.onb_design_body));

        final int[] names = { R.string.opt_design_classic, R.string.opt_design_dock };
        final int[] descs = { R.string.onb_design_classic, R.string.onb_design_dock };
        final TextView[] radios = new TextView[names.length];
        final View size = dockSizeRow();
        final Runnable paint = new Runnable() {
            public void run() {
                int d = Prefs.design(c);
                for (int i = 0; i < radios.length; i++) paintRadio(radios[i], i == d);
                size.setVisibility(d == Prefs.DESIGN_DOCK ? View.VISIBLE : View.GONE);
            }
        };
        View chosen = null;
        for (int i = 0; i < names.length; i++) {
            final int design = i;
            radios[i] = radio();
            View card = optionCard(new LayoutGlyph(i == Prefs.DESIGN_DOCK), s(names[i]), s(descs[i]),
                    radios[i], new Runnable() {
                public void run() {
                    if (Prefs.design(c) == design) return;
                    Prefs.setDesign(c, design);
                    host.rebuildGrid();
                    paint.run();
                }
            });
            content.addView(card);
            if (i == Prefs.design(c)) chosen = card;
        }
        content.addView(size);
        paint.run();

        sheetActions();
        focusTarget = chosen;
    }

    /** The colours and the landscapes shipped in the app, each row scrolling sideways. */
    private void buildLook() {
        header(stepTag(), s(R.string.onb_look_title), s(R.string.onb_look_body));

        final Map<String, View> marks = new HashMap<String, View>();
        final Map<String, View> cells = new HashMap<String, View>();
        final List<LookRow> rows = new ArrayList<LookRow>();
        // The tick moves to the look in use, and each row's caption follows.
        final Runnable paint = new Runnable() {
            public void run() {
                String now = Backgrounds.currentId(c);
                for (Map.Entry<String, View> e : marks.entrySet()) {
                    e.getValue().setVisibility(e.getKey().equals(now) ? View.VISIBLE : View.INVISIBLE);
                }
                for (int i = 0; i < rows.size(); i++) rows.get(i).rest();
            }
        };

        content.addView(sectionLabel(s(R.string.onb_look_colors), true));
        LookRow colors = new LookRow(marks, cells, paint);
        rows.add(colors);
        for (int i = 0; i < Backgrounds.PRESETS.length; i++) {
            Backgrounds.Preset p = Backgrounds.PRESETS[i];
            ImageView img = colors.add(p.id, Backgrounds.name(c, p));
            img.setImageDrawable(Backgrounds.drawable(p));
        }

        final String[] scenes = Backgrounds.landscapes(c);
        if (scenes.length > 0) {
            content.addView(sectionLabel(s(R.string.bg_landscapes), false));
            LookRow land = new LookRow(marks, cells, paint);
            rows.add(land);
            final List<String> files = new ArrayList<String>();
            final List<ImageView> views = new ArrayList<ImageView>();
            for (int i = 0; i < scenes.length; i++) {
                ImageView img = land.add(Backgrounds.landscapeId(scenes[i]),
                        Backgrounds.landscapeName(c, scenes[i]));
                Bitmap have = thumbs.get(scenes[i]);
                if (have != null) {
                    img.setImageBitmap(have);
                } else {
                    files.add(scenes[i]);
                    views.add(img);
                }
            }
            loadThumbs(files, views);
        }
        content.addView(note(s(R.string.onb_look_more)));
        paint.run();

        sheetActions();
        // On the look in use; a photo or Bing's image (set before, in Settings) has no tile.
        View chosen = cells.get(Backgrounds.currentId(c));
        focusTarget = chosen != null ? chosen : cells.get(Backgrounds.PRESETS[0].id);
    }

    /** Decode the landscapes' thumbnails off the main thread, filling each tile as it lands. */
    private void loadThumbs(final List<String> files, final List<ImageView> views) {
        if (files.isEmpty()) return;
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < files.size(); i++) {
                    final String file = files.get(i);
                    final ImageView into = views.get(i);
                    // An eighth each way: about the tile's own size on a 1080p screen.
                    final Bitmap b = Backgrounds.landscapeThumb(app, file, 8);
                    poll.post(new Runnable() {
                        public void run() {
                            if (b == null) return;
                            thumbs.put(file, b);
                            into.setImageBitmap(b);
                        }
                    });
                }
            }
        }, "homestead-thumbs").start();
    }

    private void buildDone() {
        header(s(R.string.onb_all_set), s(R.string.onb_ready), null);

        boolean home = SetupPanel.homeWorks(c);
        if (wantHome || home) {
            content.addView(checkItem(home ? ITEM_DONE : ITEM_OFF,
                    s(home ? R.string.onb_done_home_on : R.string.onb_done_home_skip)));
        }
        // The safe list, whichever step ran it, and whether or not its card is still ticked.
        if (cleanState == CLEAN_RUNNING) {
            content.addView(checkItem(ITEM_OFF, cleanText()));
        } else if (cleanState == CLEAN_DONE) {
            content.addView(checkItem(cleanDone == cleanTotal ? ITEM_DONE : ITEM_OFF, cleanText()));
        } else if (cleanState != CLEAN_IDLE) {
            content.addView(checkItem(ITEM_OFF, s(R.string.onb_done_safe_not)));
        } else if (wantClean) {
            boolean link = linked();
            content.addView(checkItem(link ? ITEM_DONE : ITEM_OFF,
                    s(link ? R.string.onb_done_clean_on : R.string.onb_done_clean_skip)));
        }
        boolean simple = Prefs.simpleMode(c);
        if (simple) content.addView(checkItem(ITEM_DONE, s(R.string.onb_done_simple)));

        // Where Settings is depends on what was just chosen: Simple mode hides it, Dock and
        // the shortcut bar have it as a button at the top, otherwise it is a tile.
        boolean bar = Prefs.dockLayout(c) || Prefs.shortcutsPlace(c) == Prefs.SHORTCUTS_BAR;
        content.addView(margin(body(s(simple ? R.string.onb_done_simple_body
                : bar ? R.string.onb_done_body_bar : R.string.onb_done_body)), dp(16), dp(4)));

        View fin = button(s(R.string.onb_finish), true, new Runnable() { public void run() { finish(); } });
        actions.addView(button(s(R.string.set_back), false, new Runnable() { public void run() { back(); } }));
        actions.addView(gap());
        actions.addView(fin);
        focusTarget = fin;
    }

    /** Back and Continue, as every step of the sheet but the last has them. */
    private void sheetActions() {
        actions.addView(button(s(R.string.set_back), false, new Runnable() { public void run() { back(); } }));
        actions.addView(gap());
        actions.addView(button(s(R.string.onb_continue), true, new Runnable() { public void run() { next(); } }));
    }

    // ---------------------------------------------------------------- live status

    private final Runnable tick = new Runnable() {
        public void run() {
            if (closing || !root.isAttachedToWindow()) return;
            if (wantSelfSetup && AdbBridge.isConnected()) runSelfSetup();
            live();
            poll.postDelayed(this, 1000L);
        }
    };

    /**
     * Catch up with the TV. When what a step waits for has happened (ADB debugging switched
     * on in Fire OS's settings, the Home button on, the link up) the step is drawn again:
     * ticked off, and with the focus on what comes next.
     */
    private void live() {
        if (closing) return;
        int id = current();
        if (id == DEBUG) {
            if (adbDebuggingOn() != debugOn) render();
        } else if (id == HOME) {
            if (SetupPanel.homeWorks(c) != homeOn) render(); else updateChip();
        } else if (id == ADB) {
            if (linked() != adbLinked) {
                render();
                // The offer to switch the safe list off has just appeared under a focus that
                // may still be taking presses meant for the key prompt.
                busyUntil = Math.max(busyUntil, SystemClock.uptimeMillis() + SURPRISE_MS);
            } else if (cleanState == CLEAN_IDLE) {
                updateChip();
            }
        }
    }

    private void updateChip() {
        if (chip == null) return;
        int id = current();
        if (id == DEBUG) {
            setChip(debugOn ? OK : WARN, s(debugOn ? R.string.onb_dbg_on : R.string.onb_dbg_off));
        } else if (id == HOME && SetupPanel.homeWorks(c)) {
            setChip(OK, s(R.string.onb_chip_on));
        } else if (id == HOME || id == ADB) {
            int st = AdbBridge.state();
            String fp = AdbBridge.fingerprint();
            if (st == AdbBridge.CONNECTED) {
                setChip(OK, s(id == HOME ? R.string.onb_chip_switching : R.string.onb_chip_ready));
            } else if (id == ADB && linked()) {
                setChip(OK, s(R.string.onb_chip_set_up));
            } else if (st == AdbBridge.AWAITING_APPROVAL) {
                setChip(WARN, fp == null ? s(R.string.onb_chip_approve)
                        : s(R.string.onb_chip_approve_fp, SetupPanel.shortFingerprint(fp)));
            } else if (st == AdbBridge.CONNECTING) setChip(WARN, s(R.string.ob_c_connecting));
            else if (st == AdbBridge.SELF_REFUSED) setChip(BAD, s(R.string.onb_chip_blocked));
            else if (st == AdbBridge.NO_DAEMON) setChip(BAD, s(R.string.onb_chip_no_daemon));
            else setChip(MUTE, s(R.string.onb_chip_not_yet));
        }
    }

    private void setChip(int color, String label) {
        chip.setText("  ●  " + label);
        chip.setTextColor(color);
        GradientDrawable g = (GradientDrawable) chip.getBackground();
        g.setColor((color & 0x00FFFFFF) | 0x1F000000);
    }

    // ---------------------------------------------------------------- widgets

    /** Whether a press counts: not while a step is still arriving, or the guide leaving. */
    private boolean pressable() {
        return !closing && SystemClock.uptimeMillis() >= busyUntil;
    }

    private View button(String label, final boolean primary, final Runnable onClick) {
        final TextView b = new TextView(c);
        b.setText(label);
        b.setTextColor(primary ? WHITE : BODY);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, inSheet ? 15 : 16);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        if (inSheet) b.setPadding(dp(22), dp(12), dp(22), dp(12));
        else b.setPadding(dp(26), dp(13), dp(26), dp(13));
        final int rest = primary ? (inSheet ? 0x24FFFFFF : ROW) : Color.TRANSPARENT;
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(rest);
        if (!primary) bg.setStroke(dp(1), GHOST_LINE);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                bg.setColor(has ? FOCUS : rest);
                bg.setStroke(has ? dp(2) : (primary ? 0 : dp(1)), has ? GLOW : GHOST_LINE);
                v.setTranslationZ(has ? dp(6) : 0f);
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (pressable()) onClick.run(); }
        });
        return b;
    }

    /** One of a row of choices of which exactly one is in use, marked with a tick. */
    private View choicePill(String label, final boolean on, final Runnable onClick) {
        final TextView b = new TextView(c);
        b.setText(on ? "✓  " + label : label);
        b.setTextColor(on ? WHITE : BODY);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.create(on ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(9), dp(18), dp(9));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(on ? ROW : Color.TRANSPARENT);
        bg.setStroke(dp(1), on ? HEAD : GHOST_LINE);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                bg.setColor(has ? FOCUS : (on ? ROW : Color.TRANSPARENT));
                bg.setStroke(has ? dp(2) : dp(1), has ? GLOW : (on ? HEAD : GHOST_LINE));
                v.setTranslationZ(has ? dp(6) : 0f);
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (pressable()) onClick.run(); }
        });
        return b;
    }

    private View featureCard(String glyph, String titleTxt, String desc, String pillTxt,
                             String stateTxt, final int kind) {
        final FrameLayout cell = new FrameLayout(c);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(ROW);
        cell.setBackground(bg);
        cell.setPadding(dp(18), dp(16), dp(18), dp(16));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView check = new TextView(c);
        check.setGravity(Gravity.CENTER);
        check.setTextColor(NAVY);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        int cs = dp(26);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(cs, cs);
        klp.rightMargin = dp(18);
        row.addView(check, klp);
        paintCheck(check, wanted(kind));

        View g = glyph(glyph, dp(44));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(44), dp(44));
        glp.rightMargin = dp(18);
        row.addView(g, glp);

        LinearLayout tcol = new LinearLayout(c);
        tcol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tl = new LinearLayout(c);
        tl.setOrientation(LinearLayout.HORIZONTAL);
        tl.setGravity(Gravity.CENTER_VERTICAL);
        tl.addView(text(titleTxt, 17, WHITE, Typeface.NORMAL));
        if (pillTxt != null) tl.addView(marginH(badge(pillTxt, WARN), dp(12)));
        // Already set up on this TV: said on the card, which stays free to tick again.
        if (stateTxt != null) tl.addView(marginH(badge(stateTxt, OK), dp(pillTxt != null ? 8 : 12)));
        tcol.addView(tl, wrap());
        tcol.addView(margin(body(desc), dp(3), 0));
        row.addView(tcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        cell.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                bg.setColor(has ? FOCUS : ROW);
                bg.setStroke(has ? dp(2) : 0, has ? GLOW : 0);
                v.setTranslationZ(has ? dp(5) : 0f);
            }
        });
        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!pressable()) return;
                // None of the three is required: the design and background steps follow
                // whatever is ticked here.
                if (kind == CARD_HOME) wantHome = !wantHome;
                else if (kind == CARD_CLEAN) wantClean = !wantClean;
                else wantSimple = !wantSimple;
                paintCheck(check, wanted(kind));
                buildDots();
                save();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        cell.setLayoutParams(lp);
        return cell;
    }

    private boolean wanted(int kind) {
        return kind == CARD_HOME ? wantHome : kind == CARD_CLEAN ? wantClean : wantSimple;
    }

    private void paintCheck(TextView check, boolean on) {
        check.setText(on ? "✓" : "");
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        if (on) g.setColor(OK); else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(2), 0x80A0B0C0); }
        check.setBackground(g);
    }

    /** A numbered instruction; its number becomes a tick once it is done. */
    private View miniStep(String n, String s, boolean done) {
        LinearLayout m = new LinearLayout(c);
        m.setOrientation(LinearLayout.HORIZONTAL);
        m.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable back = new GradientDrawable();
        back.setCornerRadius(dp(8));
        back.setColor(0x0AFFFFFF);
        m.setBackground(back);
        m.setPadding(dp(14), dp(11), dp(16), dp(11));

        TextView num = new TextView(c);
        num.setText(done ? "✓" : n);
        num.setGravity(Gravity.CENTER);
        num.setTextColor(NAVY);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable ng = new GradientDrawable();
        ng.setShape(GradientDrawable.OVAL);
        ng.setColor(done ? OK : HEAD);
        num.setBackground(ng);
        int ns = dp(24);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ns, ns);
        nlp.rightMargin = dp(12);
        m.addView(num, nlp);
        m.addView(body(s), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(9);
        m.setLayoutParams(lp);
        return m;
    }

    private TextView pill() {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(16), dp(11), dp(20), dp(11));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(0x1FE8C468);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t.setLayoutParams(lp);
        return t;
    }

    private View checkItem(int state, String s) {
        LinearLayout m = new LinearLayout(c);
        m.setOrientation(LinearLayout.HORIZONTAL);
        // Against the first line, so a line that wraps in the sheet keeps its mark at the top.
        m.setGravity(Gravity.TOP);
        TextView bx = new TextView(c);
        bx.setText(state == ITEM_DONE ? "✓" : "•");
        bx.setGravity(Gravity.CENTER);
        bx.setTextColor(state == ITEM_DONE ? NAVY : state == ITEM_PLAIN ? HEAD : MUTE);
        bx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bx.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        // Only what has happened, or has not, gets a box; a plain line is a bullet.
        if (state == ITEM_DONE) g.setColor(OK);
        else { g.setColor(Color.TRANSPARENT); if (state == ITEM_OFF) g.setStroke(dp(2), MUTE); }
        bx.setBackground(g);
        int s2 = dp(inSheet ? 22 : 24);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(s2, s2);
        blp.rightMargin = dp(inSheet ? 12 : 14);
        m.addView(bx, blp);
        TextView t = text(s, inSheet ? 15 : 17, state == ITEM_OFF ? MUTE : WHITE, Typeface.NORMAL);
        if (inSheet) t.setLineSpacing(dp(2), 1f);
        m.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(inSheet ? 12 : 10);
        m.setLayoutParams(lp);
        return m;
    }

    private View glyph(String s, int fontPx) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx * 0.5f);
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{ GRAD1, GRAD2 });
        g.setCornerRadius(dp(10));
        t.setBackground(g);
        int sz = fontPx;
        t.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        return t;
    }

    private View badge(String s, int color) {
        TextView t = new TextView(c);
        t.setText(s.toUpperCase(Locale.getDefault()));
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setLetterSpacing(0.08f);
        t.setSingleLine(true);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), (color & 0x00FFFFFF) | 0x80000000);
        t.setBackground(g);
        return t;
    }

    // ---------------------------------------------------------------- the sheet's choices

    /** A design to pick: a small drawing of it, its name, a line about it, and its mark. */
    private View optionCard(Drawable icon, String titleTxt, String desc, TextView radio,
                            final Runnable onClick) {
        final LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(SHEET_ROW);
        cell.setBackground(bg);
        cell.setPadding(dp(14), dp(13), dp(14), dp(13));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View pic = new View(c);
        pic.setBackground(icon);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(58), dp(36));
        plp.rightMargin = dp(14);
        top.addView(pic, plp);
        TextView t = text(titleTxt, 18, WHITE, Typeface.NORMAL);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int rs = dp(22);
        top.addView(radio, new LinearLayout.LayoutParams(rs, rs));
        cell.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView d = text(desc, 13, BODY, Typeface.NORMAL);
        d.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(9);
        cell.addView(d, dlp);

        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                bg.setColor(has ? FOCUS : SHEET_ROW);
                bg.setStroke(has ? dp(2) : 0, has ? GLOW : 0);
                v.setTranslationZ(has ? dp(5) : 0f);
            }
        });
        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (pressable()) onClick.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        cell.setLayoutParams(lp);
        return cell;
    }

    private TextView radio() {
        TextView r = new TextView(c);
        r.setGravity(Gravity.CENTER);
        r.setTextColor(NAVY);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        r.setTypeface(Typeface.DEFAULT_BOLD);
        return r;
    }

    private void paintRadio(TextView r, boolean on) {
        r.setText(on ? "✓" : "");
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (on) g.setColor(OK); else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(2), 0x80A0B0C0); }
        r.setBackground(g);
    }

    /**
     * The dock's size, shown with Dock only: Left and Right step through the four sizes, and
     * OK goes round them. The sheet is on the screen's edge with nothing beside this row, so
     * taking those two keys costs no way out of it.
     */
    private View dockSizeRow() {
        final int[] sizes = { R.string.opt_dock_s, R.string.opt_dock_m, R.string.opt_dock_l, R.string.opt_dock_xl };
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(16), dp(8), dp(10), dp(8));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(SHEET_ROW);
        row.setBackground(bg);

        TextView label = text(s(R.string.opt_dock_size), 15, WHITE, Typeface.NORMAL);
        label.setMaxLines(2);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView less = text("‹", 22, WHITE, Typeface.NORMAL);
        final TextView value = text("", 15, OK, Typeface.NORMAL);
        final TextView more = text("›", 22, WHITE, Typeface.NORMAL);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        less.setPadding(dp(8), 0, dp(8), dp(2));
        more.setPadding(dp(8), 0, dp(8), dp(2));
        row.addView(less, wrap());
        row.addView(value, wrap());
        row.addView(more, wrap());

        final Runnable paint = new Runnable() {
            public void run() {
                int n = Prefs.dockSize(c);
                value.setText(s(sizes[n]));
                less.setAlpha(n > 0 ? 1f : 0.25f);
                more.setAlpha(n < sizes.length - 1 ? 1f : 0.25f);
            }
        };
        paint.run();
        final Runnable[] set = new Runnable[1];
        row.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN && pressable()) {
                    int n = Prefs.dockSize(c) + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (n >= 0 && n < sizes.length) {
                        Prefs.setDockSize(c, n);
                        host.rebuildGrid();
                        paint.run();
                    }
                }
                return true;
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!pressable()) return;
                Prefs.setDockSize(c, (Prefs.dockSize(c) + 1) % sizes.length);
                host.rebuildGrid();
                paint.run();
            }
        });
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                bg.setColor(has ? FOCUS : SHEET_ROW);
                bg.setStroke(has ? dp(2) : 0, has ? GLOW : 0);
                v.setTranslationZ(has ? dp(5) : 0f);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView sectionLabel(String s, boolean first) {
        TextView h = text(s.toUpperCase(Locale.getDefault()), 12, HEAD, Typeface.NORMAL);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = first ? dp(2) : dp(8);
        h.setLayoutParams(lp);
        return h;
    }

    /**
     * One row of backgrounds, scrolling sideways, over a line that names the tile in focus
     * (or, while the focus is elsewhere, the row's tile in use). OK on a tile makes it the
     * background at once.
     */
    private final class LookRow {
        private final LinearLayout strip;
        private final TextView caption;
        private final Map<String, View> marks, cells;
        private final Map<String, String> names = new HashMap<String, String>();
        private final Runnable paint;
        /** The name of this row's tile in focus, or null. */
        private String focused;

        LookRow(Map<String, View> marks, Map<String, View> cells, Runnable paint) {
            this.marks = marks;
            this.cells = cells;
            this.paint = paint;

            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipToPadding(false);
            hs.setClipChildren(false);
            // The row runs to the sheet's edges, so the next tile shows cut off: more that way.
            hs.setPadding(dp(8), dp(7), dp(8), dp(5));
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.leftMargin = -dp(8);
            hlp.rightMargin = -dp(8);
            content.addView(hs, hlp);

            strip = new LinearLayout(c);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            hs.addView(strip, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            caption = text(" ", 14, MUTE, Typeface.NORMAL);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(caption, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        /** The caption: the tile in focus, else this row's tile in use, else nothing. */
        void rest() {
            String name = focused != null ? focused : names.get(Backgrounds.currentId(c));
            caption.setText(name == null ? " " : name);
            caption.setTextColor(focused != null ? WHITE : MUTE);
        }

        /** @return the tile's picture, for the caller to fill. */
        ImageView add(final String id, final String name) {
            names.put(id, name);
            final FrameLayout cell = new FrameLayout(c);
            cell.setFocusable(true);
            cell.setFocusableInTouchMode(true);
            int ring = dp(3);
            cell.setPadding(ring, ring, ring, ring);
            final GradientDrawable edge = new GradientDrawable();
            edge.setCornerRadius(dp(11));
            edge.setColor(Color.TRANSPARENT);
            cell.setBackground(edge);

            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(0x14FFFFFF);
            final int r = dp(8);
            img.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            img.setClipToOutline(true);
            cell.addView(img, new FrameLayout.LayoutParams(dp(116), dp(65)));

            // A faint edge, so the dark looks show on the dark sheet.
            View rim = new View(c);
            GradientDrawable rg = new GradientDrawable();
            rg.setCornerRadius(r);
            rg.setColor(Color.TRANSPARENT);
            rg.setStroke(dp(1), 0x38FFFFFF);
            rim.setBackground(rg);
            cell.addView(rim, new FrameLayout.LayoutParams(dp(116), dp(65)));

            TextView mark = new TextView(c);
            mark.setText("✓");
            mark.setGravity(Gravity.CENTER);
            mark.setTextColor(NAVY);
            mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            mark.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable mg = new GradientDrawable();
            mg.setShape(GradientDrawable.OVAL);
            mg.setColor(OK);
            mg.setStroke(dp(2), SHEET_BG);
            mark.setBackground(mg);
            FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(22), dp(22),
                    Gravity.TOP | Gravity.END);
            mlp.topMargin = dp(5);
            mlp.rightMargin = dp(5);
            cell.addView(mark, mlp);
            marks.put(id, mark);
            cells.put(id, cell);

            cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    edge.setStroke(has ? dp(2) : 0, WHITE);
                    v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120L).start();
                    v.setTranslationZ(has ? dp(6) : 0f);
                    if (has) focused = name;
                    else if (name.equals(focused)) focused = null;
                    rest();
                }
            });
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!pressable() || id.equals(Backgrounds.currentId(c))) return;
                    Backgrounds.choose(c, id);
                    host.rebuildGrid();
                    paint.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (strip.getChildCount() > 0) lp.leftMargin = dp(6);
            strip.addView(cell, lp);
            return img;
        }
    }

    /** A design in miniature: Classic's headed rows, or Dock's band along the bottom. */
    private final class LayoutGlyph extends Drawable {
        private final boolean dock;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        LayoutGlyph(boolean dock) { this.dock = dock; }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width(), h = b.height(), u = w / 58f;
            canvas.save();
            canvas.translate(b.left, b.top);
            // The screen.
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1FFFFFFF);
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, 4 * u, 4 * u, p);
            if (dock) {
                // The clock at the top left, the band, and its tiles.
                p.setColor(0x80FFFFFF);
                r.set(5 * u, 5 * u, 17 * u, 8 * u);
                canvas.drawRoundRect(r, u, u, p);
                p.setColor(0x33FFFFFF);
                r.set(4 * u, h - 15 * u, w - 4 * u, h - 4 * u);
                canvas.drawRoundRect(r, 2.5f * u, 2.5f * u, p);
                p.setColor(GRAD2);
                for (int i = 0; i < 4; i++) {
                    float x = 6.5f * u + i * 11.5f * u;
                    r.set(x, h - 13 * u, x + 10 * u, h - 6 * u);
                    canvas.drawRoundRect(r, 1.5f * u, 1.5f * u, p);
                }
            } else {
                // Two rows, each under the short line of its heading.
                for (int row = 0; row < 2; row++) {
                    float y = 5 * u + row * 14.5f * u;
                    p.setColor(0x80FFFFFF);
                    r.set(5 * u, y, 19 * u, y + 2 * u);
                    canvas.drawRoundRect(r, u, u, p);
                    p.setColor(GRAD2);
                    for (int i = 0; i < 4; i++) {
                        float x = 5 * u + i * 12.2f * u;
                        r.set(x, y + 4 * u, x + 10.4f * u, y + 11 * u);
                        canvas.drawRoundRect(r, 1.5f * u, 1.5f * u, p);
                    }
                }
            }
            canvas.restore();
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------- text helpers

    private String s(int id, Object... args) { return c.getString(id, args); }

    private TextView title(String s) {
        TextView t = text(s, inSheet ? 24 : 30, WHITE, Typeface.NORMAL);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView tag(String s) {
        TextView t = new TextView(c);
        t.setText(s.toUpperCase(Locale.getDefault()));
        t.setTextColor(HEAD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, inSheet ? 12 : 13);
        t.setLetterSpacing(0.14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView body(String s) {
        TextView t = text(s, inSheet ? 14 : 15, BODY, Typeface.NORMAL);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 13, MUTE, Typeface.NORMAL);
        t.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String s, int sizeSp, int color, int style) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (style == Typeface.BOLD) t.setTypeface(Typeface.DEFAULT_BOLD);
        else t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        // Android 6+ hyphenates by default, which splits words mid-line in the narrow sheet.
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            t.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        }
        return t;
    }

    // ---------------------------------------------------------------- layout helpers

    private int stepNo() {
        // number the "real" steps (everything between welcome and done)
        List<Integer> real = new ArrayList<Integer>();
        for (int id : steps()) if (id != WELCOME && id != DONE) real.add(id);
        return real.indexOf(current()) + 1;
    }
    private int stepTotal() {
        int n = 0;
        for (int id : steps()) if (id != WELCOME && id != DONE) n++;
        return n;
    }

    private int dp(int v) { return AppModel.dp(c, v); }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private View margin(View v, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top; lp.bottomMargin = bottom;
        v.setLayoutParams(lp);
        return v;
    }

    private View wrapMargin(View v, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top; lp.bottomMargin = bottom;
        v.setLayoutParams(lp);
        return v;
    }

    private View marginH(View v, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = left;
        v.setLayoutParams(lp);
        return v;
    }

    private View gap() {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, dp(1), 1f));
        return v;
    }

    private View space(int w) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, dp(1)));
        return v;
    }
}
