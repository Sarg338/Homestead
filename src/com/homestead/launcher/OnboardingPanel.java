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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The first-run wizard: a skippable welcome, a choice of features, then only the setup
 * screens for those features. The launcher works whether or not it is finished.
 *
 * Renders inside the launcher window like the other panels (activity or overlay); Back
 * steps backward via HomeGrid.BackHandler. The accessibility and ADB-tunnel steps poll
 * their real state and show it live, but never auto-advance.
 */
public final class OnboardingPanel {

    // The app's palette, shared with Panel/HomeGrid.
    private static final int NAVY = 0xFF0B1F38, ROW = 0xFF102C4A, FOCUS = 0xFF1D4E86;
    private static final int HEAD = 0xFF7FA8D8, BODY = 0xFF9FB2C6, MUTE = 0xFF7C8FA4;
    private static final int WHITE = 0xFFF3F6FA, OK = 0xFFA7D28F, WARN = 0xFFE8C468, BAD = 0xFFE89A8A;
    private static final int GRAD1 = 0xFF6991C7, GRAD2 = 0xFFA3BDED;
    private static final int GLOW = 0x8CA3BDED, GHOST_LINE = 0x40FFFFFF;

    private static final int WELCOME = 0, CHOOSE = 1, HOME = 2, ADB = 3, DONE = 4;

    private final Context c;
    private final SettingsPanel.Host host;
    public final FrameLayout root;
    private final LinearLayout dots, content, actions;

    private int pos = 0;                 // index into activeSteps()
    private boolean wantHome = true, wantClean = true;
    private final Handler poll = new Handler(Looper.getMainLooper());
    private TextView chip;               // live-status pill on the HOME / ADB steps, else null
    private View focusTarget;            // control to focus after a render
    private boolean wantSelfSetup;       // switch the Home button on once the link is up
    private View homeContinue;           // focused once the Home button is on
    private boolean homeWasOn;

    public static View build(Context c, SettingsPanel.Host host) {
        return new OnboardingPanel(c, host).root;
    }

    private OnboardingPanel(Context c, SettingsPanel.Host host) {
        this.c = c;
        this.host = host;

        root = new FrameLayout(c);
        root.setBackgroundColor(NAVY);
        root.setClickable(true);
        root.setTag(new HomeGrid.BackHandler() {
            public boolean onBack() {
                if (pos > 0) { pos--; render(); return true; }
                return false;   // on the first step, let Back close the wizard
            }
        });

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(40), dp(56), dp(40));
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dots = new LinearLayout(c);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(dots, wrap());

        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = dp(28);
        col.addView(content, clp);

        actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(20);
        col.addView(actions, alp);

