# Security

This app replaces your television's home screen and can change system settings on it. That
warrants being explicit about what it has, what it does with it, and where the boundaries
are.

## What the app holds

| Capability | Why | Notes |
|---|---|---|
| Accessibility service | The only way to learn the Home button was pressed | `canRetrieveWindowContent="false"`, no key filtering, no package filter. It receives *which app came to the foreground* and nothing else. |
| `INTERNET` | The on-device ADB client and the update check | Two uses, both listed under *What goes over the network* below. Nothing is uploaded and there are no analytics. |
| `WRITE_SECURE_SETTINGS` | Optional. TV Health applies animation-scale and ad-tracking settings | Declared but not held unless you grant it with `pm grant` (or the on-device tunnel grants it). Without it those pages are read-only and show the command instead. |
| `READ_LOGS` | Optional. Lets Homestead cover Amazon's home screen before it appears, and makes Home work while Amazon's home screen is showing | Declared but not held unless you grant it with `pm grant` (setup does this over the tunnel). See *The system log* below. |
| `SYSTEM_ALERT_WINDOW` | Fallback only | The overlay normally uses `TYPE_ACCESSIBILITY_OVERLAY`, which needs no permission. Declared for devices that refuse that window type. |
| `PACKAGE_USAGE_STATS` | Optional, unused in practice | Ordering is tracked internally; this is only read if the appop happens to be granted, to pick up launches predating install. |
| `RECEIVE_BOOT_COMPLETED` | Open the launcher at boot | |
| `REQUEST_INSTALL_PACKAGES` | Update fallback only | Lets the app hand a downloaded update to the system installer when the tunnel is not set up. The system installer still shows its own confirmation. |

## What goes over the network

The app has exactly two network behaviours, and no others:

- **Update check.** At most once a day, when the home screen opens or comes back, and
  whenever you press *Check for updates* in Settings, one unauthenticated HTTPS GET to
  `https://api.github.com/repos/Sarg338/Homestead/releases/latest`. It carries no token, no
  device identifier and nothing about your TV beyond what any HTTPS request carries. The
  APK itself is downloaded only when you choose *Install now*, and it is refused unless it is
  signed by the same certificate as the installed app.
- **The ADB client.** Connects only to this television's own `adbd`, directly or through
  a local tunnel on the TV. It never leaves the device. The tunnel is a VPN that routes only
  its own private subnet. Updates and re-apply after reboot do not start it while another
  VPN is running, so they never interrupt one you use.

There is no telemetry, no crash reporting and no analytics of any kind.

`android:allowBackup="false"` — so `adb backup` cannot lift the app's private data, which
includes the ADB key described below.

## The accessibility service

Accessibility is the standard escape hatch for a third-party launcher on Fire OS, and it is
also the standard way malware gets a foothold on Android, so: this service subscribes only
to `typeWindowStateChanged` and declares `canRetrieveWindowContent="false"`. It cannot read
the contents of any window, cannot see what you type, and does not request
`canRequestFilterKeyEvents`, which would route every key press on the device through this
app.

## The system log

The earliest sign of a Home press an app can see is one line in the system log, written as
the system starts Amazon's home screen and before that screen draws. A press made while
Amazon's home screen is already in front leaves no other trace at all. So with `READ_LOGS`
granted, a small background process (`:homekey`) reads the log to catch it.

Be clear about what that permission is: `READ_LOGS` lets an app read the TV's whole system
log, which can include details from other apps. Homestead asks `logcat` for only the
`ActivityManager` lines in the `system` buffer, acts only on a HOME intent started by the
system itself or by the app in front (some Amazon apps, such as Luna, pass Home on that
way), and does not keep, store or send any of it. Nothing is read when the
permission is not granted. To take it away:

    adb shell pm revoke com.homestead.launcher android.permission.READ_LOGS

After the TV next restarts, Amazon's home screen shows for a moment on each Home press
again and Home does nothing on Amazon's home screen; everything else keeps working.

## The ADB key

The app can generate an RSA-2048 key and offer it to the television's own `adbd`, so that
setup and the tuning steps do not need a computer. If you enable that:

- The key is generated **on the device** and never leaves it.
- It is stored in app-private storage, and `allowBackup` is off.
- **The television**, not this app, shows the approval dialog. The app displays the same
  fingerprint beforehand so you can compare them.
- It is off by default and only ever starts from an explicit action in Setup.
- Setup has a **Forget our key** action. Revoke the TV's copy separately under
  Settings → My Fire TV → Developer options → Revoke ADB authorisations.

Understand the trade: an authorised ADB key is `shell`-level access to your TV. If this app
were compromised, so is that. It also requires ADB debugging to stay switched on, which
means anything on your local network can attempt to connect to the TV. Leave it off unless
you want it.

## Package changes

The app only ever runs `pm disable-user --user 0` and `pm enable`. It does not call
`pm uninstall` anywhere, and it does not clear app data. Every change is reversible, and
Setup records which packages *this app* disabled so "undo everything" restores exactly
those and nothing else.

A hard protection list refuses to touch anything the system is currently relying on. That
list is computed at runtime, not guessed from package names: the active input methods, the
TV input services that drive HDMI, the registered home screens, active device admins and
the enabled accessibility services are all queried from the platform and excluded, on top
of a fixed list of core components. `com.amazon.ceviche` is permanently excluded because
disabling it crash-loops Amazon's launcher.

## The background photo endpoint

`BackgroundProvider` (authority `com.homestead.launcher.background`) is the one component
other processes can reach besides the launcher activity, the accessibility service and the
boot receiver. It exists so `adb shell content write` can hand Homestead a background photo
on Fire OS 8, where adb may not write into the app's folder. It is exported because the
shell runs as its own user, and it limits itself instead:

- Every call is refused unless it comes from adb's shell user (uid 2000) or root. Any app,
  including Homestead's own uid, gets a `SecurityException`; this was checked on the test
  TV.
- It is write-only. Opening it for reading, querying it, or asking for any path other than
  `/photo` fails, so nothing can be read back through it.
- A transfer goes to a temporary file and replaces the photo only if it arrives within
  25 MB and decodes as an image; anything else is discarded.
- The photo is kept in the app's private storage, which only Homestead can read.

**Choose a photo on this TV** uses the same endpoint from inside the app. Homestead holds no
storage permission, so it runs two commands through its on-device tunnel: `ls` of
`/sdcard/Download`, and `content write` of the chosen file into the endpoint. A file name is
used only if it consists of letters, numbers, spaces, dots, dashes, brackets and `+` and
ends in `.jpg`, `.jpeg` or `.png`, and it is single-quoted in the command; any other name
is left out, so a file name cannot add to the command. Like every tunnel use, the tunnel
is up only while this runs and for the short idle period after.

## Signing

Releases are signed with a key held outside this repository. The signing certificate's
SHA-256 fingerprint is:

    624fe3f5ac83ccdef5ce518073cbe46217de6f5ab2eb7e282983e149f0a6663e

Before the first install, check a downloaded APK with `apksigner verify --print-certs
homestead.apk` (apksigner ships with the Android build-tools) and compare the "Signer #1
certificate SHA-256 digest" line with the value above. After that first install, Android
itself refuses any update signed with a different key, and the in-app updater checks the
same thing before it hands a download to the installer, so a release signed with any other
key cannot be delivered through it.

## Reporting

Open an issue. If it is something that should not be public, say so in the issue without
the details and a private channel can be arranged.
