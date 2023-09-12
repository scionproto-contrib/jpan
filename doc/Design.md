# Design

We should look at other custom Java protocol implementations, e.g. for QUIC:
* https://github.com/ptrd/kwik
* https://github.com/trensetim/quic
* https://kachayev.github.io/quiche4j/


## Library dependencies etc

- Use Maven (instead of Gradle or something else): Maven is still the most used framework and arguable the best (
  convention over configuration)
- Use Java 8: Still the most used JDK (TODO provide reference).
  Main problem: no module support
- Logging:
    - Do not use special log classes for JDK plugin classes (DatagramSocket etc)
    - Consider using `slfj4` logging framework for other classes: widely used ond flexible.
- Use Junit 5.
- Use Google style guide for code
- We do **not** introduce custom exceptions. The rationale is that we want our API to be as similar
  as possible as the standard networking API.

## DatagramSocket

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


# TODO

## DataGramSocket

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

## Internal

* [ ] Header classes could be consolidated, e.g. ScionHeader + AddressHeader.
* [ ] Headers could store raw header info and extract data on the fly when required.
      This safes space and simplifies. Problem: we either need to copy
      the data[] in case we need the header data later (user's API calls, or when sending),
      or we need to store key data from the header.
      Solution: copy byte[] with header data instead of payload? Whichever is smaller???
