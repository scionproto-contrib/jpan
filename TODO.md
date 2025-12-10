# TODO

## Ideas
- Add an expiration date to topo files?
  -> BRs, CS, DSs may change!
  -> Should we use the TTL of the DNS NAPTR record?

## Plan

### Soon
- Allow disabling local address resolution in DNS to local-IA. Resolving 127.0.0.x to
  localIA is fine for many purposes, but it can be confusing when testing a local
  mock-network (tiny, minimal, ...)
- SCMP errors handling (above)
  - Especially for type ¨5: External Interface Down" and "6: Internal Connectivity Down"
    Problem: we need to receive() or read() to actually receive SCMP errors.
    We could do this concurrently (actually, this would probably block writes),
    or we do this only if the user calls read/receive. We can then store the failure info
    (path + AS/IP/IF of failure location). During next send/write, we compare the 
    path against this failure and try to find a better one. If no better path is found
    we can just drop the packet (default; consistent with UDP behavior) or throw an error. 
    Also: The list of broken paths should be cleaned up once the path is expired (or earlier?). 
- Look into contacting _all_ path servers! They may have different paths! -> Check! 
- SCION-Proto questions:
  - FIX: Ask why requesting an UP segment effectively returns a DOWN segment
    (it needs to be reversed + the SegID needs to be XORed)
  - Why are Java pings 8 bytes shorter than scionproto pings? -> local AS
- Segments:
  - Sorting by weight (see graph.go:195)
  - Consider peering
  - Look at newDMG (graph.go:89)
  - Order by expiration date? (netip.go:41)
- Selector support
  - Implement interfaces from nio.DatagramChannel
  - Look into Selectors:  https://www.baeldung.com/java-nio-selector
- Consider subclassing DatagramChannel directly.
- Extend NAT support with on-demand feature: Instead of sending keep-alives, we send
  STUN packets only during send() and only when the NAT is assumed to have expired.
  Downsides:
  - Small delay before sending a packet
  - Quite hard to implement, the receive() may be blocked, and may be unblocked() at any time
  - Works only for the sender, not for the receiver
- AS switching: handle localIsdAs code per Interface or IP
- Path expiry: request new path asynchronously when old path is close to expiry
  Reconsider handling of expired path on server side. Try requesting a new path?
  Throw exception? Callback?
- DNS with other options, see book p328ff, Section 13.2.3
- Fuzzing -> e.g. validate()
- For stand-alone path query, we should cache localAS->localCore paths.
- For stand-alone, fill meta/proto properly
- Consider removing DEFAULT ScionService?
- Make ScionService AutoCloseable? -> Avoid separate CloseableService class and it's usage in try().
- Convenience: Implement Transparent service that tries SCION and, if not available,
  returns a normal Java UDP DatagramChannel? Which Interface?
- Transparent fallback to plain IP if target is in same AS?
- https for topology server?
- Secure DNS requests?
- Make multi-module project for demos & inspector (also channel vs socket?)
  -> see JDO for releasing only some modules for a release.
- Support authentication for control servers
- CHeck Socket considerations in Design.md: Java 15 socket, copy buffers, ...

- Remove SHIM
  - Once SHIM is gone: REMOVE topofile requirement from SCIO server:
    Border routers will always use the same port for send/receive
    (at least when communicating with hosts). They do split port only for
    BR to BR communication
- Remove "public" keyword from topology JSON parser

#### Other
- Truncate PROTO files?
- TEST concurrent path/as/DNS lookup
- TEST concurrent use of single channel.
- IMPORTANT: In non-blocking mode, the channel should probably block if it received a partial Scion-header?
  Or not? This would be an attack, send a partial header would block the receiver....
  We could just buffer a partial header until it is complete...

### Later
- Multipathing
- EPIC, Hidden paths
- SPAO end-to-end option -> Later, not used at the moment
- MAC validation?
- Replace Protobuf:
  - Protobuf-lite: https://github.com/protocolbuffers/protobuf/blob/main/java/lite.md
  - QuickBuffers: https://github.com/HebiRobotics/QuickBuffers/tree/main/benchmarks
  - FlatBuffers
- Reproducible build
- RHINE?

###
- Remove methods that are not required anymore:
  - configureRemoteDispatcher() 

## Then

- Multipathing: We probably ignore that for now. Multipathing can be done in
  many different ways, it may be difficult to design a one-size-fits-all API.
  E.g. "Hercules" uses a round-robin fashion with multiple path to fire UDP packets. 

- For Android look into
  - android.net.Network: 
    https://developer.android.com/reference/android/net/Network.html#openConnection(java.net.URL)
  - android.net.ConnectivityManager: 
    https://developer.android.com/reference/android/net/ConnectivityManager#requestNetwork(android.net.NetworkRequest,%20android.app.PendingIntent)
  - WiFi-Direct: 
    https://developer.android.com/develop/connectivity/wifi/wifi-direct#java

## After that
- Integrate with bazel -> Simplifies integration of go testing topology.
- SECURITY: java.net.AbstractPlainDatagramSocketImpl contains a lot of 
  Security checks (see also class javadoc). Do weed need these? Isn't this
  handled by the underlying DatagramChannel? Isn't this deprecated in Java 17?
  -> Simply declare that we only support JDK 17+ for this reason?

# General TODO

## Style

- Use puppycrawl checkstyle plugin
  - to verify style in CI
  - for auto formatting in IntelliJ (if possible). How about other IDEs, e.g. MS code?
- Change line length to 120 


## Testing
- Fuzz test
- Large packets that require Splitting
- Interleaved response on server, e.g. Receive from A, Receive from B, send to B, send to A (see also NIO)
- Test MTU exceed with proper network
- Test SCMP handling, see Design.
- Test general: Test that me make a minimum of gRPC calls, e.g. to get path from daemon 

## Reconsider tooling
- Documentation
  - There seems to be no documentation specifying the latency unit. [ms]?
- Daemon.proto
  - Expiration is a 96bit Timestamp, optimize?
  - Latencies are 96bit Durations, optimize?
 
