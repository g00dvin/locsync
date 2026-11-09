# LocSync Server — HU Bootstrap (hidden ADB/installer screen)

Date: 2026-11-09
Status: v1 implemented inline. Sonnet 5.5 review was requested but the reviewer
was stopped by a safety classifier before producing findings (the telnet/adb
topic); a self-review was done instead (see end). dadb API verified against
upstream source.
Module: `server-app` (phone, Java, minSdk 24)

## v1 scope cuts (ponytail, from self-review)

- Telnet-host discovery: **manual `fe80::…%wlan0` paste + IPv4 `/24` :23 scan**
  only. ICMPv6 `ff02::1` auto-discovery and `NsdManager` mDNS are **deferred**
  (most complexity/risk for marginal UX; manual paste is the guaranteed path).
- ADB host = the same address the user entered, on port 5555.
- "Skip if same version" deferred; install is always `pm install -r -g` (`-r`
  makes it idempotent).

## Implemented files

- `server-app/.../bootstrap/TelnetUnlock.java` — root-telnet unlock (setprop +
  adb_keys inject + adbd restart).
- `server-app/.../bootstrap/HuAdb.java` — dadb wrapper (keypair, push +
  `pm install -r -g`, appops, launch). dadb API verified:
  `AdbKeyPair.generate/read`, `Dadb.create(host,port,keyPair)` (Closeable),
  `push(File,String)`, `shell→AdbShellResponse(output,exitCode)`; the generated
  `.pub` is adb-format, so the injected key matches.
- `server-app/.../bootstrap/BootstrapActivity.java` + `activity_bootstrap.xml`.
- `MainActivity`: 7-tap gesture on `rowVersion` (was `click=null`, no conflict).
- Gradle: `dev.mobile:dadb:1.2.10` in `libs.versions.toml` + `server-app`.
- Strings EN + RU.

## Builder must still do

- **Build with the Android SDK** (not available in the authoring env) and fix
  any dadb getter name if it differs (verified `getOutput()/getExitCode()`).
- Drop the client APK at `server-app/src/main/assets/locsync-client.apk`.
- **On-car acceptance:** Enable ADB → `dadb.create` connects with no RSA dialog
  → install client → client launches and feeds mock GPS. If `adb_keys`
  owner/mode or the adbd restart differs on a variant, fall back to the
  on-screen RSA dialog.

## Intent / why

Stock Geely/eCarX head units (G426, Android 11) ship bare: no ADB, no way to
sideload the LocSync client. The ADB toggle is hidden in engineering mode, and
the unit exposes a **root telnet daemon on port 23** (confirmed in recon:
`ss` shows `[::]:23` LISTEN, no password, `#` root shell). Today a user must
follow a manual telnet/PingTools guide to enable ADB and sideload the client.

This feature folds that whole bootstrap into the LocSync Server app: one hidden
screen that enables ADB on the HU, installs the LocSync client (and any APK the
user picks), and launches it — no PC, no telnet client, no USB.

The screen is **hidden**: opened by tapping the version row 7 times (Android
dev-options gesture), so normal users never see it.

## Goals

1. **Enable ADB on the HU over Wi-Fi**, from the phone, with no PC:
   - via the root telnet daemon: set `persist.service.adb.button.visible ON`
     (makes the ADB toggle visible in engineering mode), bring adbd up on TCP,
     and authorise this phone so no on-screen RSA dialog is needed.
2. **Install the bundled LocSync client APK** to the HU and launch it, and
   grant it mock-location (the client needs it to feed GPS).
3. **Install any user-picked APK** (SAF file picker) to the HU and launch it
   after install.
4. Gate all of the above behind a **hidden screen** reached by tapping the
   version row 7×.

## Non-goals

- No PC/desktop flow (that is what magic_sqd/Monji are for).
- No auto-join of the HU Wi-Fi hotspot — the user connects the phone to the HU
  AP (or vice-versa) manually; the app uses the current network.
- No cloud APK download, no QR wireless-debugging pairing, no USB-flash path
  (documented as alternatives, out of scope).
