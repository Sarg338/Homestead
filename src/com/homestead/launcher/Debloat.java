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

    /** Works, but has a consequence worth stating before the user presses the button. */
    static final boolean CAUTION = true;
    static final boolean SAFE = false;

    static final class Entry {
        final String pkg;
        final String title;
        final String note;
        final int group;
        final boolean caution;

        Entry(int group, String pkg, String title, String note, boolean caution) {
            this.group = group;
            this.pkg = pkg;
            this.title = title;
            this.note = note;
            this.caution = caution;
        }
    }

    static String groupName(int g) {
        switch (g) {
            case GROUP_ADS: return "Advertising & recommendations";
            case GROUP_TELEMETRY: return "Telemetry & diagnostics";
            case GROUP_VOICE: return "Alexa";
            default: return "Extras";
        }
    }

    /**
     * Ordered by group. Packages Fire OS refuses to disable are still listed; the attempt
     * fails cleanly and the row says so.
     */
    static final Entry[] CATALOGUE = {
            // ---------------------------------------------------------- advertising
            new Entry(GROUP_ADS, "com.amazon.hybridadidservice",
                    "Advertising ID service",
                    "Generates the identifier used to target ads at this TV.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.tv.acr",
                    "Automatic Content Recognition",
                    "Samples what is on screen, including from HDMI inputs, for ad targeting.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.media.recommendations",
                    "\"Recommended for you\" engine",
                    "Builds the recommendation rows on Amazon's home screen.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.sneakpeek",
                    "Home-screen trailer previews",
                    "The auto-playing trailer banner at the top of Amazon's home screen.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.recess",
                    "Sponsored content service",
                    "Serves the sponsored placements on Amazon's home screen.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.shoptv.client",
                    "Amazon shopping row",
                    "The shopping row on Amazon's home screen.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.shoptv.firetv.client",
                    "Amazon shopping row (Fire TV)",
                    "Fire TV variant of the same shopping row.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.minitv.android.app",
                    "Amazon MiniTV",
                    "Ad-supported free channel that appears as a row and an app.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.ftv.screensaver",
                    "Amazon ad screensaver",
                    "Replaces the idle screensaver with sponsored imagery.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.ftvads.deeplinking",
                    "Advertising deep-link handler",
                    "Handles taps on Fire TV ads. Fire OS usually refuses to disable this "
                            + "one; if the row stays enabled, that is why.", SAFE),

            // ---------------------------------------------------------- telemetry
            new Entry(GROUP_TELEMETRY, "com.amazon.tv.fw.metrics",
                    "Framework metrics collector",
                    "Collects framework-level usage metrics.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.dp.logger",
                    "Amazon logging framework",
                    "Shared logging plumbing used by Amazon services.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.wifilocker",
                    "Wi-Fi password upload",
                    "Uploads saved Wi-Fi credentials to your Amazon account so other "
                            + "devices can join automatically. Disabling it does not "
                            + "affect this TV's own Wi-Fi.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.client.metrics",
                    "Usage-metrics upload service",
                    "Runs even when the TV is idle. Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.device.metrics",
                    "Device metrics collector",
                    "Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.minerva.client.api",
                    "Minerva analytics client",
                    "Amazon's telemetry pipeline. Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.diode",
                    "Diagnostics uploader",
                    "Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.device.crashmanager",
                    "Crash-report uploader",
                    "Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.device.logmanager",
                    "Log upload service",
                    "Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.firebat",
                    "Diagnostic test harness",
                    "A factory test harness that keeps running in the background.", SAFE),

            // ---------------------------------------------------------- alexa
            new Entry(GROUP_VOICE, "com.amazon.vizzini",
                    "Alexa voice engine",
                    "The main Alexa process. It switches itself back on after a restart; "
                            + "keep \"Re-apply after reboot\" on in Setup to keep it off.",
                    CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.aca",
                    "Alexa companion agent",
                    "Reports account and media status to Alexa.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexamediaplayer.runtime.ftv",
                    "Alexa media playback runtime",
                    "Lets Alexa control playback.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexa.datastore.app",
                    "Alexa datastore", "Local storage for Alexa state.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexadirectivebrokerservice",
                    "Alexa directive broker", "Routes recognised voice commands.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexa.externalmediaplayer.fireos",
                    "Alexa external media bridge", "Bridges Alexa to third-party players.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.tv.alexaalerts",
                    "Alexa timers and alarms", "Voice timers and alarms stop working.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.tv.alexanotifications",
                    "Alexa notifications", "Alexa notification delivery.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.vizzini.ftvcds",
                    "Alexa content discovery", "Voice search across apps stops working.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.ale",
                    "Alexa Local Engine", "On-device speech handling.", CAUTION),

            // ---------------------------------------------------------- extras
            new Entry(GROUP_EXTRAS, "com.apple.airplay.amazon.airplay",
                    "AirPlay receiver",
                    "Screen mirroring from Apple devices.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.whad",
                    "Wireless Home Audio daemon",
                    "Pairing with Echo speakers for TV audio.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.whasettings",
                    "Wireless Home Audio settings",
                    "The settings screen for the above.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.tv.ffsprovisioneeclient",
                    "Frustration-Free Setup client",
                    "Automatic setup of new Amazon devices from this one.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.bueller.photos",
                    "Amazon Photos",
                    "Fed the ad screensaver. Only useful if you browse photos on the TV.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.tv.democontent.provider",
                    "Retail demo content",
                    "In-store demo loop. Nothing uses it in a home.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.tmm.tutorial",
                    "First-run tutorial", "The introductory tour. Already seen.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.storm.lightning.tutorial",
                    "Remote-app tutorial", "Tutorial for the phone remote app.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.tigris",
                    "Appstore onboarding", "Appstore first-run experience.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.naatyam",
                    "Setup redirect handler", "Onboarding launch redirection.", SAFE),
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
        if (pkg == null || pkg.length() == 0) return "no package";
        if (pkg.equals(c.getPackageName())) return "this launcher";

        for (int i = 0; i < NEVER.length; i++) {
            if (NEVER[i].equals(pkg)) {
                return "com.amazon.ceviche".equals(pkg)
                        ? "disabling this crash-loops the home screen"
                        : "core system component";
            }
        }
        String lower = pkg.toLowerCase(java.util.Locale.US);
        for (int i = 0; i < NEVER_CONTAINING.length; i++) {
            if (lower.contains(NEVER_CONTAINING[i])) return "core system component";
        }
        if (liveCritical(c).contains(pkg)) return "in use by the system right now";
        return null;
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
