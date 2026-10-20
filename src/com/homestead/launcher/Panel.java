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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A full-screen list panel: a title and focusable rows, each with a label, an optional
 * right-hand value and an action.
 *
 * Shown inside the launcher's current window rather than as an Activity, because starting
 * an Activity from the overlay host is delayed by Fire OS's background-start block.
 */
public class Panel {

    public final FrameLayout root;
    private final LinearLayout column;
    private final Context ctx;

    public Panel(Context c, String title, String subtitle) {
        this.ctx = c;

        root = new FrameLayout(c);
        root.setFocusable(false);
        // Opaque, so the grid or a panel underneath does not show through.
        root.setBackgroundColor(0xFF0B1F38);
        root.setClickable(true);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        root.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(48), dp(36), dp(48), dp(48));
        sv.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        column.addView(t, lp(dp(0), dp(4)));

        if (subtitle != null) {
            TextView s = new TextView(c);
            s.setText(subtitle);
            s.setTextColor(0xFF9FB2C6);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            column.addView(s, lp(dp(0), dp(18)));
        }
    }

    private int dp(int v) { return AppModel.dp(ctx, v); }

    private LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    /** A section heading inside the panel. */
    public void addHeader(String text) {
        TextView h = new TextView(ctx);
        h.setText(text);
        h.setTextColor(0xFF7FA8D8);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setAllCaps(true);
        column.addView(h, lp(dp(20), dp(8)));
    }

    /** Non-focusable explanatory text. Returned so the caller can change it later. */
    public TextView addNote(String text) {
        TextView n = new TextView(ctx);
        n.setText(text);
        n.setTextColor(0xFF8FA3B8);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        n.setLineSpacing(dp(2), 1f);
        column.addView(n, lp(dp(2), dp(10)));
        return n;
    }

    /** A selectable row. Returns a handle so the caller can update the value in place. */
    public Row addRow(String label, String value, final Runnable onSelect) {
        final FrameLayout cell = new FrameLayout(ctx);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setPadding(dp(16), dp(12), dp(16), dp(12));

        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(0xFF102C4A);
        cell.setBackground(bg);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextColor(Color.WHITE);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        l.setMaxLines(2);
        l.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams llp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(l, llp);

        TextView v = new TextView(ctx);
        v.setText(value == null ? "" : value);
        v.setTextColor(0xFFA7D28F);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setGravity(Gravity.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(16);
        row.addView(v, vlp);

        cell.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View view, boolean has) {
                bg.setColor(has ? 0xFF1D4E86 : 0xFF102C4A);
                view.setTranslationZ(has ? AppModel.dp(ctx, 6) : 0f);
            }
        });
        if (onSelect != null) {
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) { onSelect.run(); }
            });
        }

        column.addView(cell, lp(0, dp(8)));
        return new Row(cell, l, v);
    }

    public void focusFirst() {
        for (int i = 0; i < column.getChildCount(); i++) {
            View v = column.getChildAt(i);
            if (v.isFocusable()) { v.requestFocus(); return; }
        }
    }

    /** Handle to a row so callers can update it without rebuilding the panel. */
    public static class Row {
        public final View cell;
        private final TextView label;
        private final TextView value;

        Row(View cell, TextView label, TextView value) {
            this.cell = cell; this.label = label; this.value = value;
        }

        public void setValue(String s) { value.setText(s == null ? "" : s); }
        public void setValueColor(int c) { value.setTextColor(c); }
        public void setLabel(String s) { label.setText(s); }
    }
}
