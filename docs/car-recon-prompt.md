# Task: read-only reconnaissance of a Geely / eCarX car head unit over adb

## Context
You are on a laptop with `adb` access to the Android head unit (in-vehicle infotainment, IVI) of a
Geely car. The unit is built by eCarX. I am building an Android app, the LocSync client, that runs **on this head unit**
and wants to show car data: speed, gear, rpm, fuel %, range, outside temperature, coolant temperature.
It may later also use other signals for dead reckoning: yaw rate, wheel speeds, steering angle,
acceleration, odometer.

The app reads car data through the eCarX vendor SDK, called by reflection:
`com.ecarx.xui.adaptapi.car.Car.create(context)` → `getSensorManager()` →
`ISensor.getSensorEvent(int sensorId)`, which returns a `Number`. `Integer.MIN_VALUE` means "no value".
The only ID known so far is `SENSOR_IGNITION = 0x200100 (2097408)`.

**Goal:** collect as much information as you can about which car signals exist on this unit, their
numeric IDs, their constant names, who can read them (permissions), and their raw values next to
what the instrument cluster shows.

**Your role is collection, not analysis.** You pull dumps, APKs, jars, logs and live readings; the
heavy lifting — decompiling the APKs, extracting the `com.ecarx.xui.adaptapi.*` constants, matching
raw values to units — is done by me (the requesting agent) on a bigger machine afterward. So: do not
decompile, do not install analysis tools on the car, and when in doubt whether something helps, grab
it. Raw dumps first, light interpretation only where it's free (grep over text the device prints).

## Hard rules (non-negotiable)
- **Read-only.** Do not run `su`, try to get root, use telnet or ssh into other ECUs or the QNX side, crack or
  use credentials, `setprop`, `settings put`, `cmd car_service set-property*` or any property *write*,
  `pm uninstall/disable/clear`, `am force-stop` on system packages, `reboot`, `remount`, flash, or
  install anything on the head unit. Do not inject input events (`input tap/keyevent`).
- If a command looks like it could change car or unit state, **skip it and write it down** in the summary.
- **Hidden menus (step 8):** find them by static analysis first. You may open a found screen with
  `am start` only when the car is **parked**, the human agrees to that specific screen, and the
  human (not you) does any navigating. Look and screenshot only. Never toggle, save, "apply",
  calibrate, reset, code, or flash from an engineering or dealer menu. Never fire secret-code
  broadcasts (`android_secret_code://`) yourself: list them and let the human decide.
- **Safety:** the person driving never touches the laptop. Ask for driving phases only when a
  passenger can run the captures, or capture with the car parked and the engine idling.
- **Privacy:** the dumps will contain the VIN, serials, account names and maybe Wi-Fi info. Keep everything
  local. Upload nothing anywhere. Mask the VIN and serials in `SUMMARY.md` (raw files can stay raw).
- If a command fails or is denied, save the error output and move on. Don't fight it.

## Output
Put everything in `car-recon-<YYYYMMDD-HHMM>/` on the laptop. Use one file per command, named after
the command. At the end, write `SUMMARY.md` (format below) and pack the folder as `car-recon-*.tar.gz`.
Keep a `commands.log` that lists every command you ran and its exit code.

## Steps

### 1. Identity and platform
```
adb devices -l
adb shell getprop                      > getprop.txt
adb shell uname -a; adb shell cat /proc/cpuinfo /proc/meminfo
adb shell pm list features             > features.txt
adb shell pm list packages -f -U -i    > packages.txt
adb shell pm list libraries            > libraries.txt
adb shell service list                 > service_list.txt
adb shell dumpsys -l                   > dumpsys_list.txt
adb shell lshal                        > lshal.txt     # may fail, fine
adb shell id; adb shell whoami
```
Record the Android version, SDK level, build fingerprint, and whether it is Android Automotive
(`android.hardware.type.automotive` feature) or plain Android with vendor car services.

