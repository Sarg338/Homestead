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
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The Settings pages. Everything here is the launcher's own behaviour - nothing on these
 * pages touches the system, so none of it needs a privileged permission.
 */
public final class SettingsPanel {

    /** Implemented by HomeGrid so a panel can push another panel or apply changes. */
    public interface Host {
        void rebuildGrid();
        void showPanel(View v);
        void closePanel();
    }

    private SettingsPanel() { }

    /**
     * The hub: a sheet over the home screen with the two pages of home-screen options, then
     * Simple mode and the system pages. Changes show on the grid behind it straight away.
     */
    public static View build(final Context c, final Host host) {
        final Panel p = new Panel(c, "Settings",
                "Homestead " + UpdateChecker.installedVersionName(c), Panel.SHEET);

        final Panel.Row home = p.addRow("Home screen", homeSummary(c), null).opens();
        home.setValueColor(0xFF8FA3B8);
        home.setHelp("Hidden tiles, sorting, grid columns, the clock and the sections.");
        final Panel.Row look = p.addRow("Background", Backgrounds.label(c), null).opens();
        look.setValueColor(0xFF8FA3B8);
        look.setHelp("The colour or photo behind the home screen.");
        // Summaries catch up when a sub-page changes something.
        final Runnable summaries = new Runnable() {
            public void run() {
                home.setValue(homeSummary(c));
                look.setValue(Backgrounds.label(c));
            }
        };
        home.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(buildHome(c, host, summaries)); }
        });
        look.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(buildLook(c, host, summaries)); }
        });

        final Panel.Row simple = p.addRow("Simple mode", onOff(Prefs.simpleMode(c)), null).asSwitch();
        simple.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.simpleMode(c);
                Prefs.setSimpleMode(c, n);
                simple.setValue(onOff(n));
                host.rebuildGrid();
            }
        });
        p.addNote("For a TV set up for someone else: the Settings and TV Health tiles are "
                + "hidden (press MENU five times on the home screen to open Settings), MENU and "
                + "long-press no longer hide tiles, and there are no update pop-ups; check for "
                + "updates below. It does not change Debloat.");

        p.addHeader("System");

        boolean ready = SetupPanel.serviceEnabled(c);
        final Panel.Row setup = p.addRow("Setup", ready ? "done"
                : Prefs.homeOffByUser(c) ? "Home button off" : "not finished", null).opens();
        setup.setValueColor(ready ? 0xFFA7D28F : 0xFFE8C468);
        setup.setHelp("What this launcher needs, and how to switch it on.");
        setup.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(SetupPanel.build(c, host)); }
        });

        SetupPanel.addTurnOffRow(c, p, new Runnable() {
            public void run() {
                setup.setValue("Home button off");
                setup.setValueColor(0xFFE8C468);
            }
        });
        p.addNote(SetupPanel.TURN_OFF_HELP);

        p.addRow("TV Health", "check", new Runnable() {
            public void run() { host.showPanel(HealthPanel.build(c, host)); }
        }).opens().setHelp("This TV's speed and privacy settings and Amazon's advertising "
                + "packages, with a fix for each one it flags.");

        p.addRow("Debloat", Prefs.disabledByUs(c).size() + " off", new Runnable() {
            public void run() { host.showPanel(DebloatPanel.build(c, host)); }
        }).opens().setHelp("Switch Amazon's packages off, and back on again. Nothing is "
                + "ever uninstalled.");

        final Panel.Row updates = p.addRow("Check for updates",
                "v" + UpdateChecker.installedVersionName(c), null);
        updates.setValueColor(0xFF8FA3B8);
        updates.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { UpdatePanel.checkNow(c, host, updates); }
        });

        p.addRow("Close", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- home screen

    private static View buildHome(final Context c, final Host host, final Runnable changed) {
        final Panel p = new Panel(c, "Home screen", null, Panel.SHEET);

        final Panel.Row hidden = p.addRow("Hidden tiles",
                String.valueOf(Prefs.hiddenCount(c)), null).opens();
        hidden.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(buildHiddenList(c, host)); }
        });
        // The count changes on the page this row opens; repaint it on the way back.
        hidden.cell.setOnFocusChangeListener(chain(hidden.cell.getOnFocusChangeListener(),
                new Runnable() {
                    public void run() { hidden.setValue(String.valueOf(Prefs.hiddenCount(c))); }
                }));

        p.addNote("Press the MENU button on any tile to hide it (long-press works too; not "
                + "in Simple mode). Hiding only changes what this launcher draws - nothing is "
                + "disabled or uninstalled, and the app still works everywhere else.");

        final Panel.Row sort = p.addRow("Sort apps by", sortLabel(c), null);
        sort.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setSort(c, Prefs.sort(c) == Prefs.SORT_LAST_USED
                        ? Prefs.SORT_ALPHA : Prefs.SORT_LAST_USED);
                sort.setValue(sortLabel(c));
                AppModel.resort(c);
                host.rebuildGrid();
            }
        });

        final Panel.Row cols = p.addRow("Grid columns", String.valueOf(Prefs.columns(c)), null);
        cols.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = Prefs.columns(c) + 1;
                if (n > 8) n = 4;
                Prefs.setColumns(c, n);
                cols.setValue(String.valueOf(n));
                AppModel.invalidateMetrics();
                host.rebuildGrid();
                changed.run();
            }
        });

        final Panel.Row clock = p.addRow("Clock", clockLabel(c), null);
        clock.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = Prefs.clockMode(c) + 1;
                if (n > Prefs.CLOCK_OFF) n = Prefs.CLOCK_24H;
                Prefs.setClockMode(c, n);
                clock.setValue(clockLabel(c));
                host.rebuildGrid();
            }
        });

        p.addHeader("Sections");

        final Panel.Row secIn = p.addRow("Show \"Inputs & System\"", onOff(Prefs.showInputs(c)), null).asSwitch();
        secIn.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.showInputs(c);
                Prefs.setShowInputs(c, n);
                secIn.setValue(onOff(n));
                host.rebuildGrid();
            }
        });

        final Panel.Row secNon = p.addRow("Show \"Non-TV Applications\"", onOff(Prefs.showNonTv(c)), null).asSwitch();
        secNon.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.showNonTv(c);
                Prefs.setShowNonTv(c, n);
                secNon.setValue(onOff(n));
                host.rebuildGrid();
            }
        });

        p.addSectionHelp("Settings and TV Health always stay reachable, even with "
                + "\"Inputs & System\" switched off. Simple mode hides them; press MENU five "
                + "times on the home screen to come back here.");

        p.addHeader("Apps");
        p.addRow("Reload apps now", "", new Runnable() {
            public void run() {
                AppModel.reload(c);
                host.rebuildGrid();
            }
        }).setHelp("Reads the installed apps and the TV's inputs again.");

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    private static String homeSummary(Context c) {
        return Prefs.columns(c) + " columns";
    }

    /** Runs extra work after a row's own focus handling, when it gains focus. */
    private static View.OnFocusChangeListener chain(final View.OnFocusChangeListener first,
                                                    final Runnable onGain) {
        return new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (first != null) first.onFocusChange(v, has);
                if (has) onGain.run();
            }
        };
    }

    // ---------------------------------------------------------------- background

    private static View buildLook(final Context c, final Host host, final Runnable changed) {
        final Panel p = new Panel(c, "Background", null, Panel.SHEET);

        final Panel.Row bg = p.addRow("Background", Backgrounds.label(c), null);
        final Panel.Row dim = p.addRow("Photo dimming", Backgrounds.dimLabel(c), null);
        bg.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setBackground(c, Backgrounds.next(c));
                bg.setValue(Backgrounds.label(c));
                host.rebuildGrid();
                changed.run();
            }
        });
        dim.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setPhotoDim(c, (Prefs.photoDim(c) + 1) % Backgrounds.DIM_NAMES.length);
                dim.setValue(Backgrounds.dimLabel(c));
                host.rebuildGrid();
            }
        });
        bg.setHelp("Black is for OLED screens and night viewing. For Photo, choose a picture "
                + "saved on the TV below (needs the on-device tunnel), or send a JPEG or PNG "
                + "from a computer:\n\n"
                + "adb push background.jpg /sdcard/Download/\n"
                + "adb shell \"content write --uri content://" + BackgroundProvider.AUTHORITY
                + "/photo < /sdcard/Download/background.jpg\"");
        dim.setHelp("Dimming darkens the photo so the labels stay readable.");

        p.addRow("Choose a photo on this TV", "", new Runnable() {
            public void run() { host.showPanel(PhotoPanel.build(c, host)); }
        }).opens().setHelp("Lists the pictures in the TV's Download folder. Needs the "
                + "on-device tunnel.");

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- hidden list

    private static View buildHiddenList(final Context c, final Host host) {
        final Panel p = new Panel(c, "Hidden tiles",
                "Select one to show it on the home screen again", Panel.SHEET);

        Set<String> keys = Prefs.hidden(c);
        if (keys.isEmpty()) {
            p.addNote("Nothing is hidden. Press MENU on a tile (or long-press it) to hide it.");
        } else {
            // Resolve keys to human labels through the full model, not the visible list.
            List<AppModel.Item> all = AppModel.allItems();
            List<String> rows = new ArrayList<String>();
            final List<String> rowKeys = new ArrayList<String>();
            for (String k : keys) {
                String label = k;
                for (int i = 0; i < all.size(); i++) {
                    if (k.equals(all.get(i).key())) { label = all.get(i).label; break; }
                }
                rows.add(label);
                rowKeys.add(k);
            }
            // Stable, readable order.
            for (int i = 0; i < rows.size(); i++) {
                for (int j = i + 1; j < rows.size(); j++) {
                    if (rows.get(j).compareToIgnoreCase(rows.get(i)) < 0) {
                        Collections.swap(rows, i, j);
                        Collections.swap(rowKeys, i, j);
                    }
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                final String key = rowKeys.get(i);
                final Panel.Row r = p.addRow(rows.get(i), "hidden", null);
                r.cell.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        Prefs.setHidden(c, key, false);
                        r.setValue("shown");
                        r.cell.setEnabled(false);
                        host.rebuildGrid();
                    }
                });
            }
            p.addRow("Show all hidden tiles", "", new Runnable() {
                public void run() {
                    Prefs.clearHidden(c);
                    host.rebuildGrid();
                    host.closePanel();
                }
            });
        }
        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- labels

    private static String onOff(boolean b) { return b ? "On" : "Off"; }

    private static String sortLabel(Context c) {
        return Prefs.sort(c) == Prefs.SORT_ALPHA ? "A – Z" : "Last used";
    }

    private static String clockLabel(Context c) {
        switch (Prefs.clockMode(c)) {
            case Prefs.CLOCK_12H: return "12-hour";
            case Prefs.CLOCK_OFF: return "Off";
            default: return "24-hour";
        }
    }
}
