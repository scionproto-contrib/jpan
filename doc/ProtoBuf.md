# ProtoBuf

SCION uses RPC for communication between client and daemon, control service or path service
(path service = new Endhost API).

Specifically, the path service uses ConnectRPC's own protocol based on HTTP 1.1 with unary calls.
Unary calls are not supported by standard protobuf/protoc, so we need to use ConnectRPC's `buf`
for generating protobuf classes.


## Installation

To install `buf` (see also https://buf.build/docs/cli/installation/):

```
go install github.com/bufbuild/buf/cmd/buf@v1.65.0
```



https://connectrpc.com/docs/kotlin/getting-started/
Java code gen.










https://grpc.io/docs/languages/java/generated-code/#codegen

Example:

<build>
  <extensions>
    <extension>
      <groupId>kr.motd.maven</groupId>
      <artifactId>os-maven-plugin</artifactId>
      <version>1.4.1.Final</version>
    </extension>
  </extensions>
  <plugins>
    <plugin>
      <groupId>org.xolstice.maven.plugins</groupId>
      <artifactId>protobuf-maven-plugin</artifactId>
      <version>0.5.0</version>
      <configuration>
        <protocArtifact>com.google.protobuf:protoc:3.3.0:exe:${os.detected.classifier}</protocArtifact>
        <pluginId>grpc-java</pluginId>
        <pluginArtifact>io.grpc:protoc-gen-grpc-java:1.4.0:exe:${os.detected.classifier}</pluginArtifact>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>compile</goal>
            <goal>compile-custom</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>








### ERROR Codes
https://connectrpc.com/docs/protocol/#unary-request-response-rpcs

HTTP to Error Code
HTTP Status	Inferred Code
400 Bad Request	internal
401 Unauthorized	unauthenticated
403 Forbidden	permission_denied
404 Not Found	unimplemented
429 Too Many Requests	unavailable
502 Bad Gateway	unavailable
503 Service Unavailable	unavailable
504 Gateway Timeout	unavailable
all others	unknown