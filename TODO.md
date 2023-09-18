# TODO

- Option to disable UDP overlay / pseudo header

## Style

- Use puppycrawl checkstyle plugin
  - to verify style in CI
  - for auto formatting in IntelliJ (if possible). How about other tools, e.g. MS code?

- Change line length to 120 




## Testing
- Fuzz test
- Large packets that require Splitting
- Interleaved response on server, e.g. Receive from A, Receive from B, send to B, send to A (see also NIO)
- Test MTU exceed with proper network
- Test SCMP handling, see Design.



## Design
- Decide how to handle 
  - SCMP errors -> Exception? Log? Ignore?
  - Large packets, e 
- Look at NIO integration?
- Path selection & path policies

- Where to place generated proto files? They are currently in `target` but could be in `scr/java`...
 
