# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).



## [Unreleased]

### TODO for 0.4.0
- USe topo port range for connections in local AS!
- Fix demos to return an "int" that can be tested!
- ShimTest: TODO the serverService(null) should be removed once SHIM becomes the default
- Bootstrap JPAN with reverse lookup to get search-domain --> Francois

- Fix showpaths to show "127.0.0.81:31038" i.o. "/192.168.53.20"
- Bootstrap to find local search domain with reverse lookup, ask Francois!
- Cache local address, see AbstractChannel:600
  srcAddress = getOrCreateService().getExternalIP(path.getFirstHopAddress());
- MockDaemon: read properties from topofile, see TODO
- Cache paths
- Server should get firstHop from topofile or daemon, not from packet IP!
  -> BRs may use different ports for in/outgoing traffic.
  -> Thios would also solve the server/SHIM problem, see TODO.md 
- Fix @Disabled tests
- Support topofile port range
  - Upgrade all JUnit topo files to post 0.11 with new format (including port range)
- Create handling for SCMP errors 5 + 6 (interface down, connectivity down). Subclasses?
  fix/113 has packet captures for two of these errors in SCMPTest.java 
- remove ScionAddress?
- ScionDatagramChannel
  - GatheringByteChannel, ScatteringByteChannel
  - Selector support
  - Inherit DatagramChannel 
- Consider using https://github.com/ascopes/protobuf-maven-plugin (more up to date) 

### Added
- Add a SHIM, required for #130 (topo file port range support).
  [#130](https://github.com/scionproto-contrib/jpan/pull/130)
- ManagedThread test helper. [#136](https://github.com/scionproto-contrib/jpan/pull/136)
- Support for `dispatched_ports` in topo files
  [#130](https://github.com/scionproto-contrib/jpan/pull/130)

TODO: 
- Test demos with JUNIT topo and with scioproto topo
- FIX: if no ephemeral ports are available, just use ANY port but enforce 30041 as return port.
- Deprecate configureRemoteDispatcher()
- Remove workaround from sendRaw() that checks for SHIM. Currently required for tests....
  -> Do this in separate PR?

### Changed
- Buildified PingPong test helper. [#132](https://github.com/scionproto-contrib/jpan/pull/132)
- Server to use BR addresses instead of received addresses. 
  [#133](https://github.com/scionproto-contrib/jpan/pull/133)
- MockNetwork should use topofiles. [#134](https://github.com/scionproto-contrib/jpan/pull/134)
- MockNetwork should really use topofiles. [#135](https://github.com/scionproto-contrib/jpan/pull/135)

## [0.3.1] - 2024-10-11

### Added
- Better troubleshooting for connection problems.
  [#127](https://github.com/scionproto-contrib/jpan/pull/127)
- Auto-add port to discovery server setting + better error message.
  [#128](https://github.com/scionproto-contrib/jpan/pull/128)

### Fixed
- Do not immediately fail if discovery server is missing in topo file. 
  [#126](https://github.com/scionproto-contrib/jpan/pull/126)
  
## [0.3.0] - 2024-10-09

### Added
- Support for bootstrapper TRC metadata. [#110](https://github.com/scionproto-contrib/jpan/pull/110)
- Added `copy(...)` method for paths. [#111](https://github.com/scionproto-contrib/jpan/pull/111)
- Added Scenario builder for unit tests. [#112](https://github.com/scionproto-contrib/jpan/pull/112)
- Path construction fixes: [#104](https://github.com/scionproto-contrib/jpan/pull/104)
  - Support shortcut and on-path detection during path construction
  - Path lists are ordered by hop count
  - Path lists contain no duplicates
  - Fixed MTU calculations for link level MTU
  - Fixed some issues with IPv6 ASes
  - New option `EXPERIMENTAL_SCION_RESOLVER_MINIMIZE_REQUESTS`
- "Integration" test for scionproto "default". 
  [#114](https://github.com/scionproto-contrib/jpan/pull/114)
- Improved path duplication filtering.
  [#117](https://github.com/scionproto-contrib/jpan/pull/117)
- Added environment variable / property for DNS search domain.
  [#118](https://github.com/scionproto-contrib/jpan/pull/118)
- New SCMP API: [#119](https://github.com/scionproto-contrib/jpan/pull/119) 
  - separate ScmpSender/ScmpResponder
  - non-blocking ScmpSenderAsync
  - deprecated old ScmpChannel
  
### Changed
- Clean up TODO and deprecation info. [#100](https://github.com/scionproto-contrib/jpan/pull/100) 
- Separate topo file parser [#103](https://github.com/scionproto-contrib/jpan/pull/103)
- BREAKING CHANGE: Changed argument and return type of `setScmpErrorListener()` in 
  `ScionDatagramChannel` and `ScmpChannel` to `Scmp.ErrorMessage`. 
  [#124](https://github.com/scionproto-contrib/jpan/pull/124)
- 0.3.0 preparation. [#122](https://github.com/scionproto-contrib/jpan/pull/122)
  - Updated dependencies to latest versions

### Fixed
- Remove use of 0.0.0.0 and "::". [#103](https://github.com/scionproto-contrib/jpan/pull/103)
- Remove use of getHostName() in ScionAddress. [#106](https://github.com/scionproto-contrib/jpan/pull/106)
- SocketConcurrency test takes too long. [#108](https://github.com/scionproto-contrib/jpan/issues/108)
- Fixed useless error message when providing incorrect daemon address.
  Also: made port optional (default = 30255) [#114](https://github.com/scionproto-contrib/jpan/pull/114)
- Fixed SCMP packet loss on SCMP receive [#120](https://github.com/scionproto-contrib/jpan/pull/120)
- Fixed problem, with parsing IPv6 addresses in topo files 
  [123](https://github.com/scionproto-contrib/jpan/pull/123)

### Removed
- Removed some useless IP printing functions. 
  [#105](https://github.com/scionproto-contrib/jpan/pull/105)
- 0.3.0 preparation. [#122](https://github.com/scionproto-contrib/jpan/pull/122)
  - Removed deprecated code

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

[Unreleased]: https://github.com/scionproto-contrib/jpan/compare/v0.3.1...HEAD
[0.3.1]: https://github.com/scionproto-contrib/jpan/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/scionproto-contrib/jpan/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/scionproto-contrib/jpan/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/scionproto-contrib/jpan/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/scionproto-contrib/jpan/compare/v0.1.0-ALPHA...v0.1.0
[0.1.0-ALPHA]: https://github.com/scionproto-contrib/jpan/compare/init_root_commit...v0.1.0-ALPHA
