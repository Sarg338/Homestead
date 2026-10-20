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

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.lang.ref.WeakReference;

/**
 * Replaces Amazon's home screen with ours.
 *
 * Fire OS pins com.amazon.tv.launcher as the home activity and refuses background activity
 * starts for ~5 s after a Home press, so starting MainActivity on top is too slow. Adding a
 * window is not an activity start, so the grid is pre-built and attached as an overlay when
 * Amazon's home appears; MainActivity is started once the block lifts and takes over, and
 * the overlay is then removed.
 *
 * Each attach() opens a new epoch. Picks, activity resumes and pending hides are tied to
 * the epoch they were created in and ignored once it has moved on, so a stale event from
 * an earlier cycle cannot leave a focused overlay stuck on screen.
 */
public class HomeRedirectService extends AccessibilityService {

    private static final String TAG = "Homestead";
    private static final String AMAZON = "com.amazon.tv.launcher";

    /** Ignores duplicate window events from a single transition. */
    private static final long DEBOUNCE_MS = 350L;
    /**
     * When to hand off to the real activity: just after Fire OS's ~5 s background-start
     * block. A start issued earlier is deferred and lands on top of whatever the user
     * opened meanwhile; issuing it late lets a pick simply cancel it.
     */
    private static final long HANDOFF_MS = 5200L;
    /** If the activity still has not taken over by now, try again. */
    private static final long WATCHDOG_MS = 12000L;
    /** Cover stays at most this long if the picked app never appears. */
    private static final long PICK_TIMEOUT_MS = 10000L;
    /**
     * How long the cover stays after the picked app's window event. The event comes before
     * the app's first frame, so lifting at once shows Amazon's home in between.
     */
    private static final long REVEAL_MS = 400L;

    private static HomeRedirectService instance;

    // Main thread only (accessibility callbacks, activity lifecycle, this Handler).
    private static long epoch = 0L;
    private static long pickedEpoch = -1L;
    private static long resumedEpoch = -1L;
    /** elapsedRealtime, not wall clock: wall clock jumps when NTP syncs after boot. */
    private static long suspendUntil = 0L;
    private static WeakReference<MainActivity> liveActivity;
    /** Which WindowManager.LayoutParams type the overlay is actually using, 0 if none. */
    private static int overlayType = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastShow = 0L;

    /** Re-sort the hidden overlay after an app is opened, so it is in order before Home. */
    private final Runnable quietResort = new Runnable() {
        public void run() {
            if (attached == null && home != null) home.resort();
        }
    };

    private WindowManager wm;
    private HomeGrid home;
    private View attached;
    private Runnable pendingHide;
    private Runnable pendingOpen;
    private Runnable pendingReveal;
    private WindowManager.LayoutParams lp;

    // ---------------------------------------------------------------- static API

    public static void suspend(long ms) {
        suspendUntil = SystemClock.elapsedRealtime() + ms;
    }

    /** End a suspend early, e.g. once the user is back in the app after a setup step. */
    public static void endSuspend() {
        suspendUntil = 0L;
    }

    /** MainActivity announces itself so the overlay can hand it the live selection. */
    public static void setLiveActivity(MainActivity a) {
        liveActivity = new WeakReference<MainActivity>(a);
        resumedEpoch = epoch;
    }

    public static void clearLiveActivity(MainActivity a) {
        if (liveActivity != null && liveActivity.get() == a) liveActivity = null;
    }

    /** True only if the pick happened in the same epoch as the activity now resuming. */
    public static boolean consumeUserPicked() {
        boolean mine = (pickedEpoch == epoch);
        if (mine) pickedEpoch = -1L;
        return mine;
    }

    /**
     * Remove the overlay after a delay, so the activity behind it can finish its first
     * layout without visible flicker. The overlay's current selection is pushed to the
     * activity just before it lifts, since D-pad input during the hold goes to the overlay.
     */
    public static void hideOverlay(long delayMs) {
        final HomeRedirectService s = instance;
        if (s == null) return;
        s.cancelHide();
        s.pendingHide = new Runnable() {
            public void run() {
                s.pendingHide = null;
                s.handOverSelection();
                s.detach();
            }
        };
        s.handler.postDelayed(s.pendingHide, delayMs);
    }