### 2. Vehicle HAL / car service (most important)
```
adb shell dumpsys car_service                          > car_service.txt
adb shell cmd car_service help                         > car_service_help.txt
adb shell cmd car_service get-carpropertyconfig        > carpropertyconfig.txt
adb shell dumpsys car_service --hal                    > car_service_hal.txt   # may not exist
adb shell dumpsys android.hardware.automotive.vehicle.IVehicle/default > vhal_aidl.txt  # may not exist
```
If `get-property-value` exists, read (do not write) every property listed as READ or READ_WRITE:
`adb shell cmd car_service get-property-value <propId> [areaId]`. Do this for at least: speed,
gear, rpm, fuel level, range, outside temp, coolant/engine temp, odometer, ignition, steering angle,
wheel ticks, yaw rate, acceleration, plus anything else that looks dynamic.

### 3. eCarX vendor services and SDK
```
adb shell dumpsys -l | grep -iE 'ecarx|geely|vehicle|car|sensor|adapt'
adb shell pm list packages | grep -iE 'ecarx|geely|adapt|car'
for d in /system/framework /system_ext/framework /vendor/framework /product/framework; do
  adb shell ls -la $d; done                            > framework_ls.txt
```
- `dumpsys` **every** eCarX/vehicle-related service found above, one file each. These often print
  live signal values.
- Pull every jar and APK whose name matches `ecarx|adapt|xui|geely|vehicle|carservice`, from framework
  dirs and via `adb shell pm path <pkg>`, into `pulled/`. **Just pull the files — do not decompile
  anything.** Decompiling and reading the bytecode is my job on the other machine; your job is to
  collect everything that might help. When unsure whether a file is useful, pull it.
- Find which app or service implements the AdaptAPI sensor manager, and which **permissions** it enforces
  (`adb shell dumpsys package <pkg>`). This tells us whether a normal third-party app can read the signals.
- Also `adb shell dumpsys package com.dashboard.ru` (my other app, which already reads car data) to see the
  permissions it was granted.

### 3a. Full system dump (for offline analysis)
Pull every installed package and the readable system partitions so I can decompile them later on my
machine. **You only collect — no decompiling on the laptop.** Check free space first (expect several GB).
```
adb shell pm list packages -f -s -U   > packages_system.txt
adb shell pm list packages -f -3 -U   > packages_user.txt
# every APK (including split APKs) + its dumpsys, one dir per package:
for p in $(adb shell pm list packages | cut -d: -f2 | tr -d '\r'); do
  mkdir -p dump/apks/$p
  for a in $(adb shell pm path $p | cut -d: -f2 | tr -d '\r'); do adb pull "$a" dump/apks/$p/; done
  adb shell dumpsys package $p > dump/apks/$p/dumpsys_package.txt
done
# readable partitions (framework jars, system apps, overlays, configs):
for d in /system/framework /system/app /system/priv-app /system/etc \
         /system_ext /product /vendor/app /vendor/framework /vendor/etc /odm; do
  adb pull "$d" "dump$d" 2>&1 | tail -1; done
adb shell 'cat /system/build.prop /vendor/build.prop /product/build.prop' > dump/build_props.txt
```
Pull errors on unreadable files are expected — log them and continue. Write `dump/INDEX.tsv` with
columns `package  versionName  path  sizeKB  isPrivileged  sharedUserId`. This dump is also the base
for step 8.

### 4. Sensors, location, GNSS
```
adb shell dumpsys sensorservice     > sensorservice.txt
adb shell dumpsys location          > location.txt
adb shell dumpsys gnss              > gnss.txt          # may not exist
adb shell ls -la /dev | grep -iE 'tty|gnss|gps|can|spi|i2c|iio'   > dev_ls.txt
adb shell ls /sys/bus/iio/devices   > iio.txt
```
Report whether the unit has its own gyroscope or accelerometer, which GNSS chip and rate it uses, and
whether it does dead reckoning (look for `DR`, `fused`, `odometer` or `wheel` in the location/GNSS dumps).

### 5. Logs
```
adb shell logcat -d -b all          > logcat_all.txt
adb shell logcat -b all -v threadtime > logcat_live.txt   # run ~60 s during the driving phase, then Ctrl-C
```
Grep for tags and lines that mention `ecarx|adapt|sensor|vehicle|vhal|speed|gear|fuel|temp|odo`, and
write the interesting tag names into the summary.

