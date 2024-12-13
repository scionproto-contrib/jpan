# Design

We should look at other custom Java protocol implementations, e.g. for QUIC:

* https://github.com/ptrd/kwik
* https://github.com/trensetim/quic
* https://kachayev.github.io/quiche4j/

## Daemon

The implementation can use the daemon. Alternatively, since daemon installation may
be cumbersome on platforms such as Android, we can directly connect to a control service.

An alternative for mobile devices could be a non-local daemon that is hosted by the mobile provider.
This may work but may open opportunities for side-channel attacks on the daemon.
Also, when roaming, the provider may not actually support SCION. In this case the
device would need to connect to some kind of gateway to connect to either a daemon or
at least a topology server + control server.

## Server / Client

Server functionality, such as `open()` and `receive()` -> `send(Path)`, should _not_ require
any access to a daemon or control service. The main reason is that it ensures efficiency
by completely avoiding any type of interaction with daemon/CS.
Interaction with daemon/CS should not be denied but it should not occur during default operations.

Client functionality will usually require a `ScionService`. If none is provided during `open()`,
a service will internally be requested via `Scion.defaultService()`.
While explicit initialization is usually preferable as a design choice, this implicit
initialization was implemented to allow usage of `open()` without additional arguments.
This allows being much closer to the native Java `DatagramChannel` API.

An alternative would be to push the the delayed/implicit initialization into the `ScionService`
class. We would always have a `ScionService` instance, but it wouldn't initially connect to
a daemon or control service. The disadvantage is that creation of a `ScionService` would have
to fail "late", i.e. we can create it but only when we use it would be know whether it
is actually able to connect to something.

## ScionService

The `ScionService` implements all interactions with a local daemon or with a control service.
When a `ScionService` is required internally (e.g. by `DatagramChannel`) and none has been
specified, a `ScionService` will be requested (and if none has been created yet, created) via
`Scion.defaultService()`.

A `ScionService` created via specific factory methods (`Scion.newServiceWithDNS`, etc) will _not_ be
used or returned by `Scion.defaultService()`.

## Library dependencies etc

- Use Maven (instead of Gradle or something else): Maven is still the most used framework and
  arguable the best (
  convention over configuration)
- Use Java 8: Still the most used JDK (TODO provide reference).
    - Main problem: no module support
    - E.g. **netty** is on Java 8 (distributed libraries are JDK 6), **jetty** is on 11/17
- Logging:
    - Do not use special log classes for JDK plugin classes (DatagramSocket etc)
    - Consider using `slfj4` logging framework for other classes: widely used ond flexible.
- Use Junit 5.
- Use Google style guide for code
- We use custom exceptions. However, to make the API as compatible with standard networking API
  as possible, our Exceptions extends either IOException or RuntimeException.

## Paths

There are two types of paths (both inherit `Path`, both are (will be) package private):

- `RequestPath` are used to send initial request. They are retrieved from a path service and contain
  meta information.
- `ResponsePath` are used to respond to a client request. They are extracted from SCION packets.

`Path` contains a destination IA:IP:port, a raw path and a first hop IP:port.
`RequestPath` also contains path meta information.
`ResponsePath` also contains origin IA:IP:port.

## DatagramSocket

**DatagramSocket is not really supported, please use DatagramChannel instead.**

Some problems with DatagramSocket:

- It is not possible to associate a ScionAddress or ScionSocketAddress with a Datagram.
  This is problematic when sending a datagram, especially on the server side, because
  we cannot associate a path with a datagram. The Socket will have to remember **all** incoming
  paths so that it has a path when returning a datagram to a client.
  This may improve somewhat in future if we get reverse lookup for IP addresses -> ISD/AS;
  this would allow the socket to forget paths and get new ones from the reverse lookup.
- It is not possible to subclass InetAddress which is the address type that is store inside
  DatagramPackets.

Datagram Socket design considerations:

- Java 15 has a new Implementation based on NIO, see https://openjdk.org/jeps/373
    - If Java 8's NIO is sufficient, we can follow the same approach as Java 15.
      Otherwise we can (as proposed in JEP 373) wrap around an old DatagramSocket and use
      the nio implementation only when it is available (running on JDK 15 or later).
      See also deprecation
      note: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/DatagramSocket.html#setDatagramSocketImplFactory(java.net.DatagramSocketImplFactory)
