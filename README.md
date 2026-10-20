# Homestead

A replacement home screen for Fire TV: no advertising rows, no auto-playing trailers, no
sponsored placements. Your inputs and your apps, and nothing else.

It also replaces the Home **button**, which is the hard part — Fire OS pins Amazon's
launcher as the system home activity and will not let anything else take it.

<p align="center">
  <img src="screenshots/home.png" width="640" alt="The launcher: Inputs &amp; System, Non-TV Applications, TV Applications">
</p>

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
- **Debloat** switches off up to 40 vetted Amazon packages, each with a plain-English
  description of what it does. Nothing is uninstalled and no data is deleted, and any
  package can be switched back on from the same page. A few packages are protected by
  Fire OS and cannot be switched off by any app.
- **No computer needed.** The optional on-device tunnel lets the app grant itself the
  permissions it needs and run Debloat from the remote. It is off by default and shuts
  down when the work is done. A one-time command from a computer does the same job if you
  would rather not use it.
- **Updates itself.** It checks this repository's releases once a day and installs a new
  version only when you press Install, and only if it is signed with the same key.

## Verified on

Real hardware, not emulators:

| Device | Model | Fire OS | Verified |
|---|---|---|---|
| Insignia F30-Series Fire TV Edition | `AFTDCT31` | 7.7.1.5 | **Everything**: launcher, Home button, HDMI/Composite tiles, Settings, TV Health, Debloat, and setup without a computer |
| Amazon Fire TV Stick | `AFTSSS` | 7.7.1.6 | **Setup without a computer**: key approval, permissions, automatic shutdown of the tunnel |

Both run Android 9. Other Fire OS 7 devices are *expected* to work but are unverified.
**Fire OS 8 is untested.**

## Install

Download `homestead.apk` from the [latest release](https://github.com/Sarg338/Homestead/releases/latest)
and sideload it: `adb install homestead.apk` from a computer, or the Downloader app on the
TV. Nothing else is needed. The app checks that release page once a day and offers new
versions under Settings → **Update**; it never installs one without you pressing Install.

## Setup

1. Open **Homestead** from Amazon's app row.
2. On the remote, press Home and open **Settings → Accessibility → Homestead**, then switch
   it on.

That is all the launcher and the Home button need. No computer, no ADB.

The accessibility service is how the app learns that Home was pressed. It sees only which
app came to the front: it cannot read what is on screen and does not receive key presses.

> Installing an **update** switches the service off again. Android does that to every
> accessibility service when its app is updated. Turn it back on in the same place.

### Optional: TV Health and Debloat

Changing system settings and switching packages off needs the same access a computer gets
over ADB. You can give it from the TV itself or from a computer.

**From the TV, no computer:**

1. Turn on **ADB debugging** under Settings → My Fire TV → Developer options. If Developer
   options is not there, open My Fire TV → About and press Select on the device name seven
   times.
2. In Homestead, open the **Settings** tile, then **Setup**, and select **On-device tunnel
   (advanced)**. Accept Android's VPN prompt.
3. The TV asks whether to allow the app's ADB key. Check that the fingerprint matches the
   **Key fingerprint** row in Setup, then allow it.
4. Select **Finish setup automatically**. The app grants itself the permissions it needs.
5. Use **TV Health** and **Debloat** from the remote.

The tunnel runs only while the app is making changes and shuts itself off afterwards.
Some Amazon packages switch themselves back on after a restart; with **Re-apply after
reboot** on (the default), Homestead switches them off again about a minute after the TV
starts, which needs ADB debugging left on.

**From a computer:**

```sh
adb connect <your TV's address>          # shown on the Commands page in the app
adb shell pm grant com.homestead.launcher android.permission.WRITE_SECURE_SETTINGS
```

After that grant, TV Health can change settings from the remote. To switch packages off,
open **Commands for a computer** in the app: it lists exactly the commands still needed on
your TV.

## Removing Homestead

1. Open **Debloat** and select **Undo everything this app switched off**. This switches
   back on exactly the packages Homestead turned off. Uninstalling the app does *not* do
   this for you.
2. If you used the on-device tunnel, select **Forget our key** in Setup, then revoke the
   TV's copy under Settings → My Fire TV → Developer options, and turn ADB debugging off.
3. Uninstall Homestead under Settings → Applications → Manage Installed Applications.

Uninstalling also removes the accessibility service, so the Home button goes back to
Amazon's home screen straight away. To pause Homestead without uninstalling, switch it off
under Settings → Accessibility instead. To visit Amazon's home screen without turning
anything off, use the **Amazon Home** tile.

## How the Home button works

Fire OS does not let any app replace Amazon's launcher as the system home screen, so
Homestead uses an accessibility service to notice when Amazon's launcher comes to the
front, and covers it straight away.

Fire OS holds back app launches for several seconds after a Home press, so Homestead shows
its home screen as an overlay window, which is not held back. Its normal screen takes over
once Fire OS allows it, keeping the selected tile and scroll position.

The overlay is an accessibility overlay, which needs no extra permission. That is why the
basic setup needs no computer.

## How the on-device tunnel works

Changing system settings and switching packages off needs the same `shell` access a
computer gets over ADB. Homestead includes its own ADB client, so it can do this on the TV
itself. Fire OS refuses an ADB connection that starts on the TV, so the app makes the
connection through a small on-device VPN tunnel that carries only traffic for the TV's own
ADB service.

The tunnel is opt-in (Setup → **On-device tunnel (advanced)**) and off by default. Once on,
it starts only when the app has changes to make, including after a restart when **Re-apply
after reboot** is on, and shuts down shortly afterwards. The TV itself asks you to approve
the app's ADB key. See [SECURITY.md](SECURITY.md) for what this does and does not
expose.

## Building

No Gradle, no dependencies, about two seconds:

```sh
./build.sh            # build + sign
./build.sh install    # and adb install -r
```

Needs the Android SDK (`build-tools/36.1.0`, `platforms/android-34`) and a JDK. The signing
key is **not** in this repository; `build.sh` looks for `~/.homestead-keys/` or the
`HOMESTEAD_KEYSTORE` / `HOMESTEAD_KEYPASS` environment variables. Set `ADB_TARGET` to your
TV's `ip:port` for `./build.sh install`.

The ADB client is written from the AOSP sources (`system/core/adb`,
`libcrypto_utils/android_pubkey.c`); no code is copied.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

This app can change system settings and package states on your television. Keeping it
copyleft means anyone shipping a modified build has to publish what they changed, and you
can read exactly what it does before you let it near your TV. That matters more here than
it would for an ordinary app.
