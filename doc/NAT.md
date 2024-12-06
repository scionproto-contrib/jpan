# NAT

Scion clients need to be able to discover potential NATs and the public mapped address.
The public address needs to be put into the SCION header so that the border router can
correctly route packets from outside the AS to the correct NAT IP/port.

Scion uses STUN to detect NATs and mapped addresses.

The primary (and best) solution is that border routers can answer STUN requests.

However, this requires border routers to support STUN. Until that is implemented, JPAN offers
several ways to work around this problem:

- If known, the external address can be set explicitly with:
  ```
  ScionDatagramChannel.setOverrideSourceAddress(knownExternalAddress);
  ```
  This will override all other settings.
- There is an environment variable (`SCION_STUN`) and a Java property (`org.scion.stun`) that
  can be configured to follow different STUN approaches:
    - `STUN_CUSTOM`: define a custom stun server. The server address (e.g. `192.168.0.42:3478`) can
      be
      set via environment variable (`SCION_STUN_SERVER`) or a Java property (
      `org.scion.stun.server`).
      This forces JPAN to use the provided STUN server instead of the border router.
      If no custom server is defined, JPAN will try one of several public known STUN servers to
      detect it's external address.
    - `STUN_BR`: Try the border router (as it should be anyway)
    - `OFF`: do not attempt STUN resolution
    - `AUTO`: This goes through all the above options until it finds one that works.
        1. Check for explicitly defined STUN server with `SCION_STUN_SERVER` or
           `org.scion.stun.server`
        2. Try STUN on the border router. JPAN will wait 10ms for an answer.
        3. Try SCMP on the border router. This may succeed if no NAT is present.
        4. Try public stun server.
- STUN response timeout in milliseconds (`org.scion.stun.timeout` or `SCION_STUN_TIMEOUT`):
  This is the time (in milliseconds) that the JPAN client will wait for a response from the 
  border router or STUN server before timing out. 
- STUN mapping timeout in seconds (`org.scion.stun.mapping.timeout` or `SCION_STUN_MAPPING_TIMEOUT`):  
  This is the time (in seconds) that the JPAN client will allow to pass after the last 
  `send()`/`receive()` before requiring a rediscovery pof the mapping.
