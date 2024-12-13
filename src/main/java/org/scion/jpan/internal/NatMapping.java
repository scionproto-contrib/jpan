// Copyright 2024 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.jpan.internal;

import static org.scion.jpan.Constants.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import org.scion.jpan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides utility functions to detect: the external IP address of a potential NAT
 * between the device and a border router.
 */
public class NatMapping {

  private static final int NAT_UDP_MAPPING_TIMEOUT =
      ScionUtil.getPropertyOrEnv(
          PROPERTY_NAT_MAPPING_TIMEOUT,
          ENV_NAT_MAPPING_TIMEOUT,
          DEFAULT_NAT_MAPPING_TIMEOUT); // seconds
  private static final Logger log = LoggerFactory.getLogger(NatMapping.class);

  private static final int stunTimeoutMs =
      ScionUtil.getPropertyOrEnv(
          Constants.PROPERTY_NAT_STUN_TIMEOUT_MS,
          Constants.ENV_NAT_STUN_TIMEOUT_MS,
          Constants.DEFAULT_NAT_STUN_TIMEOUT_MS);

  // TODO use SimpleCache

  private final long localIsdAs;
  private NatMode mode;
  private long lastUsed;
  private InetSocketAddress commonAddress;
  private final Map<InetSocketAddress, Entry> sourceIPs = new HashMap<>();
  // TODO attack: send SCMP (error?) or STUN with > 100 byte length
  private final ByteBuffer buffer = ByteBuffer.allocateDirect(100);
  private final DatagramChannel channel;
  private InetAddress externalIP;

  private NatMapping(
      DatagramChannel channel, long localIsdAs, List<InetSocketAddress> borderRouters) {
    this.channel = channel;
    this.localIsdAs = localIsdAs;
    this.mode = NatMode.NOT_INITIALIZED;
    for (InetSocketAddress brAddress : borderRouters) {
      // Note: All links on a BR share the same internally visible port.
      sourceIPs.computeIfAbsent(brAddress, k -> new Entry(null, brAddress));
    }
    touch();
  }

  private boolean isExpired() {
    return mode != NatMode.NO_NAT
        && (System.currentTimeMillis() - lastUsed) > (NAT_UDP_MAPPING_TIMEOUT * 1000L);
  }

  private void touch() {
    lastUsed = System.currentTimeMillis();
  }

  private void update(NatMode mode, InetSocketAddress address) {
    this.mode = mode;
    this.commonAddress = address;
    touch();
  }

