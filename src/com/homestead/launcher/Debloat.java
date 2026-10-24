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

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The catalogue of packages that are safe to switch off, and what must never be touched.
 *
 * Nothing is ever uninstalled: every change is `pm disable-user --user 0`, which keeps the
 * APK and data and is undone by `pm enable`. Disabling frees RAM and CPU, not storage.
 *
 * isProtected() asks the system what currently serves as input method, TV input, device
 * admin, home screen or accessibility service, on top of a hard-coded list, rather than
 * guessing by package-name prefix.
 */
final class Debloat {

    static final int ABSENT = -1;
    static final int DISABLED = 0;
    static final int ENABLED = 1;

    static final int GROUP_ADS = 0;
    static final int GROUP_TELEMETRY = 1;
    static final int GROUP_VOICE = 2;
    static final int GROUP_EXTRAS = 3;
    static final int GROUP_HOME = 4;

    /** Marked safe, but Simple mode leaves it on: family photos are a common use of the TV. */
    static final String AMAZON_PHOTOS = "com.amazon.bueller.photos";

    /** Works, but has a consequence worth stating before the user presses the button. */
    static final boolean CAUTION = true;
    static final boolean SAFE = false;

    static final class Entry {
        final String pkg;
        /** String resources, so the rows follow the app's language. */
        final int title;
        final int note;
        final int group;
        final boolean caution;
        /** Never part of a bulk switch-off, never offered by the one-press buttons. */
        final boolean risky;

        Entry(int group, String pkg, int title, int note, boolean caution) {
            this(group, pkg, title, note, caution, false);
        }

        Entry(int group, String pkg, int title, int note, boolean caution, boolean risky) {
            this.group = group;
            this.pkg = pkg;
            this.title = title;
            this.note = note;
            this.caution = caution;
            this.risky = risky;
        }
    }

    static String groupName(Context c, int g) {
        switch (g) {
            case GROUP_ADS: return c.getString(R.string.db_g_ads);
            case GROUP_TELEMETRY: return c.getString(R.string.db_g_telemetry);
            case GROUP_VOICE: return "Alexa";
            case GROUP_HOME: return c.getString(R.string.db_g_home);
            default: return c.getString(R.string.db_g_extras);
        }
    }

