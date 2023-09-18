# SCION Java client

A Java client for [SCION](https://scion.org).

## Configuration

| Option             | Java property                 | Environment variable | Default value |
|--------------------|-------------------------------|----------------------|---------------|
| Path service host  | `org.scion.daemon.host`       | `SCION_DAEMON_HOST`  | 127.0.0.12    |
| Path service port  | `org.scion.daemon.port`       | `SCION_DAEMON_PORT`  | 30255         | 


## FAQ / Trouble shooting

### Cannot find symbol javax.annotation.Generated

```
Compilation failure: Compilation failure: 
[ERROR] ...<...>ServiceGrpc.java:[7,18] cannot find symbol
[ERROR]   symbol:   class Generated
[ERROR]   location: package javax.annotation
```

This can be fixed by building with Java JDK 1.8.