    public static void hideOverlay() {
        hideOverlay(0L);
    }

    /**
     * Called from MainActivity.onPause: something else is coming to the front, so a hide
     * scheduled for the activity must not fire (it would expose Amazon's launcher).
     */
    public static void cancelPendingHide() {
        HomeRedirectService s = instance;
        if (s != null) s.cancelHide();
    }

    /** Which tile the highlight is on in the overlay, so the activity can match it. */
    public static String overlaySelectedKey() {
        HomeRedirectService s = instance;
        if (s == null || s.home == null || s.attached == null) return null;
        try { return s.home.getSelectedKey(); } catch (Throwable t) { return null; }
    }

    /** How far the user had scrolled the overlay, so the activity can match that too. */
    public static int overlayScrollY() {
        HomeRedirectService s = instance;
        if (s == null || s.home == null || s.attached == null) return 0;
        try { return s.home.getScrollY(); } catch (Throwable t) { return 0; }
    }

    /** Horizontal offsets of the section rows - they scroll independently. */
    public static int[] overlayRowScrollX() {
        HomeRedirectService s = instance;
        if (s == null || s.home == null || s.attached == null) return null;
        try { return s.home.getRowScrollX(); } catch (Throwable t) { return null; }
    }

    /**
     * True while a handoff is in progress. The activity then skips re-sorting, since the
     * overlay already sorted the shared model and a mismatch would show at the swap.
     */
    public static boolean isOverlayUp() {
        HomeRedirectService s = instance;
        return s != null && s.attached != null;
    }

    // ---------------------------------------------------------------- lifecycle

