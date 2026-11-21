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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * A settings page: a title and focusable rows, each with a label, an optional right-hand
 * value and an action.
 *
 * Two layouts. FULL fills the screen with the list on the left and, on the right, a pane
 * that explains whichever row has focus. SHEET is a narrow panel on the right edge over the
 * dimmed home screen, so changes to the grid show behind it, with the explanation under the
 * list. Either way a note added straight after a row becomes that row's explanation instead
 * of a paragraph in the list.
 *
 * Shown inside the launcher's current window rather than as an Activity, because starting
 * an Activity from the overlay host is delayed by Fire OS's background-start block.
 */
public class Panel {

    public static final int FULL = 0, SHEET = 1;

    private static final int NAVY = 0xFF090D16, SHEET_BG = 0xE00E1420;
    private static final int TEXT = 0xFFF0F4FA, SUB = 0xFFA4B3C6, MUTE = 0xFF77899E;
    private static final int HEAD = 0xFF8BB4E6, ROW_BG = 0x16FFFFFF, PANE_BG = 0x14FFFFFF;
    private static final int FOCUS_BG = 0xFFF4F7FC, FOCUS_TEXT = 0xFF0B111D;
    private static final int DEFAULT_VALUE = 0xFF2EE6A6;
    /** The outline of a row picked up to be moved. */
    private static final int LIFT = 0xFF2E7BD6;

    public final FrameLayout root;
    private final Context ctx;
    private final int style;
    private final String subtitle;
    private final LinearLayout column;
    private final TextView helpTitle, helpBody;
    private final View helpBox;
    private final LinearLayout statusBox;
    private final View animated;

    /** The row a following addNote() explains; cleared by anything else added to the list. */
    private Row lastRow;
    private Section section = new Section();
    private Row focused;
    private boolean firstHeader = true;

    private static final class Section { String help; }

    public Panel(Context c, String title, String subtitle) {
        this(c, title, subtitle, FULL);
    }

    public Panel(Context c, String title, String subtitle, int style) {
        this.ctx = c;
        this.style = style;
        this.subtitle = subtitle;

        root = new FrameLayout(c);
        root.setFocusable(false);
        root.setClickable(true);

        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);

