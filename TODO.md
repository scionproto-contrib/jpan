# TODO

Remove 31012 !!!!

## Now
- Test Server with multiple clients
- Implement DNS
  - Entries can go into local /etc/host or /etc/scion-hosts(?)
  - For SCION there is RHINE as a DNS equivalent but it is deprecated
- SocketImpl. -> Then replace byte[] with ByteBuffer in Helper? 
- Look into Selectors:  https://www.baeldung.com/java-nio-selector
- socket. connect() + write() vs send()
- Extent DatagramPacket to ScionDatagramPacket with ScionPath info?!?!
- Rename ScionHelper to PacketBuilder?
- Implement SocketExceptions: 
  BindException(?), ConnectException, NoRouteToHostException, PortUnreachableException
- Add channel.send(packet, dstAddr, dstIsdAs); 
- Add socket.send(packet, dstIsdAs);

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
- PathService: extend API
- Remove PathHeaderOneHop

## After that
- CI
- Integrate with bazel -> Simplifies integration of go testing topology.
- Add OWASP dependency/vulnerability checker (or is this done bu GitHub nowadays?)

## Finally

- SPAO end-to-end option -> Later, not used at the moment


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
- Test channel: 
  - connect() vs send(addr)
  - getRemoteAddr()
  - getLocalAddr()
  - write() vs send()
- Test general: Test that me make a minimum of gRPC calls, e.g. to get path from daemon 


## Design
- Decide how to handle 
  - SCMP errors -> Exception? Log? Ignore?
  - Large packets, e 
- Look at NIO integration?
- Path selection & path policies

- Where to place generated proto files? They are currently in `target` but could be in `scr/java`...
- Should PathService be a singleton?
