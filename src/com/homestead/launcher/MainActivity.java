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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

/**
 * The home Activity, used at boot and when the launcher is opened normally.
 *
 * On the Home button, Fire OS delays background activity starts, so HomeRedirectService
 * first shows the same grid as an overlay and this activity takes over from it. See
 * HomeRedirectService for the handoff.
 */
public class MainActivity extends Activity implements HomeGrid.Listener {

    private static final String TAG = "Homestead";

    private HomeGrid home;
    private boolean receiversOn = false;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { if (home != null) home.updateClock(); }
    };

    public static void open(Context c) {
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            c.startActivity(i);
        } catch (Throwable t) { }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        home = new HomeGrid(this, this);
        setContentView(home.root);
        home.takeFocus();
        AdbBridge.init(this);

        // First run shows the wizard; later, if the accessibility service is off (e.g.
        // unbound by an app update) and the user did not turn it off, show Setup instead.
        if (!Prefs.onboarded(this)) {
            home.showPanel(OnboardingPanel.build(this, home));
        } else if (!SetupPanel.serviceEnabled(this) && !Prefs.homeOffByUser(this)) {
            home.showPanel(SetupPanel.build(this, home));
        } else {
            maybeCheckForUpdate();
        }
    }

    /**
     * Check GitHub for a newer release (throttled to once a day) and prompt once per
     * version, unless a panel is open or the version was skipped.
     */
    private void maybeCheckForUpdate() {
        if (!Prefs.autoUpdateCheck(this)) return;
        UpdateChecker.check(this, false, new UpdateChecker.Callback() {
            public void onResult(UpdateChecker.Update u, String error) {
                if (u == null || home == null || home.isPanelOpen()) return;
                if (u.versionName.equals(Prefs.skippedVersion(MainActivity.this))) return;
                if (u.versionName.equals(Prefs.notifiedVersion(MainActivity.this))) return;
                Prefs.setNotifiedVersion(MainActivity.this, u.versionName);
                home.showPanel(UpdatePanel.build(MainActivity.this, home, u));
            }
        });
    }

    /** Called by the service after it reloads the shared model. */
    void rebuildGrid() {
        if (home != null) home.rebuild();
    }

    /** Called by the service just before the overlay lifts, so late D-pad moves survive. */
    void applyState(String key, int scrollY, int[] rowScrollX) {
        if (home == null) return;
        if (key != null) home.selectKey(key);
        // After the focus restore, which would otherwise leave its own scroll position.
        home.applyScroll(scrollY, rowScrollX);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // The user launched an app from the overlay while this start was still pending;
        // resuming now would cover it. Scoped to the current Home press.
        if (HomeRedirectService.consumeUserPicked()) {
            Log.i(TAG, "user launched from overlay this cycle; finishing");
            finish();
            return;
        }

        HomeRedirectService.setLiveActivity(this);
        // Back from a settings, VPN or approval screen: Home should work again straight away.
        HomeRedirectService.endSuspend();

        String selKey = HomeRedirectService.overlaySelectedKey();
        int selScroll = HomeRedirectService.overlayScrollY();
        int[] selRows = HomeRedirectService.overlayRowScrollX();
        boolean handoff = HomeRedirectService.isOverlayUp();

        if (home != null) {
            // During a handoff the overlay has already sorted the shared model; re-sorting
            // here would make the two differ at the swap. Either way, catch up with any
            // re-sort the overlay made. Focus is restored by key, since order changes indices.
            if (!handoff) home.resort();
            home.syncOrder();
            boolean ok = (selKey != null) && home.selectKey(selKey);
            if (!ok) home.takeFocus();
            home.applyScroll(selScroll, selRows);
            Log.i(TAG, "handoff=" + handoff + " key=" + selKey + " restored=" + ok
                    + " scrollY=" + selScroll
                    + " rows=" + java.util.Arrays.toString(selRows));
        }

        // Lift the overlay once this activity has laid out behind it. The service copies
        // the overlay's selection just before lifting, so late moves are kept.
        HomeRedirectService.hideOverlay(1000L);

        if (!receiversOn) {
            registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
            receiversOn = true;
        }
        if (home != null) home.updateClock();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Something else is coming to the front; cancel the overlay hide we scheduled.
        HomeRedirectService.cancelPendingHide();
        HomeRedirectService.clearLiveActivity(this);
        if (receiversOn) {
            try { unregisterReceiver(tick); } catch (Throwable t) { }
            receiversOn = false;
        }
    }

    @Override public void onPick(AppModel.Item it) { /* activity needs no dismissal */ }

    @Override
    public void onBackPressed() {
        // A panel takes Back first; otherwise this is home and Back does nothing.
        if (home != null && home.isPanelOpen()) { home.closePanel(); return; }
    }
}
