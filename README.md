# SCION Java client

A Java client for [SCION](https://scion.org).


## FAQ / Trouble shooting

### Cannot find symbol javax.annotation.Generated

```
Compilation failure: Compilation failure: 
[ERROR] ...<...>ServiceGrpc.java:[7,18] cannot find symbol
[ERROR]   symbol:   class Generated
[ERROR]   location: package javax.annotation
```

This can be fixed by building with Java JDK 1.8.



### TODO
- Where to place generated files? Currently in `target` but could be in `scr/java`...
- 

