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

package org.scion.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.scion.Constants;
import org.scion.ScionRuntimeException;
import org.scion.ScionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HostsFileParser {

  private static final Logger LOG = LoggerFactory.getLogger(HostsFileParser.class);
  private static final String PATH_LINUX = "/etc/scion/hosts";
  private final String HOSTS_FILES =
      ScionUtil.getPropertyOrEnv(Constants.PROPERTY_HOSTS_FILES, Constants.ENV_HOSTS_FILES);

  private final Map<String, HostEntry> entries = new HashMap<>();

  public static class HostEntry {
    private final long isdAs;
    private final String[] hostNames;
    private final InetAddress address;

    HostEntry(long isdAs, InetAddress address, String[] hostNames) {
      this.isdAs = isdAs;
      this.hostNames = hostNames;
      this.address = address;
    }

    public long getIsdAs() {
      return isdAs;
    }
  }

  public HostsFileParser() {
    init();
  }

  private void init() {
    String hostsFiles;
    String os = System.getProperty("os.name");
    if (HOSTS_FILES != null && !HOSTS_FILES.isEmpty()) {
      hostsFiles = HOSTS_FILES;
    } else if ("Linux".equals(os)) {
      hostsFiles = PATH_LINUX;
    } else {
      hostsFiles = "";
    }

    for (String file : hostsFiles.split(";")) {
      Path path = Paths.get(file);
      if (!Files.exists(path)) {
        LOG.info(PATH_LINUX + " not found.");
        return;
      }
      try (Stream<String> lines = Files.lines(path)) {
        lines.forEach(this::parseLine);
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
    }
  }

  private void parseLine(String line) {
    try {
      String s = line.trim();
      if (s.isEmpty() || s.startsWith("#")) {
        return;
      }
      String[] lineParts = s.split(" ");
      check(lineParts.length >= 2, "Expected ` `");
      String[] addrParts = lineParts[0].split(",");
      check(addrParts.length == 2, "Expected `,`");
      long isdIa = ScionUtil.parseIA(addrParts[0]);
      check(addrParts[1].startsWith("["), "Expected `[` before address");
      check(addrParts[1].endsWith("]"), "Expected `]` after address");
      String addr = addrParts[1].substring(1, addrParts[1].length() - 1).trim();
      check(!addr.isEmpty(), "Address is empty");
      // We allow anything here, even host names (which will trigger a DNS lookup).
      // Is that ok?
      InetAddress inetAddress = InetAddress.getByName(addr);

      String[] hostNames = Arrays.copyOfRange(lineParts, 1, lineParts.length);
      HostEntry e = new HostEntry(isdIa, inetAddress, hostNames);
      for (String hostName : hostNames) {
        entries.put(hostName, e);
      }
      // The following may differ, e.g. for IPv6
      // TODO find a better way, i.e. use InetAddress instances as keys?
      entries.put(e.address.getHostName(), e);
      entries.put(addr, e);
    } catch (IndexOutOfBoundsException | IllegalArgumentException | UnknownHostException e) {
      LOG.info("ERROR {} while parsing file {}: {}", e.getMessage(), PATH_LINUX, line);
    }
  }

  private static void check(boolean pass, String msg) {
    if (!pass) {
      throw new IllegalArgumentException(msg);
    }
  }

  public HostEntry find(String hostName) {
    return entries.get(hostName);
  }
}
