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
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * The app's language: the TV's own, or one picked in Settings or on the first page of the
 * setup wizard. Applied to each window's resources when it is created and again when the
 * choice changes; everything visible reads its text from res/values-<lang>.
 */
final class Lang {

    /** Prefs values, in the order the Settings row cycles through them; "" is the TV's language. */
    static final String[] CODES = { "", "en", "fr" };

    private Lang() { }

    static void apply(Context c) {
        String code = Prefs.language(c);
        Locale l = code.isEmpty() ? Resources.getSystem().getConfiguration().locale : new Locale(code);
        Locale.setDefault(l);
        apply(c.getResources(), l);
        apply(c.getApplicationContext().getResources(), l);
    }

    private static void apply(Resources r, Locale l) {
        Configuration conf = new Configuration(r.getConfiguration());
        conf.setLocale(l);
        r.updateConfiguration(conf, r.getDisplayMetrics());
    }

    /** The next choice in the Settings cycle. */
    static String next(Context c) {
        String cur = Prefs.language(c);
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equals(cur)) return CODES[(i + 1) % CODES.length];
        }
        return CODES[0];
    }

    /** Language names are written in their own language, so anyone can find theirs. */
    static String label(Context c) {
        String code = Prefs.language(c);
        if (code.equals("en")) return "English";
        if (code.equals("fr")) return "Français";
        return c.getString(R.string.lang_tv);
    }
}
