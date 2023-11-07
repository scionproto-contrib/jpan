

***Under construction. Do not use.***

***Under construction. Do not use.***

***Under construction. Do not use.***

# SCION Java client

A Java client for [SCION](https://scion.org).

This client can directly connect to SCION without a dispatcher.

## API

The central classes of the API are:

- `DatagramChannel`: This class works like a `java.nio.channel.DatagramChannel`, except it does 
  not currently support selectors.
- `DatagramSocket` and `DatagramPacket`: These work similar to the old `java.net.DatagramSocket`.
- `ScionPacketHelper`: A utility class to work with SCION packet headers
- `ScionPacketInspector`: A packet inspector and builder.
- `ScionService`: Provides methods to request paths and get ISD/AS information.
- `Scion`, `ScionUtil`, `ScionAddress`, `ScionPath`, `ScionSocketAddress`.


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


