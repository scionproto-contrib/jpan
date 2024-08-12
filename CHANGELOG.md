# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).



## [Unreleased]

### TODO for 0.3.0
- BUG: System.setProperty(Constants.PROPERTY_DAEMON, "127.0.0.1");
  leads to exception about missing TRC (instead of complaining about missing ":30255")
  -> DOes this work with an IPv6 address (separaeting the port?)
  -> Think about making the port optional, it is standardized to 30255 anyway
  -> Move port to separate environment variable???? 
- Make ResponsePath/RequestPath classes private. Make at least create() private... 
- Demo that connects to Francois' website
- Fix @Disabled tests
- Support topofile port range
- `ResponsePath` is now package private (not public anymore)
- remove ScionAddress?
- Remove getPaths(long dstIsdAs, InetSocketAddress dstScionAddress) <- ISD + Scion address!!!
- ScionDatagramChannel
  - GatheringByteChannel, ScatteringByteChannel
  - Selector support
  - Inherit DatagramChannel 
- AS switching?

### TODO post 0.3.0
- Upgrade all JUnit topo files to post 0.11 with new format (including port range)
- ...

### Added
- Support for bootstrapper TRC metadata. [#110](https://github.com/scionproto-contrib/jpan/pull/110)
- Added `copy(...)` method for paths. [#111](https://github.com/scionproto-contrib/jpan/pull/111)
- Added Scenario builder for unit tests. [#112](https://github.com/scionproto-contrib/jpan/pull/112)
- Path construction fixes: [#104](https://github.com/scionproto-contrib/jpan/pull/104)
  - Support shortcut and on-path detection during path construction
  - New option `SCION_RESOLVER_MINIMIZE_REQUESTS`
  - Fixed MTU calculations for link level MTU
  - Path lists are ordered by hop count
  - Path lists contain no duplicates

### Changed
- Clean up TODO and deprecation info. [#100](https://github.com/scionproto-contrib/jpan/pull/100) 
- Separate topo file parser [#103](https://github.com/scionproto-contrib/jpan/pull/103)

### Fixed
- Remove use of 0.0.0.0 and "::". [#103](https://github.com/scionproto-contrib/jpan/pull/103)
- Remove use of getHostName() in ScionAddress. [#106](https://github.com/scionproto-contrib/jpan/pull/106)
- SocketConcurrency test takes too long. [#108](https://github.com/scionproto-contrib/jpan/issues/108)

### Removed
- Removed some useless IP printing functions. 
  [#105](https://github.com/scionproto-contrib/jpan/pull/105)

## [0.2.0] - 2024-06-24

### Added
- SCMP echo responder [#78](https://github.com/scionproto-contrib/jpan/pull/78)
- Maven Java executor [#80](https://github.com/scionproto-contrib/jpan/pull/80)
- Dev environment setup hints doc [#82](https://github.com/scionproto-contrib/jpan/pull/82)
- Added SCION_GETTING_STARTED.md [#59](https://github.com/scionproto-contrib/jpan/pull/59)

### Changed
- Some API changes: [#67](https://github.com/scionproto-contrib/jpan/pull/67)
  - Rename `DatagramChannel` to `ScionDatagramChannel`
  - Rename `DatagramSocket` to `ScionDatagramSopcketl` and move it to main package
  - Rename `ScionSocketOptions`  starting with `SN_` to `SCION_`
- SCMP API changes. [#71](https://github.com/scionproto-contrib/jpan/pull/71)
- **BREAKING CHANGE**: `DatagramChannel.receive()` returns a subclass of `InetSocketAddress` 
  [#86](https://github.com/scionproto-contrib/jpan/pull/86) 
- Internal cleanup. [#88](https://github.com/scionproto-contrib/jpan/pull/88)
- Deprecated `getPaths(InetSocketAddress)` because it wasn't clear that it did a SCION lookup. 
  [#89](https://github.com/scionproto-contrib/jpan/pull/89)
- **BREAKING CHANGE**: `ScionDatagramChannel.send(buffer, path)` returns `int`.
  [#92](https://github.com/scionproto-contrib/jpan/pull/92)
- **BREAKING CHANGE**: Path metadata has been moved to `PathMetadata`.
  [#93](https://github.com/scionproto-contrib/jpan/pull/93)
- **BREAKING CHANGE**: `receive()` returns `ScionSocketAddress`; `ResponseAddress` and 
  `ResponsePath` are removed from from public API.
  [#94](https://github.com/scionproto-contrib/jpan/pull/94)
- **BREAKING CHANGE**: removed `RequestPath` and `ScionAddress` from public API.
  [#95](https://github.com/scionproto-contrib/jpan/pull/95)
- Better error message for SCMP echo in local AS 
  [#96](https://github.com/scionproto-contrib/jpan/issues/96)

### Fixed
- Fixed locking and resizing of buffers. [#68](https://github.com/scionproto-contrib/jpan/pull/68)
- Fixed unhelpful error & log message when with topo file has wrong permissions.
  [#74](https://github.com/scionproto-contrib/jpan/issues/74)
- Topology file parser support for new "local" attribute.
  [#72](https://github.com/scionproto-contrib/jpan/issues/72)
- Fixed path lookup error when destination isn't reachable.
  [#70](https://github.com/scionproto-contrib/jpan/issues/70)
- Fixed internal raw path parsing and IP parsing.
  [#77](https://github.com/scionproto-contrib/jpan/pull/77)
- Improved bootstrap logging [#83](https://github.com/scionproto-contrib/jpan/pull/83)
- Fixed SCMP meta data reporting wrong remote port.
  [#79](https://github.com/scionproto-contrib/jpan/issues/79)

## [0.1.1] - 2024-05-10

### Fixed
- Fixed spurious CI failures, esp. Windows. [#61](https://github.com/tzaeschke/phtree-cpp/pull/61) 
- Fix SCM references in pom file + clean up. [#60](https://github.com/tzaeschke/phtree-cpp/pull/60) 
- DNS lookup caused by `InetAddress.getByName()`. 
  [#63](https://github.com/scionproto-contrib/jpan/pull/63)
- DNS lookup caused by `InetAddress.getHostName()`.
  [#64](https://github.com/scionproto-contrib/jpan/pull/64)
- JPAN renaming cleanup. [#65](https://github.com/scionproto-contrib/jpan/pull/65)
- Clean up PingPong framework + spurious CI failures. 
  [#62](https://github.com/tzaeschke/phtree-cpp/pull/62)
- Missing tests for "decimal:decimal" style ISD/AS codes.
  [#66](https://github.com/tzaeschke/phtree-cpp/pull/66)

## [0.1.0] - 2024-04-29

### Added
- Code coverage. [#11](https://github.com/scionproto-contrib/jpan/pull/11)
- Global JUnit callback for initial setup. THis allows setting global properties before centrally
  before running any tests.
  [#38](https://github.com/scionproto-contrib/jpan/pull/38)
- Support for `/etc/scion/hosts` and for OS search domains (e.g. `/etc/resolv.conf`). 
  [#40](https://github.com/scionproto-contrib/jpan/pull/40)
- CI builds for Windows and MacOS. [#41](https://github.com/scionproto-contrib/jpan/pull/41)
- Added support communicating with a dispatcher-endhost in the local AS, see 
  `DatagramChannel.configureRemoteDispatcher`. 
  [#46](https://github.com/scionproto-contrib/jpan/pull/46)
- Support for comments, multiple spaces and tabs in `/etc/scion/hosts`. 
  [#47](https://github.com/scionproto-contrib/jpan/pull/47)
- Added helper methods for `ScmpChannel` and `ScionUtil.toStringPath()`.
  [#48](https://github.com/scionproto-contrib/jpan/pull/48),
- Demo cleanup an new `ScmpShowpathsDemo`.
  [#49](https://github.com/scionproto-contrib/jpan/pull/49)
- Channel demo cleanup. [#52](https://github.com/scionproto-contrib/jpan/pull/52)
- Address/ISD/AS caching. [#54](https://github.com/scionproto-contrib/jpan/pull/54)
- `DatagramSocket` [#31](https://github.com/scionproto-contrib/jpan/pull/31)
- `setOverrideSourceAddress()` [#58](https://github.com/scionproto-contrib/jpan/pull/58)
  
### Changed
- BREAKING CHANGE: Changed maven artifactId to "client"
  [#9](https://github.com/scionproto-contrib/jpan/pull/9)
- BREAKING CHANGE: SCMP refactoring, renamed several SCMP related classes.
  [#14](https://github.com/scionproto-contrib/jpan/pull/14), 
  [#15](https://github.com/scionproto-contrib/jpan/pull/15),
  [#17](https://github.com/scionproto-contrib/jpan/pull/17),
  [#19](https://github.com/scionproto-contrib/jpan/pull/19),
  [#20](https://github.com/scionproto-contrib/jpan/pull/21)
- BREAKING CHANGE:`ScionService` instances created via `Scion.newXYZ`
  will not be considered by `Scion.defaultService()`. Also, `DatagramChannel.getService()`
  does not create a service if none exists.
  [#18](https://github.com/scionproto-contrib/jpan/pull/18)
- Doc cleanup
  [#22](https://github.com/scionproto-contrib/jpan/pull/22)
- BREAKING CHANGE: `getCurrentPath()` renamed to `getConnectionPath()`
  [#30](https://github.com/scionproto-contrib/jpan/pull/30)
- Cleaned up `MultiMap` utility class 
  [#34](https://github.com/scionproto-contrib/jpan/pull/34)
- Cleaned up `DatagramChannel`: Fixed connect()/disconnect(), improved concurrency,
  fixed buffer resizing wrt MTU, general clean up.
  [#35](https://github.com/scionproto-contrib/jpan/pull/35)
- **BREAKING CHANGE**: Renamed project to `jpan`. 
  [#43](https://github.com/scionproto-contrib/jpan/pull/43),
  [#45](https://github.com/scionproto-contrib/jpan/pull/45)
- **BREAKING CHANGE**: `Path` now returns `InetAddress` instead of `byte[]`
  [#44](https://github.com/scionproto-contrib/jpan/pull/44)
- BREAKING CHANGE: Changed `Path` API: destination->remote and source->local
  [#55](https://github.com/scionproto-contrib/jpan/pull/55)

### Fixed
- Fixed: SCMP problem when pinging local AS.
  [#16](https://github.com/scionproto-contrib/jpan/pull/16),
- Fixed SCMP timeout and error handling (IOExceptions + SCMP errors).
  [#13](https://github.com/scionproto-contrib/jpan/pull/13)
- CI (only) failures on JDK 8. [#10](https://github.com/scionproto-contrib/jpan/pull/10)
- Sporadic CI (only) failures. [#12](https://github.com/scionproto-contrib/jpan/pull/12)
- Small fixes for 0.1.0 release. [#32](https://github.com/scionproto-contrib/jpan/pull/32)
- Fix NPE after 2nd send() in unconnected channel + cleanup. 
  [#33](https://github.com/scionproto-contrib/jpan/pull/33)
- Fixed traffic class not set. [#36](https://github.com/scionproto-contrib/jpan/pull/36)
- Fixed handling of channel options. [#37](https://github.com/scionproto-contrib/jpan/pull/37)
- Merged SCION_DAEMON_HOST and SCION_DAEMON_PORT into a single SCION_DAEMON property.
  [#39](https://github.com/scionproto-contrib/jpan/pull/39)
- Some cleanup on to hosts file parser. [#42](https://github.com/scionproto-contrib/jpan/pull/42)
- Added proper error when ports 30255 or 31000 are in use when running tests
  [#50](https://github.com/scionproto-contrib/jpan/pull/50)
- Fix `Unsupported platform: protoc-3.11.4-osx-aarch_64.exe.`
  [#53](https://github.com/scionproto-contrib/jpan/pull/53)
- Fixed spurious CI failure and SimpleCache packet name.
  [#55](https://github.com/scionproto-contrib/jpan/pull/55)

### Removed
- Removed all code related to DatagramSockets
  [#21](https://github.com/scionproto-contrib/jpan/pull/21)


## [0.1.0-ALPHA] - 2024-02-01

### Added
- `DatagramSocket` [#7](https://github.com/scionproto-contrib/jpan/pull/7)
- `Path`, `RequestPath`, `ResponsePath` [#7](https://github.com/scionproto-contrib/jpan/pull/7)
- `Scion`, `ScionService` [#7](https://github.com/scionproto-contrib/jpan/pull/7)
- `ScmpChannel` for SCMP [#7](https://github.com/scionproto-contrib/jpan/pull/7)

### Changed
- Nothing

### Fixed
- Nothing

### Removed
- Nothing

[Unreleased]: https://github.com/scionproto-contrib/jpan/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/scionproto-contrib/jpan/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/scionproto-contrib/jpan/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/scionproto-contrib/jpan/compare/v0.1.0-ALPHA...v0.1.0
[0.1.0-ALPHA]: https://github.com/scionproto-contrib/jpan/compare/init_root_commit...v0.1.0-ALPHA
