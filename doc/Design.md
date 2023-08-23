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

- **Copy buffers?** We copy a packet's data array do a new DataGramPacket for the user.
  The alternative would be to use offset/length, however, this would not be really
  pluggable because user would need respect SCION header sizes when creating buffers for packets.
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
      This safes space and simplifies.
