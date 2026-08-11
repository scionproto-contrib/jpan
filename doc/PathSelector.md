# PathSelector Design

## Features

- Assigned to specific destination (ISD/AS port IP) upon connect()
- Provide a paths when asked
- Accept reports of faulty paths
- (Optional) refresh paths when they expire or become faulty

- The design should still allow users to view and select paths.
  - Viewing could be done with channel.getSelector().getPath()
  - Selection could be achieved through an *interactive* PathPolicy or PathSelector 


## How are they used

### Server

Servers will often not request paths from a path service, instead they will
use paths from incoming connections.
That means we need a to support `send(Path, ...)` without PathSelector.


### Channel/Socket API Behavior

#### Channel 

- `open()` 
  - has an optional PathSelector argument that will be
    initialized during `connect()` and  used to get paths for `write()`.
  - has an optional PathSelectorFactory argument that will be
    used to create a PathSelector for `send(InetSocketAddress)`.
- `connect()` will always use the channel's `PathSelector` 
  even if argument is a ScionSOcketAddress with a path. 
- `write()` will use whatever `PathSelector` is prepared by `connect()`
- `send(Path)` and `send(ScionSocketAddress)` will never use 
  a `PathSelector`, it will simply send on the given `Path`. 
  This is useful for server side implementations.
- `send(non-SCION-address)` will create and use a default `PathSelector`.

## Changes / Migration Guide

- `send(Path)` will not refresh paths anymore.
  The javadoc already claimed it would _not_ refresh.
- `getPathPolicy()`/`setPathPolicy()` (Channel + Socket). 
  These will be moved to PathSelectors.
  They are only available when a PathSelector is available, i.e. when connected. 
- `getMappedPath(Path)` has been removed. `getMappedPath(address)` is still available.
- `SCION_PATH_EXPIRY_MARGIN` is not a channel option anymore. Please set this 
  directly in the PathSelector.
- deprecate `connect(Path)`  

- `connect()` and `PathSelector.setPathPolicy()` will not throw if no paths are available.
  Instead, `write()` will throw. 
