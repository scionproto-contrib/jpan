// Copyright 2023 ETH Zurich
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

package org.scion.jpan.testutil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.scion.jpan.PackageVisibilityHelper;
import org.xbill.DNS.*;

public class MockDNS {
  public static void install(String isdAs, InetAddress addr) {
    install(isdAs, addr.getHostName(), addr.getHostAddress());
  }

  public static void install(String isdAs, String hostName, String address) {
    String entry = System.getProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, "");
    if (!entry.isEmpty()) {
      entry += ";";
    }
    entry += hostName + "=";
    entry += "\"scion=" + isdAs + "," + address + "\"";
    System.setProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, entry);
  }

  public static void clear() {
    System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
  }

  public static class MockResolver implements Resolver {
    private InetSocketAddress address;
    private Duration timeoutValue;

    public InetSocketAddress getAddress() {
      return this.address;
    }

    public int getPort() {
      return this.address.getPort();
    }

    public void setPort(int port) {
      this.address = new InetSocketAddress(this.address.getAddress(), port);
    }

    public void setAddress(InetSocketAddress addr) {
      this.address = addr;
    }

    public void setAddress(InetAddress addr) {
      this.address = new InetSocketAddress(addr, this.address.getPort());
    }

    public void setTCP(boolean flag) {
      // Nothing
    }

    public void setIgnoreTruncation(boolean flag) {
      // Nothing
    }

    public void setEDNS(int version, int payloadSize, int flags, List<EDNSOption> options) {
      // Nothing
    }

    public void setTSIGKey(TSIG key) {
      // Nothing
    }

    public void setTimeout(Duration timeout) {
      this.timeoutValue = timeout;
    }

    @Override
    public Duration getTimeout() {
      return this.timeoutValue;
    }

    @Override
    public CompletionStage<Message> sendAsync(Message query) {
      MockMessage m = new MockMessage();
      CompletableFuture<Message> f = new CompletableFuture<>();
      f.complete(m);
      return f;
    }

    @Override
    public CompletionStage<Message> sendAsync(Message query, Executor executor) {
      MockMessage m = new MockMessage();
      CompletableFuture<Message> f = new CompletableFuture<>();
      f.complete(m);
      return f;
    }

    public String toString() {
      return "MockResolver [" + this.address + "]";
    }

    @Override
    public Message send(Message query) throws IOException {
      return new MockMessage();
    }

    /**
     * @deprecated
     */
    @Override
    @Deprecated
    public Object sendAsync(Message query, ResolverListener listener) {
      Object id = new Object();
      //      CompletionStage<Message> f = this.sendAsync(query);
      //      f.handleAsync((result, throwable) -> {
      listener.receiveMessage(id, new MockMessage());
      //          return null;
      //      });
      return id;
    }
  }

  static class MockMessage extends Message {
    private Resolver resolver;

    public MockMessage() {
      // Nothing
    }

    @Override
    public void setHeader(Header h) {
      //
    }

    @Override
    public Header getHeader() {
      return null;
    }

    @Override
    public boolean isSigned() {
      return false;
    }

    @Override
    public boolean isVerified() {
      return false;
    }

    @Override
    public OPTRecord getOPT() {
      return null;
    }

    @Override
    public int getRcode() {
      return -1;
    }

    /**
     * @deprecated
     */
    @Override
    @Deprecated
    public org.xbill.DNS.Record[] getSectionArray(int section) {
      return new org.xbill.DNS.Record[0];
    }

    @Override
    public List<org.xbill.DNS.Record> getSection(int section) {
      return null;
    }

    @Override
    public List<RRset> getSectionRRsets(int section) {
      return null;
    }

    @Override
    public byte[] toWire() {
      return new byte[0];
    }

    @Override
    public byte[] toWire(int maxLength) {
      return new byte[0];
    }

    @Override
    public byte[] toWire(int maxLength, boolean truncate) throws MessageSizeExceededException {
      return new byte[0];
    }

    @Override
    public void setTSIG(TSIG key) {
      // Nothing
    }

    @Override
    public void setTSIG(TSIG key, int error, TSIGRecord querytsig) {
      // Nothing
    }

    @Override
    public int numBytes() {
      return 0;
    }

    @Override
    public String sectionToString(int section) {
      return "";
    }

    @Override
    public void setResolver(Resolver resolver) {
      this.resolver = resolver;
    }

    @Override
    public Optional<Resolver> getResolver() {
      return Optional.ofNullable(this.resolver);
    }

    @Override
    public Message normalize(Message query) {
      return query;
    }
  }
}
