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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything about Amazon's settings and launcher in one place: the settings screens that
 * open from here, and the switch that warns when a system update undoes Debloat.
 *
 * Every screen is an action that com.amazon.tv.settings.v2 answers, aimed at that package
 * so a second handler (the quick settings overlay also answers some) cannot turn the press
 * into an app chooser. A screen is left out on a TV where the action finds nothing, or
 * where it needs a permission only Amazon's own apps hold.
 */
public final class FireTvSettingsPanel {

    static final String SETTINGS_PKG = "com.amazon.tv.settings.v2";
    private static final String A = "com.amazon.device.settings.action.";
    private static final String PERMISSION = "com.amazon.tv.permission.LAUNCHER_SETTINGS";

    static final int ID = 0, TITLE = 1, ACTION = 2, HELP = 3;

    /** Id (also the icon), title, action, what it holds. Resolved on a TCL 40V5D, Fire OS 14. */
    static final String[][] SCREENS = {
            { "network", "Network", "android.settings.WIFI_SETTINGS",
                    "Wi-Fi, Ethernet and adding a network." },
            { "display", "Display & sounds", A + "DISPLAY_AND_SOUNDS",
                    "Resolution, HDR, audio, HDMI device control, ambient mode." },
            { "controllers", "Controllers & Bluetooth", A + "CONTROLLERS",
                    "Pair a remote, a game controller or any Bluetooth device." },
            { "accessibility", "Accessibility", A + "ACCESSIBILITY",
                    "Captions, audio description, screen reader." },
            { "applications", "Applications", A + "APPLICATIONS",
                    "Installed apps, storage and permissions." },
            { "language", "Language", A + "LANGUAGE", "The TV's language." },
            { "preferences", "Preferences", A + "PREFERENCES",
                    "Time zone, privacy, advertising, featured content." },
            { "data", "Data monitoring", A + "DATA_MONITOR", "Data used by apps and video." },
            { "account", "Amazon account", A + "MY_ACCOUNT", "The account this TV is signed in to." },
            { "device", "My Fire TV", A + "DEVICE",
                    "About this TV, software updates, Developer options and ADB debugging." },
    };

    private FireTvSettingsPanel() { }

    static Intent intentFor(String action) {
        Intent i = new Intent(action);
        i.setPackage(SETTINGS_PKG);
        return i;
    }

    /**
     * The screens that open from here on this TV. Several demand a permission only Amazon's
     * own apps hold (signature | privileged), so pressing them would throw; those titles go
     * into {@code locked} when it is given.
     */
    static List<String[]> available(Context c, List<String> locked) {
        List<String[]> out = new ArrayList<String[]>();
        for (int i = 0; i < SCREENS.length; i++) {
            ResolveInfo ri = c.getPackageManager().resolveActivity(intentFor(SCREENS[i][ACTION]), 0);
            if (ri == null || ri.activityInfo == null) continue;
            String need = ri.activityInfo.permission;
            if (need != null && c.checkSelfPermission(need) != PackageManager.PERMISSION_GRANTED) {
                if (locked != null) locked.add(SCREENS[i][TITLE]);
                continue;
            }
            out.add(SCREENS[i]);
        }
        return out;
    }

    // ---------------------------------------------------------------- the hub

    public static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, "Fire TV settings", null, Panel.SHEET);
        List<String[]> open = available(c, null);

        p.addHeader("Open from here");
        for (int i = 0; i < open.size(); i++) {
            final Intent intent = intentFor(open.get(i)[ACTION]);
            p.addRow(open.get(i)[TITLE], "", new Runnable() {
                public void run() { SetupPanel.start(c, intent); }
            }).opens().setHelp(open.get(i)[HELP]);
        }
        p.addHeader("Amazon's launcher");
        p.addRow("Amazon's launcher", AppModel.amazonLauncherOn(c) ? "on" : "off", null)
                .setHelp("Switch it off in Debloat to make Home open Homestead directly.");
        final Panel.Row watch = p.addRow("Warn if the system undoes Debloat",
                onOff(Prefs.restoreWatch(c)), null).asSwitch();
        watch.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.restoreWatch(c);
                Prefs.setRestoreWatch(c, n);
                watch.setValue(onOff(n));
                AppModel.reload(c);
                host.rebuildGrid();
            }
        });
        watch.setHelp("If an update switches back on something Debloat turned off, the home "
                + "screen shows it for 10 seconds, and OK switches it off again.");

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- small helpers

    private static String onOff(boolean on) { return on ? "on" : "off"; }
}