  public synchronized InetSocketAddress getMappedAddress(Path path) {
    // NAT mapping expired?
    if (isExpired()) {
      // We have to rediscover ALL border routers because we don't know whether they are all
      // behind
      // the same NAT or in the same subnet.
      try {
        // refresh
        detectSourceAddress();
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
    }
    touch();
    if (mode == NatMode.NO_NAT) {
      return commonAddress;
    } else if (mode == NatMode.STUN_SERVER) {
      return commonAddress;
    } else if (mode == NatMode.NOT_INITIALIZED) {
      throw new IllegalStateException();
    }

    // Find mapping
    Entry entry = sourceIPs.get(path.getFirstHopAddress());
    if (entry == null) {
      // This is not a known border router, the destination is presumably in the local AS
      if (path.getRemoteIsdAs() == localIsdAs) {
        return commonAddress;
      }
      throw new IllegalArgumentException("Unknown border router: " + path.getFirstHopAddress());
    }
    if (entry.getSource() == null) {
      // try detection again, border router may have been unresponsive earlier on.
      try {
        detectSourceAddress();
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
      if (entry.getSource() == null) {
        throw new IllegalStateException("No mapped source for: " + path.getFirstHopAddress());
      }
    }
    return entry.getSource();
  }

  /**
   * Determine the network interface and external IP used for this AS.
   *
   * @return External address
   */
  public synchronized InetAddress getExternalIP() {
    if (externalIP == null) {
      try {
        InetSocketAddress local = ((InetSocketAddress) channel.getLocalAddress());
        externalIP = local.getAddress();
        if (externalIP.isAnyLocalAddress()) {
          InetSocketAddress firstHop = sourceIPs.values().iterator().next().firstHop;
          externalIP = ExternalIpDiscovery.getExternalIP(firstHop);
        }
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
    }
    return externalIP;
  }

  private static ConfigMode getConfig() {
    String v = ScionUtil.getPropertyOrEnv(PROPERTY_NAT, ENV_NAT, DEFAULT_NAT);
    v = v.toUpperCase();
    switch (v) {
      case "OFF":
        return ConfigMode.STUN_OFF;
      case "BR":
        return ConfigMode.STUN_BR;
      case "CUSTOM":
        return ConfigMode.STUN_CUSTOM;
      case "AUTO":
        return ConfigMode.STUN_AUTO;
      default:
        throw new IllegalArgumentException("Illegal value for STUN config: \"" + v + "\"");
    }
  }

  public static NatMapping createMapping(
      long localIsdAs, DatagramChannel channel, List<InetSocketAddress> borderRouters) {
    if (borderRouters.isEmpty()) {
      log.warn("No border routers found in local topology information.");
    }

    // ASInfo entry
    NatMapping natMapping = new NatMapping(channel, localIsdAs, borderRouters);

    // detect addresses
    try {
      natMapping.detectSourceAddress();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
    return natMapping;
  }

  private void detectSourceAddress() throws IOException {
    ConfigMode configMode = getConfig(); // The AsInfoMode may change, e.g. in case of AUTO
    switch (configMode) {
      case STUN_OFF:
        update(NatMode.NO_NAT, getLocalAddress());
        break;
      case STUN_AUTO:
        autoDetect();
        break;
      case STUN_CUSTOM:
        InetSocketAddress addr = tryCustomServer(true);
        if (addr == null) {
          String custom =
              ScionUtil.getPropertyOrEnv(
                  PROPERTY_NAT_STUN_SERVER, ENV_NAT_STUN_SERVER, DEFAULT_NAT_STUN_SERVER);
          throw new ScionRuntimeException("Failed to connect to STUN servers: " + custom);
        }
        update(NatMode.STUN_SERVER, addr);
        break;
      case STUN_BR:
        tryStunBorderRouter();
        // The common address is used for communication within the local AS. In this case we
        // we don't have a mapped address so we rely on the remote host to reply to the
        // underlay address (which may be NATed or not) and ignore the SRC address,
        update(NatMode.BR_STUN, getLocalAddress());
        break;
      default:
        throw new UnsupportedOperationException("Unknown config mode: " + configMode);
    }
  }

  private InetSocketAddress getLocalAddress() throws IOException {
    InetSocketAddress local = ((InetSocketAddress) channel.getLocalAddress());
    InetAddress localIP = local.getAddress();
    if (localIP.isAnyLocalAddress()) {
      localIP = getExternalIP();
      local = new InetSocketAddress(localIP, local.getPort());
    }
    return local;
  }

  private InetSocketAddress tryCustomServer(boolean useDefault) {
    String defaultSrv = useDefault ? DEFAULT_NAT_STUN_SERVER : null;
    String custom =
        ScionUtil.getPropertyOrEnv(PROPERTY_NAT_STUN_SERVER, ENV_NAT_STUN_SERVER, defaultSrv);
    if (!useDefault && (custom == null || custom.isEmpty())) {
      // Ignore empty sever address if we don't rely on it
      return null;
    }
    return tryStunServer(custom);
  }

  private void autoDetect() throws IOException {
    // Check CUSTOM
    InetSocketAddress source = tryCustomServer(false);
    if (source != null) {
      update(NatMode.STUN_SERVER, source);
      return;
    }

    // Check BR
    // - Check if BR is NAT enabled (gives correct address)
    // Try first with STUN, this should eventually be available everywhere
    if (tryStunBorderRouter()) {
      update(NatMode.BR_STUN, getLocalAddress());
      return;
    }

    // - Check if BR responds to tr/ping (is reachable)
    // At this point we should have a local address with port.
    int localPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
    InetAddress sourceIP = getExternalIP();
    source = new InetSocketAddress(sourceIP, localPort);
    if (isBorderRouterReachable(source)) {
      // TODO this is wrong because the BR responds to the underlay i.o. SRC.
      // However, this may really mean that there is no NAT. SInce we can't tell,
      // we just assume there is none and return.
      update(NatMode.NO_NAT, source);
      return;
    }

    // Check DEFAULT servers
    // Check CUSTOM
    source = tryCustomServer(true);
    // TODO we should try default servers only if the assumed loacl IP is from the "public" range
    if (source != null) {
      // - Verify with tr/ping BR -> fail if not reachable
      if (isBorderRouterReachable(source)) {
        update(NatMode.STUN_SERVER, source);
        return;
      }
    }
    log.error("Could not find a STUN/NAT solution for any border routers.");
    throw new ScionRuntimeException("Could not find a STUN/NAT solution for the border router.");
  }

  private InetSocketAddress tryStunServer(String stunAddress) {
    String[] servers = stunAddress.split(";");
    for (String server : servers) {
      InetSocketAddress stunServer;

      // decode STUN server address
      try {
        stunServer = IPHelper.toInetSocketAddress(server);
      } catch (IllegalArgumentException e) {
        String prefix =
            "Please provide a valid STUN server address as 'address:port' in "
                + "SCION_STUN_SERVER or org.scion.stun.server, was: ";
        log.error("{}\"{}\"", prefix, server);
        throw new IllegalArgumentException(prefix + "\"" + server + "\"");
      }

      // contact STUN server
      try {
        InetSocketAddress address = tryStunCustomServer(stunServer);
        if (address != null) {
          return address;
        }
      } catch (IOException e) {
        // Ignore, try next server
      }
    }
    return null;
  }

  private boolean tryStunBorderRouter() throws IOException {
    boolean isBlocking = channel.isBlocking();
    try (Selector selector = channel.provider().openSelector()) {
      channel.configureBlocking(false);
      // start receiver
      channel.register(selector, SelectionKey.OP_READ, channel);
      return doStunRequest(selector, sourceIPs.values());
    } finally {
      channel.configureBlocking(isBlocking);
    }
  }

  private InetSocketAddress tryStunCustomServer(InetSocketAddress server) throws IOException {
    boolean isBlocking = channel.isBlocking();
    try (Selector selector = channel.provider().openSelector()) {
      channel.configureBlocking(false);
      // start receiver
      channel.register(selector, SelectionKey.OP_READ, channel);
      List<Entry> servers = new ArrayList<>();
      servers.add(Entry.createForStunServer(server));
      if (doStunRequest(selector, servers)) {
        return servers.get(0).getSource();
      }
      return null;
    } finally {
      channel.configureBlocking(isBlocking);
    }
  }

  private boolean doStunRequest(Selector selector, Collection<Entry> servers) throws IOException {
    final HashMap<STUN.TransactionID, Entry> ids = new HashMap<>();

    // Start sending
    for (Entry e : servers) {
      buffer.clear();
      STUN.TransactionID id = STUN.writeRequest(buffer);
      ids.put(id, e);
      buffer.flip();
      channel.send(buffer, e.firstHop);
    }

    // Wait
    while (selector.select(stunTimeoutMs) > 0) {
      Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
      while (iter.hasNext()) {
        SelectionKey key = iter.next();
        iter.remove();
        if (key.isReadable()) {
          DatagramChannel channelIn = (DatagramChannel) key.channel();
          buffer.clear();
          channelIn.receive(buffer);
          buffer.flip();

          final ByteUtil.MutRef<String> error = new ByteUtil.MutRef<>();
          final ByteUtil.MutRef<STUN.TransactionID> id = new ByteUtil.MutRef<>();
          InetSocketAddress external = STUN.parseResponse(buffer, ids::containsKey, id, error);
          Entry e = ids.remove(id.get());
          e.updateSource(external);
          if (external != null && error.get() == null) {
            if (ids.isEmpty()) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private boolean isBorderRouterReachable(InetSocketAddress src) throws IOException {
    boolean isBlocking = channel.isBlocking();
    try (Selector selector = channel.provider().openSelector()) {
      channel.configureBlocking(false);
      // start receiver
      channel.register(selector, SelectionKey.OP_READ, channel);

      Collection<Entry> entries = sourceIPs.values();
      sendSCMPs(entries, src);
      // For now, ew report success if at least one BE is reachable.
      // We should not simply enforce all BR to be reachable, BRs may be temporarily unreachable.
      return receiveSCMPs(entries, selector) > 0;
    } finally {
      channel.configureBlocking(isBlocking);
    }
  }

  private void sendSCMPs(Collection<Entry> entries, InetSocketAddress src) throws IOException {
    for (Entry e : entries) {
      buffer.clear();
      ScionHeaderParser.write(
          buffer,
          8,
          0,
          localIsdAs,
          src.getAddress().getAddress(),
          localIsdAs,
          e.firstHop.getAddress().getAddress(),
          InternalConstants.HdrTypes.SCMP,
          0); // correct?
      ScmpParser.buildScmpPing(buffer, Scmp.Type.INFO_128, src.getPort(), 0, new byte[0]);
      buffer.flip();
      channel.send(buffer, e.firstHop);
    }
  }

  private int receiveSCMPs(Collection<Entry> entries, Selector selector) throws IOException {
    int nReceived = 0;
    while (true) {
      int i = selector.select(stunTimeoutMs);
      if (i == 0) {
        return nReceived;
      }
      Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
      while (iter.hasNext()) {
        SelectionKey key = iter.next();
        if (key.isReadable()) {
          DatagramChannel channelIn = (DatagramChannel) key.channel();
          channelIn.receive(buffer);
          buffer.flip();
          if (ScionHeaderParser.extractNextHeader(buffer) == InternalConstants.HdrTypes.SCMP
              && ScmpParser.extractType(buffer) == Scmp.Type.INFO_129) {
            // TODO check sequence number????
            nReceived++;
            if (nReceived == entries.size()) {
              return nReceived;
            }
          }
        }
      }
    }
  }

  /** See {@link Constants#PROPERTY_NAT} for details. */
  private enum ConfigMode {
    /** No STUN discovery */
    STUN_OFF,
    /** Discovery using STUN interface of border routers */
    STUN_BR,
    /** Discovery using custom STUN server */
    STUN_CUSTOM,
    /**
     * Use auto detection.<br>
     * 1) Check for custom STUN setting and use if possible<br>
     * 2) Check border routers if they support STUN (timeout = 10ms)<br>
     * 3) If border router responds to traceroute/ping, do not use STUN at all<br>
     * 4) Try public stun server (optional: recheck with tr/ping, bail out if it fails)<br>
     */
    STUN_AUTO
  }

  private enum NatMode {
    /** Not initialized. */
    NOT_INITIALIZED,
    /** Border routers support STUN. */
    BR_STUN,
    /** No NAT detected, just use local channel address. */
    NO_NAT,
    /** Custom or public STUN server used, same local address for all border routers. */
    STUN_SERVER
  }

  private static class Entry {
    private InetSocketAddress source;
    private final InetSocketAddress firstHop;

    Entry(InetSocketAddress source, InetSocketAddress firstHop) {
      this.source = source;
      this.firstHop = firstHop;
    }

    static Entry createForStunServer(InetSocketAddress server) {
      return new Entry(null, server);
    }

    InetSocketAddress getSource() {
      return source;
    }

    public void updateSource(InetSocketAddress source) {
      this.source = source;
    }
  }
}
