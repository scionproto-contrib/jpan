# SNAP channel abstraction

SNAP (Anapaya SNAP) tunnels SCION traffic through a WireGuard-style encrypted UDP dataplane instead
of a plain SCION underlay. JPAN integrates this by giving `AbstractScionChannel` a nullable SNAP
underlay: when present, sending/receiving/closing dispatch through it instead of a raw
`DatagramChannel`, so `ScionDatagramChannel` and `ScmpSenderAsync` need no SNAP-specific logic of
their own.

## Architecture

### Channel abstraction

- `AbstractScionChannel` holds a nullable `snapUnderlay` field (`org.scion.jpan.internal.snap
  .SnapUnderlay`). `sendUnderlay()`/`receiveUnderlay()`/`close()` dispatch on
  `snapUnderlay != null`; when null, they fall through to the plain `DatagramChannel`.
- `SnapUnderlay` wraps a `SnapTunnelSession` and exposes only `send`/`receive`/`close`/
  `transportChannel()`/`ensureConnectedSourceAddress()` -- raw send/receive always goes through the
  session's `sendPacket()`/`receivePacket()`, so encryption can't be bypassed by accident.
- `SnapTunnelSession` owns the real, OS-backed transport `DatagramChannel`, performs the
  Noise/WireGuard-style handshake (hand-rolled on Bouncy Castle X25519/ChaCha20Poly1305/Blake2s
  primitives), and tracks the server-assigned local tunnel address.
- `SnapUnderlaySupport` (public, in `org.scion.jpan.internal.snap`, alongside `SnapUnderlay`/
  `SnapTunnelSession`/`SnapControlClient`) builds a `SnapUnderlay` from already-resolved values --
  a `SnapDataplaneDetails` and a `DatagramChannel` -- rather than from a `ScionService` directly.
  Callers in `org.scion.jpan` (`ScionDatagramChannel`, `ScmpSenderAsync`) fetch
  `service.getSnapDataPlane()` themselves and pass the result in, so `SnapUnderlaySupport` never
  needs cross-package access to any of `ScionService`'s package-private members.

### Socket reuse and binding

- `ScionDatagramChannel.Builder.open()` and `ScmpSenderAsync.Builder.build()` both open their outer
  `DatagramChannel` via `SnapUnderlaySupport.openChannelFor(service)`, which forces
  `StandardProtocolFamily.INET` when SNAP is active -- the dataplane is IPv4-only, but a
  family-less `DatagramChannel.open()` can non-deterministically come back IPv6/dual-stack and
  silently break the handshake.
- That same outer channel is reused as SNAP's real transport
  (`SnapUnderlaySupport.createFor(service.getSnapDataPlane(), channel)`, always -- one socket per
  channel, not two). `SnapTunnelSession` adopts an externally-provided channel without binding it;
  binding stays entirely the caller's job via the normal `ensureBound()` path.
- SNAP channels bind exactly like any other channel: the SCION dispatcher port range or an explicit
  `--port` is honored unconditionally, with no SNAP special-casing. This is deliberate: a
  SNAP-assigned tunnel address is a real, externally-addressable SCION endpoint reachable by other
  SCION hosts directly (unlike a NAT), so a stable local port matters for reachability across
  restarts.
- Operational note: the real SNAP dataplane appears to reject a handshake that reuses the exact
  same local port too soon after a prior session on that port (observed live; not root-caused with
  certainty, likely a server-side "no immediate reuse" policy). Only rapid back-to-back runs on the
  same fixed port trigger this -- a real long-running client that binds once at startup never will.

### Source address

`AbstractScionChannel.ensureSnapSourceAddress()` (called from `buildHeader()`) ensures the
WireGuard handshake has completed and installs the server-assigned tunnel address as the SCION
source address override (`overrideExternalAddress`) -- bypassing `NatMapping`/STUN entirely for
SNAP channels, both of which know nothing about the tunnel and would otherwise report the wrong
(pre-tunnel) address.

### Dataplane resolution