    /**
     * Ordered by group. Packages Fire OS refuses to disable are still listed; the attempt
     * fails cleanly and the row says so.
     */
    static final Entry[] CATALOGUE = {
            // ---------------------------------------------------------- advertising
            new Entry(GROUP_ADS, "com.amazon.hybridadidservice", R.string.db_adid_t, R.string.db_adid_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.tv.acr", R.string.db_acr_t, R.string.db_acr_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.media.recommendations", R.string.db_reco_t, R.string.db_reco_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.sneakpeek", R.string.db_sneak_t, R.string.db_sneak_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.recess", R.string.db_recess_t, R.string.db_recess_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.shoptv.client", R.string.db_shop_t, R.string.db_shop_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.shoptv.firetv.client", R.string.db_shop2_t, R.string.db_shop2_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.minitv.android.app", R.string.db_minitv_t, R.string.db_minitv_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.ftv.screensaver", R.string.db_adsaver_t, R.string.db_adsaver_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.ftvads.deeplinking", R.string.db_deeplink_t, R.string.db_deeplink_n, SAFE),

            new Entry(GROUP_ADS, "com.amazon.kso.blackbird", R.string.db_blackbird_t, R.string.db_blackbird_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.uxnotification", R.string.db_promo_t, R.string.db_promo_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.systemnotices", R.string.db_notices_t, R.string.db_notices_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.tv.releasenotes", R.string.db_whatsnew_t, R.string.db_whatsnew_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.tv.easyupgrade", R.string.db_upgrade_t, R.string.db_upgrade_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.gamehub", R.string.db_games_t, R.string.db_games_n, SAFE),
            new Entry(GROUP_ADS, "com.amazon.tv.developer.dataservice", R.string.db_devdata_t, R.string.db_devdata_n, SAFE),
            // ---------------------------------------------------------- telemetry
            new Entry(GROUP_TELEMETRY, "com.amazon.tv.fw.metrics", R.string.db_fwmetrics_t, R.string.db_fwmetrics_n, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.dp.logger", R.string.db_logger_t, R.string.db_logger_n, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.wifilocker", R.string.db_wifilocker_t, R.string.db_wifilocker_n, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.client.metrics", R.string.db_metricsup_t, R.string.db_metricsup_n, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.device.metrics", R.string.db_devmetrics_t, R.string.db_usually_protected, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.minerva.client.api", R.string.db_minerva_t, R.string.db_minerva_n, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.diode", R.string.db_diode_t, R.string.db_usually_protected, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.device.crashmanager", R.string.db_crash_t, R.string.db_usually_protected, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.device.logmanager", R.string.db_logup_t, R.string.db_usually_protected, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.wirelessmetrics.service", R.string.db_wireless_t, R.string.db_wireless_n, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.perfc", R.string.db_perfc_t, R.string.db_perfc_n, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.perfcollection", R.string.db_perf2_t, R.string.db_perf2_n, SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.client.metrics.api", R.string.db_metricsapi_t, R.string.db_metricsapi_n, SAFE),
            // ---------------------------------------------------------- alexa
            new Entry(GROUP_VOICE, "com.amazon.vizzini", R.string.db_vizzini_t, R.string.db_vizzini_n, CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.aca", R.string.db_aca_t, R.string.db_aca_n, CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexamediaplayer.runtime.ftv", R.string.db_amp_t, R.string.db_amp_n, CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexa.datastore.app", R.string.db_adata_t, R.string.db_adata_n, CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexadirectivebrokerservice", R.string.db_broker_t, R.string.db_broker_n, CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexa.externalmediaplayer.fireos", R.string.db_extmp_t, R.string.db_extmp_n, CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.tv.alexaalerts", R.string.db_alerts_t, R.string.db_alerts_n, CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.tv.alexanotifications", R.string.db_anotif_t, R.string.db_anotif_n, CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.vizzini.ftvcds", R.string.db_cds_t, R.string.db_cds_n, CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.ale", R.string.db_ale_t, R.string.db_ale_n, CAUTION),

            new Entry(GROUP_VOICE, "com.amazon.audiohome", R.string.db_theater_t, R.string.db_theater_n, CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.aiondec", R.string.db_skills_t, R.string.db_skills_n, CAUTION),
            // ---------------------------------------------------------- extras
            new Entry(GROUP_EXTRAS, "com.apple.airplay.amazon.airplay", R.string.db_airplay_t, R.string.db_airplay_n, CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.firebat", R.string.db_firebat_t, R.string.db_firebat_n, CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.whad", R.string.db_whad_t, R.string.db_whad_n, CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.whasettings", R.string.db_whaset_t, R.string.db_whaset_n, CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.tv.ffsprovisioneeclient", R.string.db_ffs_t, R.string.db_ffs_n, SAFE),
            new Entry(GROUP_EXTRAS, AMAZON_PHOTOS, R.string.db_photos_t, R.string.db_photos_n, SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.tv.democontent.provider", R.string.db_demo_t, R.string.db_demo_n, SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.tmm.tutorial", R.string.db_tour_t, R.string.db_tour_n, SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.storm.lightning.tutorial", R.string.db_remotetut_t, R.string.db_remotetut_n, SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.tigris", R.string.db_tigris_t, R.string.db_tigris_n, SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.naatyam", R.string.db_naatyam_t, R.string.db_naatyam_n, SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.hedwig", R.string.db_channels_t, R.string.db_channels_n, CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.tv.livetv", R.string.db_livetab_t, R.string.db_livetab_n, CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.tv.mattercohost", R.string.db_matter_t, R.string.db_matter_n, CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.whisperjoin.middleware.np", R.string.db_ffsprov_t, R.string.db_ffsprov_n, CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.tahoe", R.string.db_kids_t, R.string.db_kids_n, CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.ods.kindleconnect", R.string.db_showon_t, R.string.db_showon_n, CAUTION),
            new Entry(GROUP_HOME, AppModel.AMAZON_LAUNCHER, R.string.db_launcher_t, R.string.db_launcher_n, CAUTION, true),
    };

    /**
     * Packages this app never touches. com.amazon.ceviche must stay listed: disabling it
     * puts com.amazon.tv.launcher into a crash loop.
     */
    private static final String[] NEVER = {
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.providers.settings",
            "com.android.providers.media",
            "com.android.providers.downloads",
            "com.android.packageinstaller",
            "com.android.keychain",
            "com.android.certinstaller",
            "com.android.location.fused",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.shell",
            "com.android.server.telecom",
            "com.android.webview",
            "com.google.android.webview",
            "com.android.captiveportallogin",
            "com.amazon.tv.launcher",
            "com.amazon.firehomestarter",
            "com.amazon.tv.settings.v2",
            "com.amazon.device.software.ota",
            "com.amazon.device.software.ota.override",
            "com.amazon.tv.parentalcontrols",
            "com.amazon.ceviche",
            "com.amazon.tv.devicecontrolsettings",
            "com.amazon.imp",
            "com.amazon.dcp",
    };

    /** Substrings that identify subsystems the TV cannot lose. Deliberately narrow. */
    private static final String[] NEVER_CONTAINING = {
            "inputmethod",
            "tvinput",
            "com.mediatek.",
            "com.mstar.",
            "wifi",
            "bluetooth",
    };

    private Debloat() { }

    // ---------------------------------------------------------------- state

    /** ENABLED, DISABLED or ABSENT. Needs no permission. */
    static int stateOf(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return ai.enabled ? ENABLED : DISABLED;
        } catch (Throwable t) {
            return ABSENT;
        }
    }

    static String stateLabel(int state) {
        if (state == ABSENT) return "not present";
        return state == ENABLED ? "on" : "off";
    }

    // ---------------------------------------------------------------- protection

    /** Null if the package may be toggled, otherwise the reason it may not be. */
    static String protectionReason(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0) return c.getString(R.string.db_r_nopkg);
        if (pkg.equals(c.getPackageName())) return c.getString(R.string.db_r_self);

        for (int i = 0; i < NEVER.length; i++) {
            if (NEVER[i].equals(pkg)) {
                if (isRisky(pkg)) break;     // its own row, with a warning, is the way in
                return "com.amazon.ceviche".equals(pkg)
                        ? c.getString(R.string.db_r_ceviche)
                        : c.getString(R.string.db_r_core);
            }
        }
        // The substring rules guard "Browse every system app". Catalogue entries were
        // checked by hand, so they are exempt (com.amazon.wifilocker contains "wifi").
        if (!inCatalogue(pkg)) {
            String lower = pkg.toLowerCase(java.util.Locale.US);
            for (int i = 0; i < NEVER_CONTAINING.length; i++) {
                if (lower.contains(NEVER_CONTAINING[i])) return c.getString(R.string.db_r_core);
            }
        }
        // The launcher is the home screen right now by definition; Homestead also declares
        // Home, so the risky entry is allowed past this check and carries its own warning.
        if (!isRisky(pkg) && liveCritical(c).contains(pkg)) return c.getString(R.string.db_r_live);
        // Only ever blocks switching it off: once it is off, its row must stay the way back.
        if (AppModel.AMAZON_LAUNCHER.equals(pkg) && stateOf(c, pkg) == ENABLED) {
            return homeWithoutAmazonLauncher(c);
        }
        return null;
    }

    /**
     * Null when, with Amazon's launcher off, Home would come straight to Homestead; otherwise
     * who would get it. Android gives Home to the home screen with the highest priority, and
     * an app's priority is 0. On Fire OS 7 Amazon's Home Starter declares 1 and would win; it
     * only switches Amazon's launcher back on and closes, so Home would open nothing. A tie
     * with another home app would bring up a chooser instead.
     */
    static String homeWithoutAmazonLauncher(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        } catch (Throwable t) {
            return c.getString(R.string.db_r_home_unknown);
        }
        ResolveInfo ours = null, rival = null;
        for (int i = 0; ris != null && i < ris.size(); i++) {
            ResolveInfo ri = ris.get(i);
            if (ri.activityInfo == null) continue;
            String p = ri.activityInfo.packageName;
            if (AppModel.AMAZON_LAUNCHER.equals(p)) continue;
            if (p.equals(c.getPackageName())) {
                if (ours == null || ri.priority > ours.priority) ours = ri;
            } else if (rival == null || ri.priority > rival.priority) {
                rival = ri;
            }
        }
        if (ours == null) return c.getString(R.string.db_r_home_unknown);
        if (rival == null || rival.priority < ours.priority) return null;
        if (rival.priority > ours.priority) return c.getString(R.string.db_r_home_fireos);
        // Short enough for the row; a package without a label falls back to its name.
        CharSequence label = null;
        try { label = rival.loadLabel(pm); } catch (Throwable ignored) { }
        String name = label == null ? "" : label.toString();
        return name.length() == 0 || name.equals(rival.activityInfo.packageName)
                ? c.getString(R.string.db_r_home_other) : c.getString(R.string.db_r_home_shared, name);
    }

    /** The catalogue's plain-English name for a package, or the package name itself. */
    static String titleOf(Context c, String pkg) {
        for (int i = 0; i < CATALOGUE.length; i++) {
            if (CATALOGUE[i].pkg.equals(pkg)) return c.getString(CATALOGUE[i].title);
        }
        return pkg;
    }

    /** A catalogue entry that is switched off only from its own row, never in bulk. */
    static boolean isRisky(String pkg) {
        for (int i = 0; i < CATALOGUE.length; i++) {
            if (CATALOGUE[i].risky && CATALOGUE[i].pkg.equals(pkg)) return true;
        }
        return false;
    }

    private static boolean inCatalogue(String pkg) {
        for (int i = 0; i < CATALOGUE.length; i++) {
            if (CATALOGUE[i].pkg.equals(pkg)) return true;
        }
        return false;
    }

    /** Fire OS's message when disable-user hits a package it protects. */
    static boolean refusedIn(String output, String pkg) {
        return output != null && output.contains("protected package: " + pkg);
    }

    static boolean isProtected(Context c, String pkg) {
        return protectionReason(c, pkg) != null;
    }

    /**
     * Packages the system currently relies on: input methods, TV inputs (HDMI), home
     * screens, active device admins and enabled accessibility services. Not cached, since
     * the answer changes when the user switches keyboard or plugs something in.
     */
    private static Set<String> liveCritical(Context c) {
        Set<String> out = new HashSet<String>();
        PackageManager pm = c.getPackageManager();

        try {
            InputMethodManager imm =
                    (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            List<InputMethodInfo> imes = (imm == null) ? null : imm.getInputMethodList();
            if (imes != null) {
                for (int i = 0; i < imes.size(); i++) out.add(imes.get(i).getPackageName());
            }
        } catch (Throwable ignored) { }

        try {
            TvInputManager tim =
                    (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            List<TvInputInfo> inputs = (tim == null) ? null : tim.getTvInputList();
            if (inputs != null) {
                for (int i = 0; i < inputs.size(); i++) {
                    android.content.pm.ServiceInfo si = inputs.get(i).getServiceInfo();
                    if (si != null) out.add(si.packageName);
                }
            }
        } catch (Throwable ignored) { }

        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> ris = pm.queryIntentActivities(home, 0);
            if (ris != null) {
                for (int i = 0; i < ris.size(); i++) {
                    if (ris.get(i).activityInfo != null) {
                        out.add(ris.get(i).activityInfo.packageName);
                    }
                }
            }
        } catch (Throwable ignored) { }

        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            List<ComponentName> admins = (dpm == null) ? null : dpm.getActiveAdmins();
            if (admins != null) {
                for (int i = 0; i < admins.size(); i++) out.add(admins.get(i).getPackageName());
            }
        } catch (Throwable ignored) { }

        try {
            AccessibilityManager am =
                    (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            List<android.accessibilityservice.AccessibilityServiceInfo> svcs = (am == null)
                    ? null
                    : am.getEnabledAccessibilityServiceList(
                            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (svcs != null) {
                for (int i = 0; i < svcs.size(); i++) {
                    String id = svcs.get(i).getId();
                    int slash = (id == null) ? -1 : id.indexOf('/');
                    if (slash > 0) out.add(id.substring(0, slash));
                }
            }
        } catch (Throwable ignored) { }

        return out;
    }

    // ---------------------------------------------------------------- browse everything

    static final class Installed {
        final String pkg;
        final String label;
        final int state;
        final boolean system;
        Installed(String pkg, String label, int state, boolean system) {
            this.pkg = pkg; this.label = label; this.state = state; this.system = system;
        }
    }

    /**
     * Every installed package with its state, sorted by label. Protected packages are
     * included; callers must check protectionReason() before offering a toggle.
     */
    static List<Installed> installed(Context c, boolean systemOnly) {
        List<Installed> out = new ArrayList<Installed>();
        try {
            PackageManager pm = c.getPackageManager();
            List<ApplicationInfo> all =
                    pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
            for (int i = 0; i < all.size(); i++) {
                ApplicationInfo ai = all.get(i);
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (systemOnly && !sys) continue;
                String label;
                try {
                    CharSequence l = ai.loadLabel(pm);
                    label = (l == null) ? ai.packageName : l.toString();
                } catch (Throwable t) {
                    label = ai.packageName;
                }
                out.add(new Installed(ai.packageName, label,
                        ai.enabled ? ENABLED : DISABLED, sys));
            }
        } catch (Throwable ignored) { }
        java.util.Collections.sort(out, new java.util.Comparator<Installed>() {
            public int compare(Installed a, Installed b) {
                int n = a.label.compareToIgnoreCase(b.label);
                return (n != 0) ? n : a.pkg.compareTo(b.pkg);
            }
        });
        return out;
    }
}
