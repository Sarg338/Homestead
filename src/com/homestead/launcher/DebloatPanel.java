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
import java.util.List;
import java.util.Set;

/**
 * Turns Amazon's advertising, telemetry and unused services off from the remote.
 *
 * A sideloaded app cannot hold CHANGE_COMPONENT_ENABLED_STATE, so every action runs
 * `pm disable-user --user 0` or `pm enable` through the ADB link - the same shell a computer
 * gets, approved by the user on the TV. Nothing is uninstalled, and "Undo everything"
 * restores only what this app turned off.
 */
public final class DebloatPanel {

    private static final int OK = 0xFFA7D28F;
    private static final int WARN = 0xFFE8C468;
    private static final int BAD = 0xFFE89A8A;
    private static final int MUTED = 0xFF7F8FA0;

    private DebloatPanel() { }

    public static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, "Debloat",
                "Reversible. Nothing is ever uninstalled.");

        AdbBridge.init(c);

        final Panel.Row link = p.addRow("ADB link", "", null);
        link.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (AdbBridge.isConnected()) return;
                host.showPanel(SetupPanel.build(c, host));
            }
        });

        p.addNote("Each item is switched off with \"pm disable-user\", which leaves the app "
                + "and its data on the TV and is undone with \"pm enable\". Disabling does "
                + "not free storage; what you get back is memory and processor time.");

        final List<Row> rows = new ArrayList<Row>();
        int group = -1;
        for (int i = 0; i < Debloat.CATALOGUE.length; i++) {
            final Debloat.Entry e = Debloat.CATALOGUE[i];
            if (e.group != group) {
                group = e.group;
                p.addHeader(Debloat.groupName(group));
                if (group == Debloat.GROUP_VOICE) {
                    p.addNote("Switching these off removes Alexa: no voice search, no voice "
                            + "timers, and the microphone button on the remote stops doing "
                            + "anything. Everything else keeps working.");
                }
            }
            Row row = new Row(c, p, e);
            rows.add(row);
        }

        // ------------------------------------------------------------------ bulk actions
        p.addHeader("All at once");

        final Panel.Row bulkOff = p.addRow("Switch off everything marked safe", "", null);
        p.addNote("Skips the Alexa and Extras groups, which have consequences worth "
                + "choosing one at a time.");

        final Panel.Row undo = p.addRow("Undo everything this app switched off", "", null);
        final Panel.Row result = p.addRow("Last result", "", null);

        final Runnable refreshAll = new Runnable() {
            public void run() {
                for (int i = 0; i < rows.size(); i++) rows.get(i).refresh(c);
                boolean live = AdbBridge.canRun(c);
                link.setLabel(live ? "ADB link" : "ADB link - open Setup to connect");
                link.setValue(Prefs.adbEnabled(c) ? AdbBridge.describe(AdbBridge.state()) : "off");
                link.setValueColor(live ? OK : WARN);
                bulkOff.cell.setEnabled(live);
                int mine = Prefs.disabledByUs(c).size();
                undo.setValue(mine == 0 ? "nothing to undo" : mine + " to restore");
                undo.cell.setEnabled(live && mine > 0);
            }
        };

        bulkOff.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!AdbBridge.canRun(c)) return;
                List<String> cmds = new ArrayList<String>();
                final List<String> pkgs = new ArrayList<String>();
                for (int i = 0; i < Debloat.CATALOGUE.length; i++) {
                    Debloat.Entry e = Debloat.CATALOGUE[i];
                    if (e.caution) continue;
                    if (Debloat.isProtected(c, e.pkg)) continue;
                    if (Debloat.stateOf(c, e.pkg) != Debloat.ENABLED) continue;
                    cmds.add(AdbBridge.disableCommand(e.pkg));
                    pkgs.add(e.pkg);
                }
                if (cmds.isEmpty()) {
                    result.setValue("already done");
                    result.setValueColor(OK);
                    return;
                }
                result.setValue("working on " + cmds.size() + "...");
                AdbBridge.run(cmds.toArray(new String[cmds.size()]), new AdbBridge.Callback() {
                    public void onDone(boolean ok, String out) {
                        int done = 0;
                        for (int i = 0; i < pkgs.size(); i++) {
                            boolean off = Debloat.stateOf(c, pkgs.get(i)) == Debloat.DISABLED;
                            Prefs.noteDisabled(c, pkgs.get(i), off);
                            if (off) done++;
                        }
                        // Fire OS refuses some of these outright, so report a count.
                        result.setValue(done + " of " + pkgs.size() + " switched off");
                        result.setValueColor(done == pkgs.size() ? OK : WARN);
                        refreshAll.run();
                    }
                });
            }
        });

        undo.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!AdbBridge.canRun(c)) return;
                Set<String> mine = Prefs.disabledByUs(c);
                if (mine.isEmpty()) return;
                final String[] pkgs = mine.toArray(new String[mine.size()]);
                String[] cmds = new String[pkgs.length];
                for (int i = 0; i < pkgs.length; i++) cmds[i] = AdbBridge.enableCommand(pkgs[i]);
                result.setValue("restoring " + pkgs.length + "...");
                AdbBridge.run(cmds, new AdbBridge.Callback() {
                    public void onDone(boolean ok, String out) {
                        int back = 0;
                        for (int i = 0; i < pkgs.length; i++) {
                            if (Debloat.stateOf(c, pkgs[i]) != Debloat.DISABLED) {
                                Prefs.noteDisabled(c, pkgs[i], false);
                                back++;
                            }
                        }
                        result.setValue(back + " of " + pkgs.length + " restored");
                        result.setValueColor(back == pkgs.length ? OK : WARN);
                        refreshAll.run();
                    }
                });
            }
        });

        // ------------------------------------------------------------------ everything else
        p.addHeader("Beyond the list");
        p.addRow("Browse every system app", "", new Runnable() {
            public void run() { host.showPanel(buildBrowser(c, host)); }
        });
        p.addNote("The list above has been checked as safe to switch off. The browser "
                + "shows every other package but cannot tell you which are safe, and "
                + "anything the system is currently relying on is locked.");

        p.addRow("Commands for a computer", "", new Runnable() {
            public void run() { host.showPanel(CommandsPanel.build(c, host)); }
        });
        p.addNote("Fire OS 7 does not let this app change package states on its own - see "
                + "Setup for why. That page lists the exact commands, already filtered down "
                + "to what is still running on this TV.");

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });

        for (int i = 0; i < rows.size(); i++) rows.get(i).bind(c, refreshAll, result);
        refreshAll.run();

        final AdbBridge.Listener listener = new AdbBridge.Listener() {
            public void onAdbState(int state, String detail) { refreshAll.run(); }
        };
        p.root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { AdbBridge.addListener(listener); }
            public void onViewDetachedFromWindow(View v) { AdbBridge.removeListener(listener); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- one catalogue row

    private static final class Row {
        final Debloat.Entry entry;
        final Panel.Row row;
        final String locked;

        Row(Context c, Panel p, Debloat.Entry e) {
            this.entry = e;
            this.locked = Debloat.protectionReason(c, e.pkg);
            this.row = p.addRow(e.title + (e.caution ? "  (changes behaviour)" : ""), "", null);
            p.addNote(e.note);
        }

        void bind(final Context c, final Runnable after, final Panel.Row result) {
            if (locked != null) return;
            row.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!AdbBridge.canRun(c)) return;
                    final int was = Debloat.stateOf(c, entry.pkg);
                    if (was == Debloat.ABSENT) return;
                    final boolean turningOff = (was == Debloat.ENABLED);
                    row.setValue("working...");
                    String cmd = turningOff
                            ? AdbBridge.disableCommand(entry.pkg)
                            : AdbBridge.enableCommand(entry.pkg);
                    AdbBridge.run(cmd, new AdbBridge.Callback() {
                        public void onDone(boolean ok, String out) {
                            int now = Debloat.stateOf(c, entry.pkg);
                            Prefs.noteDisabled(c, entry.pkg, now == Debloat.DISABLED);
                            if (now == was) {
                                // Fire OS refuses disable-user on some Amazon packages.
                                result.setValue("Fire OS protects " + entry.title);
                                result.setValueColor(BAD);
                            } else {
                                result.setValue(entry.title + (now == Debloat.DISABLED
                                        ? " switched off" : " restored"));
                                result.setValueColor(OK);
                            }
                            after.run();
                        }
                    });
                }
            });
        }

        void refresh(Context c) {
            int st = Debloat.stateOf(c, entry.pkg);
            if (locked != null) {
                row.setValue(locked);
                row.setValueColor(MUTED);
                row.cell.setEnabled(false);
                return;
            }
            if (st == Debloat.ABSENT) {
                row.setValue("not present");
                row.setValueColor(MUTED);
                row.cell.setEnabled(false);
                return;
            }
            row.cell.setEnabled(AdbBridge.canRun(c));
            row.setValue(st == Debloat.ENABLED ? "on" : "off");
            row.setValueColor(st == Debloat.ENABLED ? WARN : OK);
        }
    }

    // ---------------------------------------------------------------- browse everything

    private static View buildBrowser(final Context c, final SettingsPanel.Host host) {
        final List<Debloat.Installed> all = Debloat.installed(c, true);
        final Panel p = new Panel(c, "Every system app",
                all.size() + " packages on this TV");

        p.addNote("Anything the system is currently using as a keyboard, a TV input, a home "
                + "screen, a device administrator or an accessibility service is locked, and "
                + "so is a short list of components the TV cannot start without. Everything "
                + "else is yours to switch off - reversibly.");

        final Panel.Row result = p.addRow("Last result", "", null);

        for (int i = 0; i < all.size(); i++) {
            final Debloat.Installed app = all.get(i);
            final String locked = Debloat.protectionReason(c, app.pkg);
            final Panel.Row row = p.addRow(app.label, "", null);
            if (locked != null) {
                row.setValue(locked);
                row.setValueColor(MUTED);
                row.cell.setEnabled(false);
                continue;
            }
            row.setValue(app.state == Debloat.ENABLED ? "on" : "off");
            row.setValueColor(app.state == Debloat.ENABLED ? WARN : OK);
            row.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!AdbBridge.canRun(c)) {
                        result.setValue("connect the ADB link first");
                        result.setValueColor(WARN);
                        return;
                    }
                    final int was = Debloat.stateOf(c, app.pkg);
                    if (was == Debloat.ABSENT) return;
                    row.setValue("working...");
                    AdbBridge.run(was == Debloat.ENABLED
                                    ? AdbBridge.disableCommand(app.pkg)
                                    : AdbBridge.enableCommand(app.pkg),
                            new AdbBridge.Callback() {
                                public void onDone(boolean ok, String out) {
                                    int now = Debloat.stateOf(c, app.pkg);
                                    Prefs.noteDisabled(c, app.pkg, now == Debloat.DISABLED);
                                    row.setValue(now == Debloat.ENABLED ? "on" : "off");
                                    row.setValueColor(now == Debloat.ENABLED ? WARN : OK);
                                    if (now == was) {
                                        result.setValue("Fire OS protects " + app.pkg);
                                        result.setValueColor(BAD);
                                    } else {
                                        result.setValue(app.label + (now == Debloat.DISABLED
                                                ? " switched off" : " restored"));
                                        result.setValueColor(OK);
                                    }
                                }
                            });
                }
            });
        }

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }
}
