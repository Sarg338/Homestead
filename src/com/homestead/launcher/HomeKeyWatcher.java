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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Reports Home presses from the system log. The HOME intent is logged as the system acts
 * on the press, a few hundred ms before Amazon's home draws, and it is the only trace of a
 * press made while Amazon's home is already in front (that changes no window).
 *
 * Needs READ_LOGS, a development permission granted over ADB. The log group it maps to is
 * applied only when a process starts, so this runs in its own process: binding after the
 * grant starts a fresh one that can read the log straight away. Only ActivityManager lines
 * from the system buffer are read, and nothing is kept.
 */
public class HomeKeyWatcher extends Service {

    static final int MSG_REGISTER = 1;
    /** arg1 is the uid that started the HOME intent. */
    static final int MSG_HOME = 2;

    private static final String TAG = "Homestead";
    private static final String PERM = "android.permission.READ_LOGS";

    private volatile Messenger client;
    private volatile boolean running;
    private volatile java.lang.Process proc;
    private Thread reader;

    private final Messenger me = new Messenger(new Handler(Looper.getMainLooper()) {
        public void handleMessage(Message m) {
            if (m.what == MSG_REGISTER) client = m.replyTo;
        }
    });

    /** True once READ_LOGS has been granted, whether or not this process has it yet. */
    public static boolean permitted(Context c) {
        return c.checkPermission(PERM, Process.myPid(), Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!permitted(this)) return;
        running = true;
        reader = new Thread(new Runnable() {
            public void run() { readLoop(); }
        }, "homestead-homekey");
        reader.start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return me.getBinder();
    }

    @Override
    public void onDestroy() {
        running = false;
        java.lang.Process p = proc;
        if (p != null) p.destroy();
        if (reader != null) reader.interrupt();
        super.onDestroy();
    }

    private void readLoop() {
        long backoff = 2000L;
        while (running) {
            long started = System.currentTimeMillis();
            try {
                // -T 1 follows from the newest line, so there is no backlog to skip.
                proc = new ProcessBuilder("logcat", "-b", "system", "-v", "epoch", "-T", "1",
                        "ActivityManager:I", "*:S").redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()));
                Log.i(TAG, "watching for Home presses");
                String line;
                while (running && (line = r.readLine()) != null) {
                    int uid = homeStartUid(line, started);
                    if (uid >= 0) send(uid);
                }
            } catch (Throwable t) {
                if (running) Log.w(TAG, "Home watch failed: " + t);
            } finally {
                java.lang.Process p = proc;
                if (p != null) p.destroy();
                proc = null;
            }
            if (!running) return;
            // logcat exited; a run that lasted a while starts the backoff over.
            if (System.currentTimeMillis() - started > 60000L) backoff = 2000L;
            try { Thread.sleep(backoff); } catch (InterruptedException e) { return; }
            backoff = Math.min(backoff * 2, 60000L);
        }
    }

    /**
     * The uid that started a HOME intent, or -1 if the line is not one. Which uids count as
     * a Home press is up to HomeRedirectService. Our "Amazon Home" tile has no HOME
     * category, so it never matches.
     */
    static int homeStartUid(String line, long notBefore) {
        if (line.indexOf("START u0 {") < 0) return -1;
        if (line.indexOf("cat=[android.intent.category.HOME]") < 0) return -1;
        int u = line.indexOf(" from uid ");
        if (u < 0) return -1;
        int s = u + 10, e = s;
        while (e < line.length() && Character.isDigit(line.charAt(e))) e++;
        if (e == s) return -1;
        // -T 1 replays the newest line, which may be an old press.
        try {
            String t = line.trim();
            int sp = t.indexOf(' ');
            long at = (long) (Double.parseDouble(t.substring(0, sp)) * 1000.0);
            if (at < notBefore) return -1;
        } catch (Throwable ignored) { }
        try { return Integer.parseInt(line.substring(s, e)); } catch (Throwable t) { return -1; }
    }

    private void send(int uid) {
        Messenger c = client;
        if (c == null) return;
        try {
            c.send(Message.obtain(null, MSG_HOME, uid, 0));
        } catch (RemoteException e) {
            client = null;
        }
    }
}