- Implementation using DatagramSocketImpl is also not possible because it does not allow
  overriding getRemoteAddress() etc. These methods should return the address of the remote
  end host.
  The only viable solution appears to be to reimplement DatagramSocket.

- **Copy buffers?** We copy a packet's data array to a new DatagramPacket for the user.
  The alternative would be to use offset/length, however, this would not be really
  pluggable because user would need to respect SCION header sizes when creating buffers for packets.
    - Replacing the buffer in the user's packet is bad, this may be surprising for users
    - Using the buffer directly to read SCION packets is also bad because it may be too small
    - --> We definitely need to copy.

## Paths

### Path & Header retainment

- Paths & Headers are retained for:
    - efficiency (when sending multiple times to the same destination)
    - reversing the path
- Path are not associated with DatagramChannels. That means:
    - They can be reused in other Channels -> TODO they need to be thread-safe / immutable
    - An incoming path may be used to create a separate outgoing channel

**Questions - TODO:**

- Should the PathService retain paths, as a form of cache?
- When a client receives a packet and wants to send another one, should it reuse the original
  path or revert the incoming path?

### Local Address

When sending a packet, the path must include a valid return address (IP+port).
Finding out the local IP address can be non-trivial.

`DatagramChannel.getLocalAddress()` may return the correct address.
However, it may also return `null` (if not bound or connected) an IP address with port `0`,
a wildcard address (`0.0.0.0`), a link-local address or a site-local address.

Even if `getLocalAddress()` returns a good external address, there are more problems.
For example, if the route changes, the first hop may change and be reachable via a different
interface. Also, with concurrent usage, the channel/socket may be actively listening while we may
need to change the outgoing interface.

How do we solve this?

We should always listen on the wildcard address to ensure we always receive returned
traffic regardless on which interface it arrives.
However, this prevents situations where different services listen on identical ports on
different interfaces (is this a realistic scenario?).

Using the IP of the first hop, we could check all local interfaces and try to select one
that is likely reachable by the first hop border router.
We use this interface IP as return address in the SCION path.

#### Solution

1) If the channel is explicitly (by the user) bound to a specific interface
   then we use that interface as return address.
    * If it turns out that the bound interface is on a different
      network than the first hop, then we could throw an exception.
    * Instead of throwing an exception, we could also
      try to look for a path that has a first hop compatible with the
      bound interface.
2) If the channel is not bound or bound to a wildcard address, we can:
    * either check all interfaces to find one that is likely to be
      reachable by the first hop border router.
    * or open s separate channel/socket and connect() to the first hop.
3) Selection of the return IP must be done every time the path changes:
    * Path expires
    * First hop becomes unreachable or interfaces appear dynamically due to topology changes
      (e.g. mobile WiFi or 5G)
    * Explicit path change requested by user.

Situations with link local or site local addresses can only
be ignored. If the first hop is reachable via these addresse

We used to use `connnect(firstHop)` to ensure a valid external IP. However,
`connect()` blocks when used concurrently while a `receive()` is in progress.
-> Solution: Use separate channel/socket with `connect()` to find external IP.

## STUN / Interface Discovery

### Main goals and approach

1) We want to detect STUN for all BR as soon as possible.
   Otherwise it would interfere with normal network operation.
   However, we need to wait until a local address is known (`bind()`) or about
   to be known (`bind()` called by `send()`). What about `receive()`?
    - We call STUN detection in `bind()` call. This works also for the SCMP
      classes which do `bind()` *before* measuring latency.
    - We don't need to know our external address for `receive()` or a subsequent `send()`.
      **However**: If a *client* defensively starts `receive()` before `send()`, we have a problem.
      Running STUN detection requires `send()` + `receive()` on the same channel. While `send()`
      can be done concurrently, `receive()` is exclusive. How do we solve that?
        - We can do STUN detection before `receive()` starts -> may be a waste on servers
        - We can create a callback in `receive()` that intercepts packets meant for the STUN
          detector
          -> We may have to do this anyway to facilitate keep-alive messages.
