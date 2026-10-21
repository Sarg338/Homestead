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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/**
 * The home screen's background presets. Each one also sets the header and focus-ring
 * colours, since a dark ring vanishes on a dark background and white text on a light one.
 */
final class Backgrounds {

    static final class Preset {
        final String name;
        final int from, to;      // bottom-left to top-right; equal for a solid colour
        final int text;          // section headers and the clock
        final int ring;          // the focus ring around a tile
        Preset(String name, int from, int to, int text, int ring) {
            this.name = name; this.from = from; this.to = to; this.text = text; this.ring = ring;
        }
    }

    /** Index 0 is the original look and the default. */
    static final Preset[] PRESETS = {
            new Preset("Sky",      0xFF6991C7, 0xFFA3BDED, 0xFFFFFFFF, 0xFF2C2C2C),
            new Preset("Midnight", 0xFF0B1F38, 0xFF1D4E86, 0xFFFFFFFF, 0xFFE6EEF8),
            new Preset("Charcoal", 0xFF1A1B1F, 0xFF3A3C44, 0xFFFFFFFF, 0xFFE6E6E6),
            new Preset("Dusk",     0xFF2A1B3D, 0xFF6B3E7C, 0xFFFFFFFF, 0xFFF0E6F5),
            new Preset("Black",    0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFE6E6E6),
    };

    private Backgrounds() { }

    static Preset current(Context c) {
        int i = Prefs.background(c);
        if (i < 0 || i >= PRESETS.length) i = 0;
        return PRESETS[i];
    }

    static Drawable drawable(Preset p) {
        return new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                new int[]{ p.from, p.to });
    }
}
