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



## Design consideration

- Use Maven (instead of Gradle or something else): Maven is still the most used framework and arguable the best (convention over configuration)
- Use Java 8: Still the most used JDK (TODO provide reference).
  Main problem: no module support
- Use slfj4 logging framework: widely used ond flexible.
- Use Junit 5.
- Use Google style guide for code


### TODO
- Where to place generated files? Currently in `target` but could be in `scr/java`...
- 