2) Sequence for AUTO:
    - Check for CUSTOM server setting
    - Check if border router supports STUN -> 1st NW call, 1 per BR
    - Check if border router responds to SCMP (means: no NAT) -> 2nd NW call, one per BR
    - Check public STUN servers and try again with new IP -> 3rd + 4th NW call, one per BR
      (Don't do 4th call if returned IP equals known local IP)

As can be seen from above, in an AS with `n` border routers (BR), we have at least `n` network calls
even if all BRs support STUN. If they don't, we get up to `3n+1` network calls, possibly more if
multiple public STUN servers are used.
To speed up the detection, we send out `n` packets at once before checking for answers.
However, following the approach above, we still get 3 rounds of `n` plus a single request to the
public STUN server, resulting in a worst case of 4*TIMEOUT (default = 10ms) before giving up.
In the best case the timeout is never reached, i.e. STUN detection may take less than 10ms.

### Intra-AS communication

For packets sent within an AS, cannot reliable use border router STUN (border routers
may be in different subnets). The better and easier solution is to omit STUN detection.
Instead, any client receiving a packet from inside the local AS should always respond to the
**underlay** address instead of the SCION source address.
This should always work, regardless of NAT or no NAT.

### Multipath, Multi-AS, AS-switching, ...

Cases to consider:

- Interface switching is probably not necessary to support.
    - Test: Presumably, switching from LAN to Wifi, any LAN socket stops working
      (and does not switch) If the JDK doesnt support this then neither should we.
    - Android has: WifiManager.WIFI_STATE_CHANGED_ACTION and ConnectivityManager.CONNECTIVITY_ACTION
- AS switching on single interface?
    - In theory, we can have multiple AS in the same subnet. Do we need to support that?
      This is apparently a realistic Scenario for an ISP with ASes in multiple ISDs.
      However, this seems like an unrealistic edge case for endhosts...?
- Multipath (MP) and Path switching (PS):
    - MP & PS using different BRs -> Yes, should be doable and is required
    - MP & PS using different AS on same Interface?? We could use multiple channels...
      See next point.
    - MP & PS using different Interfaces. Should probably require multiple sockets/channels.
      Should we provide a facility to simplify this?
      Something like a meta-socket that doesn't have a defined local address but can send
      over multiple interfaces in parallel? Yes! -> Later!

### Mapping Timeout

NATs usually time out and drop unused mappings after after a few minutes (minimum 2 minutes,
recommended 5 minutes, see [RFC 4787](https://datatracker.ietf.org/doc/html/rfc4787#section-4.3)).

To handle this we have two options:

- Detect and create new mapping
- Prevent timeouts with keep-alive messages

In both cases we keep track of activity by monitoring incoming and outgoing packets.

**Detection and reestablish**

On a call to `send()`. if the last activity is too long ago, we trigger a new round
of NAT detection.
Disadvantage: Unexpected additional time (1-5ms) when a user sends a packet.

**Keep-alive message**

We regularly send keep-alive packets to all border routers to prev ent the NAT from
timing out. These packets can be anything, plain UDP, UDP/SCION or SCMP echo.
We don't actually need to wait for incoming responses.
Disadvantage: Keep-alive message can be annoying and eat energy on mobile devices.

**Conclusion**

We implement both options. They are configurable via `SCION_NAT_KEEPALIVE` or
`org.scion.nat.keepalive`. The default value is `false`, indicating that no keep
alive messages are sent and instead we rely on "detection and reestablish".

# TODO

## DatagramChannel

### Comments

* Selectors: Implementing a selectable Channel intentionally requires reimplementing
  the whole Selector infrastructure, see also https://bugs.openjdk.org/browse/JDK-8191884.

## DatagramSocket

**DatagramSockets are currently not supported and may never be supported**.
DatagramSockets have no means of handling paths transparently (see discussion above).
That means we would need additional functions for sending/receiving SCION packets.
This is possible but usage is not transparent and inconvenient.

* [ ] Multicast support, check
  e.g. [javadoc](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/DatagramSocket.html)
    * [ ] Multicast support, e.g. check
    * [ ] `setOption()`
    * [ ] `joinGroup()` / `leaveGroup()`
    * [ ] `setReuseAddr`
* [ ] All the other stuff

## Path Selection

* TODO

## SCMP support

* TODO

## TCP (over Scion) support?

* TODO

## Testing / fuzzing

* TODO
