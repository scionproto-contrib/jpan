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

package org.scion.testutil;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.scion.proto.daemon.Daemon;
import org.scion.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockDaemon implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MockDaemon.class.getName());
    private final InetSocketAddress address;
    private Server server;

    public MockDaemon(InetSocketAddress address) {
        this.address = address;
    }

    public static MockDaemon create(InetSocketAddress address) {
        return new MockDaemon(address);
    }

    public MockDaemon start() throws IOException {
    server = NettyServerBuilder.forAddress(address).addService(new DaemonImpl()).build().start();
        //server = ServerBuilder.forPort(port).addService(new DaemonImpl()).build().start();

        logger.info("Server started, listening on " + address);

        // TODO remove?
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Shutting down daemon server");
            try {
                server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
        }));
        return this;
    }

    @Override
    public void close() throws IOException {
        try {
            //channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            logger.warn("Shutting down daemon");
            server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            logger.warn("Daemon shut down");
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }


    static class DaemonImpl extends DaemonServiceGrpc.DaemonServiceImplBase {
        @Override
        public void paths(Daemon.PathsRequest req, StreamObserver<Daemon.PathsResponse> responseObserver) {
            logger.info("Got request from client: " + req);
            Daemon.PathsResponse.Builder replyBuilder = Daemon.PathsResponse.newBuilder();
            if (req.getSourceIsdAs() == 561850441793808L && req.getDestinationIsdAs() == 561850441793810L) {
                Daemon.Path p0 = Daemon.Path.newBuilder()
                        .setInterface(Daemon.Interface.newBuilder().setAddress(
                                Daemon.Underlay.newBuilder().setAddress("127.0.0.10:31004").build()).build())
                        .addInterfaces(Daemon.PathInterface.newBuilder().setId(2).setIsdAs(561850441793808L).build())
                        .addInterfaces(Daemon.PathInterface.newBuilder().setId(1).setIsdAs(561850441793810L).build())
                        .build();
                replyBuilder.addPaths(p0);
            }
            responseObserver.onNext(replyBuilder.build());
            responseObserver.onCompleted();
        }
    }

}
