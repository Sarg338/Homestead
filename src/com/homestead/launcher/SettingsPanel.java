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
        /** Scroll the grid to a row (Prefs.ROW_*), so a change to it can be seen. */
        void showRow(String row);
        void showPanel(View v);
        void closePanel();
    }

    private SettingsPanel() { }

    /** Set just before the hub is redrawn for a new language, to land back on that row. */
    private static boolean focusLanguage;

    /**
     * The hub: a sheet over the home screen with the two pages of home-screen options, then
     * Simple mode and the system pages. Changes show on the grid behind it straight away.
     */
    public static View build(final Context c, final Host host) {
        final Panel p = new Panel(c, c.getString(R.string.set_title),
                "Homestead " + UpdateChecker.installedVersionName(c), Panel.SHEET);

        final Panel.Row home = p.addRow(c.getString(R.string.set_home), homeSummary(c), null).opens();
        home.setValueColor(0xFF8FA3B8);
        home.setHelp(c.getString(R.string.set_home_help));
        final Panel.Row look = p.addRow(c.getString(R.string.set_bg), Backgrounds.label(c), null).opens();
        look.setValueColor(0xFF8FA3B8);
        look.setHelp(c.getString(R.string.set_bg_help));
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

        final Panel.Row lang = p.addRow(c.getString(R.string.set_lang), Lang.label(c), null);
        lang.setValueColor(0xFF8FA3B8);
        lang.setHelp(c.getString(R.string.set_lang_help));
        lang.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setLanguage(c, Lang.next(c));
                HomeRedirectService.languageChanged(c);
                // This page was drawn in the old language; draw it again in the new one, back
                // on this row.
                host.closePanel();
                focusLanguage = true;
                host.showPanel(build(c, host));
            }
        });
        if (focusLanguage) {
            focusLanguage = false;
            // showPanel focuses the first row once the page is up, so this goes one step after.
            p.root.post(new Runnable() {
                public void run() {
                    p.root.post(new Runnable() { public void run() { lang.cell.requestFocus(); } });
                }
            });
        }

        final Panel.Row simple = p.addRow(c.getString(R.string.set_simple), onOff(Prefs.simpleMode(c)), null).asSwitch();
        simple.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.simpleMode(c);
                Prefs.setSimpleMode(c, n);
                simple.setValue(onOff(n));
                host.rebuildGrid();
            }
        });
        p.addNote(c.getString(R.string.set_simple_note));

        p.addHeader(c.getString(R.string.set_system));

        boolean ready = SetupPanel.homeWorks(c);
        final Panel.Row setup = p.addRow(c.getString(R.string.set_setup),
                c.getString(ready ? R.string.set_setup_done
                        : Prefs.homeOffByUser(c) ? R.string.set_setup_off : R.string.set_setup_todo),
                null).opens();
        setup.setValueColor(ready ? 0xFFA7D28F : 0xFFE8C468);
        setup.setHelp(c.getString(R.string.set_setup_help));
        setup.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(SetupPanel.build(c, host)); }
        });

        SetupPanel.addTurnOffRow(c, p, new Runnable() {
            public void run() {
                setup.setValue(c.getString(R.string.set_setup_off));
                setup.setValueColor(0xFFE8C468);
            }
        });
        p.addNote(SetupPanel.turnOffHelp(c));

        p.addRow(c.getString(R.string.set_health), c.getString(R.string.set_health_value), new Runnable() {
            public void run() { host.showPanel(HealthPanel.build(c, host)); }
        }).opens().setHelp(c.getString(R.string.set_health_help));

        p.addRow(c.getString(R.string.set_debloat),
                c.getResources().getQuantityString(R.plurals.set_debloat_value,
                        Prefs.disabledByUs(c).size(), Prefs.disabledByUs(c).size()), new Runnable() {
            public void run() { host.showPanel(DebloatPanel.build(c, host)); }
        }).opens().setHelp(c.getString(R.string.set_debloat_help));

        p.addRow(c.getString(R.string.home_sec_settings), "", new Runnable() {
            public void run() { host.showPanel(FireTvSettingsPanel.build(c, host)); }
        }).opens().setHelp(c.getString(R.string.set_firetv_help));

        final Panel.Row updates = p.addRow(c.getString(R.string.set_updates),
                "v" + UpdateChecker.installedVersionName(c), null);
        updates.setValueColor(0xFF8FA3B8);
        updates.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { UpdatePanel.checkNow(c, host, updates); }
        });

        p.addRow(c.getString(R.string.set_close), "", new Runnable() {
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
        final Panel p = new Panel(c, c.getString(R.string.lo_title), null, Panel.SHEET);

        p.addHeader(c.getString(R.string.lo_only));
        p.addRow(c.getString(R.string.lo_inputs), c.getString(R.string.lo_inputs_v), null)
                .setHelp(c.getString(R.string.lo_inputs_help));
        p.addRow(c.getString(R.string.lo_recents), c.getString(R.string.lo_use_home), null)
                .setHelp(c.getString(R.string.lo_recents_help));
        p.addRow(c.getString(R.string.lo_allapps), c.getString(R.string.lo_use_home), null)
                .setHelp(c.getString(R.string.lo_allapps_help));
        p.addRow(c.getString(R.string.lo_games), c.getString(R.string.lo_nothing), null)
                .setHelp(c.getString(R.string.lo_games_help));
        p.addRow(c.getString(R.string.lo_network), c.getString(R.string.lo_network_v), null)
                .setHelp(c.getString(R.string.lo_network_help));
        p.addRow(c.getString(R.string.lo_lang), c.getString(R.string.lo_lang_v), null)
                .setHelp(c.getString(R.string.lo_lang_help));
        p.addRow(c.getString(R.string.lo_remote), c.getString(R.string.lo_remote_v), null)
                .setHelp(c.getString(R.string.lo_remote_help));

        p.addHeader(c.getString(R.string.lo_still));
        p.addNote(c.getString(R.string.lo_still_note));
        p.addNote(c.getString(R.string.lo_back_on));

        p.addRow(c.getString(R.string.set_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- home screen

    private static View buildHome(final Context c, final Host host, final Runnable changed) {
        final Panel p = new Panel(c, c.getString(R.string.set_home), null, Panel.SHEET);

        final Panel.Row hidden = p.addRow(c.getString(R.string.set_hidden),
                String.valueOf(Prefs.hiddenCount(c)), null).opens();
        hidden.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(buildHiddenList(c, host)); }
        });
        // The count changes on the page this row opens; repaint it on the way back.
        hidden.cell.setOnFocusChangeListener(chain(hidden.cell.getOnFocusChangeListener(),
                new Runnable() {
                    public void run() { hidden.setValue(String.valueOf(Prefs.hiddenCount(c))); }
                }));

        p.addNote(c.getString(R.string.set_hidden_note));

        final Panel.Row sort = p.addRow(c.getString(R.string.set_sort), sortLabel(c), null);
        sort.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setSort(c, Prefs.sort(c) == Prefs.SORT_LAST_USED
                        ? Prefs.SORT_ALPHA : Prefs.SORT_LAST_USED);
                sort.setValue(sortLabel(c));
                AppModel.resort(c);
                host.rebuildGrid();
            }
        });

        final Panel.Row cols = p.addRow(c.getString(R.string.set_cols), String.valueOf(Prefs.columns(c)), null);
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

        final Panel.Row clock = p.addRow(c.getString(R.string.set_clock), clockLabel(c), null);
        clock.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = Prefs.clockMode(c) + 1;
                if (n > Prefs.CLOCK_OFF) n = Prefs.CLOCK_24H;
                Prefs.setClockMode(c, n);
                clock.setValue(clockLabel(c));
                host.rebuildGrid();
            }
        });

        final Panel.Row greet = p.addRow(c.getString(R.string.opt_greeting), onOff(Prefs.greeting(c)), null).asSwitch();
        greet.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.greeting(c);
                Prefs.setGreeting(c, n);
                greet.setValue(onOff(n));
                host.rebuildGrid();
            }
        });
        greet.setHelp(c.getString(R.string.opt_greeting_help));
        if (Prefs.shortcutsPlace(c) == Prefs.SHORTCUTS_BAR && !Prefs.simpleMode(c)) {
            // The bar takes the corner, so there is nothing for this switch to turn on.
            greet.setValue(c.getString(R.string.opt_greeting_bar));
            greet.setValueColor(0xFF7F8FA0);
            greet.cell.setEnabled(false);
        }

        final Panel.Row date = p.addRow(c.getString(R.string.opt_date), dateLabel(c), null);
        date.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = Prefs.dateFormat(c) + 1;
                if (n > Prefs.DATE_SHORT) n = Prefs.DATE_OFF;
                Prefs.setDateFormat(c, n);
                date.setValue(dateLabel(c));
                host.rebuildGrid();
            }
        });
        date.setHelp(c.getString(R.string.opt_date_help));

        final Panel.Row wide = p.addRow(c.getString(R.string.opt_wide), onOff(Prefs.nonTvWide(c)), null).asSwitch();
        wide.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.nonTvWide(c);
                Prefs.setNonTvWide(c, n);
                wide.setValue(onOff(n));
                AppModel.reload(c);
                host.rebuildGrid();
            }
        });
        wide.setHelp(c.getString(R.string.opt_wide_help));

        final Panel.Row fire = p.addRow(c.getString(R.string.opt_fire), onOff(Prefs.fireOsIcons(c)), null).asSwitch();
        fire.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.fireOsIcons(c);
                Prefs.setFireOsIcons(c, n);
                fire.setValue(onOff(n));
                AppModel.reload(c);
                host.rebuildGrid();
            }
        });
        fire.setHelp(c.getString(R.string.opt_fire_help));

        p.addHeader(c.getString(R.string.set_rows));

        final Panel.Row order = p.addRow(c.getString(R.string.set_row_order), orderSummary(c), null).opens();
        order.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(buildRowOrder(c, host)); }
        });
        order.setHelp(c.getString(R.string.set_row_order_help));
        // The order changes on the page this row opens; repaint it on the way back.
        order.cell.setOnFocusChangeListener(chain(order.cell.getOnFocusChangeListener(),
                new Runnable() {
                    public void run() { order.setValue(orderSummary(c)); }
                }));

        // Only offered on a TV that has the Live TV tile.
        if (AppModel.hasLiveTv()) {
            final Panel.Row tvFirst = p.addRow(c.getString(R.string.set_tvfirst), onOff(Prefs.liveTvFirst(c)), null).asSwitch();
            tvFirst.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean n = !Prefs.liveTvFirst(c);
                    Prefs.setLiveTvFirst(c, n);
                    tvFirst.setValue(onOff(n));
                    host.rebuildGrid();
                }
            });
            tvFirst.setHelp(c.getString(R.string.set_tvfirst_help));
        }

        final Panel.Row secIn = p.addRow(c.getString(R.string.set_show_section,
                c.getString(R.string.home_sec_inputs)), onOff(Prefs.showInputs(c)), null).asSwitch();
        secIn.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.showInputs(c);
                Prefs.setShowInputs(c, n);
                secIn.setValue(onOff(n));
                host.rebuildGrid();
            }
        });

        final Panel.Row secNon = p.addRow(c.getString(R.string.set_show_section,
                c.getString(R.string.home_sec_nontv)), onOff(Prefs.showNonTv(c)), null).asSwitch();
        secNon.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.showNonTv(c);
                Prefs.setShowNonTv(c, n);
                secNon.setValue(onOff(n));
                host.rebuildGrid();
            }
        });

        p.addSectionHelp(c.getString(R.string.set_sections_help, c.getString(R.string.home_sec_inputs)));

        p.addHeader(c.getString(R.string.set_apps));
        p.addRow(c.getString(R.string.set_reload), "", new Runnable() {
            public void run() {
                AppModel.reload(c);
                host.rebuildGrid();
            }
        }).setHelp(c.getString(R.string.set_reload_help));

        p.addRow(c.getString(R.string.set_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- row order

    /**
     * The home screen's rows, top to bottom. OK picks a row up, Up and Down move it, and OK
     * or Back puts it down. The grid behind the sheet follows each move.
     */
    private static View buildRowOrder(final Context c, final Host host) {
        final Panel p = new Panel(c, c.getString(R.string.set_row_order), null, Panel.SHEET);
        final List<String> order = Prefs.rowOrder(c);
        final Panel.Row[] slots = new Panel.Row[order.size()];
        final int[] lifted = { -1 };

        // Shows the order on the rows, and which of them is picked up.
        final Runnable paint = new Runnable() {
            public void run() {
                for (int i = 0; i < slots.length; i++) {
                    String row = order.get(i);
                    boolean up = i == lifted[0];
                    slots[i].setLabel(rowName(c, row));
                    String off = rowOff(c, row);
                    slots[i].setValue(up ? c.getString(R.string.ro_moving) : off);
                    slots[i].setValueColor(up ? LIFT_VALUE : off.length() > 0 ? MUTED : OK);
                    slots[i].setLifted(up);
                    slots[i].setHelp(up ? c.getString(R.string.ro_help_up)
                            : c.getString(R.string.ro_help) + (off.length() > 0 ? " " + c.getString(R.string.ro_help_off) : ""));
                }
            }
        };
        final Runnable drop = new Runnable() {
            public void run() {
                lifted[0] = -1;
                paint.run();
            }
        };

        p.addHeader(c.getString(R.string.ro_top));
        for (int i = 0; i < slots.length; i++) {
            final int at = i;
            slots[i] = p.addRow(rowName(c, order.get(i)), "", null);
            slots[i].cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    lifted[0] = lifted[0] == at ? -1 : at;
                    paint.run();
                }
            });
            slots[i].cell.setOnKeyListener(new View.OnKeyListener() {
                public boolean onKey(View v, int code, android.view.KeyEvent e) {
                    if (lifted[0] != at) return false;
                    boolean up = code == android.view.KeyEvent.KEYCODE_DPAD_UP;
                    boolean down = code == android.view.KeyEvent.KEYCODE_DPAD_DOWN;
                    if (!up && !down) {
                        // Left and Right would leave the row with it still picked up.
                        return code == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
                    }
                    int to = at + (up ? -1 : 1);
                    if (e.getAction() != android.view.KeyEvent.ACTION_DOWN
                            || to < 0 || to >= slots.length) {
                        return true;
                    }
                    Collections.swap(order, at, to);
                    Prefs.setRowOrder(c, order);
                    lifted[0] = to;
                    paint.run();
                    slots[to].cell.requestFocus();
                    host.rebuildGrid();
                    host.showRow(order.get(to));
                    return true;
                }
            });
        }
        paint.run();

        p.addRow(c.getString(R.string.ro_reset), "", new Runnable() {
            public void run() {
                order.clear();
                order.addAll(Prefs.usualRowOrder());
                Prefs.setRowOrder(c, order);
                drop.run();
                host.rebuildGrid();
                host.showRow(order.get(0));
            }
        }).setHelp(c.getString(R.string.ro_reset_help));
        p.addRow(c.getString(R.string.set_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });

        // Back puts a picked-up row down first; the next Back closes the page.
        p.root.setTag(new HomeGrid.BackHandler() {
            public boolean onBack() {
                if (lifted[0] < 0) return false;
                drop.run();
                return true;
            }
        });
        return p.root;
    }

    private static final int OK = 0xFFA7D28F, MUTED = 0xFF7F8FA0, LIFT_VALUE = 0xFF7FA8D8;

    /** A row's name, as its header reads on the home screen. */
    private static String rowName(Context c, String row) {
        if (row.equals(Prefs.ROW_SHORTCUTS)) return c.getString(R.string.home_sec_settings);
        if (row.equals(Prefs.ROW_INPUTS)) return c.getString(R.string.home_sec_inputs);
        if (row.equals(Prefs.ROW_NONTV)) return c.getString(R.string.home_sec_nontv);
        return c.getString(R.string.home_sec_tv);
    }

    /** Why a row is not on the home screen, or "" when it is. */
    private static String rowOff(Context c, String row) {
        if (row.equals(Prefs.ROW_SHORTCUTS)) {
            int place = Prefs.shortcutsPlace(c);
            return place == Prefs.SHORTCUTS_BAR ? c.getString(R.string.ro_in_bar)
                    : place == Prefs.SHORTCUTS_OFF ? c.getString(R.string.ftv_place_off) : "";
        }
        if (row.equals(Prefs.ROW_INPUTS)) return Prefs.showInputs(c) ? "" : c.getString(R.string.ro_hidden);
        if (row.equals(Prefs.ROW_NONTV)) return Prefs.showNonTv(c) ? "" : c.getString(R.string.ro_hidden);
        return "";
    }

    /** "TV Applications first": the top row of those shown. */
    private static String orderSummary(Context c) {
        List<String> order = Prefs.rowOrder(c);
        for (int i = 0; i < order.size(); i++) {
            if (rowOff(c, order.get(i)).length() == 0) return c.getString(R.string.ro_first, shortName(c, order.get(i)));
        }
        return "";
    }

    private static String shortName(Context c, String row) {
        if (row.equals(Prefs.ROW_SHORTCUTS)) return c.getString(R.string.ro_s_shortcuts);
        if (row.equals(Prefs.ROW_INPUTS)) return c.getString(R.string.ro_s_inputs);
        if (row.equals(Prefs.ROW_NONTV)) return c.getString(R.string.ro_s_nontv);
        return c.getString(R.string.ro_s_tv);
    }

    private static String homeSummary(Context c) {
        return c.getString(R.string.set_columns_n, Prefs.columns(c));
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
        final Panel p = new Panel(c, c.getString(R.string.set_bg), null, Panel.SHEET);

        // One row per look, the one in use marked. Selecting one applies it at once, and the
        // home screen beside the page shows it.
        p.addSectionHelp(c.getString(R.string.bg_list_help));
        final List<Panel.Row> rows = new ArrayList<Panel.Row>();
        final List<String> ids = new ArrayList<String>();
        final Runnable mark = new Runnable() {
            public void run() {
                String now = Backgrounds.currentId(c);
                boolean noFile = Backgrounds.photoFile(c) == null;
                for (int i = 0; i < rows.size(); i++) {
                    boolean on = ids.get(i).equals(now);
                    String v = on ? c.getString(R.string.bg_in_use) : "";
                    if (noFile && ids.get(i).equals(Backgrounds.PHOTO_ID)) {
                        v = c.getString(on ? R.string.bg_in_use_no_file : R.string.bg_no_file);
                    }
                    rows.get(i).setValue(v);
                    rows.get(i).setValueColor(on ? IN_USE : 0xFF8FA3B8);
                }
            }
        };
        for (int i = 0; i <= Backgrounds.PRESETS.length; i++) {
            boolean photo = i == Backgrounds.PRESETS.length;
            final Backgrounds.Preset look = photo ? Backgrounds.PHOTO_LOOK : Backgrounds.PRESETS[i];
            final Panel.Row r = p.addRow(Backgrounds.name(c, look), "", null);
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
        rows.get(ids.indexOf("black")).setHelp(c.getString(R.string.bg_black_help));
        rows.get(rows.size() - 1).setHelp(c.getString(R.string.bg_photo_help) + "\n\n"
                + "adb push background.jpg /sdcard/Download/\n"
                + "adb shell \"content write --uri content://" + BackgroundProvider.AUTHORITY
                + "/photo < /sdcard/Download/background.jpg\"");
        mark.run();

        p.addHeader(c.getString(R.string.bg_photo));
        final Panel.Row dim = p.addRow(c.getString(R.string.set_bg_dim), Backgrounds.dimLabel(c), null);
        dim.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setPhotoDim(c, (Prefs.photoDim(c) + 1) % Backgrounds.DIM_NAMES.length);
                dim.setValue(Backgrounds.dimLabel(c));
                host.rebuildGrid();
            }
        });
        dim.setHelp(c.getString(R.string.set_bg_dim_help));

        p.addRow(c.getString(R.string.set_photo_pick), "", new Runnable() {
            public void run() { host.showPanel(PhotoPanel.build(c, host)); }
        }).opens().setHelp(c.getString(R.string.set_photo_pick_help));

        p.addRow(c.getString(R.string.set_back), "", new Runnable() {
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
        final Panel p = new Panel(c, c.getString(R.string.set_hidden),
                c.getString(R.string.set_hidden_sub), Panel.SHEET);

        Set<String> keys = Prefs.hidden(c);
        if (keys.isEmpty()) {
            p.addNote(c.getString(R.string.set_hidden_none));
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
                final Panel.Row r = p.addRow(rows.get(i), c.getString(R.string.set_hidden_value), null);
                r.cell.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        Prefs.setHidden(c, key, false);
                        r.setValue(c.getString(R.string.set_shown_value));
                        r.cell.setEnabled(false);
                        host.rebuildGrid();
                    }
                });
            }
            p.addRow(c.getString(R.string.set_show_all), "", new Runnable() {
                public void run() {
                    Prefs.clearHidden(c);
                    host.rebuildGrid();
                    host.closePanel();
                }
            });
        }
        p.addRow(c.getString(R.string.set_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- labels

    /** Read by Panel's switches, which draw a toggle instead of showing this text. */
    private static String onOff(boolean b) { return b ? "On" : "Off"; }

    private static String sortLabel(Context c) {
        return c.getString(Prefs.sort(c) == Prefs.SORT_ALPHA ? R.string.set_sort_az : R.string.set_sort_last);
    }

    private static String clockLabel(Context c) {
        switch (Prefs.clockMode(c)) {
            case Prefs.CLOCK_12H: return c.getString(R.string.set_clock_12);
            case Prefs.CLOCK_OFF: return c.getString(R.string.set_off);
            default: return c.getString(R.string.set_clock_24);
        }
    }

    private static String dateLabel(Context c) {
        switch (Prefs.dateFormat(c)) {
            case Prefs.DATE_LONG: return c.getString(R.string.opt_date_long);
            case Prefs.DATE_SHORT: return c.getString(R.string.opt_date_short);
            default: return c.getString(R.string.set_off);
        }
    }
}
