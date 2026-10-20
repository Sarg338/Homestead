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
import android.view.ViewTreeObserver;
import android.widget.TextView;

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
        final TextView message = p.addNote("");
        message.setVisibility(View.GONE);

        final boolean[] running = { false };
        // While the system installer is open. A successful install replaces the app and ends
        // this process, so getting the window back afterwards means nothing was installed.
        final boolean[] inInstaller = { false };
        final boolean[] leftWindow = { false };
        p.root.getViewTreeObserver().addOnWindowFocusChangeListener(
                new ViewTreeObserver.OnWindowFocusChangeListener() {
            public void onWindowFocusChanged(boolean hasFocus) {
                if (!inInstaller[0]) return;
                if (!hasFocus) { leftWindow[0] = true; return; }
                if (!leftWindow[0]) return;
                inInstaller[0] = false;
                running[0] = false;
                status.setValue("not installed");
                status.setValueColor(AMBER);
                show(message, "The installer closed without installing the update. Select "
                        + "Install now to try again.");
            }
        });

        final Panel.Row install = p.addRow("Install now", "", new Runnable() {
            public void run() {
                if (running[0]) return;
                running[0] = true;
                message.setVisibility(View.GONE);
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
                    public void onInstallerOpened() {
                        inInstaller[0] = true;
                        leftWindow[0] = false;
                        status.setValue("in the installer");
                        status.setValueColor(AMBER);
                    }
                    public void onNotAllowed() {
                        running[0] = false;
                        status.setValue("needs permission");
                        status.setValueColor(AMBER);
                        show(message, "Fire OS has to allow Homestead to install apps first. "
                                + "In the TV's own Settings, open Device & Software (My Fire TV "
                                + "on some models), then Developer options, then Install "
                                + "unknown apps, and switch Homestead on. Come back here and "
                                + "select Install now again. With the on-device tunnel set up, "
                                + "updates install without this.");
                    }
                    public void onDone(boolean ok, String text) {
                        running[0] = false;
                        inInstaller[0] = false;
                        status.setValue(ok ? "done" : "failed");
                        status.setValueColor(ok ? OK : BAD);
                        show(message, text);
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

    private static void show(TextView note, String text) {
        note.setText(text);
        note.setVisibility(View.VISIBLE);
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
