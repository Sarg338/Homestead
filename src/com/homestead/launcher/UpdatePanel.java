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

/**
 * The "an update is available" panel. Shows the new version and its notes, and offers to
 * install it, defer it, or skip that version for good. Install work is handed to
 * UpdateInstaller; this panel only reflects its progress in a status row.
 */
public final class UpdatePanel {

    private static final int OK = 0xFFA7D28F;
    private static final int AMBER = 0xFFE8C468;
    private static final int BAD = 0xFFE89A8A;
    private static final int MUTE = 0xFF8FA3B8;

    private UpdatePanel() { }

    public static View build(final Context c, final SettingsPanel.Host host,
                             final UpdateChecker.Update u) {
        String installed = UpdateChecker.installedVersionName(c);
        final Panel p = new Panel(c, "Update available",
                "Homestead " + installed + "  →  " + u.versionName);

        p.addHeader("What's new");
        String notes = u.notes != null ? u.notes.trim() : "";
        if (notes.length() == 0) notes = "See the release page for details.";
        if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
        p.addNote(notes);

        p.addHeader("Install");
        final Panel.Row status = p.addRow("Status", "ready", null);
        status.cell.setFocusable(false);
        status.setValueColor(MUTE);

        final boolean[] running = { false };

        final Panel.Row install = p.addRow("Install now", "", new Runnable() {
            public void run() {
                if (running[0]) return;
                running[0] = true;
                status.setValue("starting…");
                status.setValueColor(AMBER);
                UpdateInstaller.downloadAndInstall(c, u, new UpdateInstaller.Progress() {
                    public void onProgress(int pct) {
                        status.setValue(pct < 0 ? "downloading…" : "downloading " + pct + "%");
                        status.setValueColor(AMBER);
                    }
                    public void onStatus(String s) {
                        status.setValue(s + "…");
                        status.setValueColor(AMBER);
                    }
                    public void onDone(boolean ok, String message) {
                        running[0] = false;
                        status.setValue(ok ? "done" : "failed");
                        status.setValueColor(ok ? OK : BAD);
                        p.addNote(message);
                    }
                });
            }
        });

        p.addRow("Later", "", new Runnable() {
            public void run() { host.closePanel(); }
        });

        p.addRow("Skip this version", "", new Runnable() {
            public void run() {
                Prefs.setSkippedVersion(c, u.versionName);
                host.closePanel();
            }
        });

        p.addNote("Updates install through the on-device tunnel with no prompts when it is "
                + "set up. Without it, the system installer opens to confirm. Homestead only "
                + "installs an update signed with the same key as the copy you have now.");

        final View first = install.cell;
        p.root.post(new Runnable() { public void run() { first.requestFocus(); } });
        return p.root;
    }

    /**
     * The manual "Check for updates" action from Settings. Runs a forced check, then either
     * opens the update panel or updates the row's value in place.
     */
    static void checkNow(final Context c, final SettingsPanel.Host host, final Panel.Row row) {
        row.setValue("checking…");
        row.setValueColor(AMBER);
        UpdateChecker.check(c, true, new UpdateChecker.Callback() {
            public void onResult(UpdateChecker.Update u, String error) {
                if (u != null) {
                    row.setValue("v" + u.versionName + " available");
                    row.setValueColor(AMBER);
                    host.showPanel(build(c, host, u));
                } else if (error != null) {
                    row.setValue("unavailable");
                    row.setValueColor(MUTE);
                } else {
                    row.setValue("up to date");
                    row.setValueColor(OK);
                }
            }
        });
    }
}
