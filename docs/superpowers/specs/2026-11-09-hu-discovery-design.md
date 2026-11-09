# LocSync Server — Head-unit auto-discovery (ICMPv6 + mDNS)

Date: 2026-11-09
Status: Reviewed by Sonnet 5.5 (approve-with-fixes) and implemented (ICMPv6 +
IPv4). mDNS deferred per review finding 8. Not yet built/on-car tested.
Module: `server-app` (phone, Java, minSdk 24)
Depends on: `2026-11-09-hu-bootstrap-design.md` (the hidden bootstrap screen)

## Review outcome (Sonnet 5.5) and what was applied

Verdict: approve-with-fixes. All fixes applied in `HuDiscovery.java`:

- **Bind to the Wi-Fi network** (blocking finding 1): `registerNetworkCallback`
  with a `TRANSPORT_WIFI` request, then `network.bindSocket(fd)` for the ICMP
  socket and `network.getSocketFactory()` for every TCP probe — never
  `getActiveNetwork()` (which returns cellular/VPN for an internet-less HU AP).
- **Reliable sweep** (blocking finding 2): send 3 echoes ~300 ms apart; loop
  `recvfrom` to an absolute deadline; `EAGAIN` continues, not aborts; filter out
  our own link-local addresses (`LinkProperties.getLinkAddresses()`) so
  multicast loopback isn't mistaken for a neighbour; check reply **type 129**.
- **Scope id** (finding 6): `Os.if_nametoindex(name)` + `Inet6Address.getByAddress(
  null, bytes, ifIndex)` — no `NetworkInterface` lookup (restricted on 11+).
- **MulticastLock cut** (finding 5): replies are unicast, so it isn't needed.
- **mDNS deferred** (finding 8): most unspecified behaviour; the IPv6 sweep is
  the real win. When added, follow finding 4 (single-flight resolve queue, drain
  with ~8 s cap, resolved port not `:5555`, trailing-dot type, attach scope).
- Note (finding 7): Android 17 adds `ACCESS_LOCAL_NETWORK` for targetSdk 37;
  fine at targetSdk 36, revisit on bump.

Caveat (reviewer's own): these rest on kernel/AOSP knowledge, not a device run.

## Intent / why

The bootstrap screen v1 finds the head unit only by **manual `fe80::…%wlan0`
paste** or an **IPv4 `/24` :23 scan**. On these units the root telnet binds
`[::]:23` and is reached over **IPv6 link-local**, which the user must read out
of PingTools and type — the main friction point. This feature auto-discovers
the head unit so the user taps once instead of copying an address.

Two independent mechanisms, both unprivileged (non-rooted phone):

1. **ICMPv6 neighbour sweep** — find link-local neighbours (incl. the telnet
   host) the way PingTools does.
2. **mDNS** — find an already-enabled wireless-debugging adbd directly.

## Goals

1. **Find the telnet host** (`fe80::…%wlan0`, port 23 open) with no typing,
   over IPv6 link-local.
2. **Find an adbd host** (`_adb-tls-connect._tcp`, typically `:5555`) when
   wireless debugging is already on — lets the install path skip telnet.
3. Present found hosts as a tappable list; **manual paste stays** as the
   guaranteed fallback.
4. Degrade cleanly where an OEM blocks ICMPv6/mDNS.

## Non-goals

- No raw sockets / root / `CAP_NET_RAW`.
- No reading `/proc/net/*` or `ip neigh` (blocked for apps on Android 10+).
- Not a general network scanner UI; only enough to locate the HU.

## How it works — decided design

### A. ICMPv6 link-local neighbour sweep

Non-rooted Android permits **ICMP datagram sockets** (app GIDs are in
`net.ipv4.ping_group_range`; same mechanism as `ping` and
`InetAddress.isReachable`). So:

1. Resolve the Wi-Fi interface from `ConnectivityManager` →
   `LinkProperties.getInterfaceName()`; get its index via
   `NetworkInterface.getByName(name).getIndex()`.
2. `fd = Os.socket(AF_INET6, SOCK_DGRAM, IPPROTO_ICMPV6)`; set `SO_RCVTIMEO`.
3. Build an **ICMPv6 Echo Request** (type 128, code 0, id, seq). For an ICMPv6
   datagram socket the **kernel fills the checksum**, so the checksum field is
   sent as 0.
4. `Os.sendto(fd, echo, 0, len, inet6("ff02::1", scopeId=ifIndex), 0)` — the
   all-nodes multicast on the Wi-Fi link.
5. Loop `Os.recvfrom` until the timeout; each reply's source is a neighbour's
   `fe80::…`. Dedupe.
6. Hold a `WifiManager.MulticastLock` across the sweep.
7. For each neighbour, TCP-connect `[fe80::x%wlan0]:23` (short timeout) →
   confirmed telnet host.

Scope-id handling: build `Inet6Address.getByAddress(null, bytes,
NetworkInterface)` so the `%wlan0` zone is attached for the TCP probe.

**Ceiling:** some OEMs (Samsung/MIUI) filter ICMPv6 multicast or outbound
`ff02::1`. Detect "zero replies" and fall back to the IPv4 scan + manual paste,
which always remain.

### B. mDNS (wireless debugging already on)

`NsdManager.discoverServices("_adb-tls-connect._tcp", PROTOCOL_DNS_SD, …)`:
each resolved service gives the adbd host + port directly (Android 11 wireless
debugging advertises it). Also resolve `_services._dns-sd._udp` is not needed.
Surface these as "ADB ready" entries that skip the telnet step.

Runs for a short bounded window (a few seconds), then stops the discovery to
save battery.

### Integration

`BootstrapActivity`'s **Find** button fans out, in parallel, on the io
executor:

- IPv6 ICMPv6 sweep → telnet candidates,
- mDNS → adbd candidates,
- the existing IPv4 `:23` scan.

Results merge into one list. A single candidate auto-fills the address field;
multiple → a chooser dialog. Each line says telnet (:23) vs adbd (:5555). The
manual field is never removed.

## Constraints

- `android.system.Os` + `OsConstants` (AF_INET6, SOCK_DGRAM, IPPROTO_ICMPV6,
  SO_RCVTIMEO) — all API 21+. minSdk 24 OK.
- Permissions already held: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`
  (for MulticastLock), `ACCESS_NETWORK_STATE`, `INTERNET`. No new permission.
- NsdManager is framework; no dependency.

## Security / safety

- Passive local-network discovery only (echo + mDNS + TCP connect). No payloads
  sent beyond ICMP echo and a TCP SYN. No data leaves the local link.
- Nothing is acted on automatically: discovery only fills an address the user
  then uses from the bootstrap screen.

## What's left to the builder

- ICMPv6 Echo packet byte layout + id/seq choice; recvfrom loop tuning.
- Merge/chooser UI in `BootstrapActivity`.
- Timeouts (sweep ≈1–2 s, mDNS ≈3 s), MulticastLock lifecycle.

## Test (one runnable check)

- `Icmpv6.buildEcho(id, seq)` → an `assert`-based self-check: header bytes
  (type=128, code=0, checksum=0 placeholder, id, seq) at the right offsets and
  correct length. Pure, no network, runs in a plain `test_*`.
