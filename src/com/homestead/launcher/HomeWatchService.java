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

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * Fire OS 6 fallback for the Home button.
 *
 * On Fire OS 6 the accessibility service never binds (the system refuses third-party
 * accessibility services) and Homestead cannot become the default Home app either (Amazon's
 * launcher wins Home resolution by its intent-filter priority, and the fallback home is a
 * protected package). So instead of the overlay redirect used on Fire OS 7/8, this service
 * watches the system log for the Home press (through {@link HomeKeyWatcher}, which needs only
 * READ_LOGS) and brings Homestead to the front over Amazon's home screen.
 *
 * Fire OS holds back activity starts from apps for ~5 s after a Home press, and on Fire OS 6
 * nothing an app can do lifts that (a PendingIntent, an alarm or moving a task are held too).
 * So, as on Fire OS 7/8, the grid is shown at once as an overlay window and MainActivity takes
 * over when the hold ends. Here the overlay is a system-alert window, which needs
 * SYSTEM_ALERT_WINDOW (granted with appops from a computer). Without it Homestead is started
 * as an ordinary activity and shows after the hold, with Amazon's home screen in between.
 *
 * Dormant unless {@link #shouldRun}: Fire OS 6 (Android 7.x) only, with READ_LOGS granted,
 * the accessibility redirect not running, and the Home button not turned off. Fire OS 7/8
 * keep using {@link HomeRedirectService} and never start this.
 */
public class HomeWatchService extends Service {

    private static final String TAG = "Homestead";
    /** Ignore repeat Home signals within this window (log replay, key repeat, re-entry). */
    private static final long DEBOUNCE_MS = 700L;

    /**
     * When to hand off to the real activity: just after Fire OS's ~5 s hold on app starts. A
     * start issued earlier is held and lands on top of whatever the user opened meanwhile;
     * issuing it late lets a pick simply cancel it. Same timings as {@link HomeRedirectService}.
     */
    private static final long HANDOFF_MS = 5200L;
    /** If the activity still has not taken over by now, try again. */
    private static final long WATCHDOG_MS = 12000L;
    /** Fire OS's hold on app starts after a Home press. */
    private static final long HOLD_MS = 5000L;
    /**
     * How long the cover stays after the hold ends, for an app picked on the overlay: its
     * held start runs then, and this gives it time to draw. Fire OS 6 does not show apps the
     * log lines that say when another app's window is drawn, so this is a fixed time.
     */
    private static final long PICK_REVEAL_MS = 1200L;
    /** How long the cover stays after picking Amazon's home, which is already behind it. */
    private static final long REVEAL_MS = 400L;
    /** Id of the notification that keeps this service in the foreground. */
    private static final int FOREGROUND_ID = 6;

    private static HomeWatchService instance;

    // Main thread only. Each overlay shown opens a new epoch; picks, activity resumes and
    // pending hides belong to the epoch they happened in (as in HomeRedirectService).
    private static long epoch = 0L;
    private static long pickedEpoch = -1L;
    private static long resumedEpoch = -1L;
    private static java.lang.ref.WeakReference<MainActivity> liveActivity;

    private boolean watcherBound;
    /** elapsedRealtime of the last Home press: Fire OS's hold runs from it. */
    private long lastOpen;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private HomeGrid home;
    /** The overlay window: the grid, and in front of it a guard for keys after a pick. */
    private FrameLayout frame;
    /** After a pick: keys are swallowed until the picked app's start runs. */
    private boolean frozen;
    private View attached;
    private Runnable pendingOpen;
    private Runnable pendingHide;
    private Runnable pendingReveal;

    private final Messenger fromWatcher = new Messenger(new Handler(Looper.getMainLooper()) {
        public void handleMessage(Message m) {
            if (m.what == HomeKeyWatcher.MSG_HOME) onHomeKey(m.arg1);
        }
    });

    private final ServiceConnection watcherConn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            try {
                Message m = Message.obtain(null, HomeKeyWatcher.MSG_REGISTER);
                m.replyTo = fromWatcher;
                new Messenger(b).send(m);
            } catch (RemoteException e) { }
        }
        // If its process dies the system restarts it and calls onServiceConnected again.
        public void onServiceDisconnected(ComponentName n) { }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Lang.apply(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    /** A system change (the TV's language, say) resets our resources; keep the user's choice. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration conf) {
        super.onConfigurationChanged(conf);
        Lang.apply(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!shouldRun(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!watcherBound && HomeKeyWatcher.permitted(this)) {
            try {
                watcherBound = bindService(new Intent(this, HomeKeyWatcher.class),
                        watcherConn, BIND_AUTO_CREATE);
                Log.i(TAG, "FO6 Home watch started");
            } catch (Throwable t) {
                Log.w(TAG, "FO6 Home watch bind failed: " + t);
            }
        }
        // Build the overlay's grid ahead of the first press, so it is up before Amazon's
        // home draws (building it takes a few hundred ms).
        if (home == null && overlayPermitted(this)) {
            handler.post(new Runnable() {
                public void run() {
                    if (home != null || !overlayPermitted(HomeWatchService.this)) return;
                    try {
                        home = buildGrid();
                        Log.i(TAG, "FO6 overlay grid pre-built");
                    } catch (Throwable t) {
                        Log.w(TAG, "FO6 overlay grid pre-build failed: " + t);
                    }
                }
            });
        }
        keepForeground();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /**
     * With the overlay, run as a foreground service. Otherwise, while another app is in front,
     * this process is in the background scheduling group and adding the overlay takes long
     * enough for Amazon's home to show first. Fire OS 7/8 get this for free: the system binds
     * the accessibility service at a high priority.
     */
    private void keepForeground() {
        if (!overlayPermitted(this)) return;
        try {
            android.app.Notification n = new android.app.Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(getApplicationInfo().loadLabel(getPackageManager()))
                    .setPriority(android.app.Notification.PRIORITY_MIN)
                    .build();
            startForeground(FOREGROUND_ID, n);
        } catch (Throwable t) {
            Log.w(TAG, "FO6 could not run in the foreground: " + t);
        }
    }

    @Override
    public void onDestroy() {
        cancelHide();
        detach();
        if (instance == this) instance = null;
        if (watcherBound) {
            try { unbindService(watcherConn); } catch (Throwable t) { }
            watcherBound = false;
        }
        super.onDestroy();
    }

    /**
     * A HOME start reported from the log. The log line appears as the system starts Amazon's
     * home, so Homestead is launched right behind it. We start our own activity by explicit
     * component (not a HOME-category intent), so it never produces a log line that re-triggers
     * this. A short debounce drops key repeats.
     */
    private void onHomeKey(int uid) {
        // Turned off since this started: stand down rather than wait to be stopped.
        if (Prefs.homeOffByUser(this)) {
            stopSelf();
            return;
        }
        // The system starts HOME for a Home press (uid 1000; uid 0 on some later Fire OS).
        // Without the accessibility service there is no way to tell which app is in front,
        // so a HOME start from any other uid, such as an app in the background or Amazon's
        // settings on its way into Settings, must not pull Homestead over what is on screen.
        if (uid != Process.SYSTEM_UID && uid != 0) {
            Log.i(TAG, "FO6 HOME start from uid " + uid + ", not a Home press");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastOpen < DEBOUNCE_MS) return;
        lastOpen = now;
        Log.i(TAG, "FO6 Home press (uid " + uid + "); opening Homestead");
        if (!showOverlay()) MainActivity.open(this);
    }

    // ---------------------------------------------------------------- overlay

    /** True once SYSTEM_ALERT_WINDOW has been allowed (appops, from a computer). */
    static boolean overlayPermitted(Context c) {
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Shows the grid over Amazon's home at once and queues the handoff to MainActivity.
     * @return false if there is no overlay, and the caller starts the activity instead.
     */
    private boolean showOverlay() {
        if (attached != null) {
            // A pick is in flight; Amazon's home behind the cover is expected until the
            // picked app starts.
            if (pickedEpoch == epoch) return true;
            // The activity has taken over and the overlay is in its hide delay: raise it.
            if (resumedEpoch == epoch) {
                Log.i(TAG, "FO6 Home pressed during handoff; re-raising activity");
                cancelHide();
                MainActivity.open(this);
                return true;
            }
            // Pressed again while the overlay is up: back to the grid. The press starts a new
            // hold, so the handoff moves with it.
            home.closePanels();
            if (!home.isPanelOpen()) home.takeStartFocus();
            if (pendingOpen != null) {
                handler.removeCallbacks(pendingOpen);
                handler.postDelayed(pendingOpen, HANDOFF_MS);
            }
            return true;
        }
        if (!overlayPermitted(this) || wm == null) return false;
        try {
            if (home == null) home = buildGrid();
            // A failed removeView can leave the frame parented, which would make addView throw.
            if (frame.getParent() != null) {
                try { wm.removeViewImmediate(frame); } catch (Throwable t) { }
            }
            long t0 = SystemClock.uptimeMillis();
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    // Focusable, so D-pad input does not reach Amazon's launcher.
                    0,
                    PixelFormat.OPAQUE);
            frozen = false;
            wm.addView(frame, lp);
            attached = frame;
            epoch++;
            home.closePanels();
            // Settings changed in the activity's grid would otherwise show here only later.
            home.syncOrder();
            home.applyBackground();
            home.updateClock();
            home.takeStartFocus();
            Log.i(TAG, "FO6 overlay shown in " + (SystemClock.uptimeMillis() - t0)
                    + "ms epoch=" + epoch);
        } catch (Throwable t) {
            Log.w(TAG, "FO6 overlay failed, opening the activity instead: " + t);
            if (frame != null) {
                try { wm.removeViewImmediate(frame); } catch (Throwable ignored) { }
            }
            attached = null;
            return false;
        }

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
        // Hand off once Fire OS allows the start; cancelled by a pick and by detach.
        pendingOpen = new Runnable() {
            public void run() {
                pendingOpen = null;
                if (myEpoch != epoch || attached == null) return;
                Log.i(TAG, "FO6 handing off to activity");
                MainActivity.open(HomeWatchService.this);
            }
        };
        handler.postDelayed(pendingOpen, HANDOFF_MS);
        // Retry once if the start never landed.
        handler.postDelayed(new Runnable() {
            public void run() {
                if (myEpoch != epoch || attached == null || pickedEpoch == myEpoch) return;
                if (resumedEpoch == myEpoch) return;
                Log.w(TAG, "FO6 watchdog: activity never took over, re-issuing start");
                MainActivity.open(HomeWatchService.this);
            }
        }, WATCHDOG_MS);
        return true;
    }

    /** Builds the grid and the overlay frame around it. */
    private HomeGrid buildGrid() {
        HomeGrid g = newGrid();
        frame = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                return frozen || super.dispatchKeyEvent(e);
            }
        };
        frame.addView(g.root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return g;
    }

    private HomeGrid newGrid() {
        return new HomeGrid(this, new HomeGrid.Listener() {
            public void onPick(AppModel.Item it) {
                Log.i(TAG, "FO6 onPick epoch=" + epoch);
                pickedEpoch = epoch;
                cancelPendingOpen();
                cancelHide();
                // No second pick, and no key may reach Amazon's home behind the cover.
                frozen = true;
                // Amazon's home is already behind the overlay.
                if (it != null && it.type == AppModel.TYPE_AMAZON_HOME) {
                    revealSoon(REVEAL_MS);
                    return;
                }
                // The picked app's start is held too: keep covering until it has had time to
                // draw. Let go of the D-pad when the hold ends, so keys then go to the app.
                long holdLeft = Math.max(0L, lastOpen + HOLD_MS - SystemClock.elapsedRealtime());
                final long myEpoch = epoch;
                handler.postDelayed(new Runnable() {
                    public void run() {
                        if (myEpoch == epoch) setOverlayFocusable(false);
                    }
                }, holdLeft);
                revealSoon(holdLeft + PICK_REVEAL_MS);
            }
        });
    }

    private void setOverlayFocusable(boolean focusable) {
        if (attached == null || lp == null) return;
        try {
            lp.flags = focusable ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            wm.updateViewLayout(attached, lp);
        } catch (Throwable t) {
            Log.w(TAG, "updateViewLayout failed: " + t);
        }
    }

    private void revealSoon(long delayMs) {
        if (pendingReveal != null) return;
        final long myEpoch = epoch;
        pendingReveal = new Runnable() {
            public void run() {
                pendingReveal = null;
                if (myEpoch == epoch) detach();
            }
        };
        handler.postDelayed(pendingReveal, delayMs);
    }

    private void cancelPendingOpen() {
        if (pendingOpen != null) {
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

    private void detach() {
        cancelPendingOpen();
        if (pendingReveal != null) {
            handler.removeCallbacks(pendingReveal);
            pendingReveal = null;
        }
        if (attached == null) return;
        try { wm.removeView(attached); } catch (Throwable t) {
            // If it is still parented, the next showOverlay() clears it.
            Log.w(TAG, "removeView failed: " + t);
        }
        attached = null;
    }

    /** Push the overlay's live selection and open page into the activity before it lifts. */
    private void handOverSelection() {
        try {
            if (home == null || attached == null) return;
            MainActivity a = (liveActivity == null) ? null : liveActivity.get();
            if (a == null) return;
            a.applyState(home.getSelectedKey(), home.getScrollY(), home.getRowScrollX());
            a.adoptPanels(home);
        } catch (Throwable t) { }
    }

    // ---------------------------------------------------------------- MainActivity hooks
    // The same calls MainActivity makes on HomeRedirectService; no-ops without an overlay.

    static void setLiveActivity(MainActivity a) {
        liveActivity = new java.lang.ref.WeakReference<MainActivity>(a);
        resumedEpoch = epoch;
    }

    static void clearLiveActivity(MainActivity a) {
        if (liveActivity != null && liveActivity.get() == a) liveActivity = null;
    }

    /** True only if the pick happened in the same epoch as the activity now resuming. */
    static boolean consumeUserPicked() {
        boolean mine = (pickedEpoch == epoch);
        if (mine) pickedEpoch = -1L;
        return mine;
    }

    /** Lift the overlay after the activity has laid out behind it. */
    static void hideOverlay(long delayMs) {
        final HomeWatchService s = instance;
        if (s == null || s.attached == null) return;
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

    /** Something else is coming to the front: a hide scheduled for the activity must not fire. */
    static void cancelPendingHide() {
        HomeWatchService s = instance;
        if (s != null) s.cancelHide();
    }

    static boolean isOverlayUp() {
        HomeWatchService s = instance;
        return s != null && s.attached != null;
    }

    static String overlaySelectedKey() {
        HomeWatchService s = instance;
        if (s == null || s.home == null || s.attached == null) return null;
        try { return s.home.getSelectedKey(); } catch (Throwable t) { return null; }
    }

    static int overlayScrollY() {
        HomeWatchService s = instance;
        if (s == null || s.home == null || s.attached == null) return 0;
        try { return s.home.getScrollY(); } catch (Throwable t) { return 0; }
    }

    static int[] overlayRowScrollX() {
        HomeWatchService s = instance;
        if (s == null || s.home == null || s.attached == null) return null;
        try { return s.home.getRowScrollX(); } catch (Throwable t) { return null; }
    }

    /** The language was changed in the app: the overlay's grid reads its text again. */
    static void languageChanged() {
        HomeWatchService s = instance;
        if (s == null) return;
        Lang.apply(s);
        if (s.home != null) s.home.rebuild();
    }

    // ---------------------------------------------------------------- gate + start

    /** Start the Fire OS 6 Home watch when this device needs it; a no-op otherwise. */
    public static void startIfNeeded(Context c) {
        if (!shouldRun(c)) return;
        try {
            c.startService(new Intent(c, HomeWatchService.class));
        } catch (Throwable t) {
            Log.w(TAG, "could not start FO6 Home watch: " + t);
        }
    }

    /** Whether this is how the Home button works here, and the user has not turned it off. */
    static boolean shouldRun(Context c) {
        return available(c) && !Prefs.homeOffByUser(c);
    }

    /**
     * Fire OS 6 (Android 7.x) with READ_LOGS granted and the accessibility redirect not
     * running: the Home button works through this service, whether or not it is turned off.
     * Fire OS 7/8 (SDK 28/30) never qualify, so their behaviour is unchanged. Fire OS 5
     * (Android 5.1) is left out until someone confirms how Home behaves there.
     */
    static boolean available(Context c) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1   // 25 = Fire OS 6
                && HomeKeyWatcher.permitted(c)
                && !accessibilityRunning(c);
    }

    /** "Turn off the Home button" on Fire OS 6. Unlike Fire OS 7/8, no ADB to undo it. */
    static void turnOff(Context c) {
        HomeWatchService s = instance;
        if (s != null) {
            s.cancelHide();
            s.detach();
        }
        Prefs.setHomeOffByUser(c, true);
        c.stopService(new Intent(c, HomeWatchService.class));
    }

    /** Back on: READ_LOGS stays granted, so this needs no computer. */
    static void turnOn(Context c) {
        Prefs.setHomeOffByUser(c, false);
        startIfNeeded(c);
    }

    /** Whether our accessibility service is actually bound - not merely set in the setting. */
    private static boolean accessibilityRunning(Context c) {
        try {
            AccessibilityManager am =
                    (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null || !am.isEnabled()) return false;
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) return false;
            String pkg = c.getPackageName();
            for (int i = 0; i < list.size(); i++) {
                AccessibilityServiceInfo info = list.get(i);
                if (info != null && info.getId() != null && info.getId().contains(pkg)) return true;
            }
        } catch (Throwable t) { }
        return false;
    }
}
