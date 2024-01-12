# Design

We should look at other custom Java protocol implementations, e.g. for QUIC:
* https://github.com/ptrd/kwik
* https://github.com/trensetim/quic
* https://kachayev.github.io/quiche4j/

## Daemon
The implementation can use the daemon. Alternatively, since daemon installation may
be cumbersome on platforms such as Android, we can directly connect to a control service.

## Library dependencies etc

- Use Maven (instead of Gradle or something else): Maven is still the most used framework and arguable the best (
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
  as possible, our Exceptions extend either IOException or RuntimeExcdeption.

## Paths

There are two types of paths (both inherit `Path`:
- `RequestPath` are used to send initial request. They are retrieved from a path service and contain meta information.
- `ResponsePath` are used to respond to a client request. They are extracted from Scion packets.

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
  this would allows the socket to forget paths and get new ones from the reverse lookup.
- It is not possible to subclass InetAddress which is the address type that is store inside 
  DatagramPackets.


Datagram Socket design considerations:
- Java 15 has a new Implementation based on NIO, see https://openjdk.org/jeps/373
  - If Java 8's NIO is sufficient, we can follow the same approach as Java 15.
    Otherwise we can (as proposed in JEP 373) wrap around an old DatagramSocket and use
    the nio implementation only when it is available (running on JDK 15 or later).
    See also deprecation note: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/DatagramSocket.html#setDatagramSocketImplFactory(java.net.DatagramSocketImplFactory)

- **Copy buffers?** We copy a packet's data array do a new DataGramPacket for the user.
  The alternative would be to use offset/length, however, this would not be really
  pluggable because user would need respect SCION header sizes when creating buffers for packets.
  - Replacing the buffer in the user's packet is bad, this may be surprising for users
  - Using the buffer directly to read SCION packets is also bad because it may be too small
  - --> We definitely need to copy.
- **Inherit DatagramSocket?** For now we do not inherit DatagramSocket.
  - Making DatagramSocket a super class of ScionDatagramSocket can easily be done later on.
  - Disadvantages 
    - When the JDK adds methods to DatagramSocket, these are initially not implemented
      in our implementation and may cause erroneous behavior. 
    - Implementing missing methods later on is difficult because it may not be compatible with
      earlier JDK version. At the very least, we cannot use `@Override`.
  - Advantages
    - Better "plugability", users can use a variable of type DatagramSocket to store
      a `ScionDatagramSocket`.


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

# TODO

## DatagramChannel

### Comments
* Selectors: Implementing a selectable Channel intentionally requires reimplementing
  the whole Selector infrastructure, see also https://bugs.openjdk.org/browse/JDK-8191884.

## DatagramSocket

**DatagramSockets are currently not supported and may never be supported**. 
DatagramSockets have no means of handling paths transparently (see discussion above).
That means we would need additional functions for sending/receiving Scion packets.
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
