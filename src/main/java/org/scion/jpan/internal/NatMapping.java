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
 * This class provides utility functions to detect the external IP address of a potential NAT that
 * sits between the local device and a border router.
 */
public class NatMapping {

  private static final Logger log = LoggerFactory.getLogger(NatMapping.class);

  private final long localIsdAs;
  private NatMode mode;
  private Entry commonAddress;
  private final Map<InetSocketAddress, Entry> sourceIPs = new HashMap<>();
  private final ByteBuffer buffer = ByteBuffer.allocateDirect(100);
  private final DatagramChannel channel;
  private InetAddress externalIP;
  private final Timer timer;
  private final int natMappingTimeoutSeconds = Config.getNatMappingTimeout(); // seconds
  private final int stunTimeoutMs = Config.getStunTimeoutMs();

  private NatMapping(
      DatagramChannel channel, long localIsdAs, List<InetSocketAddress> borderRouters) {
    this.channel = channel;
    this.localIsdAs = localIsdAs;
    this.mode = NatMode.NOT_INITIALIZED;
    this.timer = new Timer();
    boolean useTimer = Config.useNatMappingKeepAlive();

    for (InetSocketAddress brAddress : borderRouters) {
      // Note: All links on a BR share the same internally visible port.
      sourceIPs.computeIfAbsent(
          brAddress,
          k -> {
            Entry e = new Entry(null, brAddress);
            if (useTimer) {
              timer.schedule(new NatMappingTimerTask(e), natMappingTimeoutSeconds * 1000L);
            }
            return e;
          });
    }
  }

  public synchronized void touch(InetSocketAddress borderRouterAddress) {
    Entry e = sourceIPs.get(borderRouterAddress);
    if (e == null) {
      log.info("No border router found for {}", borderRouterAddress);
      // TODO instead we could check the path for "empty"
      return;
    }
    e.touch();
  }

  private void update(NatMode mode, InetSocketAddress address) {
    this.mode = mode;
    if (commonAddress == null) {
      commonAddress = new Entry(address, null);
    } else {
      commonAddress.updateSource(address);
    }
  }

  public synchronized InetSocketAddress getMappedAddress(Path path) {
    switch (mode) {
      case NO_NAT:
        return commonAddress.getMappedSource();
      case STUN_SERVER:
        if (commonAddress.isExpired(mode, natMappingTimeoutSeconds)) {
          InetSocketAddress address = tryCustomServer(true);
          if (address == null) {
            String custom = Config.getNatStunServer();
            throw new ScionRuntimeException(
                "Failed to connect to STUN servers: \"" + custom + "\"");
          }
          commonAddress.updateSource(address);
        }
        return commonAddress.getMappedSource();
      case BR_STUN:
        return getMappedAddressFromBR(path);
      case NOT_INITIALIZED:
      default:
        throw new IllegalStateException();
    }
  }