Each `ScionService` resolves at most **one** SNAP dataplane, once, at bootstrap:
`initializeSnapDataPlaneIfEnabled(localAS)` calls `SnapControlClient.create(localAS)` (a static
factory that resolves the control endpoint itself -- an explicit `org.scion.snap.controlPlane`
override, else the first entry of `localAS.getSnapControlNodes()`), then
`SnapControlClient.getDataPlaneAddress()` to fetch the dataplane's UDP address and WireGuard
static key. The result (a `SnapDataplaneDetails`, held as `ScionService.snapDataplaneDetails`) is
what every channel built from that service uses to construct its `SnapTunnelSession`.

`LocalAS.getBorderRouterAddress(interfaceId)` is a plain, strict border-router lookup: it throws if
the interface ID isn't found. `LocalAS` carries no SNAP-specific state at all -- it is purely
immutable, derived data from the endhost API's response (`getSnapControlNodes()` returns the raw
`SnapControlNode` list, nothing more). The "first hop" needed for `PathMetadata`/`RequestPath` is
resolved where it's actually used instead: `RequestPath.create(metadata, dstIP, dstPort, localAS)`
calls `getBorderRouterAddress()` when `localAS.getBorderRouters()` is non-empty, and simply leaves
the first hop `null` otherwise (a SNAP-only tenant AS with no native border routers) -- no
SNAP-specific fallback needed on `LocalAS` at all. A SNAP channel's actual first hop for sending
traffic never comes from this path anyway; it comes from its own `SnapTunnelSession`, resolved
independently once the handshake completes.

## Known limitations

- **`ScmpResponder` has no SNAP support.** It extends `AbstractScionChannel` directly and would
  need the same treatment as `ScionDatagramChannel`/`ScmpSenderAsync` if inbound SNAP traffic is
  ever needed.
- **Tunnel-construction logic is duplicated**: `ScionDatagramChannel.Builder.open()` and
  `ScmpSenderAsync.Builder.build()` each independently call
  `SnapUnderlaySupport.openChannelFor()`/`createFor()`, rather than sharing one call site.
- **Only one SNAP dataplane per `ScionService`** -- the more fundamental gap, detailed below.

### Multiple SNAP nodes per AS are not supported

The endhost API's `ListUnderlays` response can advertise *more than one* SNAP node
(`Underlays.SnapUnderlay.getSnapsList()`), each potentially scoped to a different reachable ISD/AS
set -- e.g. a host with connectivity to two different tenant ASes through two different SNAP nodes.
JPAN currently only ever looks at the first one:

- `LocalAsFromPathService.getLocalIsdAsFromSnap()` reads `u.getSnap().getSnaps(0)` exclusively when
  determining the local AS's reachable ISD/AS set.
- `SnapControlClient.create(localAS)` picks `snapControlNodes.get(0)` when no explicit
  `org.scion.snap.controlPlane` override is set.
- `ScionService` resolves and holds exactly one `SnapDataplaneDetails` (one dataplane address plus one
  static key) for its entire lifetime.

Every additional `SnapControlNode` beyond the first is preserved as raw data
(`LocalAS.getSnapControlNodes()` returns the full list), but never turned into a usable dataplane
connection or reflected in the local ISD/AS set. In practice, a host with two SNAP nodes serving
disjoint ISD/AS sets can only ever reach whichever AS the first-listed node serves -- the second is
silently unreachable. This is pinned down by
`LocalAsFromPathServiceTest.create_multipleSnapNodes_onlyFirstIsdAsSetIsUsed_knownLimitation()`.

A real fix needs `ScionService` to resolve and hold a `SnapDataplaneDetails` **per relevant `SnapControlNode`**
(keyed by reachable ISD/AS) rather than a single field, plus a lookup at `send()`/path-resolution
time to pick the right one based on the destination's ISD/AS -- which isn't known until a specific
`Path`/destination is in hand, not at `ScionService` bootstrap time. This is a materially bigger
change than anything else in this document: a single channel sending to two different tenant ASes
would need two different tunnels, so a channel's `SnapUnderlay` can likely no longer be fixed once
at channel-construction time. Not implemented; no target date.
