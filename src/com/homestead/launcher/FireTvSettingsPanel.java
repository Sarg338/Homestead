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

    /**
     * Id (also the icon), title, action, what it holds. Resolved on a TCL 40V5D (Fire OS 14)
     * and an Insignia (Fire OS 7), where Network, Controllers, Applications and My Fire TV
     * open from here and the rest need Amazon's permission.
     */
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

    /** The home screen's names for SCREENS, row for row, in the TV's language: tile, pill. */
    private static final int[][] HOME_LABELS = {
            { R.string.home_sc_network, R.string.home_sc_network },
            { R.string.home_sc_display, R.string.home_sc_display_short },
            { R.string.home_sc_controllers, R.string.home_sc_controllers_short },
            { R.string.home_sc_accessibility, R.string.home_sc_accessibility },
            { R.string.home_sc_applications, R.string.home_sc_applications },
            { R.string.home_sc_language, R.string.home_sc_language },
            { R.string.home_sc_preferences, R.string.home_sc_preferences },
            { R.string.home_sc_data, R.string.home_sc_data_short },
            { R.string.home_sc_account, R.string.home_sc_account_short },
            { R.string.home_sc_device, R.string.home_sc_device },
    };

    /** What each screen holds, row for row with SCREENS, in the TV's language. */
    private static final int[] HELPS = {
            R.string.ftv_h_network, R.string.ftv_h_display, R.string.ftv_h_controllers,
            R.string.ftv_h_accessibility, R.string.ftv_h_applications, R.string.ftv_h_language,
            R.string.ftv_h_preferences, R.string.ftv_h_data, R.string.ftv_h_account,
            R.string.ftv_h_device,
    };

    private FireTvSettingsPanel() { }

    /** A screen's name and help on these pages: the home screen's name, the help above. */
    private static String title(Context c, String[] s) { return homeLabel(c, s[ACTION], false); }

    private static String help(Context c, String[] s) {
        for (int i = 0; i < SCREENS.length; i++) if (SCREENS[i] == s) return c.getString(HELPS[i]);
        return s[HELP];
    }

    /** A screen's name on the home screen, by its action; null for an action not in SCREENS. */
    static String homeLabel(Context c, String action, boolean pill) {
        for (int i = 0; i < SCREENS.length; i++) {
            if (SCREENS[i][ACTION].equals(action)) return c.getString(HOME_LABELS[i][pill ? 1 : 0]);
        }
        return null;
    }

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
            // checkPermission, not checkSelfPermission: the latter is Android 6+ only.
            if (need != null && c.checkPermission(need, android.os.Process.myPid(),
                    android.os.Process.myUid()) != PackageManager.PERMISSION_GRANTED) {
                if (locked != null) locked.add(c.getString(HOME_LABELS[i][0]));
                continue;
            }
            out.add(SCREENS[i]);
        }
        return out;
    }

    // ---------------------------------------------------------------- the hub

    public static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, c.getString(R.string.home_sec_settings), null, Panel.SHEET);
        List<String> locked = new ArrayList<String>();
        List<String[]> open = available(c, locked);

        p.addHeader(c.getString(R.string.ftv_open_here));
        for (int i = 0; i < open.size(); i++) {
            final Intent intent = intentFor(open.get(i)[ACTION]);
            p.addRow(title(c, open.get(i)), "", new Runnable() {
                public void run() { SetupPanel.start(c, intent); }
            }).opens().setHelp(help(c, open.get(i)));
        }
        final Panel.Row all = p.addRow(c.getString(R.string.ftv_all),
                AppModel.amazonLauncherOn(c) ? "" : c.getString(R.string.ftv_all_needs), null).opens();
        all.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openAll(c, host); }
        });
        all.setHelp((locked.isEmpty() ? c.getString(R.string.ftv_all_help)
                        : c.getString(R.string.ftv_all_help_incl, join(locked)))
                + (AppModel.amazonLauncherOn(c) ? "" : " " + c.getString(R.string.ftv_all_help_off)));

        p.addHeader(c.getString(R.string.ftv_sc_header));
        final Panel.Row place = p.addRow(c.getString(R.string.ftv_sc_row), placeLabel(c, Prefs.shortcutsPlace(c)), null);
        place.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int now = Prefs.shortcutsPlace(c);
                int n = now == Prefs.SHORTCUTS_OFF ? Prefs.SHORTCUTS_ROW
                        : now == Prefs.SHORTCUTS_ROW ? Prefs.SHORTCUTS_BAR : Prefs.SHORTCUTS_OFF;
                Prefs.setShortcutsPlace(c, n);
                place.setValue(placeLabel(c, n));
                AppModel.reload(c);
                host.rebuildGrid();
            }
        });
        place.setHelp(c.getString(R.string.ftv_sc_row_help));
        p.addRow(c.getString(R.string.ftv_choose), "", new Runnable() {
            public void run() { host.showPanel(buildChoose(c, host)); }
        }).opens().setHelp(c.getString(R.string.ftv_choose_help));

        p.addHeader(c.getString(R.string.db_launcher_t));
        if (AppModel.amazonLauncherOn(c)) {
            p.addRow(c.getString(R.string.db_launcher_t), c.getString(R.string.v_on), null)
                    .setHelp(c.getString(R.string.ftv_launcher_on_help));
        } else {
            p.addRow(c.getString(R.string.db_launcher_t), c.getString(R.string.v_off), new Runnable() {
                public void run() { host.showPanel(SettingsPanel.buildLauncherOff(c, host)); }
            }).opens().setHelp(c.getString(R.string.ftv_launcher_off_help));
        }
        final Panel.Row watch = p.addRow(c.getString(R.string.ftv_watch),
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
        watch.setHelp(c.getString(R.string.ftv_watch_help));

        p.addRow(c.getString(R.string.set_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    private static View buildChoose(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, c.getString(R.string.ftv_choose), null, Panel.SHEET);
        p.addHeader(c.getString(R.string.ftv_choose_header));
        final java.util.Set<String> on = Prefs.shortcuts(c);
        List<String[]> open = available(c, null);
        for (int i = 0; i < open.size(); i++) {
            final String id = open.get(i)[ID];
            final Panel.Row row = p.addRow(title(c, open.get(i)), onOff(on.contains(id)), null).asSwitch();
            row.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean n = !Prefs.shortcuts(c).contains(id);
                    Prefs.setShortcut(c, id, n);
                    row.setValue(onOff(n));
                    AppModel.reload(c);
                    host.rebuildGrid();
                }
            });
            row.setHelp(help(c, open.get(i)));
        }
        if (Prefs.shortcutsPlace(c) == Prefs.SHORTCUTS_OFF) {
            p.addNote(c.getString(R.string.ftv_choose_off));
        }
        p.addRow(c.getString(R.string.set_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- all settings, via the launcher

    /** The warning, then the button: what is about to happen and what it costs. */
    private static View buildOpenRest(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, c.getString(R.string.ftv_all), null, Panel.SHEET);

        p.addHeader(c.getString(R.string.ftv_rest_what));
        p.addNote(c.getString(R.string.ftv_rest_1));
        p.addNote(c.getString(R.string.ftv_rest_2));
        p.addNote(c.getString(R.string.ftv_rest_3));
        p.addHeader(c.getString(R.string.ftv_rest_cost));
        p.addNote(c.getString(R.string.ftv_rest_cost_note));

        p.addRow(c.getString(R.string.ftv_rest_go), "", new Runnable() {
            public void run() { openRest(c); }
        });
        p.addRow(c.getString(R.string.ftv_cancel), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    private static void openRest(final Context c) {
        if (!AdbBridge.canRun(c)) {
            Toast.makeText(c, R.string.home_restore_failed, Toast.LENGTH_LONG).show();
            return;
        }
        // In the list of what this app switched off, so the restore tile offers to undo it,
        // also when the launcher was switched off some other way than Debloat.
        Prefs.noteDisabled(c, AppModel.AMAZON_LAUNCHER, true);
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
     * with it off. Also what the top bar's "More settings" pill does.
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

    private static String placeLabel(Context c, int place) {
        if (place == Prefs.SHORTCUTS_ROW) return c.getString(R.string.ftv_place_row);
        if (place == Prefs.SHORTCUTS_BAR) return c.getString(R.string.ftv_place_bar);
        return c.getString(R.string.ftv_place_off);
    }

    // The switch rows draw "on"/"off" as a toggle, so these stay as they are, in any language.
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