### 6. Network topology (observe only, connect to nothing)
```
adb shell ip addr; adb shell ip route; adb shell ip neigh
adb shell cat /proc/net/tcp /proc/net/tcp6 /proc/net/udp   > net_sockets.txt
adb shell ss -tulnp                                        # may fail without root
```
Point out internal links (for example to the QNX cluster: private subnets, `eth`/`vlan` interfaces).
**Do not** open connections to them.

### 7. Live calibration captures
Repeat the dynamic reads (VHAL `get-property-value` for the relevant properties, plus any eCarX
service dumps that show live values) in each state below. For each capture, write down **what the
instrument cluster shows** (speed, rpm, gear, fuel %, range, outside temp, coolant temp) and the time:
1. Ignition on, engine off, parked.
2. Engine idling, P.
3. Engine idling, D, brake held.
4. Moving at a steady ~30 km/h, then ~60 km/h. **Passenger runs the captures, driver drives.**
5. Optional: a slow full-lock turn in a parking lot (for steering angle and yaw rate).

Save these as `capture_<n>_<state>.txt`, with the cluster readings at the top of each file.

### 8. Hidden settings and engineering menus (discover, don't operate)
This is the user's own head unit; the goal is to find factory/engineering screens and any hidden
entry gesture, so the user can decide whether to open them. **Discovery is static. Do not drive the
UI yourself and do not change anything.**

**A. Collect from the device (no decompiling — that's my job on the pulled APKs):**
- List activities the device already exposes without decompiling:
  `adb shell cmd package query-activities -a android.intent.action.MAIN > activities_main.txt` and
  `adb shell dumpsys package > dumpsys_package_all.txt`. From these, pull out every activity whose
  name or label matches `engineer|factory|debug|dealer|diag|test|hidden|develop|service|mode|calib|hmi|vehicle|can|vin`
  into `hidden_activities.tsv` (`package  activity  exported  permission  label`). This is a grep over
  text the device prints — no bytecode needed.
- Secret-code receivers also show in `dumpsys package` (action `android.provider.Telephony.SECRET_CODE`,
  data `android_secret_code://<digits>`). List the code + target in `secret_codes.tsv`.
  **Just list them — do not broadcast any code.**
- Make sure the APKs I'll need to decompile are in the step-3a dump: Settings, launcher, SystemUI, and
  every eCarX/Geely app. Confirm each is present under `dump/apks/`; if `pm path` showed one that
  didn't pull, note it. **I decompile these and hunt the tap-counter / multi-finger / corner gestures
  myself** — you just guarantee the files are here.
- If a screen looks gated by a code or password, **write it down** (which screen, what kind of gate).
  Do not try to recover, guess, or bypass it.

**B. Optional, only with the user present and the car parked:**
- The user may try a documented gesture from part A on the touchscreen themselves. You observe and
  screenshot (`adb exec-out screencap -p > shot_<name>.png`).
- An exported, permission-free activity may be opened read-only with
  `adb shell am start -n <pkg>/<activity>` **only** after the user approves that specific screen and
  only while parked. Screenshot it. **Do not** tap, toggle, save, apply, calibrate, reset, code, or
  flash anything inside. If a screen warns it will change vehicle config, back out.

## SUMMARY.md format
1. **Platform:** Android version, SDK, AAOS or not, build fingerprint (masked), SoC.
2. **Signal table**, one row per signal found (wanted ones first, then everything else that looks useful):

   | signal | source (AdaptAPI const / VHAL prop / other) | id dec | id hex | readable by 3rd-party app? (perm) | raw @ capture 1..5 | cluster @ capture 1..5 | guessed scale/offset/unit |

3. **Wanted signals not found:** list them, with where else they might live (for example the QNX side).
4. **Sensors/GNSS:** onboard IMU yes/no, GNSS chip, rate, any DR or fused provider.
5. **Vendor services:** names, packages, enforced permissions.
6. **Network:** interfaces and subnets that look internal or point to the cluster.
7. **Hidden settings:** the `hidden_activities.tsv` / `secret_codes.tsv` findings, plus screenshots of
   any screen opened in part B. Flag which entry points are gated. Confirm Settings/launcher/SystemUI/
   eCarX APKs are in the dump for me to decompile.
8. **Full dump:** confirm `dump/` and `dump/INDEX.tsv` are complete; note packages that failed to pull.
9. **Skipped / failed commands** and why.
10. **Surprises:** anything odd or useful.
