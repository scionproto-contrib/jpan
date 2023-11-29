# Problems

# Why ScionAddress?
- ScionAddress has two properties:
  - It has Isd/As info
  - It represents an IP from a DNS/TXT lookup!


## Other

- How do we handle Interface switchover (e.g. WLAN -> 5G)?
  Is it enough to always call getLocalAddress() ?
- How do I get the local external IP? Make it configurable?
- Expired path, what to do?
  - Server side: just keep using it? -> problematic with write() which may be reused infinitely
    - Just stop sending? 
  - Client side: 
    - try to automatically get a new path? Concurrently to avoid hickups?
    - Define callback for user?
    - throw exception????  -> probably not a good idea
- We cannot really use "connect()" because the first hop may change if the path changes...? 
- It seems we cannot use DatagramSocketImpl for implementing a Scion DatagramSocket.
  Instead we may simply implement a class that is *similar* to DatagramSocket, e.g.
  with enforcing bind()/connect() before send()/receive() and enforcing that connect() uses
  ScionSocketAddresses. Problematic: how to handle path on server side?

# TODO
- Test: Send multiple packets before reading
- Deprecate SSocketAddress for now


- Rename *Path* to *Route* or *ScionSocketAddress*?
- Handle expired paths
  - Provide callback for user? -> Maybe useful if path was selected manually
    and if it changes.
  - Handle SCMP reporting a failed path -> request new one!
- Test path switching, e.g. with 2nd BR
- Test 
  - PathPolicies
- Truncate PROTO files! 
- TEST router failure: ....?  MTU too big, TTL run out, packet inconsistent, ...?
- MOVE Channel to ".channel"
- CHECK if getLocalAddress() returns an external IP when connecting to a remote host.
- TEST concurrent path/as/DNS lookup
- TEST concurrent use of single channel.
- IMPORTANT: In non-blocking mode, the channel should probably block if it received a partial Scion-header?
  Or not? This would be an attack, send a partial header would block the receiver....
  We could just buffer a partial header until it is complete... 
- Merge Scion + ScionService?
- Remove slf4j from API?

## Plan

### 0.1.0
- Path Expiry
- Path switching
- SCMP error handling (only error, not info)
  - implement callbacks (+ option to NOT ignore)
  - E>g. MTU exceeded, path expired, checksum problem, "destination unreachable"
- Handle "no path found" / NoRouteToHost?.>!?!?

Discuss required for 0.1.0:
- SCMP errors handling (above)
  - Especially for expired paths / revoked paths / broken paths?  
- Bootstrapping
- /etc/scion-hosts
- RHINE


### 0.2.0
- Selector support
  - Implement interfaces from nio.DatagramChannel
  - Look into Selectors:  https://www.baeldung.com/java-nio-selector
- DatagramSocket
  - Extent DatagramPacket to ScionDatagramPacket with ScionPath info?!?!
  - Add socket.send(packet, dstIsdAs); ?
- DNS /etc/scion-hosts
- UDP checksum validation + creation
- Fuzzing
- Remove daemon requirement -> support connecting directly to control service
- Replace Protobuf:
  - Protobuf-lite: https://github.com/protocolbuffers/protobuf/blob/main/java/lite.md
  - QuickBuffers: https://github.com/HebiRobotics/QuickBuffers/tree/main/benchmarks
  - FlatBuffers
- Bootstrapping: DNS, see https://github.com/netsec-ethz/bootstrapper
  - dig NAPTR inf.ethz.ch
  - Contact netsec-w37w3w.inf.ethz.ch for topology file
  - Parse topology file for Control Server address

### 0.3.0
- SCMP info handling: ping, traceroute, ...
- SCMP error _sending_ e.h. in case of corrupt packet
- Multipathing
- Reproducible build
- RHINE?

### 0.4.0
- EPIC, Hidden paths
- SPAO end-to-end option -> Later, not used at the moment
- MAC validation?



## Then

- Multipathing: We probably ignore that for now. Multipathing can be done in
  many different ways, it may be difficult to design a one-size-fits-all API.
  E.g. "Hercules" uses a round-robin fashion with multiple path to fire UDP packets. 
- MulticastSocket / MulticastChannel (?)
- Abuse socket/channel.setOption() to set path policies?

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
 