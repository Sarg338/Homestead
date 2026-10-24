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
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything about Amazon's settings and launcher in one place: the settings screens that
 * open from here, the one that needs Amazon's launcher, an optional row of shortcuts on the
 * home screen, and what to know with Amazon's launcher switched off.
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
        List<String> locked = new ArrayList<String>();
        List<String[]> open = available(c, locked);

        p.addHeader("Open from here");
        for (int i = 0; i < open.size(); i++) {
            final Intent intent = intentFor(open.get(i)[ACTION]);
            p.addRow(open.get(i)[TITLE], "", new Runnable() {
                public void run() { SetupPanel.start(c, intent); }
            }).opens().setHelp(open.get(i)[HELP]);
        }
        final Panel.Row all = p.addRow("All Amazon settings",
                AppModel.amazonLauncherOn(c) ? "" : "needs the launcher", null).opens();
        all.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openAll(c, host); }
        });
        all.setHelp("Amazon's full settings menu"
                + (locked.isEmpty() ? "." : ", including " + join(locked) + ".")
                + (AppModel.amazonLauncherOn(c) ? ""
                        : " With Amazon's launcher off it is switched on for a moment first."));

        p.addHeader("Shortcuts on the home screen");
        final Panel.Row place = p.addRow("Shortcuts row", placeLabel(Prefs.shortcutsPlace(c)), null);
        place.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = (Prefs.shortcutsPlace(c) + 1) % 3;
                Prefs.setShortcutsPlace(c, n);
                place.setValue(placeLabel(n));
                AppModel.reload(c);
                host.rebuildGrid();
            }
        });
        place.setHelp("A row of settings shortcuts on the home screen: off, above the apps or "
                + "below them.");
        p.addRow("Choose shortcuts", "", new Runnable() {
            public void run() { host.showPanel(buildChoose(c, host)); }
        }).opens().setHelp("Which screens go in the row.");

        p.addHeader("Amazon's launcher");
        if (AppModel.amazonLauncherOn(c)) {
            p.addRow("Amazon's launcher", "on", null)
                    .setHelp("Switch it off in Debloat to make Home open Homestead directly.");
        } else {
            p.addRow("Amazon's launcher", "off", new Runnable() {
                public void run() { host.showPanel(SettingsPanel.buildLauncherOff(c, host)); }
            }).opens().setHelp("What changes while it is off, and how to get the screens "
                    + "that need it.");
        }
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

    private static View buildChoose(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, "Choose shortcuts", null, Panel.SHEET);
        p.addHeader("In the shortcuts row");
        final java.util.Set<String> on = Prefs.shortcuts(c);
        List<String[]> open = available(c, null);
        for (int i = 0; i < open.size(); i++) {
            final String id = open.get(i)[ID];
            final Panel.Row row = p.addRow(open.get(i)[TITLE], onOff(on.contains(id)), null).asSwitch();
            row.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean n = !Prefs.shortcuts(c).contains(id);
                    Prefs.setShortcut(c, id, n);
                    row.setValue(onOff(n));
                    AppModel.reload(c);
                    host.rebuildGrid();
                }
            });
            row.setHelp(open.get(i)[HELP]);
        }
        if (Prefs.shortcutsPlace(c) == Prefs.SHORTCUTS_OFF) {
            p.addNote("The row is off. Turn it on with \"Shortcuts row\" on the previous page.");
        }
        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- all settings, via the launcher

    /** The warning, then the button: what is about to happen and what it costs. */
    private static View buildOpenRest(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, "All Amazon settings", null, Panel.SHEET);

        p.addHeader("What will happen");
        p.addNote("1. Amazon's launcher is switched back on, over the ADB link.");
        p.addNote("2. Amazon's settings menu opens. Change what you need.");
        p.addNote("3. Press Home when you are done. Homestead covers Amazon's home again, with "
                + "a tile on top saying the launcher was switched back on. Press OK on it to "
                + "switch the launcher off again.");
        p.addHeader("What it costs if you leave it on");
        p.addNote("With Amazon's launcher on, Home goes through Amazon's home screen again: "
                + "its ads and a flash of it on every press. This also turns on \"Warn if the "
                + "system undoes Debloat\", which is what shows the tile in step 3.");

        p.addRow("Switch it on and open the settings", "", new Runnable() {
            public void run() { openRest(c); }
        });
        p.addRow("Cancel", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    private static void openRest(final Context c) {
        if (!AdbBridge.canRun(c)) {
            Toast.makeText(c, R.string.home_restore_failed, Toast.LENGTH_LONG).show();
            return;
        }
        // Kept in the list of what this app switched off, so the restore tile offers to undo it.
        Prefs.setRestoreWatch(c, true);
        Toast.makeText(c, R.string.home_connecting, Toast.LENGTH_SHORT).show();
        AdbBridge.runRetry(new String[]{ AdbBridge.enableCommand(AppModel.AMAZON_LAUNCHER) },
                new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) {
                if (Debloat.stateOf(c, AppModel.AMAZON_LAUNCHER) != Debloat.ENABLED) {
                    Toast.makeText(c, R.string.home_restore_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                openAllSettings(c);
            }
        });
    }

    /**
     * Amazon's full settings menu: straight away with the launcher on, behind the warning
     * with it off.
     */
    static void openAll(Context c, SettingsPanel.Host host) {
        if (AppModel.amazonLauncherOn(c)) openAllSettings(c);
        else host.showPanel(buildOpenRest(c, host));
    }

    /** Amazon's own settings menu, which the launcher serves and needs no permission to start. */
    private static void openAllSettings(Context c) {
        Intent i = new Intent();
        i.setClassName(AppModel.AMAZON_LAUNCHER, AppModel.AMAZON_LAUNCHER + ".ui.MainSettingsActivity");
        SetupPanel.start(c, i);
    }

    // ---------------------------------------------------------------- small helpers

    private static String placeLabel(int place) {
        if (place == Prefs.SHORTCUTS_TOP) return "above the apps";
        if (place == Prefs.SHORTCUTS_BOTTOM) return "below the apps";
        return "off";
    }

    private static String onOff(boolean on) { return on ? "on" : "off"; }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
