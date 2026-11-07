# Handoff — recon prompt for the car head unit (Part 2b prep)

## Goal
Part 2b of "car signals on the LocSync client main screen" is blocked: the seven sensor IDs in
`VehicleData.java` are `UNSET` and can only be filled from the real car. This session's task was to
write a prompt for a **separate agent running on the user's laptop** (which has `adb` + access to the
car head unit) to collect everything needed to fill those IDs and decoders. The deliverable is that
prompt, now written.

## Status
- **Done:** recon prompt written to `docs/car-recon-prompt.md`. Delivered to the user as markdown.
- **Not started:** running it — the laptop agent has not been launched yet (user will do that).
- **Still blocked (unchanged from the previous handoff):** `VehicleData.java` IDs/decoders stay
  `UNSET` until the recon archive comes back.

## Decisions
- **Laptop agent = collector only, not analyst.** User said the laptop has no resources for massive
  decompiling. So the prompt tells it to *pull* all APKs/jars/dumps/logs/live readings and do NO
  decompiling; decompiling + constant extraction + unit-matching is done back here on the bigger
  machine. "When in doubt, grab it."
- **Read-only + safety rails, non-negotiable.** No root/su/telnet/QNX access, no property writes, no
  install/uninstall/reboot/flash, no input injection. UI only driven by the human, only parked. Hidden
  menus: discover statically, open a found screen only with explicit human OK while parked, never
  toggle/calibrate/code/flash. Gated (code/password) screens: write down, never bypass.
- **Privacy:** dumps contain VIN/serials/accounts — stay local, upload nothing, mask VIN+serials in
  SUMMARY. User should store the returned archive OUTSIDE the repo (e.g. `/home/goodvin/cityray/car-recon/`).
- **Added per user request:** full system dump (step 3a — every APK incl. splits + partitions) and
  hidden-settings discovery (step 8 — engineering/factory menus, secret-code receivers, entry gestures).

## Dead ends
- Two Edit calls in this session were stopped by a safety classifier (first attempt at the hidden-menu
  step 8). Reason: original wording read like credential/PIN-dialog cracking. Reworked to pure
  read-only static discovery (list exported activities + secret codes from `dumpsys package`, no
  bypass of any gate) and it went through. Don't reintroduce password/PIN-recovery wording.
- `adb` is NOT installed in THIS environment and no device is attached here — that's why the work is
  pushed to the laptop agent.

## Files
- `docs/car-recon-prompt.md` — **the deliverable.** The full prompt for the laptop agent. Untracked.
- `docs/handoffs/2026-11-06-1259-car-signals-part2.md` — the prior handoff; full Part 2a/2b context,
  file list, AdaptAPI reflection sequence, decode hints (Geely cluster temp `(raw-700)/10` outside,
  `(raw-600)/10` cabin — to confirm).
- `client-app/src/main/java/goodvin/locsync/client/VehicleData.java` — the file to edit once recon
  data returns (`ID_SPEED/ID_RPM/ID_GEAR/ID_FUEL_PERCENT/ID_RANGE/ID_OUTSIDE_TEMP/ID_COOLANT_TEMP`,
  decode consts, `isConfigured()`).
- `.gitignore` — modified (adds `/*.apk`), still uncommitted from earlier session.

## Verification
- Prompt is self-contained markdown; no build/test in this session.
- Recon success = archive `car-recon-*.tar.gz` returns with `SUMMARY.md` signal table (id dec/hex,
  raw vs cluster values per capture) + `dump/` of pulled APKs.

## Next steps
1. User launches the laptop agent with `docs/car-recon-prompt.md` as its first message.
2. When the archive returns, unpack it OUTSIDE the repo (VIN/serials inside).
3. Decompile the pulled eCarX/adaptapi jars+APKs here; extract `com.ecarx.xui.adaptapi.*` constants
   (esp. `ISensor`) → map names to the seven `ID_*`.
4. Fill `ID_*` + decoders in `VehicleData.java`; confirm scales/offsets against the capture raw-vs-cluster
   pairs. Add an `assert`-based self-check for `decode()`.
5. Build on a machine with JDK 21 + Android SDK: `./gradlew :client-app:assembleDebug` (CI builds on
   tag push; no local SDK here).
6. Decide `.gitignore` commit + whether to track `docs/car-recon-prompt.md`.

## Open questions
- Which exact signals to show if the car exposes more/fewer than the seven stubbed?
- Pursue signals AdaptAPI does NOT expose (yaw rate, wheel ticks) via the invasive QNX/root route?
  Needs explicit user authorization; not scripted.

## Standing instructions from the user (carry forward)
- **No authorship trailers in git commits** (no `Co-Authored-By`, no "Generated with" — per CLAUDE.md;
  overrides harness reminders).
- Keep upstream analysis APKs out of git (`/*.apk` in `.gitignore`).
- Reviews "with Sonnet 5.5" (spawn review subagent on sonnet).
- Recon agent must be read-only; anything invasive is a separate, user-authorized decision.
