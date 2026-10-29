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

        boolean ready = SetupPanel.homeWorks(c);
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
        p.addNote(SetupPanel.turnOffHelp(c));

        p.addRow("TV Health", "check", new Runnable() {
            public void run() { host.showPanel(HealthPanel.build(c, host)); }
        }).opens().setHelp("This TV's speed and privacy settings and Amazon's advertising "
                + "packages, with a fix for each one it flags.");

        p.addRow("Debloat", Prefs.disabledByUs(c).size() + " off", new Runnable() {
            public void run() { host.showPanel(DebloatPanel.build(c, host)); }
        }).opens().setHelp("Switch Amazon's packages off, and back on again. Nothing is "
                + "ever uninstalled.");

        p.addRow("Fire TV settings", "", new Runnable() {
            public void run() { host.showPanel(FireTvSettingsPanel.build(c, host)); }
        }).opens().setHelp("Amazon's settings screens that open from here, such as network, "
                + "Bluetooth and applications, its full settings menu, and settings shortcuts "
                + "for the home screen.");

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

    // ---------------------------------------------------------------- amazon launcher off

    /**
     * The actions Amazon's launcher registers that nothing else on the TV answers, found by
     * resolving them with the launcher switched off (TCL 40V5D, Fire OS 14). A list to keep
     * up to date, not a promise about every TV.
     */
    static View buildLauncherOff(final Context c, final Host host) {
        final Panel p = new Panel(c, "Amazon's launcher is off", null, Panel.SHEET);

        p.addHeader("Only Amazon's launcher had these");
        p.addRow("Inputs screen", "use the input tiles", null)
                .setHelp("com.amazon.tv.action.NAVIGATE_TO_INPUTS. Each input is a tile on the "
                        + "home screen and opens directly.");
        p.addRow("Recents", "use the home screen", null)
                .setHelp("com.amazon.tv.action.NAVIGATE_TO_RECENTS. Apps are ordered by last "
                        + "use on the home screen.");
        p.addRow("All apps", "use the home screen", null)
                .setHelp("android.intent.action.ALL_APPS. The home screen lists every app.");
        p.addRow("Games", "nothing", null)
                .setHelp("com.amazon.tv.action.NAVIGATE_TO_GAMES. Games are ordinary tiles.");
        p.addRow("Network details", "Network in Fire TV settings", null)
                .setHelp("amazon.net.UNIFIED_NETWORK. The Network screen opens from Settings > "
                        + "System > Fire TV settings.");
        p.addRow("Language, Accessibility", "All settings", null)
                .setHelp("Amazon's settings app opens these only for Amazon's own launcher. "
                        + "\"All Amazon settings\" in Fire TV settings switches the launcher on "
                        + "for a moment to reach them.");
        p.addRow("Remote's Settings button", "account, apps, picture, sound", null)
                .setHelp("It opens Amazon's quick settings. Language and Accessibility are not "
                        + "in it.");

        p.addHeader("Still works");
        p.addNote("Amazon's Settings app (com.amazon.tv.settings.v2) is a separate package and "
                + "keeps working. Settings > System > Fire TV settings opens each of its "
                + "screens directly.");
        p.addNote("To switch Amazon's launcher back on: Debloat, \"Amazon's launcher\", or "
                + "adb shell pm enable --user 0 com.amazon.tv.launcher");

        p.addRow("Back", "", new Runnable() {
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

        final Panel.Row greet = p.addRow("Greeting", onOff(Prefs.greeting(c)), null).asSwitch();
        greet.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.greeting(c);
                Prefs.setGreeting(c, n);
                greet.setValue(onOff(n));
                host.rebuildGrid();
            }
        });
        greet.setHelp("\"Good morning\", \"Good afternoon\" or \"Good evening\" at the top left. "
                + "Not shown while the shortcuts bar is on.");
        if (Prefs.shortcutsPlace(c) == Prefs.SHORTCUTS_BAR && !Prefs.simpleMode(c)) {
            // The bar takes the corner, so there is nothing for this switch to turn on.
            greet.setValue("not with the shortcuts bar");
            greet.setValueColor(0xFF7F8FA0);
            greet.cell.setEnabled(false);
        }

        final Panel.Row date = p.addRow("Date", dateLabel(c), null);
        date.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = Prefs.dateFormat(c) + 1;
                if (n > Prefs.DATE_SHORT) n = Prefs.DATE_OFF;
                Prefs.setDateFormat(c, n);
                date.setValue(dateLabel(c));
                host.rebuildGrid();
            }
        });
        date.setHelp("Under the greeting, or next to the clock when the shortcuts bar is on. "
                + "Both formats follow the TV's language.");

        final Panel.Row wide = p.addRow("Non-TV apps as wide tiles", onOff(Prefs.nonTvWide(c)), null).asSwitch();
        wide.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.nonTvWide(c);
                Prefs.setNonTvWide(c, n);
                wide.setValue(onOff(n));
                AppModel.reload(c);
                host.rebuildGrid();
            }
        });
        wide.setHelp("Like Fire TV's own home: each app's icon on a banner-sized tile, in a grid "
                + "like the TV apps.");

        final Panel.Row fire = p.addRow("Fire TV system icons", onOff(Prefs.fireOsIcons(c)), null).asSwitch();
        fire.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.fireOsIcons(c);
                Prefs.setFireOsIcons(c, n);
                fire.setValue(onOff(n));
                AppModel.reload(c);
                host.rebuildGrid();
            }
        });
        fire.setHelp("Amazon Home, Settings and TV Health use the icons of Fire OS's own home and "
                + "Settings, read from this TV. Where one is missing, the usual icon stays.");

        p.addHeader("Sections");

        // Only offered on a TV that has the Live TV tile.
        if (AppModel.hasLiveTv()) {
            final Panel.Row tvFirst = p.addRow("Live TV first", onOff(Prefs.liveTvFirst(c)), null).asSwitch();
            tvFirst.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean n = !Prefs.liveTvFirst(c);
                    Prefs.setLiveTvFirst(c, n);
                    tvFirst.setValue(onOff(n));
                    host.rebuildGrid();
                }
            });
            tvFirst.setHelp("A large Live TV tile on top, then TV apps, then inputs; Home always "
                    + "lands on Live TV.");
        }

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

        // One row per look, the one in use marked. Selecting one applies it at once, and the
        // home screen beside the page shows it.
        p.addSectionHelp("Select one to use it. The home screen beside this page changes "
                + "straight away.");
        final List<Panel.Row> rows = new ArrayList<Panel.Row>();
        final List<String> ids = new ArrayList<String>();
        final Runnable mark = new Runnable() {
            public void run() {
                String now = Backgrounds.currentId(c);
                boolean noFile = Backgrounds.photoFile(c) == null;
                for (int i = 0; i < rows.size(); i++) {
                    boolean on = ids.get(i).equals(now);
                    String v = on ? "in use" : "";
                    if (noFile && ids.get(i).equals(Backgrounds.PHOTO_ID)) {
                        v = on ? "in use, no file yet" : "no file yet";
                    }
                    rows.get(i).setValue(v);
                    rows.get(i).setValueColor(on ? IN_USE : 0xFF8FA3B8);
                }
            }
        };
        for (int i = 0; i <= Backgrounds.PRESETS.length; i++) {
            boolean photo = i == Backgrounds.PRESETS.length;
            final Backgrounds.Preset look = photo ? Backgrounds.PHOTO_LOOK : Backgrounds.PRESETS[i];
            final Panel.Row r = p.addRow(look.name, "", null);
            android.graphics.Bitmap shot = photo ? Backgrounds.lastPhoto() : null;
            r.setSwatch(shot != null
                    ? new android.graphics.drawable.BitmapDrawable(c.getResources(), shot)
                    : swatch(c, look));
            r.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Backgrounds.choose(c, look.id);
                    mark.run();
                    host.rebuildGrid();
                    changed.run();
                }
            });
            rows.add(r);
            ids.add(look.id);
        }
        rows.get(ids.indexOf("black")).setHelp("Black is for OLED screens and night viewing.");
        rows.get(rows.size() - 1).setHelp("Choose a picture saved on the TV below (needs the "
                + "on-device tunnel), or send a JPEG or PNG from a computer:\n\n"
                + "adb push background.jpg /sdcard/Download/\n"
                + "adb shell \"content write --uri content://" + BackgroundProvider.AUTHORITY
                + "/photo < /sdcard/Download/background.jpg\"");
        mark.run();

        p.addHeader("Photo");
        final Panel.Row dim = p.addRow("Photo dimming", Backgrounds.dimLabel(c), null);
        dim.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setPhotoDim(c, (Prefs.photoDim(c) + 1) % Backgrounds.DIM_NAMES.length);
                dim.setValue(Backgrounds.dimLabel(c));
                host.rebuildGrid();
            }
        });
        dim.setHelp("Dimming darkens the photo so the labels stay readable.");

        p.addRow("Choose a photo on this TV", "", new Runnable() {
            public void run() { host.showPanel(PhotoPanel.build(c, host)); }
        }).opens().setHelp("Lists the pictures in the TV's Download folder. Needs the "
                + "on-device tunnel.");

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });

        // Start on the look in use. showPanel focuses the first row once the page is up,
        // so this goes one step after that.
        final View start = rows.get(Math.max(0, ids.indexOf(Backgrounds.currentId(c)))).cell;
        p.root.post(new Runnable() {
            public void run() {
                p.root.post(new Runnable() { public void run() { start.requestFocus(); } });
            }
        });
        return p.root;
    }

    private static final int IN_USE = 0xFFA7D28F;

    /** A preset's colours as a small rounded sample, edged so dark ones show on the page. */
    private static android.graphics.drawable.Drawable swatch(Context c, Backgrounds.Preset look) {
        android.graphics.drawable.GradientDrawable d = Backgrounds.drawable(look);
        d.setCornerRadius(AppModel.dp(c, 5));
        d.setStroke(AppModel.dp(c, 1), 0x59FFFFFF);
        return d;
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
                // Live TV leaves the model when its app is switched off; still name it.
                if (label.equals(AppModel.LIVETV_KEY)) label = c.getString(R.string.home_livetv);
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

    private static String dateLabel(Context c) {
        switch (Prefs.dateFormat(c)) {
            case Prefs.DATE_LONG: return "Weekday and month";
            case Prefs.DATE_SHORT: return "Numbers";
            default: return "Off";
        }
    }
}
