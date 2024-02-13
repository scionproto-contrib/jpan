# TODO
- Handle expired paths
  - Provide callback for user? -> Maybe useful if path was selected manually
    and if it changes.
  - Handle SCMP reporting a failed path -> request new one!
- Test 
  - PathPolicies
- Truncate PROTO files! 
- TEST router failure: ....?  MTU too big, TTL run out, packet inconsistent, ...?
- MOVE Channel to ".channel"?
- TEST concurrent path/as/DNS lookup
- TEST concurrent use of single channel.
- IMPORTANT: In non-blocking mode, the channel should probably block if it received a partial Scion-header?
  Or not? This would be an attack, send a partial header would block the receiver....
  We could just buffer a partial header until it is complete... 
- Merge Scion + ScionService?
- Remove slf4j from API?
- Why ScionAddress? ScionAddress has two properties:
  - It has Isd/As info
  - It represents an IP from a DNS/TXT lookup!

## Reconsider bind/connect
- We should be able to do any off bind(null), bind(port), bind(IP, port)
- Problem: connect() ESTABLISHES a connection to a specific IP/port, disallowing
  incoming packets from any other source.
  - (WILL BE FIXED:) This is problematic for ping, where answers come via dispatcher
  - ....?
- Multipath: connect to multiple BRs...??!?!?!
- Multi AS: connect to 

- PING: It is fine to manually specify local IP (as scion ping command does)

# TODO #2
- TODO Fix: getPath for src&dst in same AS-ISD
  What does daemon return as first hop in this case:
  - The destination IP?
  - Still the BR IP, and then forwards it? 
    -> check behaviour of TINY  
- TODO setTTL or similar
- TODO test SCMP
- TODO test Bootstrapping
- TODO document stand-alon feature (no daemon or local installation required)

## Known Shortcomings

- ScionService always returns the same localIsdAs number, even when the interface changes or
  a new IP is assigned. How does it work with mobile phones?
  Is assigning new IPs a thing? 

## Questions
- What to do on server if path is expired? Currently we just don't check and send anyway.
- Should I have separate ServerChannel & ClientChannel classes
  - ServerChannel has receive()/send()/bind()
  - ClientChannel has read()/write()/connect()
  - This would be cleaner but may be confusing and
- Related to previous: Should receive()/send() be specific about RequestPath/ResponsePath?
  I.e. hide complexity vs being specific....   check Effective Java?   

## Ideas
- Why are CSs distributing Segments?
  Because that is what beacons return? For historical reasons?
  It seems much more efficient to distribute a graph 
  (less bandwidth, can be directly digested by the daemons, possibly more complete than the segments)
  We may also choose do distribute the WHOLE graph (depending on how big it is), the daemons can use it
  for *all* path queries.
  Updates/refresh (after changes/expiration) are also cheaper, we don´t resend the whole segment but
  only the expired parts.
- Add an expiration date to topo files!
  -> BRs, CS, DSs may change!
  -> Should we use the TTL of the DNS NAPTR record?

## Plan

### 0.1.0
- SCMP error handling (only error, not info)
  - BUG: Ping to iaOVGU causes 3 CS requests that have type UP,CORE,CORE....?
  - FIX: Ask why requesting an UP segment effectively returns a DOWN segment
    (it needs to be reversed + the SegID needs to be XORed)
  - FIX: API ping takes 4-5 extra milliseconds
- Docs:
  https://github.com/marcfrei/scion-time#setting-up-a-scion-test-environment
  https://github.com/netsec-ethz/lightning-filter#develop
Discuss required for 0.1.0:
- SCMP errors handling (above)
  - Especially for expired paths / revoked paths / broken paths?  

### 0.2.0
- Segments:
  - Sorting by weight (see graph.go:195)
  - Consider peering
  - Look at newDMG (graph.go:89)
  - Order by expiration date? (netip.go:41)
  - Consider shortcuts and on-paths (book sec 5.5, pp105 ff)
- Selector support
  - Implement interfaces from nio.DatagramChannel
  - Look into Selectors:  https://www.baeldung.com/java-nio-selector
- Consider SHIM support. SHIM is a compatibility component that supports
  old border-router software (requiring a fixed port on the client, unless
  the client is listening on this very port).  When SHIM is used, we cannot 
  get the return address (server mode) from the received packet because we receive it 
  from the SHIM i.o. the BR. Fix: Either have server use daemon of topofile to find
  first hop, OR extend SHIM to accept and forward packets to the correct BR.
- AS switching: handle localIsdAs code per Interface or IP
- Path expiry: request new path asynchronously when old path is close to expiry
- DNS /etc/scion/hosts e.g.:
  71-2:0:4a,[127.0.0.1]	www.netsys.ovgu.de netsys.ovgu.de
  71-2:0:48,[127.0.0.1]	dfw.source.kernel.org
- DNS with other options, see book p328ff, Section 13.2.3
- UDP checksum validation + creation
- Fuzzing -> e.g. validate()
- remove "internals" package?
- For stand-alone path query, we should cache localAS->localCore paths.
- For stand-alone, fill meta/proto properly
- Consider removing DEFAULT ScionService?
- Make ScionService AutoCloseable? -> Avoid separate CloseableService class and it's usage in try().
- Convenience: Implement Transparent service that tries SCION and, if not available,
  returns a normal Java UDP DatagramChannel? Which Interface?
- Transparent fallback to plain IP if target is in same AS?
- https for topology server?
- Secure DNS requests?
- Reconsider handling of expired path on server side. Try requesting a new path?
  Throw exception? Callback?
- Make multi-module project for demos & inspector (also channel vs socket?)
  -> see JDO for releasing only some modules for a release.
- Support authentication for control servers

### 0.3.0
- SCMP info handling: ping, traceroute, ...
- SCMP error _sending_ e.h. in case of corrupt packet
- DatagramSocket
  - Extent DatagramPacket to ScionDatagramPacket with ScionPath info?!?!
  - Add socket.send(packet, dstIsdAs); ?
  - It seems we cannot use DatagramSocketImpl for implementing a Scion DatagramSocket.
    Instead we may simply implement a class that is *similar* to DatagramSocket, e.g.
    with enforcing bind()/connect() before send()/receive() and enforcing that connect() uses
    ScionSocketAddresses. Problematic: how to handle path on server side?
- Reproducible build
- RHINE?

### 0.4.0
- Multipathing
- EPIC, Hidden paths
- SPAO end-to-end option -> Later, not used at the moment
- MAC validation?
- Replace Protobuf:
  - Protobuf-lite: https://github.com/protocolbuffers/protobuf/blob/main/java/lite.md
  - QuickBuffers: https://github.com/HebiRobotics/QuickBuffers/tree/main/benchmarks
  - FlatBuffers



## Then

- Multipathing: We probably ignore that for now. Multipathing can be done in
  many different ways, it may be difficult to design a one-size-fits-all API.
  E.g. "Hercules" uses a round-robin fashion with multiple path to fire UDP packets. 
- MulticastSocket / MulticastChannel (?)

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


## Design
- Where to place generated proto files? They are currently in `target` but could be in `scr/java`...


## Reconsider tooling
- Documentation
  - There seems to be no documentation specifying the latency unit. [ms]?
- Daemon.proto
  - Expiration is a 96bit Timestamp, optimize?
  - Latencies are 96bit Durations, optimize?
 