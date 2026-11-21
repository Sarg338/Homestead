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

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/**
 * Lightweight floating media diagnostics HUD rendered via HomeRedirectService's
 * accessibility overlay window so it stays visible over any app or video stream.
 */
final class MediaHudOverlay {

    private static final String TAG = "Homestead";
    private static final long TICK_MS = 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static TextView hudView;
    private static WindowManager wm;
    private static long lastRx = -1L, lastTx = -1L, lastAt = 0L;
    private static String foregroundApp = "Homestead";

    private static final Runnable TICK = new Runnable() {
        public void run() {
            if (hudView == null) return;
            updateText(hudView.getContext());
            MAIN.removeCallbacks(this);
            MAIN.postDelayed(this, TICK_MS);
        }
    };

    private MediaHudOverlay() { }

    static void noteForegroundApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        if (pkg.equals("com.homestead.launcher")) foregroundApp = "Homestead";
        else foregroundApp = pkg;
    }

    static void sync(Context c) {
        if (Prefs.mediaHud(c)) show();
        else hide();
    }

    private static void show() {
        HomeRedirectService svc = HomeRedirectService.current();
        if (svc == null || hudView != null) return;
        try {
            WindowManager mgr = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            if (mgr == null) return;
            int dp = AppModel.dp(svc, 1);
            TextView tv = new TextView(svc);
            tv.setTextColor(0xFFEAF2FA);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setPadding(12 * dp, 8 * dp, 12 * dp, 8 * dp);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xB8080D16);
            bg.setCornerRadius(10 * dp);
            bg.setStroke(Math.max(1, dp), 0x33FFFFFF);
            tv.setBackground(bg);

            int type = Build.VERSION.SDK_INT >= 22
                    ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = 28 * dp;
            lp.y = 24 * dp;

            mgr.addView(tv, lp);
            wm = mgr;
            hudView = tv;
            lastRx = TrafficStats.getTotalRxBytes();
            lastTx = TrafficStats.getTotalTxBytes();
            lastAt = SystemClock.elapsedRealtime();
            updateText(svc);
            MAIN.removeCallbacks(TICK);
            MAIN.postDelayed(TICK, TICK_MS);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to show Media HUD: " + t);
            hide();
        }
    }

    static void hide() {
        MAIN.removeCallbacks(TICK);
        if (hudView != null && wm != null) {
            try { wm.removeViewImmediate(hudView); } catch (Throwable ignored) { }
        }
        hudView = null;
        wm = null;
    }

    private static void updateText(Context c) {
        if (hudView == null || wm == null) return;
        Display d = wm.getDefaultDisplay();
        String dispStr = "Display";
        if (d != null) {
            if (Build.VERSION.SDK_INT >= 23) {
                Display.Mode m = d.getMode();
                dispStr = String.format(Locale.US, "%dx%d @ %.0fHz",
                        m.getPhysicalWidth(), m.getPhysicalHeight(), m.getRefreshRate());
            } else {
                dispStr = String.format(Locale.US, "%.0fHz", d.getRefreshRate());
            }
            if (Build.VERSION.SDK_INT >= 24 && d.getHdrCapabilities() != null) {
                int[] hdr = d.getHdrCapabilities().getSupportedHdrTypes();
                if (hdr != null && hdr.length > 0) dispStr += " HDR";
            }
        }

        long now = SystemClock.elapsedRealtime();
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        long dt = Math.max(1L, now - lastAt);
        double downKbps = (rx >= 0 && lastRx >= 0) ? ((rx - lastRx) * 1000.0 / dt) / 1024.0 : 0;
        double upKbps = (tx >= 0 && lastTx >= 0) ? ((tx - lastTx) * 1000.0 / dt) / 1024.0 : 0;
        lastRx = rx;
        lastTx = tx;
        lastAt = now;

        String netStr = downKbps >= 1024.0
                ? String.format(Locale.US, "%.1f MB/s", downKbps / 1024.0)
                : String.format(Locale.US, "%.0f KB/s", downKbps);

        String ramStr = "";
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                ramStr = String.format(Locale.US, " | RAM %dMB", mi.availMem / (1024L * 1024L));
            }
        } catch (Throwable ignored) { }

        hudView.setText(foregroundApp + "\n" + dispStr + "\n\u2193 " + netStr
                + String.format(Locale.US, "  \u2191 %.0f KB/s", upKbps) + ramStr);
    }
}
