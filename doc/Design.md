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

Server functionality, such as `open()` and `receive()` -> `send(RequestPath)`, should _not_ require
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

There are two types of paths (both inherit `Path`:

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
