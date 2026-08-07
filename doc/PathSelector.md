# PathSelector Design

## Features

- Assigned to specific destination (???)
- Provide a paths when asked
- Accept reports of faulty paths
- (Optional) refresh paths when they expire or become faulty

- The design should still allow users to view and select paths.
  - Viewing could be done with channel.getSelector().getPath()
  - Selection could be achieved through an *interactive* PathPolicy or PathSelector 

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
 That means we need a to support `send(Path, ...)` without PathSelector.


### Client

Clients have several options:

- open(PathSelectorFactory)
- connect(PathSelector)
- no PathSelector given: use default PathSelector 

### Channel/Socket API Behavior

#### Channel 

- `open()` has an optional PathSelectorFActory argument that will be 
  used to create a PathSelector for `write()` if no PathSelector is  
  given during `connect()`.
- `connect()` has an optional `PathSelector` argument that will provide
  paths for `write()`. If the argument is a simple address, a `PathSelector` 
  will be created from the `PathSelectorFactory`. 
- `write()` will use whatever `PathSelector` is prepared by `connect()`
- `send(Path)` and `send(ScionSocketAddress)` will never use 
  a `PathSelector` it will simply send on the given `Path`. 
  This is useful for server side implementations.
- `send(non-SCION-address)` will create and use a default `PathSelector`.

## Changes / Migration Guide

- `send(Path)` will not refresh paths anymore.
  To enable Path refreshing, implement a flag? TODO
- `getPathPolicy()`/`setPathPolicy()` (Channel + Socket). 
  These will be moved to PathSelectors.
  They are only available when a PathSelector is available, i.e. when connected. 
- `getMappedPath(Path)` has been removed. `getMappedPath(address)` is still available (?)
- `SCION_PATH_EXPIRY_MARGIN` is not a channel option anymore. Please set this 
  directly in the PathSelector.
- deprecate `connect(Path)`?!?!?!?  

- `connect()` and `PathSelector.setPathPolicy()` will not throw if no paths are available.
  Instead, `write()` will throw. 

## TODO

- Check if ScionService and PathPolicy should reside in AbstractChannel or in PathSelector
- Consider renaming to PathSelector
- Move Classes to public packet.
- Turn Factory into Interface? -> UDP SelectorFactory?
- Javadoc in PathProvide + reduce connect() methods

- PathProvideNoOp.connect(ScionSocketAddress) is not nice! Fix!!!!

- Think about separate connect() method in PathSelector. Is that useful? 

- Replace subscribe() with getPath() -> allow viewing a path without storing it in the channel