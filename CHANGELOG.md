# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).


## [Unreleased]

### TODO for 0.7.0 and beyond
- Deprecate ScionService.getLocalIsdAs(). Also: build header with src-IA from path!
  - deprecate local MTU ?! 
  - deprecate ASInfo.isCore() ?
  - Port range?
- Implement `send(PathProvider)`? Useful e.g. for a browser that connects to many
  servers.
- SCMP responder must not send to broadcast addresses etc, see SCMP processing rules.
- Add PingPong demo to integration tests. Move Integration tests to separate folder.
- Add SCION discovery extension?! -> seg_extension.proto and daemon.proto
- Java 17: 
  - DNS injection
  - Better HTML requests
  - Modules
  - OkHttp 5.2 or later requires modules (?)
- P-ISD - SCIONPROTO
  - We need to define use cases. 
    - Do we really need automatic switching between ISDs?
      - If yes, what are the switching conditions?
    - SCIERA: Use private ISD if possible, otherwise public ISD (requires RANKING)
      --> This is a very solid use case, but how many other are there like SCIERA? 
    - SSFN: Application dependent? No switching necessary ever?
    - Backup ISD: Automatic switching to other ISD is 1st is unavailable (requires RANKING)
  - scionproto supports multiple P-ISDs simply by running everything in parallel, including 
    separate daemons or loading separate topology files into the application.
    THe actual SCION UDP socket can be used for multiple ISDs, because it simply takes a path
    and send out the the packet.
    --> Bootstrapping is still a problem, how do we get multiple topology files?
    Multiple New-endhost-APIs?- 
  - Possible refactorings:
    - Design bootstrapping (not necessary for shared CS)
    - Multi-ISD topology file? May work, but needs adaptation of staticInfoConfig.json.
    - Single vs multiple daemons?
      - Considered in new daemon-less API in scionproto? 
    - Somehow we need to support seamless switching in endhosts. If one ISD is unavailable, we 
      should try the other one? Is that a valid scenario? Is it worth putting a lot of work into?
      --> "Backup" scenario.
- API!!!!
  A Path should NOT have a remote Address! 
  - Remove remoteAddress from Path.equals()
  - Remove getRemote(Socket)Address and especially getRemotePort()
  - Remove send(path)...
  - BUT: What do we call a Path with associated remote(/local) addresses? 
