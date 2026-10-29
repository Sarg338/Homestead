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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
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
 * There is no overlay: Homestead is started as an ordinary activity, so Amazon's home screen
 * is visible for a moment first. Nothing is ever left covering another app.
 *
 * Dormant unless {@link #shouldRun}: Fire OS 6 (Android 7.x) only, with READ_LOGS granted,
 * the accessibility redirect not running, and the Home button not turned off. Fire OS 7/8
 * keep using {@link HomeRedirectService} and never start this.
 */
public class HomeWatchService extends Service {

    private static final String TAG = "Homestead";
    /** Ignore repeat Home signals within this window (log replay, key repeat, re-entry). */
    private static final long DEBOUNCE_MS = 700L;

    private boolean watcherBound;
    private long lastOpen;

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
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
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
        MainActivity.open(this);
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
