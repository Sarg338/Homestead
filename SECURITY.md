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
| `SYSTEM_ALERT_WINDOW` | Fallback only | The overlay normally uses `TYPE_ACCESSIBILITY_OVERLAY`, which needs no permission. Declared for devices that refuse that window type. |
| `PACKAGE_USAGE_STATS` | Optional, unused in practice | Ordering is tracked internally; this is only read if the appop happens to be granted, to pick up launches predating install. |
| `RECEIVE_BOOT_COMPLETED` | Open the launcher at boot | |
| `REQUEST_INSTALL_PACKAGES` | Update fallback only | Lets the app hand a downloaded update to the system installer when the tunnel is not set up. The system installer still shows its own confirmation. |

## What goes over the network

The app has exactly two network behaviours, and no others:

- **Update check.** Once a day when the launcher opens, and whenever you press *Check now*
  under Settings → Update, one unauthenticated HTTPS GET to
  `https://api.github.com/repos/Sarg338/Homestead/releases/latest`. It carries no token, no
  device identifier and nothing about your TV beyond what any HTTPS request carries. The
  APK itself is downloaded only when you choose *Install*, and it is refused unless it is
  signed by the same certificate as the installed app.
- **The ADB client.** Connects only to this television's own `adbd`, directly or through
  the local tunnel described below. It never leaves the device.

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

## The ADB key

The app can generate an RSA-2048 key and offer it to the television's own `adbd`, so that
the tuning steps do not need a computer. If you enable that:

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

## The on-device tunnel

Fire OS refuses an ADB connection that starts on the TV itself. The optional **on-device
tunnel** is a `VpnService` that carries the app's connection to the TV's own `adbd`. It is
built to be hard to misuse:

- **Not startable by any other app.** `VpnSetupActivity` is `android:exported="false"`, so
  only this app can bring the tunnel up.
- **Off by default.** Once you turn it on in Setup, it starts only when the app has ADB work
  to do: when you use Setup, TV Health or Debloat, and about a minute after a restart if a
  package you switched off has turned itself back on. That last case is **Re-apply after
  reboot**, which you can switch off in Setup.
- **Short-lived.** It runs only while the app is running ADB commands and shuts down about
  25 seconds after they finish.
- **Routes only its own private subnet** (`10.113.77.0/24`), never a default route, so it
  never sees your Wi-Fi or streaming traffic. It refuses to start if that subnet is already
  in use on your network, and it makes no internet connection.
- **The remaining risk:** while the tunnel is up, other apps on the TV can reach `adbd` too,
  because a `VpnService` routes by destination and cannot filter by app. They cannot use
  this app's key, which is in app-private storage; each would have to get *you* to approve
  *its own* key on the television's dialog. Approve only prompts you were expecting, and
  turn ADB debugging back off under My Fire TV when you are done.

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

## Signing

Releases are signed with a key held outside this repository. The in-app updater refuses
any APK whose signing certificate does not match the installed app's, so a release signed
with any other key cannot be delivered through it.

## Reporting

Open an issue. If it is something that should not be public, say so in the issue without
the details and a private channel can be arranged.