        render();
        poll.postDelayed(tick, 1000L);
    }

    // ---------------------------------------------------------------- flow

    private List<Integer> steps() {
        List<Integer> s = new ArrayList<Integer>();
        s.add(WELCOME);
        s.add(CHOOSE);
        if (wantHome) s.add(HOME);
        if (wantClean) s.add(ADB);
        s.add(DONE);
        return s;
    }

    private int current() {
        List<Integer> s = steps();
        if (pos >= s.size()) pos = s.size() - 1;
        return s.get(pos);
    }

    private void next() { if (pos < steps().size() - 1) { pos++; render(); } }
    private void back() { if (pos > 0) { pos--; render(); } }

    private void finish() {
        poll.removeCallbacks(tick);
        Prefs.setOnboarded(c, true);
        host.rebuildGrid();
        host.closePanel();
    }

    // ---------------------------------------------------------------- render

    private void render() {
        content.removeAllViews();
        actions.removeAllViews();
        chip = null;
        focusTarget = null;

        int id = current();
        switch (id) {
            case WELCOME: buildWelcome(); break;
            case CHOOSE:  buildChoose();  break;
            case HOME:    buildHome();    break;
            case ADB:     buildAdb();     break;
            case DONE:    buildDone();    break;
        }
        buildDots();

        final View f = focusTarget;
        if (f != null) f.post(new Runnable() { public void run() { f.requestFocus(); } });
    }

    private void buildDots() {
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

        content.addView(margin(title("A home screen that stays out of your way."), dp(26), dp(10)));
        content.addView(body("No ad banners, no auto-playing trailers, no sponsored rows - just your "
                + "apps and inputs. And, if you want, a one-tap way to switch off Amazon's "
                + "advertising and telemetry."));

        View start = button("Get started", true, new Runnable() { public void run() { next(); } });
        actions.addView(start);
        actions.addView(gap());
        actions.addView(button("Skip setup", false, new Runnable() { public void run() { finish(); } }));
        focusTarget = start;
    }

    private void buildChoose() {
        content.addView(tag("Step " + stepNo() + " of " + stepTotal()));
        content.addView(margin(title("What should we set up?"), dp(6), dp(6)));
        content.addView(body("Turn on what you want now - you can change either one later from the Settings tile."));

        content.addView(featureCard("⌂", "Replace the home screen",
                "The Home button opens this launcher instead of Amazon's.", null,
                true));
        content.addView(featureCard("✦", "Clean up my TV",
                "Switch off Amazon's ads and telemetry, and turn off the animations.", "Advanced",
                false));

        View cont = button("Continue", true, new Runnable() { public void run() { next(); } });
        actions.addView(button("Back", false, new Runnable() { public void run() { back(); } }));
        actions.addView(gap());
        actions.addView(cont);
        focusTarget = cont;
    }

    private void buildHome() {
        content.addView(tag("Step " + stepNo() + " of " + stepTotal()));
        content.addView(margin(title("Turn on the home screen"), dp(6), dp(6)));
        content.addView(body("Fire OS doesn't list Homestead in its Accessibility menu, so the "
                + "app switches it on itself, through the TV's own debugging service. No "
                + "computer needed."));

        content.addView(miniStep("1", "Turn on ADB debugging: Settings, My Fire TV, "
                + "Developer options"));
        content.addView(miniStep("2", "Press Set it up and approve two prompts: a VPN "
                + "notice, then the TV's key prompt. Tick \"Always allow\" on the second"));

        chip = pill();
        content.addView(wrapMargin(chip, dp(14), dp(4)));
        content.addView(note("In each prompt, press Down to reach OK. No Developer options? "
                + "Open My Fire TV, then About, and press Select on the device name seven "
                + "times. To do this later, press Continue; you can still open Homestead "
                + "from Amazon's app row."));
        updateChip();

        View open = button("Open My Fire TV", !adbDebuggingOn(), new Runnable() {
            public void run() { SetupPanel.openDeviceSettings(c); }
        });
        View setup = button("Set it up", adbDebuggingOn(), new Runnable() {
            public void run() { setUpHome(); }
        });
        actions.addView(button("Back", false, new Runnable() { public void run() { back(); } }));
        actions.addView(gap());
        actions.addView(open);
        actions.addView(space(dp(10)));
        actions.addView(setup);
        actions.addView(space(dp(10)));
        homeContinue = button("Continue", false, new Runnable() { public void run() { next(); } });
        actions.addView(homeContinue);
        focusTarget = SetupPanel.serviceEnabled(c) ? homeContinue
                : adbDebuggingOn() ? setup : open;
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
            public void onDone(boolean ok, String out) { updateChip(); }
        });
    }

    private void buildAdb() {
        content.addView(tag("Step " + stepNo() + " of " + stepTotal()));
        content.addView(margin(title("Let the app clean up your TV"), dp(6), dp(6)));
        content.addView(body("To switch Amazon's packages off, the app needs the access a computer "
                + "has over ADB - so it makes a private connection to the TV's own service. No "
                + "computer, no account, and nothing ever leaves the TV."));
        content.addView(miniStep("!", "You approve two quick prompts: a VPN notice, then the TV's "
                + "key prompt. Tick \"Always allow\" on the second"));

        chip = pill();
        content.addView(wrapMargin(chip, dp(14), dp(4)));
        content.addView(note("It only runs while cleaning up, then switches itself off."));
        updateChip();

        View setup = button("Set it up", true, new Runnable() {
            public void run() { SetupPanel.startTunnel(c); }
        });
        actions.addView(button("Back", false, new Runnable() { public void run() { back(); } }));
        actions.addView(gap());
        actions.addView(setup);
        actions.addView(space(dp(10)));
        View cont = button("Continue", true, new Runnable() { public void run() { next(); } });
        actions.addView(cont);
        // Already connected on the Home step: nothing more to do here.
        focusTarget = (Prefs.tunnelEnabled(c) && AdbCrypto.hasKey(c)) ? cont : setup;
    }

    private void buildDone() {
        content.addView(tag("All set"));
        content.addView(margin(title("You're ready."), dp(6), dp(14)));

        boolean svc = SetupPanel.serviceEnabled(c);
        boolean tun = Prefs.tunnelEnabled(c) || AdbBridge.isConnected();
        if (wantHome) content.addView(checkItem(svc, svc ? "Home screen - on" : "Home screen - skipped for now"));
        if (wantClean) content.addView(checkItem(tun, tun ? "TV cleanup - ready; choose what to switch off in Debloat" : "TV cleanup - skipped for now"));

        content.addView(margin(body("Find all of it again from the Settings tile on your home "
                + "screen - hidden apps, sorting, TV Health and Debloat."), dp(14), dp(4)));

        View fin = button("Finish", true, new Runnable() { public void run() { finish(); } });
        actions.addView(fin);
        focusTarget = fin;
    }

    // ---------------------------------------------------------------- live status

    private final Runnable tick = new Runnable() {
        public void run() {
            if (root.getParent() == null) return;   // panel closed; stop polling
            if (wantSelfSetup && AdbBridge.isConnected()) runSelfSetup();
            updateChip();
            poll.postDelayed(this, 1000L);
        }
    };

    private void updateChip() {
        if (chip == null) return;
        int id = current();
        if (id == HOME && SetupPanel.serviceEnabled(c)) {
            setChip(OK, "On - press Home to try it");
            if (!homeWasOn && homeContinue != null) homeContinue.requestFocus();
            homeWasOn = true;
        } else if (id == HOME || id == ADB) {
            int st = AdbBridge.state();
            String fp = AdbBridge.fingerprint();
            if (st == AdbBridge.CONNECTED) {
                setChip(OK, id == HOME ? "Connected - switching it on…" : "Connected - ready to clean up");
            } else if (id == ADB && Prefs.tunnelEnabled(c) && AdbCrypto.hasKey(c)) {
                setChip(OK, "Set up - ready to clean up");
            } else if (st == AdbBridge.AWAITING_APPROVAL) {
                setChip(WARN, fp == null ? "Approve the key on screen"
                        : "Approve the key on screen - it starts " + SetupPanel.shortFingerprint(fp));
            } else if (st == AdbBridge.CONNECTING) setChip(WARN, "Connecting…");
            else if (st == AdbBridge.SELF_REFUSED) setChip(BAD, "This TV blocked it - see Commands for a computer");
            else if (st == AdbBridge.NO_DAEMON) setChip(BAD, "Turn on ADB debugging first");
            else setChip(MUTE, "Not set up yet");
        }
    }

    private void setChip(int color, String label) {
        chip.setText("  ●  " + label);
        chip.setTextColor(color);
        GradientDrawable g = (GradientDrawable) chip.getBackground();
        g.setColor((color & 0x00FFFFFF) | 0x1F000000);
    }

    // ---------------------------------------------------------------- widgets

    private View button(String label, final boolean primary, final Runnable onClick) {
        final TextView b = new TextView(c);
        b.setText(label);
        b.setTextColor(primary ? WHITE : BODY);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(26), dp(13), dp(26), dp(13));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(primary ? ROW : Color.TRANSPARENT);
        if (!primary) bg.setStroke(dp(1), GHOST_LINE);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                bg.setColor(has ? FOCUS : (primary ? ROW : Color.TRANSPARENT));
                bg.setStroke(has ? dp(2) : (primary ? 0 : dp(1)), has ? GLOW : GHOST_LINE);
                v.setTranslationZ(has ? dp(6) : 0f);
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onClick.run(); }
        });
        return b;
    }

    private View featureCard(String glyph, String titleTxt, String desc, String pillTxt,
                             final boolean isHome) {
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
        paintCheck(check, isHome ? wantHome : wantClean);

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
        if (pillTxt != null) tl.addView(marginH(badge(pillTxt), dp(12)));
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
                boolean on = isHome ? wantHome : wantClean;
                // Keep at least one selected, so the flow always has a real step.
                if (on && !(isHome ? wantClean : wantHome)) return;
                if (isHome) wantHome = !wantHome; else wantClean = !wantClean;
                paintCheck(check, isHome ? wantHome : wantClean);
                buildDots();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        cell.setLayoutParams(lp);
        return cell;
    }

    private void paintCheck(TextView check, boolean on) {
        check.setText(on ? "✓" : "");
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        if (on) g.setColor(OK); else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(2), 0x80A0B0C0); }
        check.setBackground(g);
    }

    private View miniStep(String n, String s) {
        LinearLayout m = new LinearLayout(c);
        m.setOrientation(LinearLayout.HORIZONTAL);
        m.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable back = new GradientDrawable();
        back.setCornerRadius(dp(8));
        back.setColor(0x0AFFFFFF);
        m.setBackground(back);
        m.setPadding(dp(14), dp(11), dp(16), dp(11));

        TextView num = new TextView(c);
        num.setText(n);
        num.setGravity(Gravity.CENTER);
        num.setTextColor(NAVY);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable ng = new GradientDrawable();
        ng.setShape(GradientDrawable.OVAL);
        ng.setColor(HEAD);
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

    private View checkItem(boolean on, String s) {
        LinearLayout m = new LinearLayout(c);
        m.setOrientation(LinearLayout.HORIZONTAL);
        m.setGravity(Gravity.CENTER_VERTICAL);
        TextView bx = new TextView(c);
        bx.setText(on ? "✓" : "•");
        bx.setGravity(Gravity.CENTER);
        bx.setTextColor(on ? NAVY : MUTE);
        bx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bx.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        if (on) g.setColor(OK); else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(2), MUTE); }
        bx.setBackground(g);
        int s2 = dp(24);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(s2, s2);
        blp.rightMargin = dp(14);
        m.addView(bx, blp);
        m.addView(text(s, 17, on ? WHITE : MUTE, Typeface.NORMAL), wrap());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
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

    private View badge(String s) {
        TextView t = new TextView(c);
        t.setText(s.toUpperCase(java.util.Locale.US));
        t.setTextColor(WARN);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), 0x80E8C468);
        t.setBackground(g);
        return t;
    }

    // ---------------------------------------------------------------- text helpers

    private TextView title(String s) {
        TextView t = text(s, 30, WHITE, Typeface.NORMAL);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView tag(String s) {
        TextView t = new TextView(c);
        t.setText(s.toUpperCase(java.util.Locale.US));
        t.setTextColor(HEAD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setLetterSpacing(0.14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView body(String s) {
        TextView t = text(s, 15, BODY, Typeface.NORMAL);
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
        return t;
    }

    // ---------------------------------------------------------------- layout helpers

    private int stepNo() {
        // number the "real" steps (choose + chosen setup screens), not welcome/done
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