- No pushed `.dex` shell-installer (Monji's trick); plain `pm install -r -g`
  via the shell uid is already silent on this unit.
- No arbitrary-activity picker for launch in v1 (launcher activity only).

## Anti-goals (would make this a failure even if it "works")

- Enabling ADB / writing `adb_keys` on a device that is **not the user's own**.
  The screen must make clear it acts on the connected HU and is user-initiated.
- Silently weakening HU security beyond what the user asked (e.g. leaving adbd
  wide open with no mention). We surface what was changed.
- Shipping a flaky binary-transfer hack that half-installs an APK.

## Constraints (from recon, verified on the car)

- HU: Android 11, `ro.secure=1`, `ro.debuggable=1`, `persist.adb.tcp.port=5555`
  already set, `persist.service.adb.button.visible=OFF`,
  `persist.sys.usb.config=diag,adb`. Telnet `[::]:23` root, no auth.
- `ro.secure=1` ⇒ adbd requires RSA auth. We bypass the on-screen dialog by
  writing the phone's ADB public key into `/data/misc/adb/adb_keys` **via the
  root telnet shell** (telnet is already root, so this is trivial and one-time).
- Telnet is reachable via **IPv6 link-local** `fe80::…%wlan0` (the `[::]:23`
  bind; the manual guide uses `telnet <fe80>%wlan0`). IPv4 gateway:23 may also
  work but is not guaranteed → IPv6 is primary.
- Phone: Android 7+ (minSdk 24). Non-rooted. Server app already holds
  `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`,
  `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES`.
- Project is GPL-3.0 (© Dezz). New files carry the same GPL-3.0 header.

## How it works — decided design

### Entry (hidden screen)

- Attach a tap counter to `rowVersion` (the `@string/version_label` row in
  `view_settings.xml`, group *About*). 7 taps within a short window (reset on
  timeout) → open `BootstrapActivity`. A Toast counts down the last few taps
  ("2 taps away…"), mirroring Android dev-options. **My call:** 7 taps, 3 s
  inactivity reset.

### Transport: telnet unlocks, ADB does the work

Two-stage, matching magic_sqd's proven model but phone-side:

1. **Telnet (one-time unlock).** Open a raw TCP socket to `HU:23`, read/discard
   banner, send, each line `\r\n`-terminated, as root:
   - `setprop persist.service.adb.button.visible ON`
   - append this phone's ADB public key to `/data/misc/adb/adb_keys`
     (`mkdir -p /data/misc/adb; echo '<pubkey>' >> /data/misc/adb/adb_keys;
     chmod 640 …; chown system:shell …`) — exact owner/mode validated at build
     time against the unit; **ceiling**: if the write path differs on a variant,
     fall back to the on-screen RSA dialog.
   - `setprop service.adb.tcp.port 5555; stop adbd; start adbd`
2. **ADB (install/launch/grant).** Embed an ADB client library (**`dadb`**,
   `dev.mobile:dadb`, pure-Kotlin ADB-over-TCP). dadb owns the RSA keypair; its
   public key is the one telnet injected, so `dadb.create(host, 5555)` connects
   **authorised, no dialog**. Then:
   - `install`: dadb push APK to `/data/local/tmp`, `pm install -r -g <path>`
     (shell uid ⇒ silent, `-g` grants all declared runtime perms), clean up tmp.
   - `launch`: resolve launcher activity (`cmd package resolve-activity --brief
     <pkg>` or `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`).
   - `grant appops`: `appops set <pkg> <op> allow` for specials.

Rationale: telnet alone can't move a 10 MB binary cleanly (no curl on AOSP;
base64-over-telnet is slow/fragile). ADB push is the clean binary channel, and
telnet is only the one-time key-and-prop unlock.

### Discovery

