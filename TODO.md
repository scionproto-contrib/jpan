# TODO
- Test: Send multiple packets before reading
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


## Plan

### 0.1.0
- Test switching of firstHop when path changes
  - Should be easy to test now, the daemon returns two paths
    and we can just use the second one.
    We just need to find a way to test this. 
- SCMP error handling (only error, not info)
  - implement callbacks (+ option to NOT ignore)
  - E>g. MTU exceeded, path expired, checksum problem, "destination unreachable"
  - Handle Scion's "no path found" with NoRouteToHost?.>!?!?
- Run testing with IPv6 again? -> MockNetwork 
- Path Expiry: ms or ns instead of seconds!

Discuss required for 0.1.0:
- SCMP errors handling (above)
  - Especially for expired paths / revoked paths / broken paths?  

### 0.2.0
- Bootstrapping: DNS, see https://github.com/netsec-ethz/bootstrapper
  - dig NAPTR inf.ethz.ch
  - Contact netsec-w37w3w.inf.ethz.ch for topology file
  - Parse topology file for Control Server address
- Selector support
  - Implement interfaces from nio.DatagramChannel
  - Look into Selectors:  https://www.baeldung.com/java-nio-selector
- Path expiry: request new path asynchronously when old path is close to expiry
- DNS /etc/scion-hosts
- UDP checksum validation + creation
- Fuzzing
- Remove daemon requirement -> support connecting directly to control service
- remove "internals" package

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
 