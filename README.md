


# JPAN - Java API for path aware networking with SCION

[![codecov](https://codecov.io/gh/scionproto-contrib/jpan/graph/badge.svg?token=3U8J50E4N5)](https://codecov.io/gh/scionproto-contrib/jpan)

This library is 100% pure Java network stack for using [SCION](https://scion.org). It currently provides 
support for UDP and [SCMP](https://docs.scion.org/en/latest/protocols/scmp.html). More information about SCION can be found 
in [Getting Started](SCION_GETTING_STARTED.md) and more generally [here](https://docs.scion.org). 
JPAN provides functionality similar to 
[snet (Go)](https://pkg.go.dev/github.com/scionproto/scion/pkg/snet),
[PAN (Go)](https://pkg.go.dev/github.com/netsec-ethz/scion-apps/pkg/pan) and 
[scion-rs (Rust)](https://github.com/MystenLabs/scion-rs). 

The following artifact contains the complete SCION Java implementation:
```xml 
<dependency>
    <groupId>org.scion</groupId>
    <artifactId>jpan</artifactId>
    <version>0.6.1</version>
</dependency>
```

### Feature summary
- 100% Java
- UDP over SCION via `ScionDatagramChannel` or `ScionDatagramSocket`
- [SCMP](https://docs.scion.org/en/latest/protocols/scmp.html) (ICMP for SCION)
- Works stand-alone or with a local SCION daemon (without dispatcher, see below) 
- NAT support, see [here](doc/NAT.md) for details and restrictions.

### Planned features
- API: `Selector` for `ScionDatagramChannel`
- Paths with peering routes
- Improve docs, demos and testing
- Many more

### WARNING - Dispatcher
JPAN connects directly to SCION **without dispatcher**.

Currently, the SCION system uses a "dispatcher" (a process that runs on endhosts,
listens on a fixed port (30041) and forwards any incoming SCION packets, after stripping the SCION 
header, to local application).

JPAN cannot be used with a dispatcher.
JPAN can be used in one of the following ways:
- You can use JPAN stand-alone (without local SCION installation),
  however it must listen on port 30041 for incoming SCION packets because
  SCION routers currently will forward data only to that port. 
- When you need to run a local system with dispatcher, you can try to use port forwarding
  to forward incoming data to your Java application port. The application port must not be 30041.

## API

The central classes of the API are:

- `ScionDatagramChannel`: This class works like a `java.nio.channel.DatagramChannel`. It implements 
  `Channel` and `ByteChannel`. Scattering, gathering, multicast and selectors are currently not
  supported.
- `ScionSocketAddress` is an `InetSocketAddress` with the IP of a Scion enabled endhost.
  A `ScionSocketAddress` also has the ISD/AS code of that endhost and a path to the that endhost. 
- `Path` objects contain a route to a destination ("raw path") plus the full 
    destination, i.e. SCION-enabled IP address and port. 
  - If the path was created by the `ScionService` then it has `PathMetadata` with meta information 
    (bandwidth, geo info, etc).
  - A path returned by `receive()` (as part of a `ScionSocketAddress`) has no meta information.
- `PathPolicy` is an interface with several example implementations for:
  first path returned by daemon (default), max bandwidth, min latency, min hops, ... .
  There is also `PplPolicy`, an implementation of the 
  [path policy language (PPL)](https://docs.scion.org/en/latest/dev/design/PathPolicy.html#policy)
- `ScionService`: Provides methods to request paths and get ISD/AS information.
  `ScionService` instances can be created with the `Scion` class. The first instance that is created is subsequently
  returned by `Scion.defaultService()`.
- `Scion`, `ScionUtil`, `ScionConstants`: Utility classes.
- `ScionSocketOptions`: Options for the `ScionDatagramChannel`.
- `Scmp`:
  - `Scmp.newSenderAsyncBuilder(...)` and `Scmp.newSenderBuilder(...)` for sending echo or traceroute requests
  - `Scmp.newResponderBuilder(...)` for receiving and responding to echo requests
  - `Scmp.Type` and `Scmp.TypeCode` enums with text messages. 
  - `Scmp.ErrorMessage`/`Scmp.EchoMessage`/`Scmp.TracerouteMessage` types

### Features
Supported:
- `DatagramChannel` support via `ScionDatagramChannel`: `read()`, `write()`, `receive()`, `send()`, `bind()`, `connect()`, ...
- `DatagramSocket` support via `ScionDatagramSocket`
- Path selection policies, including path policy language (PPL)
- Path expiry/refresh
- Packet validation
- SCION address lookup via DNS/TXT entry or `/etc/scion/hosts` 
  (see https://github.com/netsec-ethz/scion-apps)
- Configurable:
  - daemon address
  - bootstrapping via topo file, bootstrapper IP, DNS NAPTR or SRV entry, or /etc/resolv.conf 
  - path expiry
  - ...
- Packet inspector for debugging
- Bootstrapping of discovery service via DNS NAPTR, SRV, SOA, PTR, ...
- No "dispatcher"



## Getting started

A simple client looks like this:
```java
InetSocketAddress addr = new InetSocketAddress(...);
try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
  channel.configureBlocking(true);
  channel.connect(addr);
  channel.write(ByteBuffer.wrap("Hello Scion".getBytes()));
  ...
  ByteBuffer response = ByteBuffer.allocate(1000);
  channel.read(response); 
}
```

Examples can be found [this repository](https://github.com/netsec-ethz/scion-java-packet-example).

### How to connect to a remote server with JPAN

We assume that you have local SCION connectivity including access to a SCION daemon (on your 
machine) or a control service (of your local AS). Alternatively you have a local topology running, 
e.g. with [scionproto](https://github.com/scionproto/scion)
/[docs](https://docs.scion.org/en/latest/dev/run.html), 
or a[Freestanding SCION network](https://docs.scion.org/en/latest/tutorials/deploy.html)
or [SCIONlab](https://www.scionlab.org/).
Having connectivity through a SIG does not work.

#### Scenario 1: remote IP + ISD/AS

If you know the IP/port and ISD/AS, the easiest way is to get paths directly from the 
`ScionService`: `Scion.defaultService().getPaths(ISD/AS, IP)` and then use that with 
`connect(path)` or `send(packet, path)`.

#### Scenario 2: URL/host name + DNS entry

If you don't know the IP or ISD/AS, JPAN may be able to look it up via DNS. 
However, this requires the host name to have a DNS TXT entry that maps to the  IP + ISD/AS. 
Note that this IP may be different that the normal IP returned by a DNS lookup.

To check whether a SCION DNS TXT entry exists. check the following:

```
$ dig TXT  ethz.ch | grep scion=
ethz.ch.		130	IN	TXT	"scion=64-2:0:9,129.132.230.98"
``` 

In JPAN you can simply create an `InetSocketAddress` with your host name and use it with 
`connect(address)` or `send(packet, address)`.
JPAN will then internally query DNS and request and use paths to the destination.

### Working with JPAN sources

If you want to work on JPAN or simply browse the code locally, you can install it locally.

JPAN is available as a 
[Maven artifact](https://central.sonatype.com/artifact/org.scion/jpan).
Many IDEs comes with maven plugins. If you want to use Maven from the command line, you
can install it with `sudo apt install maven` (Ubuntu etc) or download it 
[here](https://maven.apache.org/index.html).

To install it locally:
```shell 
git clone https://github.com/scionproto-contrib/jpan.git
cd jpan
mvn clean install
```

**Note** on MacOS the tests may fail, see 
[Troubleshooting below](https://github.com/scionproto-contrib/jpan?tab=readme-ov-file#failurestimeout-when-running-tests-on-macos).
To skip tests, please use `mvn clean test -DskipTests=true`.

### Demos

Some demos can be found in [src/test/java/org/scion/jpan/demo](src/test/java/org/scion/jpan/demo).
Before running the demos, you may have [set up your development environment](doc/DevEnvironment.md) 
and execute `mvn install -DskipTests=true`.

After compilation, demos can be executed from the IDE (recommended) or from command line.
For example: `mvn exec:java -Dexec.mainClass="org.scion.jpan.demo.ScmpEchoDemo"`.

The following demos are included:

- `DatagramChannel` ping pong 
  [PingPongChannelClient.java](src/test/java/org/scion/jpan/demo/PingPongChannelClient.java) 
  and [PingPongChannelServer.java](src/test/java/org/scion/jpan/demo/PingPongChannelServer.java)
- `DatagramSocket` ping pong 
  [PingPongSocketClient.java](src/test/java/org/scion/jpan/demo/PingPongSocketClient.java)
  and [PingPongSocketServer.java](src/test/java/org/scion/jpan/demo/PingPongSocketServer.java)
- SCMP echo: [ScmpEchoDemo.java](src/test/java/org/scion/jpan/demo/ScmpEchoDemo.java)
- SCMP traceroute [ScmpTracerouteDemo.java](src/test/java/org/scion/jpan/demo/ScmpTracerouteDemo.java)
- Showpaths: [ShowpathsDemo.java](src/test/java/org/scion/jpan/demo/ShowpathsDemo.java)

If you encounter problems, please check the troubleshooting section below. 

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
- [Freestanding SCION network](https://docs.scion.org/en/latest/tutorials/deploy.html).
  This is a standalone network of SCION nodes that are not connected to the global SCION network. 
  It is a good way to test your application in a real world environment.
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
destination ISD/AS. For example, if `dig TXT your-domain.org` returns something like
`your-domain.org.		610	IN	TXT	"scion=64-2:0:9,129.x.x.x"`, then you can simply
use something like:
```java
InetSocketAddress serverAddress = new InetSocketAddress("your-domain.org", 80);
channel.connect(serverAddress);
```
or
```java
InetSocketAddress serverAddress = new InetSocketAddress("your-domain.org", 80);
Path path = Scion.getDefaultService().lookupAndGetPath(serverAddress, PathPolicy.DEFAULT);
channel.send(buffer, path);
```

Alternatively, the ISD/AS can be specified explicitly in several ways.

#### /etc/scion/hosts file

Create a file `/etc/scion/hosts` to assign ISD/AS ans SCION IP to host names:
```
# /etc/scion/hosts test file
1-ff00:0:111,[42.0.0.11] test-server
1-ff00:0:112,[42.0.0.12] test-server-1 test-server-2
1-ff00:0:113,[::42] test-server-ipv6
```

#### Specify ISD/AS in program

We can use the ISD/AS directly to request a path:
```java
long isdAs = ScionUtil.parseIA("64-2:0:9");
InetSocketAddress serverAddress = new InetSocketAddress("129.x.x.x", 80);
Path path = Scion.defaultService().getPaths(isdAs, serverAddress).get(0);
channel.connect(path);
```

### Options

Options are defined in `ScionSocketOptions`, see javadoc for details.

| Option                        | Default | Short description                                               |
|-------------------------------|---------|-----------------------------------------------------------------|
| `SCION_API_THROW_PARSER_FAILURE`    | `false` | Throw exception when receiving an invalid packet          | 
| `SCION_PATH_EXPIRY_MARGIN` | `2`     | A new path is requested if `now + margin > pathExpirationDate` | 

The following standard options are **not** supported:

| Option                         | 
|--------------------------------|
| `StandardSocketOptions.SO_BROADCAST` | 
| `StandardSocketOptions.IP_MULTICAST_IF` |
| `StandardSocketOptions.IP_MULTICAST_TTL` |
| `StandardSocketOptions.IP_MULTICAST_LOOP` |

## DatagramSocket

`DatagramSocket` work similar to `DatagramChannel` in terms of using `Path` or `Service`.
`DatagramSocket` is somewhat discouraged because it requires storing/caching of paths internally
which can lead to increased memory usage or even failure to resolve paths, especially when handling
multiple connections over a single socket.

The problem is that `DatagramPacket` and `InetAddress` are not extensible to store path information.
For a server to be able to send data back to a client, it has to remember these paths internally.
This is done internally in a path cache that stores the received path for every remote IP address.
The cache is by default limited to 100 entries (`setPathCacheCapacity()`). In case there are more 
than 100 remote clients, the cache will 'forget' those paths that haven't been used for the longest
time. That means the server won't be able to send anything anymore to these forgotten clients.

This can become a security problem if an attacker initiates connections from many different (or 
spoofed) IPs, causing the cache to consume a lot of memory or to overflow, becoming unable to
answer to valid requests.

Internally, the `DatagramSocket` uses a SCION `DatagraChannel`.

API beyond the standard Java `DatagramScoket`:

* `create(ScionService)` and `create(SocketAddress, ScionService)` for creating a `DatagramSocket`
  with a non-default `ScionService`.
* `connect(Path path)` for connecting to a remote host
* `getConnectionPath()` gets the connected path if the socket has been connected 
* `getCachedPath(InetAddress address)` get the cached path for a given IP
* `setPathCacheCapacity(int capacity)` and `getPathCacheCapacity()` for managing the cache size
* `setOption(...)` and `getOption()` are supported even though they were only added in Java 9.
  They support the same (additional) options as `ScionDatagramChannel`. 


## Performance pitfalls

- **Using `SocketAddress` for `send()`**. `send(buffer, socketAddress)` is a convenience function. 
  However, when sending multiple packets to the same destination, one should use 
  `path = send(buffer, path)` or `connect()` + `write()` in order to avoid frequent path lookups.

- **Using expired path (client).** When using `send(buffer, path)` with an expired `Path`, the 
  channel will transparently look up a new path. This works but causes a path lookup for every 
  `send()`. Solution: always use the latest path returned by send, e.g. `path = send(buffer, path)`.

- **Using expired path (server).** When using `send(buffer, path)` with an expired `Path`, the 
  channel will simple send it anyway.

## Configuration

### Bootstrapping / daemon
JPAN can be used in standalone mode or with a local daemon.
- Standalone mode will directly connect to a topology server and control server, in a properly
  configured AS this should all happen automatically - this is the **RECOMMENDED WAY** of using this 
  library.
- The daemon is available if you have a [local installation of SCION](https://docs.scion.org/en/latest/dev/run.html).

Without daemon there are several methods for bootstrapping, including several methods of DNS lookup
or specifying a local topology file or a topology server address. 

The method `Scion.defaultService()` (internally called by `ScionDatagramChannel.open()`) will 
attempt to get network information in the following order until it succeeds:
- For debugging: Check for local topology file (if file name is provided)
- For debugging: Check for bootstrap server address (if address is provided)
- For debugging: Check for DNS records in non-default DNS server (if DNS server name is provided)
- Check for path service (new endhost API) 
- Check for to daemon
- Check search domain (as given in `/etc/resolv.conf`) for topology server:
  - Detect search domains
    - Use default OS search domain (e.g. as given in `/etc/resolv.conf`)
    - Identify public IP from interface list or via whoami. Als check subnet registration. 
      Then lookup domain via PTR or SOA records. 
  - Detect SCION discovery server (bootstrap server)
    - NAPTR records with "A" and "S" flags, IPv4 and IPv6 [RFC 2915]
    - SRV records, IPv4 and IPv6 [RFC 2782]
    - DNS SD via PTR records, IPv4 and IPv6 [RFC 6763]
    - Port: TXT record: `"x-sciondiscovery"`
    - See also [SCION Bootstrapping](https://docs.scion.org/en/latest/dev/design/endhost-bootstrap.html)

| Option                                        | Java property                       | Environment variable            | Default value   |
|-----------------------------------------------|-------------------------------------|---------------------------------|-----------------|
| Daemon port, IP, or IP:port                   | `org.scion.daemon`                  | `SCION_DAEMON`                  | localhost:30255 | 
| Bootstrap topology file path                  | `org.scion.bootstrap.topoFile`      | `SCION_BOOTSTRAP_TOPO_FILE`     |                 | 
| Bootstrap server host + port (typically 8041) | `org.scion.bootstrap.host`          | `SCION_BOOTSTRAP_HOST`          |                 |
| Bootstrap DNS host name (with NAPTR or SRV)   | `org.scion.bootstrap.naptr.name`    | `SCION_BOOTSTRAP_NAPTR_NAME`    |                 | 
| Bootstrap with path service (new endhost API) | `org.scion.bootstrap.pathservice`   | `SCION_BOOTSTRAP_PATH_SERVICE`  |                 | 
| List of DNS search domains                    | `org.scion.dnsSearchDomains`        | `SCION_DNS_SEARCH_DOMAINS`      |                 |
| NAT/STUN policy, see [here](doc/NAT.md)       | `org.scion.nat`                     | `SCION_NAT`                     | off             |
| NAT mapping timeout in seconds                | `org.scion.nat.mapping.timeout`     | `SCION_NAT_MAPPING_TIMEOUT`     | 110             |
| Send NAT mapping keep-alive packets           | `org.scion.nat.mapping.keepalive`   | `SCION_NAT_MAPPING_KEEPALIVE`   | false           |
| STUN server                                   | `org.scion.nat.stun.server`         | `SCION_NAT_STUN_SERVER`         |                 |
| STUN response timeout in milliseconds         | `org.scion.nat.stun.timeout`        | `SCION_NAT_STUN_TIMEOUT`        | 10              |
| Use OS search domains, e.g. /etc/resolv.conf  | `org.scion.test.useOsSearchDomains` | `SCION_USE_OS_SEARCH_DOMAINS`   | true            |


### DNS

JPAN will check the OS default DNS server to resolve SCION addresses.
In addition, addresses can be specified in a `/etc/scion/hosts` file. The location of the hosts file
is configurable, see next section.  

### SHIM

The SHIM is required to support the `dispatched_ports` feature in topo files.
Every JPAN application will try to start a  
[SHIM dispatcher](https://docs.scion.org/en/latest/dev/design/router-port-dispatch.html)
on port 30041, unless the port range is set to `all`. 

A SHIM does no traffic checking, it blindly forwards every parseable packet to the inscribed SCION 
destination address. That means a JPAN SHIM will act as a SHIM for all applications on a machine.
(The slight problem being that if the application stops, the SHIM is stopped, leaving all other 
applications without a SHIM).

If the SHIM cannot be started because port 30041 is taken, the application will start anyway, 
assuming that another SHIM is running on 30041.
 
Whether a SHIM is started can be controlled with a configuration option, see below.

### Other Options

| Option                                                                                                               | Java property                                     | Environment variable                            | Default value      |
|----------------------------------------------------------------------------------------------------------------------|---------------------------------------------------|-------------------------------------------------|--------------------|
| Timeout for daemon / control service (milliseconds).                                                                 | `org.scion.controlPlane.timeoutMs`                | `SCION_CONTROL_PLANE_TIMEOUT_MS`                |                    |
| Location of `hosts` file. Multiple location can be specified separated by `;`.                                       | `org.scion.hostsFiles`                            | `SCION_HOSTS_FILES`                             | `/etc/scion/hosts` |
| Path expiry margin. Before sending a packet a new path is requested if the path is about to expire within X seconds. | `org.scion.pathExpiryMargin`                      | `SCION_PATH_EXPIRY_MARGIN`                      | `10`               |
| Path polling interval. Interval at which a client may poll for new paths for connected channels or sockets.          | `org.scion.pathPollIntervalSec`                   | `SCION_PATH_POLL_INTERVAL_SEC`                  | `60`               |
| Minimize segment requests to local AS at the cost of reduced range of path available.                                | `org.scion.resolver.experimentalMinimizeRequests` | `EXPERIMENTAL_SCION_RESOLVER_MINIMIZE_REQUESTS` | `false`            |
| Start SHIM. If not set, SHIM will be started unless the dispatcher port range is set to `all`.                       | `org.scion.shim`                                  | `SCION_SHIM`                                    |                    |

`EXPERIMENTAL_SCION_RESOLVER_MINIMIZE_REQUESTS` is a non-standard option that requests CORE segments only 
if noother path can be constructed. This may reduce response time when requesting new paths. It is very likely,
but not guaranteed, that the shortest path (fewest hops) will be available. 
If this property is not set (= default), CORE segments are always requested, resulting in additional
path options. While these additional path options almost always result in longer paths, they may have 
other advantages.  

## FAQ / Troubleshooting

### Enable logging

JPAN uses the slf4j logging library. To use it, you have to install a logger. For example, to use the slf4j simple logger, add the following to your dependencies (eg. maven pom file):

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.13</version>
</dependency>
```

Then enable the logger by placing a [`simplelogger.properties`](src/test/resources/simplelogger.properties) 
file into you resources folder, or enable logging programmatically with 
`System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "INFO");` 
before using *any* JPAN code. 

### No DNS search domain found. Please check your /etc/resolv.conf or similar. / No DNS record found for bootstrap server.

You may have to set the DNS search domain explicitly to a server with SCION NAPTR records. For example (works only if you are inside ETH):  


This happens, for example, on Windows when using a VPN.
There are several solutions to this (aside from reconfiguring your system).

#### Solution #1: Provide search domain

This is useful if you have access to a search domain with a NAPTR record of the discovery server.
You can specify the search domain via environment variable `SCION_DNS_SEARCH_DOMAINS` or via Java 
property `org.scion.dnsSearchDomains`. 
One example of a search domain is `ethz.ch.` but it obviously works only when you are in the ETH 
domain. For example:

```java
System.setProperty(Constants.PROPERTY_DNS_SEARCH_DOMAINS, "yourDomain.org.");
```

#### Solution #2: Provide a discovery server

You can directly set the IP:port of the discovery server via environment variable 
`SCION_BOOTSTRAP_HOST` or via Java property `org.scion.bootstrap.host`. 

```java
System.setProperty(Constants.PROPERTY_BOOTSTRAP_HOST, "serverIP:8041");
```

#### Solution #3: Provide a topology file

If you have a topology file, you can specify it via environment variable `SCION_BOOTSTRAP_TOPO_FILE` 
or via Java property `org.scion.bootstrap.topoFile`:

```java
System.setProperty(Constants.PROPERTY_BOOTSTRAP_HOST, "yourTopoFile.json");
```

### Local testbed (scionproto) does not contain any path

A common problem is that the certificates of the testbed have expired (default validity: 3 days).
The certificates can be renewed by recreating the network with 
`./scion.sh topology -c <your_topology_here.topo>`.

Another thing to remember is that the network needs a few seconds to exchange beacons and 
discover segments. Waiting a few seconds may fix the missing paths problem.

### ERROR: "TRC NOT FOUND"
This error occurs when requesting a path with an ISD/AS code that is not
known in the network.

### Response packets cannot get past a local NAT or PROXY
Solving this requires some additional configuration, see `setOverrideSourceAddress` above.

### IllegalThreadStateException
```
[WARNING] thread Thread[grpc-default-worker-ELG-1-1,5,com.app.SimpleScmp] was interrupted but is still alive after waiting at least 15000msecs
...
[WARNING] Couldn't destroy threadgroup org.codehaus.mojo.exec.ExecJavaMojo$IsolatedThreadGroup[name=com.app.SimpleScmp,maxpri=10]
java.lang.IllegalThreadStateException
at java.lang.ThreadGroup.destroy (ThreadGroup.java:803)
at org.codehaus.mojo.exec.ExecJavaMojo.execute (ExecJavaMojo.java:321)
...
```
This can happen in your JUnit tests if the `ScionService` is not closed properly.
To fix, close the service manually, for example by calling `ScionService.close()`.
In normal applications this is rarely necessary because services are closed automatically by a 
shut-down hook when the application shuts down.

### "Cannot find symbol javax.annotation.Generated"

```
Compilation failure: Compilation failure: 
[ERROR] ...<...>ServiceGrpc.java:[7,18] cannot find symbol
[ERROR]   symbol:   class Generated
[ERROR]   location: package javax.annotation
```

This can be fixed by building with Java JDK 1.8.

### Failures/timeout when running tests on MacOS

This happens because the tests uses local IP addresses other than 127.0.0.1, e.g. 127.0.0.15.
These are blocked by default on MacOS. To enable these addresses you can run the script
`./config/enable-macos-loopback.sh`.


## License

This project is licensed under the Apache License, Version 2.0 
(see [LICENSE](LICENSE) or https://www.apache.org/licenses/LICENSE-2.0).