- API to drop current path or even use most diverse one.
  - To be called if application detects interruption (but no SCMP errors arrive?)
  - Do not drop, but move to end of list (or attach time for retry (in 10secs or so). 
     They may become valid/valuable again. Especially if the problem is actually the 
     remote server and not the path itself.
  - Make sure that e.g. SCMP does _not_  replace the path with something else!
- Make PathProvider public 

TODO
- Implement Bootstrapping: IPv6 NDP: DNS resolver and DNS Search List [RFC6106]

- PATH_POLLING:
  - Consolidate polling to once every 60 seconds per remote AS.
    TODO generally, we should think about doing the polling centrally so that we
    reuse path requests to the same remote AS. Central polling could be done with
    subscriptions or with a central polling timer that consolidates polling to
    identical remote ASes.
  - Make PAthProvider public API? Later?
    Create new package for Paths with Provider interface and implementations. -> "pathProviders"?
    Or put into ppl???
- PathProvider: 
  - implement timeout after which a faulty path will be tried again. Try it again in PP?
  - Implement reportFaulty() to take a hint that reports also other paths. E.g. if a link is
    faulty, all paths with that links should be deselected.
    -> Keep error for refresh to filter out affected paths during refresh
  - Move class to public API
- why is expiration part of the metadata? Server paths can also expire!
  Add Path.isExpiredBy(Instant)
- Peering: consider: https://github.com/scionproto/scion/tree/peering_test
- Deprecate send(PATH)!!!!!
- Path caching!!! -> Cache validity e.g. 1 minute ensures that new path become available quickly
- Remove BR IP detection when receiving packets. -> getFirstHopAddress(): this reverts #133
- BIG: extract testframework into separate project -> reuse by others!
- JSON is not an ordered file format -> Use JSON array for ordering...
  - More tests on unordered "destinations" in JSON
- Selectors: PingLatency, ReversePath, ...
- Authenticate SCMP / EPIC !?!?!?
- STUN: 
  - Always listen for STUN packets (e.g. eternally blocking receive()) in order to support on-demand STUN
  - Validate IP of incoming packets agains known NAT mapped addresses? 
- Improve PathPolicy API:
  - Chainable PathPolicies (can be done manually or by providing a combinator class)
  - PathProvider/Supplier (e.g. for traceroute policies)
  - Ability to check for new Path or skip a broken paths, see fix/171
- Scmp API: 
  - Move ResponderCallback to Scmp?
  - Make onTimeout() a default method? 
  - Merge all methods into one? A single onMessage()???
- Check: examples appear to be logging INFO by default!?!?!? -> JPAN exercise 
- TC: ask about port field in SCMP? Or must it be sent from 30041?

- We could also do revers-lookup inside service.lookup() -> e.g. works for "129.132.175.104"
  -> is a SCION enabled IP; this gives us the ISD/AS.
 
- topofiles + TRC server will be deprecated at some point.
  -> migrate to new API (where possible).

- FABRID is currently in SCIONlab. WHen ported to scionproto, JPAN should show policies in
  "showpaths" etc, see
  https://github.com/netsec-ethz/scion/blob/b45a8ff2a753e95b647801577bca019c9c4a124a/private/app/path/path.go#L415
  https://github.com/netsec-ethz/scion/blob/b45a8ff2a753e95b647801577bca019c9c4a124a/private/app/path/path.go#L327
 
- implement "scion address" command
- TODO implement PathPolicy https://docs.scion.org/en/latest/dev/design/PathPolicy.html

- Change MockControlService to return parsed Segments i.o. hardcoded segments.
- Consider refactoring: separate Datagram classes for STUN/SCMP/UDP handling

- TEST all(default) with AUTO??? BR????
  - Requires improved MockChannel that can handle STUN requests (even if returning no packet)
    e.g. configureBlocking()
- Config-ify PROPERTIES etc 
- Cache paths --> We already do some caching with ScionDatagramChannel::resolvedDestinations
- Fix @Disabled tests
- Create handling for SCMP errors 5 + 6 (interface down, connectivity down). Subclasses?
  fix/113 has packet captures for two of these errors in SCMPTest.java 
  Adhere to https://docs.scion.org/en/latest/protocols/scmp.html#processing-rules
- remove ScionAddress?
- ScionDatagramChannel
  - GatheringByteChannel, ScatteringByteChannel
  - Selector support
  - Inherit DatagramChannel 
- Authenticate SCMP with DR-key
- Bootstrap with DHCP - Check Book page 327, chapter 13.2
- Consider using https://github.com/ascopes/protobuf-maven-plugin (more up to date) 
- Multi-release-jar?

## 0.7.0 - WIP

### Added

- SCMP packet validation (+ SCMP cleanup)
  [#212](https://github.com/scionproto-contrib/jpan/pull/212)
- SCMP error parsing
  [#213](https://github.com/scionproto-contrib/jpan/pull/213)
- SCMP error handling, e.g. switch path in case of SCMP errors 5 or 6.
  [#214](https://github.com/scionproto-contrib/jpan/pull/214)
- Ensure extensibility of AbstractChannel for protocols other than UDP.
  [#217](https://github.com/scionproto-contrib/jpan/pull/217)
- Support for new endhost API
  [#218](https://github.com/scionproto-contrib/jpan/pull/218)

### Fixed

- Multiple control services should be all queried if on fails, and retried if all fails,
  regardless of error. Also: some refactoring and support for multiple CS in MockNetworks. 
  Also: better reuse of channels and stubs.
  [#208](https://github.com/scionproto-contrib/jpan/pull/208)
- Fixed JUnit warning, cleanup error messages in ControlServiceGrpc
  [#210](https://github.com/scionproto-contrib/jpan/pull/210)
- NAT keepalive uses non-daemon timer
  [#211](https://github.com/scionproto-contrib/jpan/pull/211)

### Changes

- Refactor bootstrapping, AS info, and rearrange internal classes.
  [#219](https://github.com/scionproto-contrib/jpan/pull/219)
- Refactoring to use `PathMetadata` as internal path representation rather than `Daemon.Path`.
  [#220](https://github.com/scionproto-contrib/jpan/pull/220)
  - Remove `Daemon.Path` as internal data structure, use `PathMetadata` instead
  - Remove proto references from `ScionService`
  - Move firstHop from `PathMetadata` to `Path`
  - Add local ISD/AS to Path
- Renamed "List" methods in PathMetadata API
  [#223](https://github.com/scionproto-contrib/jpan/pull/223)
- Endhost API improvements -> Remove ISDAS from AddressLookupService
  [#224](https://github.com/scionproto-contrib/jpan/pull/224)
  TODO 
  - fix fixed ISD/AS for DNSUtil in DatagramSocketApiTest 
  

### TODO
  - Test! -> Receive paths with different ISD?!?! -> separate PR?
    - also test PathProvider.
    - Test: MTU with new endhost API...
  - Avoid using local ISD/AS in:
    - AddressLookupService
    - Segments -> get srcIsdAs from segments!
    - ScionService
  - Have ScionService return list of known ISDs..? Or remove API?
  - Think about moving address into path
    - That would simplify Path API (remove response/request) but may
      complicate PathProviders...?
      Or: ScionAddress has path, but path has no reference to source address?
      Actually: the path is in PathMetadata. not in "Path", so we already have that...


### Removed

- Removed some deprecated methods.
  [#215](https://github.com/scionproto-contrib/jpan/pull/215)
- SCMP and PathProvider clean up.
  [#216](https://github.com/scionproto-contrib/jpan/pull/216)


## [0.6.1] - 2025-12-15

### Fixed

- Release 0.6.0 post cleanup
  [#205](https://github.com/scionproto-contrib/jpan/pull/205)
- PathProvider may cause application to hang on exit.
  [#206](https://github.com/scionproto-contrib/jpan/pull/206)
- Release 0.6.1 preparation
  [#207](https://github.com/scionproto-contrib/jpan/pull/207)

## [0.6.0] - 2025-12-12

### Added

- Support for multiple control servers per AS (fallback), compartmentalize GRPC usage and
  fully switch to OkHttp.
  [#179](https://github.com/scionproto-contrib/jpan/pull/179)
- Added instructions for getting a path from an IP address.
  [#184](https://github.com/scionproto-contrib/jpan/pull/184)
- Added bootstrapping support for DNS-SD
  [#197](https://github.com/scionproto-contrib/jpan/pull/197)
  [#198](https://github.com/scionproto-contrib/jpan/pull/198)
- Add API for concurrent PathProviders. 
  [#191](https://github.com/scionproto-contrib/jpan/pull/191)
  This enables:
  - Refresh path if expired (implemented)
  - Replace path if it causes errors (separate PR)
  - Replace path if better paths are available
  - Concurrent probing paths for latency/reliability/MTU... (separate PR)

### Changed

- **Breaking change** `ScionService.close()` should not declare `throws IOException`
  [#180](https://github.com/scionproto-contrib/jpan/pull/180)
- Changed protoc compiler to ConnectRPC buf.
  [#189](https://github.com/scionproto-contrib/jpan/pull/189)
- Clean-up: Made `ScionAddress` package private and move host file parsing to separate class.
  [#190](https://github.com/scionproto-contrib/jpan/pull/190)
- Channel `send()` cleanup.
  [#192](https://github.com/scionproto-contrib/jpan/pull/192)
- Bootstrap with reverse lookup now also considers subnet addresses, support for SOA and SRV.
  [#194](https://github.com/scionproto-contrib/jpan/pull/194)
- SCMP builder port now accepts `null` to indicate default port
  [#199](https://github.com/scionproto-contrib/jpan/pull/199)

### Fixed

- Post 0.5.0 cleanup
  [#176](https://github.com/scionproto-contrib/jpan/pull/176)
- Use `Range` in `Segments`.
  [#177](https://github.com/scionproto-contrib/jpan/pull/177)
- Cleanup unit tests for segments.
  [#178](https://github.com/scionproto-contrib/jpan/pull/178)
- Report path not found if no CORE segments are available.
  [#185](https://github.com/scionproto-contrib/jpan/pull/185)
- Fixed AS number formatting for 32bit ASes to decimal.
  [#186](https://github.com/scionproto-contrib/jpan/pull/186)
- Fixed SHIM to forward only packets with local interfaces as destination.
  [#187](https://github.com/scionproto-contrib/jpan/pull/187)
- Added timeout for connections to control service and daemon.
  [#174](https://github.com/scionproto-contrib/jpan/issues/174)
- Fixed bootstrapping for NAPTR "A" IPv6 records and added NAPTR "S" record support.
  [#195](https://github.com/scionproto-contrib/jpan/pull/195)
- Fixed bootstrapping: NAPTR "order" and "preference", SRV "priority" and "weight"
  [#196](https://github.com/scionproto-contrib/jpan/pull/196)
- Release 0.6.0 preparation
  [#200](https://github.com/scionproto-contrib/jpan/pull/200)
  [#201](https://github.com/scionproto-contrib/jpan/pull/201)
  [#202](https://github.com/scionproto-contrib/jpan/pull/202)
  [#203](https://github.com/scionproto-contrib/jpan/pull/203)
  [#204](https://github.com/scionproto-contrib/jpan/pull/204)
  
## [0.5.3] - 2025-04-14

### Fixed
- Fix broken extraction of BR identifier from path when creating return paths
  [#183](https://github.com/scionproto-contrib/jpan/pull/183)

## [0.5.2] - 2025-04-11

### Fixed
- Fix problem returning intra-AS packets with SHIM present
  [#181](https://github.com/scionproto-contrib/jpan/pull/181)
  
## [0.5.1] - 2025-04-02

### Fixed
- Use JDK 8 `javac` instead of JDK 21
  [#174](https://github.com/scionproto-contrib/jpan/pull/174)

## [0.5.0] - 2025-04-02

** BREAKING CHANGES **
- Minor: Some methods have `throw IOException` removed from their declaration.
For example: `Path.getFirstHopAddress()`, `DatagramChannel.setPathPolicy()`
- Medium: `PathPolicy.filter(...)` now returns `List<Path>` instead of `Path`.

### Added

- Added Path construction tests for tiny4. 
  [#146](https://github.com/scionproto-contrib/jpan/pull/146)
- Added experimental support for STUN / NAT traversal.
  This also reduces network calls by starting SHIM w/o service.
  [#142](https://github.com/scionproto-contrib/jpan/pull/142)
- Added LICENCE file to packaged jar. [#152](https://github.com/scionproto-contrib/jpan/pull/152)
- Added keep-alive protocol for NAT. [#151](https://github.com/scionproto-contrib/jpan/pull/151)
- Added implementation of STUN responder (currently not needed)
  [#154](https://github.com/scionproto-contrib/jpan/pull/154)
- Path policies `PplPolicy` and `PplPolicyGroup` created from Path Policy Language
  [#158](https://github.com/scionproto-contrib/jpan/pull/158)
- Path policies JSON import export
  [#170](https://github.com/scionproto-contrib/jpan/pull/170)
- Add missing metadata from staticInfo
  [#173](https://github.com/scionproto-contrib/jpan/pull/173)

### Changed
 
- Cleaned up test topologies. [#145](https://github.com/scionproto-contrib/jpan/pull/145)
- Changed checkstyle rules. [#153](https://github.com/scionproto-contrib/jpan/pull/143)
- **BREAKING CHANGE** `PathPolicy.filter(..)` to return a `List` of paths. 
  [#159](https://github.com/scionproto-contrib/jpan/pull/159)
- Policy filters should not need to throw Exceptions when the path list is empty.
  [#160](https://github.com/scionproto-contrib/jpan/pull/160)
- PplPolicy refactoring [#169](https://github.com/scionproto-contrib/jpan/pull/169)
  - renamed `PplPolicy` to `PplSubPolicy`
  - renamed `PplPolicyGroup` to `PplPolicy`
  - renamed `"group"` to `"destinations"`
  - added requirements: minMtu, minValidity, minBandwidth
  - added ordering: hops_asc, hops_desc, meta_latency_asc, meta_bandwidth_desc
  
### Fixed

- SHIM should not crash when receiving unparseable packet (e.g. dstPort = -1).
  Also, SHIM should parse SCMP error packets correctly.
  Cleaned up test topologies.  [#148](https://github.com/scionproto-contrib/jpan/pull/148)
- Fixed disabled SHIM tests and general test cleanup.  
  [#149](https://github.com/scionproto-contrib/jpan/pull/149)
- Fixed parsing of /etc/hosts [#150](https://github.com/scionproto-contrib/jpan/pull/150)
- Fixed warning when dnsjava parses /etc/hosts with SCION adresses
  [#155](https://github.com/scionproto-contrib/jpan/pull/155)
- Cleanup: `TestUtil` class for string output and sleep()
  [#156](https://github.com/scionproto-contrib/jpan/pull/156)
- Stop contacting daemon of every received packet
  [#157](https://github.com/scionproto-contrib/jpan/pull/157)
- Fixed troubleshooting documentation for slf4j
  [#161](https://github.com/scionproto-contrib/jpan/pull/161),
  [#162](https://github.com/scionproto-contrib/jpan/pull/162)
- Fixed Android problems with grpc-netty-shaded jar.
  [#165](https://github.com/scionproto-contrib/jpan/pull/165)
- Stabilized some NAT unit tests that kept failing on MacOS
  [#166](https://github.com/scionproto-contrib/jpan/pull/166)
- Fixed missing expiration time for Segments from control server
  [#171](https://github.com/scionproto-contrib/jpan/pull/171)

## [0.4.1] - 2024-11-22

### Fixed
- No path constructed for single UP segment in case there is only a single core AS.
  [#144](https://github.com/scionproto-contrib/jpan/pull/144)

### Changed
- Post 0.4.0 release updates. [#143](https://github.com/scionproto-contrib/jpan/pull/143)
- Pre 0.4.1 release updates. [#147](https://github.com/scionproto-contrib/jpan/pull/147)

## [0.4.0] - 2024-11-19

**BREAKING CHANGE**
- The SHIM now occupies port 30041. This means any application trying to use that port will fail.
  - Solution #1: Just use any other port instead, the SHIM will forward traffic to it.
  - Solution #2: Disable the SHIM with `org.scion.shim = false` or `SCION_SHIM = false`.

### Added
- Add a SHIM, required for #130 (topo file port range support).
  [#131](https://github.com/scionproto-contrib/jpan/pull/131)
- ManagedThread unit test helper. [#136](https://github.com/scionproto-contrib/jpan/pull/136)
- Support for `dispatched_ports` in topo files. Deprecated `configureRemoteDispatcher()`.
  [#130](https://github.com/scionproto-contrib/jpan/pull/130)
- Bootstrapping: use reverse domain lookup to find NAPTR records.
  [#138](https://github.com/scionproto-contrib/jpan/pull/138)

### Changed
- Buildified PingPong test helper. [#132](https://github.com/scionproto-contrib/jpan/pull/132)
- Server to use BR addresses instead of received addresses. 
  [#133](https://github.com/scionproto-contrib/jpan/pull/133)
- MockNetwork should use topofiles. [#134](https://github.com/scionproto-contrib/jpan/pull/134)
- MockNetwork should really use topofiles. [#135](https://github.com/scionproto-contrib/jpan/pull/135)

### Fixed
- Do not start SHIM if dispatcher port range is ALL
  [#139](https://github.com/scionproto-contrib/jpan/pull/139)
- Cleanup and fixed SHIM tests and other tests
  [#140](https://github.com/scionproto-contrib/jpan/pull/140)
- Cleanup in `AbstractDatagramChannel` [#137](https://github.com/scionproto-contrib/jpan/pull/137)
  - `buildHeader()`
  - undeprecate `SCION_TRAFFIC_CLASS`
  - Cleanup `Selector.open()` usages
  - Caching for `getExternalIP()`
  - `ShowpathsDemo` output
  - Spurious failures of SCMP exception handling tests

### Removed
- Removed deprecated code, e.g. `ScmpChannel` and public `ScionAddress`
  [#141](https://github.com/scionproto-contrib/jpan/pull/141)

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

[Unreleased]: https://github.com/scionproto-contrib/jpan/compare/v0.6.1...HEAD
[0.6.1]: https://github.com/scionproto-contrib/jpan/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/scionproto-contrib/jpan/compare/v0.5.0...v0.6.0
[0.5.3]: https://github.com/scionproto-contrib/jpan/compare/v0.5.2...v0.5.3
[0.5.2]: https://github.com/scionproto-contrib/jpan/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/scionproto-contrib/jpan/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/scionproto-contrib/jpan/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/scionproto-contrib/jpan/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/scionproto-contrib/jpan/compare/v0.3.0...v0.4.0
[0.3.1]: https://github.com/scionproto-contrib/jpan/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/scionproto-contrib/jpan/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/scionproto-contrib/jpan/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/scionproto-contrib/jpan/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/scionproto-contrib/jpan/compare/v0.1.0-ALPHA...v0.1.0
[0.1.0-ALPHA]: https://github.com/scionproto-contrib/jpan/compare/init_root_commit...v0.1.0-ALPHA
