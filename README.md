# Homestead

A replacement home screen for Fire TV: no advertising rows, no auto-playing trailers, no
sponsored placements. Your inputs and your apps, and nothing else.

It also replaces the Home **button**, which is the hard part — Fire OS pins Amazon's
launcher as the system home activity and will not let anything else take it.

<img src="screenshots/before_after.png" alt="Before: Amazon's home screen with a sponsored banner, rows of promoted shows and the apps in one strip. After: Homestead with HDMI inputs up front, all apps in one grid, and an Amazon Home tile.">

Both pictures are from the same TV, a few minutes apart.

## What it does

- **A home screen without the ads.** Your inputs, your TV apps and everything else, in
  three rows. No sponsored rows, no auto-playing trailers, no promoted tiles.
- **The Home button comes here.** Pressing Home opens Homestead in about 170 ms instead of
  Amazon's home screen.
- **HDMI and Composite inputs as tiles**, so switching source is one press from home.
- **Apps in the order you use them**, counted on the TV itself.
- **Hide anything** you do not want to see: press MENU on a tile, or hold Select.
- **TV Health** shows the state of the TV (animation speed, ad-tracking settings, the
  screensaver, which Amazon telemetry packages are running, free memory and storage). Each
  setting it flags can be fixed with one press.
- **Debloat** switches off dozens of vetted Amazon packages, each with a plain-English
  description of what it does. Nothing is uninstalled and no data is deleted, and any
  package can be switched back on from the same page. A few packages are protected by
  Fire OS and cannot be switched off by any app.
- **No computer needed.** The on-device tunnel lets the app switch on the Home button,
  grant itself the permissions it needs and run Debloat, all from the remote. It is off
  until you turn it on and shuts down when the work is done. A few commands from a
  computer do the same job if you would rather not use it.
- **Updates itself.** It checks this repository's releases once a day and installs a new
  version only when you press Install, and only if it is signed with the same key.

## Verified on

Real hardware, not emulators:

| Device | Model | Fire OS | Verified |
|---|---|---|---|
| Insignia F30-Series Fire TV Edition | `AFTDCT31` | 7.7.1.5 | **Everything**: launcher, Home button, HDMI/Composite tiles, Settings, TV Health, Debloat, and setup without a computer |
| Amazon Fire TV Stick | `AFTSSS` | 7.7.1.6 | **Setup without a computer**: key approval, permissions, automatic shutdown of the tunnel |
| Amazon Fire TV Stick 4K (2nd gen) | `AFTKM` | 8.1.8.5 | **Reported by a user, not tested here**: Home button, Debloat (28 packages switched off; Fire OS 8 protects a few more than Fire OS 7), several reboots. Set up from a computer. |
| Hisense 65U65SFC Pro (Fire TV Edition) | `AFTCIBFBD1` | 8.1.8.0 | **Reported by a user, not tested here**: Home button, Debloat, a full reboot, and the fix for repeated package events (1.2.3 and later). Set up from a computer. |

The first two run Android 9 and were tested by the maintainer. The last two are user
reports on Fire OS 8 (Android 11). Other devices are *expected* to work but are unverified.

Debloat's list of packages was checked by hand on the Insignia TV above. Other models
and Fire OS versions may use different package names. Anything on the list that your TV
does not have is shown as "not present" and skipped, and packages the list does not know
about are only reachable through **Browse every system app**, which locks anything the
system is using but cannot tell you what is safe to switch off. If
your TV's packages differ, please open an issue with your model and the output of
`adb shell pm list packages -s` (system packages only, so it does not list the apps you
installed).

## Install

Download `homestead.apk` from the [latest release](https://github.com/Sarg338/Homestead/releases/latest)
and sideload it: `adb install homestead.apk` from a computer, or the Downloader app on the
TV. When its home screen comes up, the app checks that release page (at most once a day)
and shows a new version once, with **Install now**, **Later** and **Skip this version**.
Settings → **Check for updates** checks straight away. It never installs one until you press
**Install now**.

Without the on-device tunnel, an update goes through Fire OS's installer, which first needs
Homestead allowed to install apps: in the TV's Settings, **Device & Software** (**My Fire TV**
on some models) → **Developer options** → **Install unknown apps** → **Homestead**. The
update page tells you when this is needed.

## Setup