- **Telnet host (IPv6 link-local).** Non-rooted phones can't read the neighbour
  table (`ip neigh` / `/proc/net/*` blocked on Android 10+), so we do what
  PingTools does: send an **ICMPv6 Echo Request to `ff02::1` (all-nodes)** on
  wlan0 via `android.system.Os.socket(AF_INET6, SOCK_DGRAM, IPPROTO_ICMPV6)`
  (unprivileged ICMP is allowed on Android via `ping_group_range`), collect
  responders' `fe80::…` sources, then TCP-probe `:23` on each. Hold a
  `WifiManager.MulticastLock` during the sweep. Interface comes from
  `ConnectivityManager`→`LinkProperties.getInterfaceName()`, scope id from
  `NetworkInterface`.
  - Fallbacks: **manual paste** of `fe80::…%wlan0` (always available); optional
    IPv4 `/24` TCP:23 sweep when the HU is reachable by IPv4.
  - **Ceiling:** on some phones (Samsung/MIUI) ICMPv6 multicast is filtered →
    manual paste is the guaranteed path and is always offered.
- **ADB host.** After unlock, find adbd: IPv4 gateway:5555, `NsdManager` mDNS
  `_adb-tls-connect._tcp`, or reuse the telnet host's address on 5555.

### Features / screen layout

`BootstrapActivity`, top-to-bottom:

1. **Device** — discovered HU address + Connect; manual-address field.
2. **Enable ADB** — runs the telnet unlock; shows result (button visible, adbd
   up, key installed).
3. **Install LocSync client** — installs the bundled client APK, grants
   `android:mock_location` (and selects the client as mock app if needed),
   launches it.
4. **Install APK…** — SAF picker → install `-r -g` → launch. Parse
   package name + version locally (`getPackageArchiveInfo`), skip if the same
   version is already installed (`cmd package`/dumpsys check).
5. **Log view** — scrollable, every telnet/adb line, copyable.

### Bundled client APK

The LocSync client APK ships as a `server-app` asset (or is resolved from the
existing `UpdateChecker` artifact). **Open for the builder:** reuse the
`UpdateChecker`'s downloaded client artifact if present, else bundle under
`assets/`. Default = bundle under `assets/` to keep bootstrap offline.

### Permissions granted (matches our analysis of Monji)

- On install: **all declared runtime perms** via `pm install -g` (no hardcoded
  list — whatever the APK requests).
- Specials via **appops**, chosen by checkboxes on the screen and applied after
  install as `appops set <pkg> <op> allow`:
  - Mock location → `android:mock_location` (default on; the client needs it —
    `ACCESS_MOCK_LOCATION` is signature-only, so the appop is the only route).
    Verified on-car: the client already injects mock GPS once this op is set.
  - Install unknown apps → `android:request_install_packages` (lets the app
    install other APKs; true *silent* install still needs a system installer).
  - VPN → `ACTIVATE_VPN` (pre-consents VPN, skipping the system dialog).
  - Modify system settings → `android:write_settings` (NOT
    `WRITE_SECURE_SETTINGS`, which is signature-only and ungrantable).

Client APK: expected at `server-app/src/main/assets/locsync-client.apk` (built
artifact, not committed; CI/build copies it). Missing → the client button says
so; the user-picked APK path works regardless. **First release scope: enable
ADB + install any user-picked APK** (bundled-client install is a later add).

## Security / safety

- The screen is destructive-capable (roots-adjacent control of the HU). It is
  hidden (7-tap), user-initiated per action, and every change is logged on
  screen. Copy states plainly: "enables ADB on the connected head unit".
- We write `adb_keys` and enable adbd **only** on the HU the user explicitly
  connected to and confirmed. No background/auto execution.
- This is the user's own vehicle; the tool is a provisioning aid, not an
  exploit against third-party devices. No obfuscation, no hiding of what runs.

## What's left to the builder

- Exact `adb_keys` owner/mode on G426 and the live adbd-restart sequence
  (validate on the car; `demo()`-style check: after unlock, `dadb.create`
  connects without a dialog).
- dadb version pin + transitive-dep review (okio/bouncycastle); add to
  `libs.versions.toml`.
- Whether to bundle the client APK or pull via `UpdateChecker` (default:
  bundle in `assets/`).
- UI polish / strings (RU + EN), theming to match existing `view_settings`.
- ICMPv6 Echo packet construction + timeout tuning.

## Test (one runnable check per non-trivial piece)

- ICMPv6 Echo packet builder: assert-based self-check (checksum/placement) in a
  small `test_*`.
- Install-flow state machine: unit test the "skip if same version" decision.
- Manual on-car acceptance: enable ADB → install client → client launches and
  feeds mock GPS.
