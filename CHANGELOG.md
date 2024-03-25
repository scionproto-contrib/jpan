# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Code coverage. [#11](https://github.com/tzaeschke/phtree-cpp/pull/11)
  
### Changed
- BREAKING CHANGE: Changed maven artifactId to "client"
  [#9](https://github.com/netsec-ethz/scion-java-client/pull/9)
- BREAKING CHANGE: SCMP refactoring, renamed several SCMP related classes.
  [#14](https://github.com/netsec-ethz/scion-java-client/pull/14), 
  [#15](https://github.com/netsec-ethz/scion-java-client/pull/15),
  [#17](https://github.com/netsec-ethz/scion-java-client/pull/17),
  [#19](https://github.com/netsec-ethz/scion-java-client/pull/19),
  [#20](https://github.com/netsec-ethz/scion-java-client/pull/21)
- BREAKING CHANGE:`ScionService` instances created via `Scion.newXYZ`
  will not be considered by `Scion.defaultService()`. Also, `DatagramChannel.getService()`
  does not create a service if none exists.
  [#18](https://github.com/netsec-ethz/scion-java-client/pull/18)
- Doc cleanup
  [#22](https://github.com/netsec-ethz/scion-java-client/pull/22)
- BREAKING CHANGE: `getCurrentPath()` renamed to `getConnectionPath()`
  [#30](https://github.com/netsec-ethz/scion-java-client/pull/30)
- Cleaned up `MultiMap` utility class 
  [#34](https://github.com/netsec-ethz/scion-java-client/pull/34)
- Cleaned up `DatagramChannel`: Fixed connect()/disconnect(), improved concurrency,
  fixed buffer resizing wrt MTU, general clean up.
  [#35](https://github.com/netsec-ethz/scion-java-client/pull/35)

### Fixed
- Fixed: SCMP problem when pinging local AS.
  [#16](https://github.com/netsec-ethz/scion-java-client/pull/16),
- Fixed SCMP timeout and error handling (IOExceptions + SCMP errors).
  [#13](https://github.com/netsec-ethz/scion-java-client/pull/13)
- CI (only) failures on JDK 8. [#10](https://github.com/netsec-ethz/scion-java-client/pull/10)
- Sporadic CI (only) failures. [#12](https://github.com/netsec-ethz/scion-java-client/pull/12)
- Small fixes for 0.1.0 release. [#32](https://github.com/netsec-ethz/scion-java-client/pull/32)
- Fix NPE after 2nd send() in unconnected channel + cleanup. 
  [#33](https://github.com/netsec-ethz/scion-java-client/pull/33)

### Removed

- Removed all code related to DatagramSockets
  [#21](https://github.com/netsec-ethz/scion-java-client/pull/21)


## [0.1.0-ALPHA] - 2024-02-01

### Added
- `DatagramSocket` [#7](https://github.com/netsec-ethz/scion-java-client/pull/7)
- `Path`, `RequestPath`, `ResponsePath` [#7](https://github.com/netsec-ethz/scion-java-client/pull/7)
- `Scion`, `ScionService` [#7](https://github.com/netsec-ethz/scion-java-client/pull/7)
- `ScmpChannel` for SCMP [#7](https://github.com/netsec-ethz/scion-java-client/pull/7)

### Changed
- Nothing

### Fixed
- Nothing

### Removed
- Nothing

[Unreleased]: https://github.com/netsec-ethz/scion-java-client/compare/v0.1.0-ALPHA...HEAD
[0.1.0-ALPHA]: https://github.com/netsec-ethz/scion-java-client/compare/init_root_commit...v0.1.0-ALPHA