        TextView t = text(title, style == SHEET ? 24 : 28, TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        list.addView(t, lp(0, dp(2)));
        if (subtitle != null) {
            list.addView(text(subtitle, 14, SUB), lp(0, dp(14)));
        } else {
            // A fixed height: a bare View asked to wrap its content takes all the space left.
            list.addView(new View(c), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        }

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setClipChildren(false);
        // Room for the focused card's zoom and lift on all sides.
        sv.setPadding(dp(12), dp(10), dp(12), dp(14));
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        sv.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // A sheet's help sits under the list and grows with the focused row's text. The D-pad
        // scroll toward that row is worked out before the new help is laid out, so a longer
        // help can then cover the row. When the list's height changes, scroll again to it;
        // a new scroll replaces the one still running.
        final ScrollView scroller = sv;
        sv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (b - t == ob - ot) return;
                scroller.post(new Runnable() {
                    public void run() { keepFocusedInView(scroller); }
                });
            }
        });

        helpTitle = text("", 19, TEXT);
        helpTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        helpBody = text("", style == SHEET ? 13 : 15, SUB);
        helpBody.setLineSpacing(dp(style == SHEET ? 2 : 4), 1f);
        statusBox = new LinearLayout(c);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setVisibility(View.GONE);

        if (style == SHEET) {
            // Dim the home screen, which stays visible and live to the left.
            root.setBackgroundColor(0x80000000);

            LinearLayout sheet = new LinearLayout(c);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setPadding(dp(28), dp(32), dp(28), dp(22));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(SHEET_BG);
            bg.setStroke(Math.max(1, dp(1)), 0x22FFFFFF);
            float r = dp(24);
            bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
            sheet.setBackground(bg);
            sheet.setElevation(dp(16));
            sheet.setClipToPadding(false);
            sheet.addView(list, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            LinearLayout foot = new LinearLayout(c);
            foot.setOrientation(LinearLayout.VERTICAL);
            View rule = new View(c);
            rule.setBackgroundColor(0x1FFFFFFF);
            foot.addView(rule, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
            foot.addView(helpBody, lp(dp(12), 0));
            helpBody.setMaxLines(12);
            helpBody.setEllipsize(TextUtils.TruncateAt.END);
            sheet.addView(foot, lp(dp(6), 0));
            helpBox = foot;
            helpBox.setVisibility(View.GONE);
            sheet.addView(statusBox, lp(dp(10), 0));

            int screen = c.getResources().getDisplayMetrics().widthPixels;
            int w = Math.min(dp(420), screen / 2);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
            root.addView(sheet, slp);
            animated = sheet;
        } else {
            // Opaque, so the grid or a panel underneath does not show through.
            root.setBackgroundColor(NAVY);

            LinearLayout split = new LinearLayout(c);
            split.setOrientation(LinearLayout.HORIZONTAL);
            split.setPadding(dp(48), dp(36), dp(48), dp(32));
            split.addView(list, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f));

            LinearLayout pane = new LinearLayout(c);
            pane.setOrientation(LinearLayout.VERTICAL);
            pane.setPadding(dp(28), dp(28), dp(28), dp(24));
            GradientDrawable pbg = new GradientDrawable();
            pbg.setColor(PANE_BG);
            pbg.setStroke(Math.max(1, dp(1)), 0x1CFFFFFF);
            pbg.setCornerRadius(dp(18));
            pane.setBackground(pbg);
            pane.addView(helpTitle, lp(0, dp(10)));
            pane.addView(helpBody, lp(0, 0));
            pane.addView(new View(c), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            pane.addView(statusBox, lp(dp(12), 0));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f);
            plp.leftMargin = dp(32);
            split.addView(pane, plp);
            helpBox = pane;

            root.addView(split, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            animated = split;
            helpTitle.setText(title);
            helpBody.setText(subtitle == null ? "" : subtitle);
        }

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private boolean shown;
            public void onViewAttachedToWindow(View v) {
                if (shown) return;
                shown = true;
                enter();
            }
            public void onViewDetachedFromWindow(View v) { }
        });
    }

    /** Sheets slide in from the right edge; full pages fade in. */
    private void enter() {
        if (style == SHEET) {
            animated.setTranslationX(dp(420));
            animated.animate().translationX(0f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            root.getBackground().setAlpha(0);
            final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(0, 255);
            a.setDuration(220);
            a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(android.animation.ValueAnimator va) {
                    root.getBackground().setAlpha((Integer) va.getAnimatedValue());
                }
            });
            a.start();
        } else {
            animated.setAlpha(0f);
            animated.setTranslationX(dp(24));
            animated.animate().alpha(1f).translationX(0f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    // ---------------------------------------------------------------- building

    /** A section heading inside the list. */
    public void addHeader(String text) {
        TextView h = text(text.toUpperCase(Locale.US), 12, HEAD);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setLetterSpacing(0.12f);
        column.addView(h, lp(firstHeader ? dp(4) : dp(22), dp(8)));
        firstHeader = false;
        section = new Section();
        lastRow = null;
    }

    /**
     * Explanatory text. Straight after a row it becomes that row's explanation, shown while
     * the row has focus; anywhere else it is a paragraph in the list. Returned so the caller
     * can change it later, which updates whichever of the two it became.
     */
    public TextView addNote(String text) {
        if (lastRow != null) {
            HelpText h = new HelpText(lastRow);
            h.setText(text);
            return h;
        }
        return addText(text);
    }

    /** A paragraph in the list itself, whatever comes before it. */
    public TextView addText(String text) {
        TextView n = text(text, 14, SUB);
        n.setLineSpacing(dp(3), 1f);
        column.addView(n, lp(dp(2), dp(12)));
        lastRow = null;
        return n;
    }

    /** A focusable text field styled to match the panel rows. */
    public EditText addField(String initial, String hint) {
        EditText e = new EditText(ctx);
        e.setText(initial == null ? "" : initial);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTE);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setSingleLine(true);
        e.setFocusable(true);
        e.setFocusableInTouchMode(true);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ROW_BG);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x33FFFFFF);
        e.setBackground(bg);
        column.addView(e, lp(dp(2), dp(10)));
        lastRow = null;
        return e;
    }

    /** Explains every row of the current section that has no explanation of its own. */
    public void addSectionHelp(String text) {
        section.help = text;
        if (focused != null && focused.section == section) showHelp(focused);
    }

    /** A selectable row. Returns a handle so the caller can update it in place. */
    public Row addRow(String label, String value, final Runnable onSelect) {
        final RowCell cell = new RowCell(ctx);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setMinimumHeight(dp(54));
        cell.setPadding(dp(18), dp(12), dp(16), dp(12));

        RowLine line = new RowLine(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView l = text(label, 17, TEXT);
        l.setMaxLines(2);
        l.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(l, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
        line.name = l;

        TextView badge = text("", 10, 0xFFE8C468);
        badge.setLetterSpacing(0.08f);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        badge.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(10);
        line.addView(badge, blp);

        line.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView v = text(value == null ? "" : value, 15, DEFAULT_VALUE);
        v.setGravity(Gravity.END);
        v.setMaxLines(2);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(14);
        line.addView(v, vlp);
        line.value = v;

        Toggle sw = new Toggle(ctx);
        sw.setVisibility(View.GONE);
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(dp(40), dp(22));
        swlp.leftMargin = dp(14);
        line.addView(sw, swlp);

        TextView chev = text("›", 22, MUTE);
        chev.setVisibility(View.GONE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(12);
        line.addView(chev, clp);

        cell.addView(line, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL));

        final Row row = new Row(this, cell, l, v, badge, sw, chev, section);
        cell.row = row;
        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View view, boolean has) {
                row.focused = has;
                row.paint();
                view.setTranslationZ(has ? dp(8) : 0f);
                view.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f)
                        .setDuration(110).start();
                if (has) { focused = row; showHelp(row); }
            }
        });
        if (onSelect != null) {
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) { onSelect.run(); }
            });
        }
        row.paint();

        column.addView(cell, lp(0, dp(8)));
        lastRow = row;
        return row;
    }

    /**
     * A status line pinned below the list or the explanation pane, for a result that
     * changes as rows are used. Not focusable; hidden until it has a value.
     */
    public Row addStatus(String label) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(dp(10));
        box.setBackground(bg);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setFocusable(false);

        TextView l = text(label.toUpperCase(Locale.US), 11, MUTE);
        l.setLetterSpacing(0.1f);
        box.addView(l, lp(0, dp(2)));
        TextView v = text("", 15, DEFAULT_VALUE);
        box.addView(v, lp(0, 0));

        statusBox.addView(box, lp(statusBox.getChildCount() == 0 ? 0 : dp(8), 0));
        statusBox.setVisibility(View.VISIBLE);
        box.setVisibility(View.GONE);
        Row r = new Row(this, box, l, v, null, null, null, null);
        r.status = true;
        return r;
    }

    public void focusFirst() {
        for (int i = 0; i < column.getChildCount(); i++) {
            View v = column.getChildAt(i);
            if (v.isFocusable()) { v.requestFocus(); return; }
        }
    }

    // ---------------------------------------------------------------- explanation

    /** Scroll just enough to show the focused row whole, if part of it is out of view. */
    private void keepFocusedInView(ScrollView s) {
        Row f = focused;
        if (f == null || !f.cell.hasFocus()) return;
        android.graphics.Rect rc = new android.graphics.Rect(0, 0, f.cell.getWidth(), f.cell.getHeight());
        s.offsetDescendantRectToMyCoords(f.cell, rc);
        int top = s.getScrollY() + s.getPaddingTop();
        int bottom = s.getScrollY() + s.getHeight() - s.getPaddingBottom();
        int delta = rc.bottom > bottom ? rc.bottom - bottom : rc.top < top ? rc.top - top : 0;
        if (delta != 0) s.smoothScrollBy(0, delta);
    }

    private void showHelp(Row r) {
        String body = r.help != null ? r.help
                : (r.section != null && r.section.help != null) ? r.section.help : null;
        if (style == SHEET) {
            helpBody.setText(body == null ? "" : body);
            helpBox.setVisibility(body == null || body.length() == 0 ? View.GONE : View.VISIBLE);
        } else {
            helpTitle.setText(r.label.getText());
            String b = body != null ? body : (subtitle == null ? "" : subtitle);
            // The pane does not scroll, so the longest explanations get a smaller size.
            helpBody.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    b.length() > 700 ? 12 : b.length() > 420 ? 13 : 15);
            helpBody.setText(b);
        }
    }

    /** A note that explains a row; it is never drawn itself. */
    private final class HelpText extends TextView {
        private Row row;

        HelpText(Row r) { super(ctx); row = r; r.help = ""; }

        @Override protected void onTextChanged(CharSequence t, int start, int before, int after) {
            super.onTextChanged(t, start, before, after);
            if (row == null) return;   // still inside the TextView constructor
            row.help = t == null ? "" : t.toString();
            if (row == focused) showHelp(row);
        }
    }

    // ---------------------------------------------------------------- helpers

    private int dp(int v) { return AppModel.dp(ctx, v); }

    private LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        // Android 6+ hyphenates by default, which splits words like "op-tions" mid-line.
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            t.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        }
        return t;
    }

    /** A value colour made readable on the light focus fill. */
    static int onLight(int c) {
        switch (c) {
            case 0xFFA7D28F: return 0xFF2F6B2A;   // OK green
            case 0xFFE8C468: return 0xFF7A5600;   // warning amber
            case 0xFFE89A8A: return 0xFFA3261B;   // problem red
            default:
                return Color.rgb((int) (Color.red(c) * 0.42f), (int) (Color.green(c) * 0.42f),
                        (int) (Color.blue(c) * 0.46f));
        }
    }

    /**
     * Repaints its row when a caller enables or disables it. Disabling a focused view makes
     * Android drop its focus, which would land on the grid behind a sheet, so focus moves to
     * a neighbouring row of the same page first.
     */
    private static final class RowCell extends FrameLayout {
        Row row;
        RowCell(Context c) { super(c); }
        @Override public void setEnabled(boolean e) {
            if (!e && isFocused() && row != null) {
                View next = focusSearch(View.FOCUS_DOWN);
                if (!row.panel.owns(next)) next = focusSearch(View.FOCUS_UP);
                if (row.panel.owns(next)) next.requestFocus();
            }
            super.setEnabled(e);
            if (row != null) row.paint();
        }
    }

    /**
     * One row's line of name, badge, value, switch and arrow. The name gives way: what
     * stands beside it is measured first and keeps its room, and the name takes what is
     * left, on two lines when it has to. A plain row hands the name the whole width first,
     * so a long name (in a language with longer words, say) pushes the switch or the value
     * off the end of the row. A row where everything fits is measured as before.
     */
    private static final class RowLine extends LinearLayout {
        TextView name, value;
        /** Room for the name and for the value in this pass; -1 while everything fits. */
        private int nameRoom = -1, valueRoom = -1;

        RowLine(Context c) { super(c); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            nameRoom = -1;
            valueRoom = -1;
            if (name != null && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                int inner = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                int fixed = 0, valueW = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View ch = getChildAt(i);
                    if (ch == name || ch.getVisibility() == GONE) continue;
                    LayoutParams lp = (LayoutParams) ch.getLayoutParams();
                    if (lp.weight > 0) continue;   // the gap that takes up the slack
                    ch.measure(getChildMeasureSpec(free, 0, lp.width),
                            getChildMeasureSpec(free, 0, lp.height));
                    int w = ch.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                    if (ch == value) valueW = w; else fixed += w;
                }
                name.measure(free, free);
                int nameW = name.getMeasuredWidth();
                if (nameW + fixed + valueW > inner) {
                    int rest = Math.max(0, inner - fixed);
                    // The value keeps its own width up to a little under half of the row;
                    // past that it wraps as well, and a short name leaves it the rest.
                    int keep = Math.min(valueW, Math.round(rest * 0.45f));
                    if (nameW < rest - keep) keep = rest - nameW;
                    nameRoom = rest - keep;
                    valueRoom = keep;
                }
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        /** LinearLayout measures each child through here; the room worked out above applies. */
        @Override protected void measureChildWithMargins(View child, int parentWidthSpec,
                int widthUsed, int parentHeightSpec, int heightUsed) {
            int inner = MeasureSpec.getSize(parentWidthSpec) - getPaddingLeft() - getPaddingRight();
            if (child == name && nameRoom >= 0) {
                widthUsed = Math.max(widthUsed, inner - nameRoom);
            } else if (child == value && valueRoom >= 0) {
                widthUsed = Math.max(widthUsed, inner - valueRoom);
            }
            super.measureChildWithMargins(child, parentWidthSpec, widthUsed, parentHeightSpec, heightUsed);
        }
    }

    /** True if v is a view inside this page. */
    private boolean owns(View v) {
        for (View x = v; x != null; ) {
            if (x == root) return true;
            Object parent = x.getParent();
            x = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /** A drawn on/off switch: the track takes the row's value colour. */
    private static final class Toggle extends View {
        boolean on, light;
        int color = DEFAULT_VALUE;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();

        Toggle(Context c) { super(c); }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            rect.set(0, 0, w, h);
            int track = on ? (light ? onLight(color) : color)
                    : (light ? 0x40000000 : 0x40FFFFFF);
            paint.setColor(track);
            canvas.drawRoundRect(rect, r, r, paint);
            float inset = h * 0.14f, tr = r - inset;
            paint.setColor(on ? Color.WHITE : (light ? 0xFFF7F9FC : 0xFFC9D4E0));
            canvas.drawCircle(on ? w - r : r, r, tr, paint);
        }
    }

    /** Handle to a row so callers can update it without rebuilding the panel. */
    public static class Row {
        public final View cell;
        private final Panel panel;
        private final TextView label;
        private final TextView value;
        private final TextView badge;
        private final Toggle toggle;
        private final TextView chevron;
        private final Section section;
        private int valueColor = DEFAULT_VALUE;
        private boolean focused, isSwitch, status, lifted;
        private String help;

        Row(Panel panel, View cell, TextView label, TextView value, TextView badge,
            Toggle toggle, TextView chevron, Section section) {
            this.panel = panel; this.cell = cell; this.label = label; this.value = value;
            this.badge = badge; this.toggle = toggle; this.chevron = chevron;
            this.section = section;
        }

        public void setValue(String s) {
            value.setText(s == null ? "" : s);
            if (status) cell.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
            paint();
        }
        public void setValueColor(int c) { valueColor = c; paint(); }
        public void setLabel(String s) {
            label.setText(s);
            if (panel.focused == this) panel.showHelp(this);
        }

        /** The explanation shown while this row has focus. */
        public Row setHelp(String s) {
            help = s;
            if (panel.focused == this) panel.showHelp(this);
            return this;
        }

        /** A small tag after the label, such as CAUTION. Null removes it. */
        public Row setBadge(String s) {
            if (badge == null) return this;
            badge.setText(s == null ? "" : s.toUpperCase(Locale.US));
            badge.setVisibility(s == null ? View.GONE : View.VISIBLE);
            paint();
            return this;
        }

        /** Draw an "on"/"off" value as a switch; other values still show as text. */
        public Row asSwitch() { isSwitch = true; paint(); return this; }

        /** Picked up to be moved: outlined until put down. */
        public void setLifted(boolean l) {
            lifted = l;
            paint();
        }

        /** Mark a row that opens another page. */
        public Row opens() {
            if (chevron != null) chevron.setVisibility(View.VISIBLE);
            return this;
        }

        /** A small sample before the label, for a row that picks a look. */
        public Row setSwatch(android.graphics.drawable.Drawable d) {
            View s = new View(panel.ctx);
            s.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(panel.dp(36), panel.dp(24));
            lp.rightMargin = panel.dp(14);
            ((LinearLayout) label.getParent()).addView(s, 0, lp);
            return this;
        }

        void paint() {
            if (status) {
                value.setTextColor(valueColor);
                return;
            }
            boolean enabled = cell.isEnabled();
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(panel.dp(14));
            bg.setColor(focused ? FOCUS_BG : ROW_BG);
            if (lifted) bg.setStroke(panel.dp(3), LIFT);
            else if (!focused) bg.setStroke(Math.max(1, panel.dp(1)), 0x1EFFFFFF);
            cell.setBackground(bg);

            label.setTextColor(focused ? FOCUS_TEXT : TEXT);
            label.setTypeface(Typeface.create(focused ? "sans-serif-medium" : "sans-serif",
                    Typeface.NORMAL));
            int vc = focused ? onLight(valueColor) : valueColor;
            value.setTextColor(vc);

            String v = value.getText().toString();
            boolean isOnVal = v.equalsIgnoreCase("on") || v.equalsIgnoreCase("activ\u00E9");
            boolean isOffVal = v.equalsIgnoreCase("off") || v.equalsIgnoreCase("d\u00E9sactiv\u00E9");
            boolean sw = isSwitch && (isOnVal || isOffVal);
            value.setVisibility(sw || v.length() == 0 ? View.GONE : View.VISIBLE);
            toggle.setVisibility(sw ? View.VISIBLE : View.GONE);
            if (sw) {
                toggle.on = isOnVal;
                toggle.light = focused;
                toggle.color = valueColor;
                toggle.invalidate();
            }

            if (badge.getVisibility() == View.VISIBLE) {
                GradientDrawable b = new GradientDrawable();
                b.setCornerRadius(panel.dp(20));
                int bc = focused ? 0xFF7A5600 : 0xFFE8C468;
                b.setStroke(Math.max(1, panel.dp(1)), bc);
                badge.setBackground(b);
                badge.setTextColor(bc);
            }
            chevron.setTextColor(focused ? FOCUS_TEXT : MUTE);

            float a = enabled ? 1f : 0.45f;
            label.setAlpha(a);
            value.setAlpha(a);
            toggle.setAlpha(a);
        }
    }
}
