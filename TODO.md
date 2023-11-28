# Problems

# Why ScionAddress?
- ScionAddress has two properties:
  - It has Isd/As info
  - It represents an IP from a DNS/TXT lookup!


## Other

- How do we handle Interface switchover (e.g. WLAN -> 5G)?
  Is it enough to always call getLocalAddress() ?
- Download server / Streaming server. What happens if path breaks?
  How does server get a new path? Is it common for a client to 
  keep sending requests with the latest (working) path?

- How do I get the local external IP? Make it configurable?
- DNS calls are awkward, outsource to Daemon?!?
  -> Android: We may not have a daemon -> do everything inside JVM 
- Expired path, what to do?
  - Server side: just keep using it? -> problematic with write() which may be reused infinitely
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
- Rename *Path* to *Route*?
- Handle expired paths
  - Provide callback for user? -> Maybe useful if path was selected manually
    and if it changes.
  - Handle SCMP reporting a failed path -> request new one!
- Test path switching, e.g. with 2nd BR
- Test 
  - service.getPaths()
  - PathPolicies
- Truncate PROTO files! 
- Implement callback for
  - SCMP errors (+ option to NOT ignore)
  - SCMP pings (+ option to NOT ignore)
- Bootstrapping: DNS, see https://github.com/netsec-ethz/bootstrapper
  - dig NAPTR inf.ethz.ch
  - Contact netsec-w37w3w.inf.ethz.ch for topology file
  - Parse topology file for Control Server address
- TEST router failure: ....?  MTU too big, TTL run out, packet inconsistent, ...?
- MOVE Channel to ".channel"
- CHECK if getLocalAddress() returns an external IP when connecting to a remote host.
- TEST concurrent path/as/DNS lookup
- TEST concurrent use of single channel.
- ScionPacketParser.strip()/augment(): 
  - strip(buffer) sets position() and returns address/path
  - augment(buffer, address+path) inserts scionHeader 

- IMPORTANT: In non-blocking mode, the channel should probably block if it received a partial Scion-header?
  Or not? This would be an attack, send a partial header would block the receiver....
  We could just buffer a partial header until it is complete... 

- Daemon on mobile? Java?
- Make configurable: client uses own path vs reversed server path.
- Merge Scion + ScionService?

- Lookup FlowID -> mandatory - MTU?
- Remove slf4j from API?

## Now
- Implement interfaces from nio.DatagramChannel
- Implement DNS
  - Entries can go into local /etc/host or /etc/scion-hosts(?)
  - For SCION there is RHINE as a DNS equivalent but it is deprecated
- Look into Selectors:  https://www.baeldung.com/java-nio-selector
- Extent DatagramPacket to ScionDatagramPacket with ScionPath info?!?!
- Add socket.send(packet, dstIsdAs);
- UDP checksum for overlay packet?

## Then

- Multipathing: We probably ignore that for now. Multipathing can be done in
  many different ways, it may be difficult to design a one-size-fits-all API.
  E.g. "Hercules" uses a round-robin fashion with multiple path to fire UDP packets. 
  
- Inherit DatagramSocket? 
- Extract path info from server socket in order to support multiple clients
- MulticastSocket / MulticastChannel (?)
- Send SCMP on error? Probably yes, e.g. "Parameter Problem" when processing
  extension headers (which are only processed ayt end-hosts)
- Abuse socket/channel.setOption() to set path policies?
- ScionService: extend API
- Remove PathHeaderOneHop
- SCMP ping, traceroute?
- Maven: reproducible build

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
- UDP Checksum validation? UDP checksum creation?

Look at:
- Protobuf-lite: https://github.com/protocolbuffers/protobuf/blob/main/java/lite.md
- QuickBuffers: https://github.com/HebiRobotics/QuickBuffers/tree/main/benchmarks
- FlatBuffers

## Finally

- HiddenPath, EPIC, ...
- SPAO end-to-end option -> Later, not used at the moment
- MAC validation?

# Open Questions

- Add ISD/AS lookup to daemon API ?!?
- How to determine the srcIP ? It depends on the interface....


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
- Decide how to handle 
  - SCMP errors -> Exception? Log? Ignore?
  - Large packets, e 
- Path selection & path policies

- Where to place generated proto files? They are currently in `target` but could be in `scr/java`...


## Reconsider tooling
- Documentation
  - There seems to be no documentation specifying the latency unit. [ms]?
- Daemon.proto
  - Expiration is a 96bit Timestamp, optimize?
  - Latencies are 96bit Durations, optimize?
 