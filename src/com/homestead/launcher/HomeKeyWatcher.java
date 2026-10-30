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
import android.os.Build;
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
 * grant starts a fresh one that can read the log straight away. Only ActivityManager and
 * ActivityTaskManager lines from the system buffer are read, and nothing is kept.
 *
 * Any app can write a line with any tag to any log buffer, so the text of a line proves
 * nothing. What it cannot fake is the uid the log daemon stamps on the entry from the
 * writer's own credentials, which "-v uid" prints: only entries written by the system's
 * process (uid 1000, where ActivityManager lives) count as a Home press.
 */
public class HomeKeyWatcher extends Service {

    static final int MSG_REGISTER = 1;
    /** arg1 is the uid that started the HOME intent. */
    static final int MSG_HOME = 2;

    private static final String TAG = "Homestead";
    private static final String PERM = "android.permission.READ_LOGS";
    /** "-v uid" exists from Android 7. Fire OS 5 has no uid stamp to check. */
    private static final boolean UID_FIELD = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    /** The uid of the process that logs ActivityManager lines: the system itself. */
    private static final String SYSTEM_UID = String.valueOf(Process.SYSTEM_UID);

    private volatile Messenger client;
    private volatile boolean running;
    private volatile java.lang.Process proc;
    private Thread reader;
    /** Set to false if this TV's logcat refuses "-v uid"; the lines are then read as before. */
    private boolean uidField = UID_FIELD;

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
            long lines = 0;
            try {
                // -T 1 follows from the newest line, so there is no backlog to skip.
                // Android 9 (Fire OS 7) logs activity starts as ActivityManager; Android 10
                // and later (Fire OS 8) as ActivityTaskManager. Fire OS 5 (Android 5.1) has
                // no "-v epoch", so it reads local times instead.
                String format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? "epoch" : "time";
                java.util.List<String> cmd = new java.util.ArrayList<String>(java.util.Arrays.asList(
                        "logcat", "-b", "system", "-v", format));
                if (uidField) { cmd.add("-v"); cmd.add("uid"); }
                cmd.addAll(java.util.Arrays.asList("-T", "1",
                        "ActivityManager:I", "ActivityTaskManager:I", "*:S"));
                proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()));
                Log.i(TAG, "watching for Home presses" + (uidField ? "" : " (no uid stamp)"));
                String line;
                while (running && (line = r.readLine()) != null) {
                    lines++;
                    int uid = homeStartUid(line, started, uidField);
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
            // A logcat that quits at once having printed nothing did not understand its
            // arguments: this TV has no "-v uid". Read the lines as before, without the check.
            if (uidField && lines == 0 && System.currentTimeMillis() - started < 3000L) {
                Log.w(TAG, "logcat refused -v uid; reading without the uid stamp");
                uidField = false;
                continue;
            }
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
     *
     * @param uidField the line carries the writer's uid after the time ("-v uid"); the line
     *                 then counts only if the system's own process wrote it
     */
    static int homeStartUid(String line, long notBefore, boolean uidField) {
        if (line.indexOf("START u0 {") < 0) return -1;
        if (line.indexOf("cat=[android.intent.category.HOME]") < 0) return -1;
        if (uidField && !writtenBySystem(line)) return -1;
        int u = line.indexOf(" from uid ");
        if (u < 0) return -1;
        int s = u + 10, e = s;
        while (e < line.length() && Character.isDigit(line.charAt(e))) e++;
        if (e == s) return -1;
        // -T 1 replays the newest line, which may be an old press.
        try {
            if (lineTime(line.trim(), System.currentTimeMillis()) < notBefore) return -1;
        } catch (Throwable ignored) { }
        try { return Integer.parseInt(line.substring(s, e)); } catch (Throwable t) { return -1; }
    }

    /**
     * True if the entry's uid stamp, the second field of a "-v epoch -v uid" line, is the
     * system's. logcat prints the uid as a number, or as a short account name; "system" is
     * accepted too in case a build names it.
     */
    static boolean writtenBySystem(String line) {
        String t = line.trim();
        int a = t.indexOf(' ');
        if (a < 0) return false;
        int b = a;
        while (b < t.length() && t.charAt(b) == ' ') b++;
        int e = t.indexOf(' ', b);
        if (e < 0) return false;
        String uid = t.substring(b, e);
        if (uid.endsWith(":")) uid = uid.substring(0, uid.length() - 1);
        return uid.equals(SYSTEM_UID) || uid.equals("system");
    }

    /**
     * When a line was logged, in ms since 1970: "-v epoch" starts with seconds, "-v time"
     * (Fire OS 5) with a local "MM-DD HH:MM:SS.mmm" and no year. A "-v time" line more than
     * a day ahead of now is from last December, read in January.
     */
    static long lineTime(String t, long now) throws java.text.ParseException {
        int sp = t.indexOf(' ');
        String first = t.substring(0, sp);
        if (first.indexOf('-') < 0) return (long) (Double.parseDouble(first) * 1000.0);
        String rest = t.substring(sp + 1).trim();
        String clock = rest.substring(0, rest.indexOf(' '));
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        int year = cal.get(java.util.Calendar.YEAR);
        long at = f.parse(year + "-" + first + " " + clock).getTime();
        if (at > now + 86400000L) at = f.parse((year - 1) + "-" + first + " " + clock).getTime();
        return at;
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