Fire OS does not list Homestead under Settings → Accessibility, so the Home button has to
be switched on over ADB, the TV's debugging connection. You can do that from the TV itself
or from a computer. Without it Homestead still works as an app you open from Amazon's app
row; only the Home button needs it.

The accessibility service is how the app learns that Home was pressed. It sees only which
app came to the front: it cannot read what is on screen and does not receive key presses.

### From the TV, no computer

1. Turn on **ADB debugging** under Settings → My Fire TV → Developer options. If Developer
   options is not there, open My Fire TV → About and press Select on the device name seven
   times.
2. Open **Homestead** from Amazon's app row. The first-run setup walks you through the
   rest. Later, the same steps are under the **Settings** tile → **Setup**.
3. Select **Set it up** (or **On-device tunnel (advanced)** in Setup) and accept Android's
   VPN prompt. In both prompts, press Down to reach OK.
4. The TV asks whether to allow the app's ADB key. Check that the fingerprint matches the
   one Homestead shows, tick **Always allow from this computer**, then select OK. Without
   the tick, the TV asks again every time the app connects.
5. The first-run setup then switches the Home button on by itself. In Setup, select
   **Finish setup automatically**, which also grants the permissions TV Health, Debloat
   and the Home button need.
6. Press Home.

The tunnel runs only while the app is making changes and shuts itself off afterwards.
[SECURITY.md](SECURITY.md) explains what the app can access and what goes over the network.
Some Amazon packages switch themselves back on after a restart; with **Re-apply after
reboot** on (the default), Homestead switches them off again about a minute after the TV
starts, which needs ADB debugging left on.

If the Home button ever goes back to Amazon's home screen, for example after an update,
select **Finish setup automatically** again. If you set Homestead up before version 1.2, run
it once more so Home also works while Amazon's home screen is showing.

### From a computer

```sh
adb connect <your TV's address>          # shown on the Commands page in the app
adb shell settings put secure enabled_accessibility_services com.homestead.launcher/com.homestead.launcher.HomeRedirectService
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.homestead.launcher android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.homestead.launcher android.permission.READ_LOGS
```

The first two commands switch the Home button on; the first replaces any other
accessibility service you have switched on. The first grant lets TV Health change settings
from the remote. The second lets Homestead catch the Home press early, so Amazon's home
screen does not flash first, and makes Home work while Amazon's home screen is showing.
To switch packages off, open **Commands for a computer** in the app: it lists exactly the
commands still needed on your TV.

## Removing Homestead

1. Open **Debloat** and select **Undo everything this app switched off**. This switches
   back on exactly the packages Homestead turned off. Uninstalling the app does *not* do
   this for you.
2. If you used the on-device tunnel, select **Forget our key** in Setup, then revoke the
   TV's copy under Settings → My Fire TV → Developer options, and turn ADB debugging off.
3. Uninstall Homestead under Settings → Applications → Manage Installed Applications.

Uninstalling also removes the accessibility service, so the Home button goes back to
Amazon's home screen straight away.

**To keep Homestead but give the Home button back to Amazon,** select **Turn off the Home
button** in Settings or Setup (press it twice to confirm). Homestead stays installed as an
ordinary app. **Re-apply after reboot** stops while the Home button is off. To turn it back
on, select **Finish setup automatically** in Setup, which needs ADB debugging still on. This
switch needs Fire OS 6 or later. To visit Amazon's home screen for a moment without turning
anything off, use the **Amazon Home** tile.

## How the Home button works

Fire OS does not let any app replace Amazon's launcher as the system home screen, so
Homestead uses an accessibility service to notice when Amazon's launcher comes to the
front, and covers it straight away.

Fire OS holds back app launches for several seconds after a Home press, so Homestead shows
its home screen as an overlay window, which is not held back. Its normal screen takes over
once Fire OS allows it, keeping the selected tile and scroll position.

The overlay is an accessibility overlay, which needs no extra permission.

The accessibility service only hears about Amazon's launcher once its screen is being
drawn, which is a few hundred milliseconds too late to hide it. The system log records the
Home press earlier, as the system starts Amazon's launcher, so a small separate process
watches the log for that one line and the overlay goes up first. It is also the only way to
notice a Home press made while Amazon's home screen is already in front, since that changes
no window. That needs `READ_LOGS`, which setup grants over ADB; [SECURITY.md](SECURITY.md)
says exactly what is read.

