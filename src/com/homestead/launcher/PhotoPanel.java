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
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Picks the background photo from the TV's Download folder, without a computer. Apps such
 * as Downloader and the Silk browser save there. Homestead holds no storage permission, so
 * it lists the folder and sends the chosen file to BackgroundProvider through the
 * on-device tunnel, the same way the computer commands do.
 */
public final class PhotoPanel {

    private static final int OK = 0xFFA7D28F;
    private static final int WARN = 0xFFE8C468;
    private static final int BAD = 0xFFE89A8A;

    private static final String DIR = "/sdcard/Download";
    /**
     * Names that are safe to put inside single quotes in a shell command, and that look
     * like a picture. Anything else is left out rather than escaped.
     */
    private static final Pattern SAFE =
            Pattern.compile("[A-Za-z0-9 ._()+-]{1,120}\\.(?i:jpe?g|png)");
    /** How long to wait for BackgroundProvider to report on a transfer. */
    private static final long RESULT_WAIT_MS = 8000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PhotoPanel() { }

    public static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, "Choose a photo on this TV",
                "Pictures in the TV's Download folder");
        p.addNote("Save a picture on the TV first: the Downloader app and the Silk browser "
                + "both put files in Download. Homestead looks there through its on-device "
                + "tunnel, so it needs no storage permission.");
        final Panel.Row status = p.addStatus("Status");
        status.cell.setFocusable(false);

        final Runnable close = new Runnable() { public void run() { host.closePanel(); } };

        if (!AdbBridge.canRun(c)) {
            status.setValue("needs the tunnel");
            status.setValueColor(WARN);
            p.addNote("This uses the on-device tunnel, which is not set up on this TV. Set "
                    + "it up in Setup, or send a photo from a computer (Settings, "
                    + "Background has the commands).");
            p.addRow("Back", "", close);
            return p.root;
        }

        status.setValue("looking...");
        status.setValueColor(WARN);
        // Newest first, so a picture just downloaded is at the top. Through "| cat" because
        // the shell runs in a terminal, where ls escapes spaces as "\ ".
        AdbBridge.run("ls -1t " + DIR + " | cat", new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) {
                if (!AdbBridge.ran(out)) {
                    status.setValue("could not connect");
                    status.setValueColor(BAD);
                    p.addRow("Back", "", close).cell.requestFocus();
                    return;
                }
                List<String> names = new ArrayList<String>();
                int skipped = 0;
                String[] lines = out.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String n = lines[i].trim();
                    if (n.length() == 0 || n.startsWith("$ ") || n.startsWith("(exit")) continue;
                    if (SAFE.matcher(n).matches()) names.add(n);
                    else if (n.toLowerCase(java.util.Locale.US).matches(".*\\.(jpe?g|png)")) skipped++;
                }
                if (names.isEmpty()) {
                    status.setValue("no pictures found");
                    p.addNote("No JPEG or PNG files in Download yet.");
                } else {
                    status.setValue(names.size() + (names.size() == 1 ? " picture" : " pictures"));
                    status.setValueColor(OK);
                }
                if (skipped > 0) {
                    p.addNote(skipped == 1
                            ? "1 picture is not listed because of unusual characters in its "
                                    + "name. Rename it using letters, numbers, spaces, dots or "
                                    + "dashes."
                            : skipped + " pictures are not listed because of unusual characters "
                                    + "in their names. Rename them using letters, numbers, "
                                    + "spaces, dots or dashes.");
                }
                Panel.Row first = null;
                for (int i = 0; i < names.size(); i++) {
                    final String name = names.get(i);
                    final Panel.Row[] self = new Panel.Row[1];
                    self[0] = p.addRow(name, "", new Runnable() {
                        public void run() { pick(c, host, status, self[0], name); }
                    });
                    if (first == null) first = self[0];
                }
                Panel.Row back = p.addRow("Back", "", close);
                (first != null ? first : back).cell.requestFocus();
            }
        });
        return p.root;
    }

    /** Send one file to BackgroundProvider through the tunnel, then use it if it arrived. */
    private static void pick(final Context c, final SettingsPanel.Host host,
                             final Panel.Row status, final Panel.Row row, final String name) {
        if (!SAFE.matcher(name).matches()) return;
        status.setValue("sending...");
        status.setValueColor(WARN);
        row.setValue("");
        final long since = System.currentTimeMillis();
        String cmd = "content write --uri content://" + BackgroundProvider.AUTHORITY
                + "/photo < '" + DIR + "/" + name + "'";
        AdbBridge.run(cmd, new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) {
                if (!AdbBridge.ran(out)) {
                    status.setValue("could not connect");
                    status.setValueColor(BAD);
                    return;
                }
                awaitResult(c, host, status, row, since, System.currentTimeMillis());
            }
        });
    }

    /** The provider finishes on its own thread after the command returns; wait for it. */
    private static void awaitResult(final Context c, final SettingsPanel.Host host,
                                    final Panel.Row status, final Panel.Row row,
                                    final long since, final long started) {
        if (BackgroundProvider.lastResultAt >= since) {
            if (BackgroundProvider.lastOk) {
                Prefs.setBackground(c, Backgrounds.PHOTO);
                host.rebuildGrid();
                status.setValue("set as the background");
                status.setValueColor(OK);
                row.setValue("in use");
                row.setValueColor(OK);
            } else {
                status.setValue("not a picture Homestead can use");
                status.setValueColor(BAD);
                row.setValue("not used");
                row.setValueColor(BAD);
            }
            return;
        }
        if (System.currentTimeMillis() - started > RESULT_WAIT_MS) {
            status.setValue("no answer - try again");
            status.setValueColor(BAD);
            return;
        }
        MAIN.postDelayed(new Runnable() {
            public void run() { awaitResult(c, host, status, row, since, started); }
        }, 200L);
    }
}
