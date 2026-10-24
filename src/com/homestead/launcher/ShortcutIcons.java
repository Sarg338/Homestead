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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Line icons for the Fire TV settings shortcuts, drawn in code so there is no artwork to
 * ship and they stay sharp at any size. One thin white stroke, like the system's own.
 */
final class ShortcutIcons {

    private static final int INK = 0xFFE6EEF5;

    private ShortcutIcons() { }

    /** A square bitmap of the icon for a screen id (see FireTvSettingsPanel). */
    static Bitmap render(String id, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float s = px;
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(INK);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(s * 0.06f);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(INK);
        fill.setStyle(Paint.Style.FILL);

        if ("network".equals(id)) {
            float cx = s * 0.5f, cy = s * 0.76f;
            float[] r = { 0.2f, 0.34f, 0.48f };
            for (int i = 0; i < r.length; i++) {
                c.drawArc(new RectF(cx - s * r[i], cy - s * r[i], cx + s * r[i], cy + s * r[i]),
                        225f, 90f, false, line);
            }
            c.drawCircle(cx, cy, s * 0.05f, fill);
        } else if ("controllers".equals(id)) {
            c.drawRoundRect(new RectF(s * 0.36f, s * 0.1f, s * 0.64f, s * 0.9f),
                    s * 0.12f, s * 0.12f, line);
            c.drawCircle(s * 0.5f, s * 0.3f, s * 0.07f, line);
            for (int i = 0; i < 3; i++) {
                c.drawCircle(s * 0.5f, s * (0.52f + i * 0.12f), s * 0.02f, fill);
            }
        } else if ("applications".equals(id)) {
            c.drawRoundRect(new RectF(s * 0.12f, s * 0.12f, s * 0.44f, s * 0.44f), s * 0.04f, s * 0.04f, line);
            c.drawRoundRect(new RectF(s * 0.56f, s * 0.12f, s * 0.88f, s * 0.44f), s * 0.04f, s * 0.04f, line);
            c.drawRoundRect(new RectF(s * 0.12f, s * 0.56f, s * 0.44f, s * 0.88f), s * 0.04f, s * 0.04f, line);
            c.drawLine(s * 0.72f, s * 0.58f, s * 0.72f, s * 0.86f, line);
            c.drawLine(s * 0.58f, s * 0.72f, s * 0.86f, s * 0.72f, line);
        } else if ("device".equals(id) || "display".equals(id)) {
            c.drawRoundRect(new RectF(s * 0.1f, s * 0.2f, s * 0.9f, s * 0.68f), s * 0.05f, s * 0.05f, line);
            c.drawLine(s * 0.5f, s * 0.68f, s * 0.5f, s * 0.8f, line);
            c.drawLine(s * 0.32f, s * 0.82f, s * 0.68f, s * 0.82f, line);
            if ("display".equals(id)) {
                c.drawArc(new RectF(s * 0.42f, s * 0.34f, s * 0.64f, s * 0.54f), -50f, 100f, false, line);
            }
        } else if ("accessibility".equals(id)) {
            c.drawCircle(s * 0.5f, s * 0.2f, s * 0.07f, line);
            c.drawLine(s * 0.2f, s * 0.38f, s * 0.8f, s * 0.38f, line);
            c.drawLine(s * 0.5f, s * 0.38f, s * 0.5f, s * 0.62f, line);
            c.drawLine(s * 0.5f, s * 0.62f, s * 0.36f, s * 0.9f, line);
            c.drawLine(s * 0.5f, s * 0.62f, s * 0.64f, s * 0.9f, line);
        } else if ("language".equals(id)) {
            c.drawCircle(s * 0.5f, s * 0.5f, s * 0.38f, line);
            c.drawOval(new RectF(s * 0.34f, s * 0.12f, s * 0.66f, s * 0.88f), line);
            c.drawLine(s * 0.12f, s * 0.5f, s * 0.88f, s * 0.5f, line);
        } else if ("preferences".equals(id)) {
            float[] y = { 0.28f, 0.5f, 0.72f };
            float[] knob = { 0.34f, 0.66f, 0.46f };
            for (int i = 0; i < 3; i++) {
                c.drawLine(s * 0.14f, s * y[i], s * 0.86f, s * y[i], line);
                c.drawCircle(s * knob[i], s * y[i], s * 0.07f, fill);
            }
        } else if ("data".equals(id)) {
            float[] top = { 0.62f, 0.4f, 0.22f };
            for (int i = 0; i < 3; i++) {
                float x = s * (0.26f + i * 0.24f);
                c.drawLine(x, s * 0.86f, x, s * top[i], line);
            }
        } else if ("account".equals(id)) {
            c.drawCircle(s * 0.5f, s * 0.34f, s * 0.14f, line);
            c.drawArc(new RectF(s * 0.2f, s * 0.58f, s * 0.8f, s * 1.1f), 200f, 140f, false, line);
        } else {
            c.drawCircle(s * 0.5f, s * 0.5f, s * 0.36f, line);
            c.drawLine(s * 0.5f, s * 0.46f, s * 0.5f, s * 0.68f, line);
            c.drawCircle(s * 0.5f, s * 0.33f, s * 0.025f, fill);
        }
        return b;
    }
}