## Known limitations

- **Without `READ_LOGS`, Amazon's home screen shows for a moment** on each Home press, and
  Home does nothing while Amazon's home screen is already showing (open any app, then press
  Home). Setup grants it.
- **With it, the screen can still go black for a moment** between the app you leave and
  Homestead: usually under a quarter of a second, longer on the first press after an
  update. Pressing Home while Homestead itself is in front can still show Amazon's home
  briefly, because Amazon's screen comes back quickly from there.
- **The Amazon Home tile pauses Homestead for 30 seconds.** After that, Homestead may take
  over again while you are still browsing Amazon's home screen. Pressing Home brings
  Homestead back at any time.

## Building

Only needed if you want to build the app from source; to use Homestead, install the signed
`homestead.apk` from the [releases](https://github.com/Sarg338/Homestead/releases/latest).
No Gradle, no dependencies, about two seconds:

```sh
./build.sh            # build + sign
./build.sh install    # and adb install -r
```

Needs the Android SDK (`build-tools/36.1.0`, `platforms/android-34`) and a JDK. The signing
key is **not** in this repository; `build.sh` looks for `~/.homestead-keys/` or the
`HOMESTEAD_KEYSTORE` / `HOMESTEAD_KEYPASS` environment variables (to make your own key,
see [Building and signing your own copy](#building-and-signing-your-own-copy)). Set
`ADB_TARGET` to your TV's `ip:port` for `./build.sh install`.

The ADB client is written from the AOSP sources (`system/core/adb`,
`libcrypto_utils/android_pubkey.c`); no code is copied.

## AI assistants

Notes for coding agents, and for anyone else picking this repository up cold.

### Where things are

There is no Gradle; everything is in `src/com/homestead/launcher/`. Each class starts with a
comment explaining why it exists. Read it before changing the class.

| Area | Classes |
|---|---|
| Home screen | `MainActivity`, `HomeGrid` (the UI, shared by the activity and the overlay), `AppModel` (tiles, artwork, order), `UsageTracker`, `HdmiActivity`, `BootReceiver` |
| Home button | `HomeRedirectService` (accessibility service and overlay; its timings are deliberate), `HomeKeyWatcher` (Home presses, from the system log) |
| Pages | `Panel` (the list page), `OnboardingPanel` (first-run setup), `SetupPanel`, `SettingsPanel`, `HealthPanel` (TV Health), `DebloatPanel`, `CommandsPanel`, `UpdatePanel` |
| ADB | `AdbBridge` (one worker thread, link state, command batches), `AdbConnection` (the client), `AdbCrypto` (the key), `TunnelVpnService` and `VpnSetupActivity` (the on-device tunnel) |
| Debloat list | `Debloat` (the package catalogue, the never-touch lists, live locks) |
| Updates | `UpdateChecker` (the GitHub repo is `UpdateChecker.REPO`), `UpdateInstaller`, `UpdateFileProvider` |
| Preferences | `Prefs` |

### Building and signing your own copy

`./build.sh` needs a JDK (11 or newer), Android build-tools 36.1.0 and platform android-34.
It has been run on Windows (Git Bash) only; it picks the right tool names for Linux and
macOS, but those are untested. `minSdk` is 22 and `targetSdk` is 28. Do not raise
`targetSdk`: newer levels tighten the background-start rules the Home button relies on.

The release signing key is not in the repository. To build your own, make a key once:

```sh
mkdir -p ~/.homestead-keys
keytool -genkeypair -keystore ~/.homestead-keys/homestead-release.keystore \
  -alias homestead -keyalg RSA -keysize 2048 -validity 10000
export HOMESTEAD_KEYPASS='<the password you chose>'
./build.sh
```

A build signed with your own key cannot install over an official one (or the other way
round), so uninstall first. Uninstalling loses Homestead's record of what it switched off,
so run **Undo everything this app switched off** in Debloat before you do.

### Debugging on a TV

Test on real Fire TV hardware. The behaviour this app works around (Amazon's pinned home
screen, the delay on app starts after Home, protected packages, the missing Accessibility
entry) does not exist on an emulator or stock Android.

```sh
adb connect <tv-ip>:5555
adb logcat -s Homestead                                        # every log line uses this tag
adb shell settings get secure enabled_accessibility_services   # is the Home button switched on?
adb shell dumpsys accessibility                                # ...and running? look for label=Homestead
adb shell dumpsys activity activities | grep mResumedActivity  # what is on screen
adb shell pm list packages -d                                  # what is switched off
adb exec-out screencap -p > screen.png
```

Log lines worth knowing: `HomeRedirectService connected` (the service started),
`Home pressed` then `overlay shown in … ms` (one Home press, caught by `HomeKeyWatcher`),
`dispatchLag=…` (Amazon's launcher reached the front), `handing off to activity`,
`foreground is now …, removing overlay`, `tunnel up`, `adb connected`, `adb idle, closing`,
`package change […], grid unchanged` or `…, reloading model once` (package and HDMI input
events, gathered for 1.5 seconds; inputs are listed as `input:…`), `opening Settings (key … from device …, source …)` (device -1 is an
injected key rather than the remote, and `(HDMI-CEC)` marks one sent over HDMI),
`update check: …` (the result of each check for a new release).

Things that look like bugs but are Fire OS:

- Fire OS's Accessibility menu does not list third-party services. The service has to be
  switched on over ADB.
- The system's Home broadcast is restricted to Amazon-signed apps, and the accessibility
  event for Amazon's launcher arrives as its screen is drawn (and not at all if it is
  already in front). The earliest trace an app can read is ActivityManager's
  `START ... cat=[android.intent.category.HOME] ... from uid 1000` line in the system log,
  which is what `HomeKeyWatcher` looks for. `READ_LOGS` takes effect only in a process
  started after the grant, which is why the watcher runs in its own process (`:homekey`).
- Some Amazon apps, such as Luna, handle Home themselves (`CustomHomeButtonHandler:
  Bypassing HOME key` in the log) and then start the home screen under their own uid, so a
  HOME start from the app in front counts as a press too. That hook needs an Amazon-only
  permission, so Homestead cannot use it for its own screens.
- Fire OS refuses an ADB connection that starts on the TV itself; that is why the tunnel
  exists. The connection over Wi-Fi from a computer also drops often, so reconnect before
  each command when scripting.
- `pm disable-user` on some Amazon packages fails with "Cannot disable a protected
  package". Batches carry on past it, and TV Health counts those as protected.
- In the system VPN and USB-debugging prompts, no button has focus at first. Press Down to
  reach OK.
- `uiautomator dump` cannot read Homestead's windows. Use screenshots.

Homestead pauses the Home redirect while the user is on a setup screen (settings, VPN
prompt, key prompt), and lifts the pause when they come back. If Home "does nothing" during
testing, check whether a pause is active before suspecting the service.

### Rules for changes

- **Debloat list:** add a package only after switching it off, restarting the TV and
  switching it back on, on a real device, with nothing broken. Say which device in the
  pull request. `SAFE` entries are included in the one-press switch-off; anything with a
  visible side effect is `CAUTION`. Never add anything on the never-touch lists.
- Everything the app changes must be reversible from the app.
- Keep the accessibility service as narrow as it is (`canRetrieveWindowContent="false"`,
  no key filtering, no package filter). [SECURITY.md](SECURITY.md) promises this.
- **Release notes** appear as raw text in the app's Update available page, so write them
  as plain text: no Markdown links, code spans or headings. Bump `versionCode` and
  `versionName` in `AndroidManifest.xml`; the app compares the release tag (`v1.2`) with
  `versionName`.
- Describe only what you have tested. "Should work on X" belongs in a pull request
  description, not in the README.

### Verified Homestead on a device not in the table?

Open a pull request that adds a row to **Verified on** above:

- the device's name;
- the model, from `adb shell getprop ro.product.model`;
- the Fire OS version, from `adb shell getprop ro.build.version.name`;
- what you checked: the launcher, the Home button, setup from the TV, TV Health, Debloat.

Only list what you actually tried. If Debloat shows rows as "not present", or the TV has
Amazon packages the list does not know about, attach the output of
`adb shell pm list packages -s` (system packages only, so it does not list the apps you
installed).

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

This app can change system settings and package states on your television. Keeping it
copyleft means anyone shipping a modified build has to publish what they changed, and you
can read exactly what it does before you let it near your TV. That matters more here than
it would for an ordinary app.
