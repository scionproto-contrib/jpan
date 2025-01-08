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
- There is an environment variable (`SCION_NAT`) and a Java property (`org.scion.nat`) that
  can be configured to follow different STUN approaches:
    - `STUN_CUSTOM`: define a custom stun server. The server address (e.g. `192.168.0.42:3478`) can
      be set via environment variable (`SCION_NAT_STUN_SERVER`) or a Java property (
      `org.scion.nat.stun.server`).
      This forces JPAN to use the provided STUN server instead of the border router.
    - `STUN_BR`: Try the border router (as it should be anyway)
    - `OFF`: do not attempt STUN resolution
    - `AUTO`: This goes through all the above options until it finds one that works.
        1. Check for explicitly defined STUN server with `SCION_NAT_STUN_SERVER` or
           `org.scion.nat.stun.server`. If that works, use the detected address and return.
        2. Try STUN on the border router. JPAN will wait 10ms for an answer. If any border router
           answers, use the detected addresses and return.
        3. Assume that not NAT is present and return the external interface address.
- STUN response timeout in milliseconds (`org.scion.nat.stun.timeout` or `SCION_NAT_STUN_TIMEOUT`):
  This is the time (in milliseconds) that the JPAN client will wait for a response from the
  border router or STUN server before timing out.
- NAT mapping timeout in seconds (`org.scion.nat.mapping.timeout` or `SCION_NAT_MAPPING_TIMEOUT`):  
  This is the time (in seconds) that the JPAN client will allow to pass after the last
  `send()`/`receive()` before requiring a rediscovery of the mapping.

## Keep Alive

The environment variable (`SCION_NAT_MAPPING_KEEPALIVE`) or the Java property (
`org.scion.nat.mapping.keepalive`) can be used to enable sending keep alive messages.
When enabled, a timer ensure that a STUN packet is sent to every border router
before an assumed timeout occurs. This prevents the NAT from dropping the mapping.

The presumed timeout is equal to `org.scion.nat.mapping.timeout` or `SCION_NAT_MAPPING_TIMEOUT`.
The timeout is counted per border router. It counts from the last sent or received packet from that
border router.

## On-demand STUN (not implemented)

Instead of sending keep-alives, we send STUN packets only during send() and only when the NAT is
assumed to have expired.
Downsides:

- Small delay before sending a packet
- Quite hard to implement, the receive() may be blocked, and may be unblocked() at any time
- Works only for the sender, not for the receiver
