


# SCION Java client

[![codecov](https://codecov.io/gh/netsec-ethz/scion-java-client/graph/badge.svg?token=3U8J50E4N5)](https://codecov.io/gh/netsec-ethz/scion-java-client)

This library is pure Java network stack for using [SCION](https://scion.org). More information about SCION can be found [here](https://docs.scion.org).
It provides functionality similar to 
[snet (Go)](https://pkg.go.dev/github.com/scionproto/scion/pkg/snet),
[PAN (Go)](https://pkg.go.dev/github.com/netsec-ethz/scion-apps/pkg/pan) and 
[scion-rs (Rust)](https://github.com/MystenLabs/scion-rs). 

The following artifact contains the complete SCION Java client:
``` 
<dependency>
    <groupId>org.scion</groupId>
    <artifactId>scion-java-client</artifactId>
    <version>0.1.0-ALPHA</version>
</dependency>
```

### Planned features
- `DatagramSocket` and `DatagramPacket`
- `Selector` for `DatagramChannel`
- Path creation with short-cuts, on-path and peering routes
- `/etc/scion/hosts` and `/etc/hosts`, see https://github.com/netsec-ethz/scion-apps
- Improve docs, demos and testing
- EPIC, path authentication and other SCION features
- TCP
- Many more

### WARNING
This client can directly connect to SCION **without dispatcher**.

Currently (January 2024), the SCION system uses a "dispatcher" (a process that runs on endhosts,
listens on a fixed port (30041) and forwards any incoming SCION packets, after stripping the SCION 
header, to local application).

This Java client cannot be used with a dispatcher.
The Java client can be used in one of the following ways:
- You can use the client stand-alone (without local SCION installation),
  however it must listen on port 30041 for incoming SCION packets because
  SCION routers currently will forward data only to that port. 
- If you need a local SCION installation (Go implementation),
  consider using the dispatch-off branch/PR.
- When you need to run a local system with dispatcher, you can try to use port forwarding
  to forward incoming data to your Java application port. The application port must not be 30041.

## API

The central classes of the API are:

- `DatagramChannel`: This class works like a `java.nio.channel.DatagramChannel`. It implements 
  `Channel` and `ByteChannel`. Scattering, gathering, multicast and selectors are currently not
  supported.
- `Path`, `RequestPath`, `ResponsePath`: The notion of path is slightly different than in other 
    parts of SCION. A `Path` contains a route to a destination ("raw path") plus the full 
    destination, i.e. IP-address and port.
  - `RequestPath` is a `Path` with meta information (bandwidth, geo info, etc).
  - `ResponsePath` is a `Path` with source IA, IP & port.
- `PathPolicy` is an interface with several example implementations for:
  first path returned by daemon (default), max bandwidth, min latency, min hops, ...
- `ScionService`: Provides methods to request paths and get ISD/AS information.
  `ScionService` instances can be created with the `Scion` class. The first instance that is created will subsequently
  returned by `Scion.defaultService()`.
- `Scion`, `ScionUtil`, `ScionConstants`: Utility classes.
- `ScionSocketOptions`: Options for the `DatagramChannel`.
- `SCMP` provides `ScmpType` and `ScmpCode` enums with text messages. It also contains
  `ScmpMessage` (for SCMP errors) and `ScmpEcho`/`ScmpTraceroute` types. These can be used with the
  `DatagramChannel`'s `sendXXXRequest()` and `setXXXListener()` methods.
- **TODO** Currently residing in `test`: `ScionPacketInspector`: A packet inspector and builder.
- **TODO** `DatagramSocket` and `DatagramPacket`: These work similar to the old `java.net.DatagramSocket`.
  This is currently deprecated because it does not work well.

### Features
Supported:
- DatagramChannel support: read(), write(), receive(), send(), bind(), connect(), ... 
- Path selection policies
- Path expiry/refresh
- Packet validation
- DNS/TXT scion entry lookup
- Configurable:
  - daemon address
  - bootstrapping via topo file, bootstrapper IP or DNS 
  - path expiry
- Packet inspector for debugging
- No "dispatcher"

Missing:
- DatagramChannel support for Selectors
- DatagramSockets
- Path construction with short-cuts, on-path, peering
- EPIC
- RHINE
- ...

## Getting started

A simple client looks like this:
```java
InetSocketAddress addr = new InetSocketAddress(...);
try (DatagramChannel channel = DatagramChannel.open()) {
  channel.configureBlocking(true);
  channel.connect(addr);
  channel.write(ByteBuffer.wrap("Hello Scion".getBytes()));
  ...
  ByteBuffer response = ByteBuffer.allocate(1000);
  channel.read(response); 
}
```

### Demos

Some demos can be found in [src/test/java/org/scion/demo](src/test/java/org/scion/demo).

- `DatagramChannel` ping pong [client](src/test/java/org/scion/demo/PingPongChannelClient.java) 
  and [server](src/test/java/org/scion/demo/PingPongChannelServer.java)
- [SCMP echo](src/test/java/org/scion/demo/ScmpEchoDemo.java)
- [SCMP traceroute](src/test/java/org/scion/demo/ScmpTracerouteDemo.java)


### General documentation

- Reference manual: https://docs.scion.org
- Reference implementation of SCION: https://github.com/scionproto/scion
- SCIONLab, a global testbed for SCION applications: https://www.scionlab.org/
- Awesome SCION, a collection of SCION projects: https://github.com/scionproto/awesome-scion 

### Real world testing and evaluation

The JUnit tests in this Java project use a very rudimentary simulated network.
For proper testing it is recommended to use one of the following:

- [scionproto](https://github.com/scionproto/scion), the reference implementation of SCION, comes 
  with a framework that allows defining a topology and running a local network with daemons, control 
  servers, border routers and more, see [docs](https://docs.scion.org/en/latest/dev/run.html).
- [SEED](https://github.com/seed-labs/seed-emulator/tree/master/examples/scion) is a network
  emulator that can emulate SCION networks.
- [SCIONlab](https://www.scionlab.org/) is a world wide testing framework for SCION. You can define your own AS
  and use the whole network. It runs as overlay over normal internet so it has limited 
  security guarantees and possibly reduced performance compared to native SCION.
- [SCIERA](https://sciera.readthedocs.io/) is a network of Universities with SCION connection. It is part
  part of the global SCION  network
- [AWS](https://aws.amazon.com/de/blogs/alps/connecting-scion-networks-to-aws-environments/) offers SCION nodes with connection to the global SCION network.



## DatagramChannel

### Destinations
In order to find a path to a destination IP, a `DatagramChannel` or `DatagramSocket` must know the 
ISD/AS numbers of the destination.


If the destination host has a DNS TXT entry for SCION then this be used to determine the 
destination ISD/AS.
Alternatively, the ISD/AS can be specified explicitly.

#### DNS lookup
```
$ dig TXT ethz.ch
...
ethz.ch.		610	IN	TXT	"scion=64-2:0:9,129.132.230.98"
...
```

```
InetSocketAddress serverAddress = new InetSocketAddress("ethz.ch", 80);​
channel.connect(serverAddress);
```


#### Explicit ISD/AS specification

We can use the ISD/AS directly to request a path:
```
long isdAs = ScionUtil.parseIA("64-2:0:9");
InetSocketAddress serverAddress = new InetSocketAddress("129.132.19.216", 80);​
Path path = Scion.defaultService().getPaths(isdAs, serverAddress).get(0);
channel.connect(path);
```


### Demo application - ping pong

There is a simple ping pong client-server application in `src/test/demo`.

It has some hardcoded ports/IP so it works only with the scionproto `tiny.topo` and only with the 
dispatcher-free version of scionproto: https://github.com/scionproto/scion/pull/4344

The client and server communicate directly with the border router (without dispatcher).

The server is located in `1-ff00:0:112` (IP `[::1]:44444`). The client is located in `1-ff00:0:110`.

### Options

Options are defined in `ScionSocketOptions`, see javadoc for details.

| Option                        | Default | Short description                                               |
|-------------------------------|---------|-----------------------------------------------------------------|
| `SN_API_WRITE_TO_USER_BUFFER`    | `false` | Throw exception when receiving an invalid packet          | 
| `SN_PATH_EXPIRY_MARGIN` | `2`     | A new path is requested if `now + margin > pathExpirationDate` | 

## Performance pitfalls

- **Using `SocketAddress` for `send()`**. `send(buffer, socketAddress)` is a convenience function. However, when sending 
  multiple packets to the same destination, one should use `path = send(buffer, path)` or `connect()` + `write()` in 
  order to avoid frequent path lookups.

- **Using expired path (client).** When using `send(buffer, path)` with an expired `RequestPath`, the channel will 
  transparently look up a new path. This works but causes a path lookup for every `send()`.
  Solution: always use the latest path returned by send, e.g. `path = send(buffer, path)`.

- **Using expired path (server).** When using `send(buffer, path)` with an expired `ResponsePath`, the channel will
  simple send it anyway.

## Configuration

### Bootstrapping / daemon
In order to find paths and connect to the local AS, the application needs either a [local 
installation of SCION](https://docs.scion.org/en/latest/dev/run.html) 
or some other means to get bootstrap information.

The method `Scion.defaultService()` (internally called by `DatagramChannel.open()`) will 
attempt to get network information in the following order until it succeeds:
- Check for local topology file (if file name is given)
- Check for bootstrap server address (if address is given)
- Check for DNS NAPTR record (if record entry name is given)
- Check for to daemon

The reason that the daemon is checked last is that it has a default setting (localhost:30255) while
the other options are skipped if no property or environment variable is defined. 

| Option                              | Java property                    | Environment variable         | Default value |
|-------------------------------------|----------------------------------|------------------------------|---------------|
| Daemon host                         | `org.scion.daemon.host`          | `SCION_DAEMON_HOST`          | localhost     |
| Daemon port                         | `org.scion.daemon.port`          | `SCION_DAEMON_PORT`          | 30255         | 
| Bootstrap topology file path        | `org.scion.bootstrap.topoFile`   | `SCION_BOOTSTRAP_TOPO_FILE`  |               | 
| Bootstrap server host               | `org.scion.bootstrap.host`       | `SCION_BOOTSTRAP_HOST`       |               |
| Bootstrap DNS NAPTR entry host name | `org.scion.bootstrap.naptr.name` | `SCION_BOOTSTRAP_NAPTR_NAME` |               | 

### Other

| Option                                                                                                               | Java property           | Environment variable | Default value |
|----------------------------------------------------------------------------------------------------------------------|-------------------------|----------------------|---------------|
| Path expiry margin. Before sending a packet a new path is requested if the path is about to expire within X seconds. | `org.scion.pathExpiryMargin` | `SCION_PATH_EXPIRY_MARGIN`  | 10           |

## FAQ / trouble shooting

### Local testbed (scionproto) does not contain any path

A common problem is that the certificates of the testbed have expired (default validity: 3 days).
The certificates can be renewed by recreating the network with 
`./scion.sh topology -c <your_topology_here.topo>`.

### ERROR: "TRC NOT FOUND"
This error occurs when requesting a path with an ISD/AS code that is not
known in the network.

### Cannot find symbol javax.annotation.Generated

```
Compilation failure: Compilation failure: 
[ERROR] ...<...>ServiceGrpc.java:[7,18] cannot find symbol
[ERROR]   symbol:   class Generated
[ERROR]   location: package javax.annotation
```

This can be fixed by building with Java JDK 1.8.


## License

This project is licensed under the Apache License, Version 2.0 
(see [LICENSE](LICENSE) or https://www.apache.org/licenses/LICENSE-2.0).