    private final BroadcastReceiver pkgs = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            // Registered on the service because the activity is rarely resumed while
            // packages change. Our own reinstall is ignored: reloading every banner for
            // it is wasted work and memory.
            android.net.Uri d = (i == null) ? null : i.getData();
            String changed = (d == null) ? null : d.getSchemeSpecificPart();
            if (changed != null && changed.equals(getPackageName())) return;
            Log.i(TAG, "package change (" + changed + "), reloading model once");
            AppModel.reload(c);
            if (home != null) home.rebuild();
            MainActivity a = (liveActivity == null) ? null : liveActivity.get();
            if (a != null) a.rebuildGrid();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (home != null) return;               // guard against a second connect
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            home = buildGrid();
            Log.i(TAG, "grid pre-built");
        } catch (Throwable t) {
            Log.w(TAG, "grid pre-build failed: " + t);
        }
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            registerReceiver(pkgs, f);
        } catch (Throwable t) { }
        AdbBridge.init(this);
        scheduleBootMaintenance();
        Log.i(TAG, "HomeRedirectService connected");
    }

    /**
     * Some Amazon packages re-enable themselves across a reboot, so if the user opted in,
     * re-disable their choices a while after the service starts (delayed to stay out of
     * the busy post-boot period).
     */
    private void scheduleBootMaintenance() {
        if (!Prefs.adbEnabled(this) || !Prefs.reapplyOnBoot(this)) return;
        if (!AdbCrypto.hasKey(this)) return;
        handler.postDelayed(new Runnable() {
            public void run() {
                java.util.Set<String> want = Prefs.disabledByUs(HomeRedirectService.this);
                if (want.isEmpty()) return;
                java.util.List<String> cmds = new java.util.ArrayList<String>();
                for (String pkg : want) {
                    if (Debloat.stateOf(HomeRedirectService.this, pkg) == Debloat.ENABLED) {
                        cmds.add(AdbBridge.disableCommand(pkg));
                    }
                }
                if (cmds.isEmpty()) return;
                Log.i(TAG, "boot maintenance: re-disabling " + cmds.size() + " package(s)");
                AdbBridge.run(cmds.toArray(new String[cmds.size()]), null);
            }
        }, 45000L);
    }

    @Override
    public void onDestroy() {
        cancelHide();
        detach();
        try { unregisterReceiver(pkgs); } catch (Throwable t) { }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private HomeGrid buildGrid() {
        return new HomeGrid(this, new HomeGrid.Listener() {
            public void onPick(AppModel.Item it) {
                Log.i(TAG, "onPick epoch=" + epoch);
                pickedEpoch = epoch;
                cancelPendingOpen();
                cancelHide();
                // Do not detach: the picked app's start is also delayed ~5 s, and removing
                // the cover would expose Amazon's launcher and trigger a new cycle. Keep
                // covering, but drop focus so accessibility events show the app arriving.
                setOverlayFocusable(false);
                armPickTimeout();
            }
        });
    }

    // ---------------------------------------------------------------- overlay

    private void attach() {
        if (attached != null && pickedEpoch == epoch) {
            // A pick is in flight; Amazon's launcher behind the cover is expected until
            // the picked app starts. Re-raising MainActivity here would cancel the launch.
            return;
        }
        if (attached != null) {
            // After the activity has taken over, this is a Home press during the hide
            // delay and the activity must be re-raised. Before that it is Amazon's
            // duplicate window event and is ignored.
            if (resumedEpoch == epoch) {
                Log.i(TAG, "home pressed during handoff; re-raising activity");
                cancelHide();
                MainActivity.open(this);
            }
            return;
        }
        try {
            if (home == null) home = buildGrid();
            // A failed removeView can leave the root parented, which would make addView throw.
            if (home.root.getParent() != null) {
                try { wm.removeViewImmediate(home.root); } catch (Throwable t) { }
            }
            long t0 = SystemClock.uptimeMillis();
            if (!addOverlay(home.root)) {
                throw new IllegalStateException("no usable overlay window type");
            }
            attached = home.root;
            epoch++;
            home.updateClock();
            home.takeFocus();
            Log.i(TAG, "overlay shown in " + (SystemClock.uptimeMillis() - t0)
                    + "ms epoch=" + epoch);

            final long myEpoch = epoch;
            // Refresh ordering after the frame is up, never before it.
            handler.postDelayed(new Runnable() {
                public void run() {
                    if (myEpoch != epoch || attached == null || home == null) return;
                    String k = home.getSelectedKey();
                    home.resort();
                    if (k != null) home.selectKey(k);
                }
            }, 300L);

            // Hand off once Fire OS allows the start; cancelled by onPick and detach.
            pendingOpen = new Runnable() {
                public void run() {
                    pendingOpen = null;
                    if (myEpoch != epoch || attached == null) return;
                    Log.i(TAG, "handing off to activity");
                    MainActivity.open(HomeRedirectService.this);
                }
            };
            handler.postDelayed(pendingOpen, HANDOFF_MS);

            // Retry once if the start never landed: a focused overlay blocks accessibility
            // events, so nothing else would notice.
            handler.postDelayed(new Runnable() {
                public void run() {
                    if (myEpoch != epoch || attached == null) return;
                    if (resumedEpoch == myEpoch) return;
                    Log.w(TAG, "watchdog: activity never took over, re-issuing start");
                    MainActivity.open(HomeRedirectService.this);
                }
            }, WATCHDOG_MS);
        } catch (Throwable t) {
            Log.w(TAG, "overlay attach failed, falling back to activity: " + t);
            MainActivity.open(this);
        }
    }

    /**
     * Add the grid as a system window. TYPE_ACCESSIBILITY_OVERLAY needs no permission for
     * an enabled accessibility service; the system-alert types are a fallback and need
     * SYSTEM_ALERT_WINDOW.
     */
    private boolean addOverlay(View v) {
        int[] types = (android.os.Build.VERSION.SDK_INT >= 26)
                ? new int[]{ WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                             WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
                : new int[]{ WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                             WindowManager.LayoutParams.TYPE_SYSTEM_ALERT };
        for (int i = 0; i < types.length; i++) {
            try {
                lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        types[i],
                        // Focusable, so D-pad input does not reach Amazon's launcher.
                        0,
                        PixelFormat.OPAQUE);
                wm.addView(v, lp);
                if (overlayType != types[i]) {
                    Log.i(TAG, "overlay window type " + typeName(types[i]));
                }
                overlayType = types[i];
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "window type " + typeName(types[i]) + " refused: " + t);
                // addView can fail after partially attaching; clear it for the next try.
                try { wm.removeViewImmediate(v); } catch (Throwable ignored) { }
            }
        }
        overlayType = 0;
        return false;
    }

    private static String typeName(int t) {
        if (t == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) return "accessibility (2032)";
        if (t == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) return "application (2038)";
        if (t == WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) return "system alert (2003)";
        return String.valueOf(t);
    }

    /** For the Setup and TV Health pages: which window type the overlay actually got. */
    public static String overlayTypeLabel() {
        if (overlayType == 0) return null;
        return typeName(overlayType);
    }

    /** True once we have proven the overlay works without SYSTEM_ALERT_WINDOW. */
    public static boolean overlayIsFree() {
        return overlayType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    }

    /**
     * After a pick the overlay is only a cover. A focusable overlay blocks accessibility
     * events for other apps, so focus is dropped to let us see the picked app arrive.
     */
    private void setOverlayFocusable(boolean focusable) {
        if (attached == null || lp == null) return;
        try {
            lp.flags = focusable ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            wm.updateViewLayout(attached, lp);
            Log.i(TAG, "overlay focusable=" + focusable);
        } catch (Throwable t) {
            Log.w(TAG, "updateViewLayout failed: " + t);
        }
    }

    /** Last resort: if the picked app never appears, do not sit on the screen forever. */
    private void armPickTimeout() {
        final long myEpoch = epoch;
        handler.postDelayed(new Runnable() {
            public void run() {
                if (myEpoch != epoch || attached == null) return;
                Log.w(TAG, "picked app never appeared; removing cover");
                detach();
            }
        }, PICK_TIMEOUT_MS);
    }

    private void cancelPendingOpen() {
        if (pendingOpen != null) {
            Log.i(TAG, "cancelling queued activity start");
            handler.removeCallbacks(pendingOpen);
            pendingOpen = null;
        }
    }

    private void cancelHide() {
        if (pendingHide != null) {
            handler.removeCallbacks(pendingHide);
            pendingHide = null;
        }
    }

    /** Push the overlay's live selection into the activity before the overlay lifts. */
    private void handOverSelection() {
        try {
            if (home == null || attached == null) return;
            MainActivity a = (liveActivity == null) ? null : liveActivity.get();
            if (a == null) return;
            // Read live: the user can keep moving and scrolling during the hold.
            a.applyState(home.getSelectedKey(), home.getScrollY(), home.getRowScrollX());
        } catch (Throwable t) { }
    }

    private void revealSoon() {
        if (pendingReveal != null) return;
        final long myEpoch = epoch;
        pendingReveal = new Runnable() {
            public void run() {
                pendingReveal = null;
                if (myEpoch == epoch) detach();
            }
        };
        handler.postDelayed(pendingReveal, REVEAL_MS);
    }

    private void detach() {
        cancelPendingOpen();
        if (pendingReveal != null) {
            handler.removeCallbacks(pendingReveal);
            pendingReveal = null;
        }
        if (attached == null) return;
        try { wm.removeView(attached); } catch (Throwable t) {
            // If it is still parented, the next attach() clears it.
            Log.w(TAG, "removeView failed: " + t);
        }
        attached = null;
    }

    // ---------------------------------------------------------------- events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        if (p == null) return;

        // Another app took the screen: remove the overlay so it does not cover it.
        if (!AMAZON.contentEquals(p)) {
            boolean ours = getPackageName().contentEquals(p);
            // Usage tracking comes from these events, so PACKAGE_USAGE_STATS is not needed.
            if (!ours && AppModel.hasApp(p.toString())) {
                UsageTracker.record(this, p.toString());
                handler.removeCallbacks(quietResort);
                handler.postDelayed(quietResort, 1500L);
            }
            CharSequence cls = e.getClassName();
            // Our own HDMI viewer counts as "the user got what they picked" too.
            boolean ourHdmi = ours && cls != null && cls.toString().endsWith("HdmiActivity");
            if ((!ours || ourHdmi) && attached != null) {
                Log.i(TAG, "foreground is now " + p + ", removing overlay");
                cancelHide();
                revealSoon();
            }
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now < suspendUntil) return;

        // Amazon's launcher can emit a second window event for a single Home press, so a
        // double press cannot be detected reliably; the "Amazon Home" tile is the way out.
        if (now - lastShow < DEBOUNCE_MS) return;
        lastShow = now;

        Log.i(TAG, "dispatchLag=" + (SystemClock.uptimeMillis() - e.getEventTime()) + "ms");
        attach();
    }

    @Override
    public void onInterrupt() { }
}
