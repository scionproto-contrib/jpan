# PathProvider Design

## Features

- Assigned to specific destination (???)
- Provide a paths when asked
- Accept reports of faulty paths
- (Optional) refresh paths when they expire or become faulty

- The design should still allow users to view and select paths.
  - Viewing could be done with channel.getSelector().getPath()
  - Selection could be achieved through an *interactive* PathPolicy or PathProvider 

TODO consider:
- Make Path subclass of ScionSOcketAddress. Remove Path from ScionSocketAddress
  Or: Make Path in ScionSocketAddress optional.
  -> For servers, We still need a class that is subclass of InetSockertAddress and that 
     has a path attached. 
  -> ScionService API: 
  - resolve address to ScionAddress
  - getPaths for Scion Address
  

## How are they used

### Server

Servers will often not request paths from a path service, instead they will
use paths from incoming connections.
 That means we need a to support `send(Path, ...)` without PathProvider.


### Client

Clients have several options:

- open(PathProviderFactory)
- connect(PathProvider)
- no PathProvider given: use default PathProvider 

### Channel/Socket API Behavior

#### Channel 

- `open()` has an optional PathProviderFActory argument that will be 
  used to create a PathProvider for `write()` if no PathProvider is  
  given during `connect()`.
- `connect()` has an optional `PathProvider` argument that will provide
  paths for `write()`. If the argument is a simple address, a `PathProvider` 
  will be created from the `PathProviderFactory`. 
- `write()` will use whatever `PathProvider` is prepared by `connect()`
- `send(Path)` and `send(ScionSocketAddress)` will never use 
  a `PathProvider` it will simply send on the given `Path`. 
  This is useful for server side implementations.
- `send(non-SCION-address)` will create and use a default `PathProvider`.

## TODO

- Check if ScionService and PathPolicy should reside in AbstractChannel or in PathProvider
- Consider renaming to PathSelector
- Move Classes to public packet.
- Turn Factory into Interface? -> UDP SelectorFactory?
- Javadoc in PathProvide + reduce connect() methods

- PathProvideNoOp.connect(ScionSocketAddress) is not nice! Fix!!!!

- Think about separate connect() method in PathProvider. Is that useful? 

- Replace subscribe() with getPath() -> allow viewing a path without storing it in the channel