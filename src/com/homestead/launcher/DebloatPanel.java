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
        final Panel p = new Panel(c, c.getString(R.string.set_debloat),
                c.getString(R.string.db_sub));

        AdbBridge.init(c);

        p.addHeader(c.getString(R.string.db_this_tv));

        final Panel.Row link = p.addRow(c.getString(R.string.setup_link), "", null);
        link.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (AdbBridge.isConnected()) return;
                host.showPanel(SetupPanel.build(c, host));
            }
        });

        p.addSectionHelp(c.getString(R.string.db_help));

        // The list was checked on one model; say how much of it applies to this TV.
        int absent = 0;
        for (int i = 0; i < Debloat.CATALOGUE.length; i++) {
            if (Debloat.stateOf(c, Debloat.CATALOGUE[i].pkg) == Debloat.ABSENT) absent++;
        }
        p.addRow(c.getString(R.string.db_on_tv), c.getString(R.string.db_n_listed,
                Debloat.CATALOGUE.length - absent, Debloat.CATALOGUE.length), null);
        if (absent > 0) {
            p.addNote(c.getString(R.string.db_absent_note, absent));
        }

        final List<Row> rows = new ArrayList<Row>();

        // ------------------------------------------------------------------ bulk actions
        p.addHeader(c.getString(R.string.db_all_at_once));

        final Panel.Row bulkOff = p.addRow(c.getString(R.string.db_bulk_safe), "", null);
        p.addNote(c.getString(R.string.db_bulk_safe_note));

        final Panel.Row bulkCaution = p.addRow(c.getString(R.string.db_bulk_caution), "", null);
        p.addNote(c.getString(R.string.db_bulk_caution_note));

        final Panel.Row undo = p.addRow(c.getString(R.string.db_undo), "", null);
        p.addNote(c.getString(R.string.db_undo_note));
        final Panel.Row result = p.addStatus(c.getString(R.string.setup_last));

        final Runnable refreshAll = new Runnable() {
            public void run() {
                for (int i = 0; i < rows.size(); i++) rows.get(i).refresh(c);
                boolean live = AdbBridge.canRun(c);
                link.setLabel(c.getString(live ? R.string.setup_link : R.string.db_link_open_setup));
                link.setValue(AdbBridge.statusLabel(c));
                link.setValueColor(AdbBridge.isReady(c) ? OK : WARN);
                bulkOff.cell.setEnabled(live);
                bulkCaution.cell.setEnabled(live);
                int mine = Prefs.disabledByUs(c).size();
                undo.setValue(mine == 0 ? c.getString(R.string.db_nothing_undo) : c.getString(R.string.db_n_restore, mine));
                undo.cell.setEnabled(live && mine > 0);
            }
        };

        bulkOff.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runBulk(c, false, result, refreshAll); }
        });
        bulkCaution.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runBulk(c, true, result, refreshAll); }
        });

        undo.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!AdbBridge.canRun(c)) return;
                Set<String> mine = Prefs.disabledByUs(c);
                if (mine.isEmpty()) return;
                final String[] pkgs = mine.toArray(new String[mine.size()]);
                String[] cmds = new String[pkgs.length];
                for (int i = 0; i < pkgs.length; i++) cmds[i] = AdbBridge.enableCommand(pkgs[i]);
                result.setValue(c.getString(R.string.db_restoring, pkgs.length));
                AdbBridge.run(cmds, new AdbBridge.Callback() {
                    public void onDone(boolean ok, String out) {
                        int back = 0;
                        for (int i = 0; i < pkgs.length; i++) {
                            if (Debloat.stateOf(c, pkgs[i]) != Debloat.DISABLED) {
                                Prefs.noteDisabled(c, pkgs[i], false);
                                back++;
                            }
                        }
                        result.setValue(c.getString(R.string.db_n_restored, back, pkgs.length));
                        result.setValueColor(back == pkgs.length ? OK : WARN);
                        refreshAll.run();
                    }
                });
            }
        });

        // ------------------------------------------------------------------ the list
        int group = -1;
        for (int i = 0; i < Debloat.CATALOGUE.length; i++) {
            final Debloat.Entry e = Debloat.CATALOGUE[i];
            if (e.group != group) {
                group = e.group;
                p.addHeader(Debloat.groupName(c, group));
            }
            Row row = new Row(c, p, e);
            rows.add(row);
        }

        // ------------------------------------------------------------------ everything else
        p.addHeader(c.getString(R.string.db_beyond));
        p.addRow(c.getString(R.string.db_browse), "", new Runnable() {
            public void run() { host.showPanel(buildBrowser(c, host)); }
        }).opens();
        p.addNote(c.getString(R.string.db_browse_note));

        p.addRow(c.getString(R.string.setup_commands), "", new Runnable() {
            public void run() { host.showPanel(CommandsPanel.build(c, host)); }
        }).opens();
        p.addNote(c.getString(R.string.db_commands_note));

        p.addRow(c.getString(R.string.set_back), "", new Runnable() {
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
            this.row = p.addRow(c.getString(e.title), "", null).asSwitch();
            if (e.risky) row.setBadge(c.getString(R.string.db_risky));
            else if (e.caution) row.setBadge(c.getString(R.string.db_caution));
            String why = e.group == Debloat.GROUP_VOICE ? c.getString(R.string.db_alexa_why)
                    : e.risky ? c.getString(R.string.db_risky_why, e.pkg)
                    : e.caution ? c.getString(R.string.db_caution_why)
                    : null;
            row.setHelp(c.getString(e.note) + (why != null ? "\n\n" + why : "") + "\n\n" + e.pkg);
        }

        void bind(final Context c, final Runnable after, final Panel.Row result) {
            if (locked != null) return;
            // A risky entry is switched off only on a second press within five seconds.
            final boolean[] armed = { false };
            final Runnable disarm = new Runnable() {
                public void run() { armed[0] = false; refresh(c); }
            };
            row.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!AdbBridge.canRun(c)) return;
                    final int was = Debloat.stateOf(c, entry.pkg);
                    if (was == Debloat.ABSENT) return;
                    final boolean turningOff = (was == Debloat.ENABLED);
                    if (turningOff && entry.risky && !armed[0]) {
                        armed[0] = true;
                        row.setValue(c.getString(R.string.db_press_again));
                        row.setValueColor(BAD);
                        row.cell.postDelayed(disarm, 5000L);
                        return;
                    }
                    row.cell.removeCallbacks(disarm);
                    armed[0] = false;
                    row.setValue(c.getString(R.string.setup_log_working));
                    String cmd = turningOff
                            ? AdbBridge.disableCommand(entry.pkg)
                            : AdbBridge.enableCommand(entry.pkg);
                    AdbBridge.run(cmd, new AdbBridge.Callback() {
                        public void onDone(boolean ok, String out) {
                            int now = Debloat.stateOf(c, entry.pkg);
                            Prefs.noteDisabled(c, entry.pkg, now == Debloat.DISABLED);
                            boolean ran = AdbBridge.ran(out);
                            if (ran && turningOff) {
                                Prefs.noteRefused(c, entry.pkg, Debloat.refusedIn(out, entry.pkg));
                            }
                            if (!ran) {
                                result.setValue(c.getString(R.string.db_could_not_connect));
                                result.setValueColor(BAD);
                            } else if (now == was) {
                                // Fire OS refuses disable-user on some Amazon packages.
                                result.setValue(c.getString(R.string.db_protects, c.getString(entry.title)));
                                result.setValueColor(BAD);
                            } else {
                                result.setValue(c.getString(now == Debloat.DISABLED ? R.string.db_x_off : R.string.db_x_restored,
                                        c.getString(entry.title)));
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
                row.setValue(c.getString(R.string.db_not_present));
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

    private static void runBulk(final Context c, final boolean cautionGroup,
            final Panel.Row result, final Runnable refreshAll) {
        if (!AdbBridge.canRun(c)) return;
        int n = switchOffGroup(c, cautionGroup, null, new BulkResult() {
            public void onResult(boolean connected, int done, int total) {
                if (!connected) {
                    result.setValue(c.getString(R.string.db_could_not_connect));
                    result.setValueColor(BAD);
                } else {
                    // Fire OS refuses some of these outright, so report a count.
                    result.setValue(c.getResources().getQuantityString(R.plurals.db_n_off, done, done, total));
                    result.setValueColor(done == total ? OK : WARN);
                }
                refreshAll.run();
            }
        });
        if (n == 0) {
            result.setValue(c.getString(R.string.db_already));
            result.setValueColor(OK);
        } else {
            result.setValue(c.getString(R.string.db_working_on, n));
        }
    }

    public interface BulkResult {
        /** @param connected false when the batch never ran; done and total are then 0. */
        void onResult(boolean connected, int done, int total);
    }

    /**
     * Disable every catalogue entry in one group (caution or not) that is present, enabled
     * and not Fire-OS-protected, in a single ADB batch. Refused packages are recorded as
     * protected and do not stop the rest. Also used by Simple mode's setup step.
     *
     * @param skip packages to leave alone, or null
     * @return how many packages the batch covers; 0 means nothing to do and no callback.
     */
    static int switchOffGroup(final Context c, boolean cautionGroup, Set<String> skip,
            final BulkResult cb) {
        AdbBridge.init(c);
        List<String> cmds = new ArrayList<String>();
        final List<String> pkgs = new ArrayList<String>();
        for (int i = 0; i < Debloat.CATALOGUE.length; i++) {
            Debloat.Entry e = Debloat.CATALOGUE[i];
            if (e.caution != cautionGroup) continue;
            if (e.risky) continue;
            if (skip != null && skip.contains(e.pkg)) continue;
            if (Debloat.isProtected(c, e.pkg)) continue;
            if (Debloat.stateOf(c, e.pkg) != Debloat.ENABLED) continue;
            cmds.add(AdbBridge.disableCommand(e.pkg));
            pkgs.add(e.pkg);
        }
        if (cmds.isEmpty()) return 0;
        AdbBridge.run(cmds.toArray(new String[cmds.size()]), new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) {
                if (!AdbBridge.ran(out)) { cb.onResult(false, 0, 0); return; }
                int done = 0;
                for (int i = 0; i < pkgs.size(); i++) {
                    boolean off = Debloat.stateOf(c, pkgs.get(i)) == Debloat.DISABLED;
                    Prefs.noteDisabled(c, pkgs.get(i), off);
                    Prefs.noteRefused(c, pkgs.get(i), !off && Debloat.refusedIn(out, pkgs.get(i)));
                    if (off) done++;
                }
                cb.onResult(true, done, pkgs.size());
            }
        });
        return cmds.size();
    }

    private static View buildBrowser(final Context c, final SettingsPanel.Host host) {
        final List<Debloat.Installed> all = Debloat.installed(c, true);
        final Panel p = new Panel(c, c.getString(R.string.db_every_app),
                c.getString(R.string.db_n_packages, all.size()));

        p.addText(c.getString(R.string.db_browser_text));

        final Panel.Row result = p.addStatus(c.getString(R.string.setup_last));

        for (int i = 0; i < all.size(); i++) {
            final Debloat.Installed app = all.get(i);
            final String locked = Debloat.protectionReason(c, app.pkg);
            final Panel.Row row = p.addRow(app.label, "", null).asSwitch();
            row.setHelp(app.pkg);
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
                        result.setValue(c.getString(R.string.db_connect_first));
                        result.setValueColor(WARN);
                        return;
                    }
                    final int was = Debloat.stateOf(c, app.pkg);
                    if (was == Debloat.ABSENT) return;
                    row.setValue(c.getString(R.string.setup_log_working));
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
                                        result.setValue(c.getString(R.string.db_protects, app.pkg));
                                        result.setValueColor(BAD);
                                    } else {
                                        result.setValue(c.getString(now == Debloat.DISABLED ? R.string.db_x_off : R.string.db_x_restored,
                                                app.label));
                                        result.setValueColor(OK);
                                    }
                                }
                            });
                }
            });
        }

        p.addRow(c.getString(R.string.set_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }
}
