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
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.util.IPHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class HostsFileParser {

  private static final Logger LOG = LoggerFactory.getLogger(HostsFileParser.class);
  private static final String PATH_LINUX = "/etc/scion/hosts;/etc/hosts";

  // We use hostName/addressString as key.
  private final Map<String, HostEntry> entries = new HashMap<>();

  static class HostEntry {
    private final long isdAs;
    private final InetAddress address;

    HostEntry(long isdAs, InetAddress address) {
      this.isdAs = isdAs;
      this.address = address;
    }

    public long getIsdAs() {
      return isdAs;
    }

    public InetAddress getAddress() {
      return address;
    }
  }

  HostsFileParser() {
    init(ScionUtil.getPropertyOrEnv(Constants.PROPERTY_HOSTS_FILES, Constants.ENV_HOSTS_FILES));
  }

  void refresh() {
    entries.clear();
    init(ScionUtil.getPropertyOrEnv(Constants.PROPERTY_HOSTS_FILES, Constants.ENV_HOSTS_FILES));
  }

  private void init(String propHostsFiles) {
    String hostsFiles;
    String os = System.getProperty("os.name");
    if (propHostsFiles != null && !propHostsFiles.isEmpty()) {
      hostsFiles = propHostsFiles;
    } else if ("Linux".equals(os) || "Mac OS X".equals(os)) {
      hostsFiles = PATH_LINUX;
    } else {
      hostsFiles = "";
    }
    LOG.debug("OS={}; hostsFiles=\"{}\"", os, hostsFiles);

    for (String file : hostsFiles.split(";")) {
      Path path = Paths.get(file);
      // On Windows /etc/hosts is reported as ¨: not a file"
      if (!Files.exists(path) || !Files.isRegularFile(path)) {
        LOG.info("File not found or not accessible: {}", path);
        return;
      }
      try (Stream<String> lines = Files.lines(path)) {
        lines.forEach(line -> parseLine(line, path));
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
    }
  }

  private void parseLine(String line, Path path) {
    try {
      String s = line.trim();
      if (s.isEmpty() || s.startsWith("#")) {
        return;
      }
      String[] lineParts = s.split("\\s+");
      check(lineParts.length >= 2, "Expected ` `");
      String[] addrParts = lineParts[0].split(",");
      check(addrParts.length == 2, "Expected `,`");
      long isdIa = ScionUtil.parseIA(addrParts[0]);
      check(addrParts[1].startsWith("["), "Expected `[` before address");
      check(addrParts[1].endsWith("]"), "Expected `]` after address");
      String addrStr = addrParts[1].substring(1, addrParts[1].length() - 1).trim();
      check(!addrStr.isEmpty(), "Address is empty");

      byte[] addrBytes = IPHelper.toByteArray(addrStr);
      check(addrBytes != null, "Address string is not a legal address");
      for (int i = 1; i < lineParts.length; i++) {
        String hostName = lineParts[i];
        if (hostName.startsWith("#")) {
          // ignore comments
          break;
        }
        InetAddress inetAddr = InetAddress.getByAddress(hostName, addrBytes);
        entries.put(hostName, new HostEntry(isdIa, inetAddr));
      }
      InetAddress inetAddr = InetAddress.getByAddress(addrStr, addrBytes);
      // Use original address string as key
      entries.put(addrStr, new HostEntry(isdIa, inetAddr));
      // Use "normalized" address string as key (these may differ fo IPv6)
      entries.put(inetAddr.getHostAddress(), new HostEntry(isdIa, inetAddr));
    } catch (IndexOutOfBoundsException | IllegalArgumentException | UnknownHostException e) {
      LOG.info("ERROR parsing file {}: error=\"{}\" line=\"{}\"", path, e.getMessage(), line);
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
