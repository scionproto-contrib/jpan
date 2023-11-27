

***Under construction. Do not use.***

***Under construction. Do not use.***

***Under construction. Do not use.***

# SCION Java client

A Java client for [SCION](https://scion.org).

This client can directly connect to SCION **without dispatcher**.

## API

The central classes of the API are:

- `DatagramChannel`: This class works like a `java.nio.channel.DatagramChannel`. It implements 
  `Channel` and `ByteChannel`. Scattering. gathering, multicast and selectors are currently not
  supported.
- **TODO** `DatagramSocket` and `DatagramPacket`: These work similar to the old `java.net.DatagramSocket`.
  This is currently deprecated because it does not work well.
- `Path`, `RequestPath`, `ResponsePath`: THe notion of path is slightly different than in other 
    parts of Scion. A `Path` contains a route to a destination ("raw path") plus the full 
    destination, i.e. IP-address and port.
  - A `RequestPath` is a `Path` with meta information (bandwidth, geo info, etc).
  - A `ResponsePath` is a `Path` with a first hop address.
- **TODO** `ScionPacketInspector`: A packet inspector and builder.
- `ScionService`: Provides methods to request paths and get ISD/AS information.
- `Scion`, `ScionUtil`, `ScionAddress`, `ScionPath`, `ScionSocketAddress`.
  - `ScionAddress` and `ScionSocketAddress` contain ISD/AS information on top of a 
    `InetAddress`/`InetSocketAddress`
  - `ScionSocketAddress` can contain a `ScionPath`

## DatagramChannel

### Options

Options are defined in `ScionSocketOptions`, see javadoc for details.

| Option            | Default    | Short description         |
|-------------------|------------|---------------------------|
| `API_THROW_PARSER_FAILURE` | `false` | Throw exception when reading invalid packet | 

## Demo application - ping pong

There is a simple ping pong client-server application in `src/test/demo`.

It has some hardcoded ports/IP so it works only with the scionlab tiny.topo and only with the dispatcher-free
version of scionlab: https://github.com/scionproto/scion/pull/4344

The client and server connects directly to the border router (without dispatcher).

The server is located in `1-ff00:0:112` (IP [::1]:44444). The client is located in `1-ff00:0:110`.


## Configuration

| Option            | Java property           | Environment variable | Default value |
|-------------------|-------------------------|----------------------|---------------|
| Path service host | `org.scion.daemon.host` | `SCION_DAEMON_HOST`  | 127.0.0.12    |
| Path service port | `org.scion.daemon.port` | `SCION_DAEMON_PORT`  | 30255         | 

## FAQ / Trouble shooting

### Cannot find symbol javax.annotation.Generated

```
Compilation failure: Compilation failure: 
[ERROR] ...<...>ServiceGrpc.java:[7,18] cannot find symbol
[ERROR]   symbol:   class Generated
[ERROR]   location: package javax.annotation
```

This can be fixed by building with Java JDK 1.8.