  public InetSocketAddress getMappedAddressFromBR(Path path) {
    Entry entry = sourceIPs.get(path.getFirstHopAddress());
    if (entry == null) {
      // This is not a known border router, the destination is presumably in the local AS
      if (path.getRemoteIsdAs() == localIsdAs) {
        return commonAddress.getMappedSource();
      }
      throw new IllegalArgumentException("Unknown border router: " + path.getFirstHopAddress());
    }
    if (entry.getSource() == null || entry.isExpired(mode, natMappingTimeoutSeconds)) {
      // null: try detection again, border router may have been unresponsive earlier on.
      try {
        entry.updateSource(tryStunAddress(entry.firstHop));
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
    switch (Config.getNat()) {
      case "OFF":
        return ConfigMode.STUN_OFF;
      case "BR":
        return ConfigMode.STUN_BR;
      case "CUSTOM":
        return ConfigMode.STUN_CUSTOM;
      case "AUTO":
        return ConfigMode.STUN_AUTO;
      default:
        throw new IllegalArgumentException(
            "Illegal value for NAT config: \"" + Config.getNat() + "\"");
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
        InetSocketAddress address = tryCustomServer(true);
        if (address == null) {
          String custom = Config.getNatStunServer();
          throw new ScionRuntimeException("Failed to connect to STUN servers: \"" + custom + "\"");
        }
        update(NatMode.STUN_SERVER, address);
        break;
      case STUN_BR:
        tryStunBorderRouters();
        // The common address is used for communication within the local AS. In this case we
        // we don't have a mapped address so we rely on the remote host to reply to the
        // underlay address (which may be NATed or not) and ignore the SRC address,
        // TODO ????? Why do we have the commonAddress? As an optimistic fallback?
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

  private InetSocketAddress tryCustomServer(boolean throwOnFailure) {
    String custom = Config.getNatStunServer();
    if (!throwOnFailure && (custom == null || custom.isEmpty())) {
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
      log.info("NAT AUTO: Found custom STUN server.");
      return;
    }

    // Check if BR is STUN enabled (gives correct address)
    if (tryStunBorderRouters()) {
      update(NatMode.BR_STUN, getLocalAddress());
      log.info("NAT AUTO: Found STUN enabled border routers.");
      return;
    }

    // At this point we can only hope that there is no NAT.
    int localPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
    InetAddress sourceIP = getExternalIP();
    source = new InetSocketAddress(sourceIP, localPort);
    update(NatMode.NO_NAT, source);
    log.info("Could not find custom STUN server or NAT enabled border routers. Hoping for no NAT.");
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
        InetSocketAddress address = tryStunAddress(stunServer);
        if (address != null) {
          return address;
        }
      } catch (IOException e) {
        // Ignore, try next server
      }
    }
    return null;
  }

  private boolean tryStunBorderRouters() throws IOException {
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

  private InetSocketAddress tryStunAddress(InetSocketAddress server) throws IOException {
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
          if (e != null) {
            e.updateSource(external);
            if (external != null && error.get() == null && ids.isEmpty()) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  public void close() {
    timer.cancel();
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
    private long lastUsed;

    Entry(InetSocketAddress source, InetSocketAddress firstHop) {
      this.source = source;
      this.firstHop = firstHop;
      touch();
    }

    static Entry createForStunServer(InetSocketAddress server) {
      return new Entry(null, server);
    }

    InetSocketAddress getSource() {
      return source;
    }

    private void updateSource(InetSocketAddress source) {
      touch();
      this.source = source;
    }

    private InetSocketAddress getMappedSource() {
      touch();
      return source;
    }

    private void touch() {
      lastUsed = System.currentTimeMillis();
    }

    private boolean isExpired(NatMode mode, long natMappingTimeoutSeconds) {
      return mode != NatMode.NO_NAT
          && (System.currentTimeMillis() - lastUsed) > (natMappingTimeoutSeconds * 1000L);
    }
  }

  private class NatMappingTimerTask extends TimerTask {
    private final Entry e;
    private final Exception e2;

    NatMappingTimerTask(Entry e) {
      this.e = e;
      this.e2 = new RuntimeException();
    }

    NatMappingTimerTask(Entry e, Exception e2) {
      this.e = e;
      this.e2 = e2;
    }

    @Override
    public void run() {
      long nextRequiredUse = e.lastUsed + natMappingTimeoutSeconds * 1000L - 1;
      if (System.currentTimeMillis() >= nextRequiredUse) {
        // Send a simple message to the desired BR. Do not wait for an answer.
        ByteBuffer buf = ByteBuffer.allocate(100);
        STUN.writeRequest(buf);
        buf.flip();
        try {
          channel.send(buf, e.firstHop);
        } catch (IOException ex) {
          log.error("Error while sending keep alive to {}", e.firstHop, ex);
        }

        e.touch();
      }
      // reset timer -> assert >= 1
      long delay = Math.max(nextRequiredUse - System.currentTimeMillis(), 1);
      timer.schedule(new NatMappingTimerTask(e, e2), delay);
    }
  }
}
