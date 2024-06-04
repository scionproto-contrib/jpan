# Development Environment

JPAN development requires:

- JDK 8 or later
- Maven
- An IDE

JPAN builds are tested on

- Windows 11 with Java 8
- MacOS with Java 21
- Ubuntu 20.04 with Java 8, 11, 17 and 21

## MacOS

Setting up Java and Maven on MacOS can be tricky, so here are some pointers:

Prerequisites

- Java JDK 8 (=1.8) or later. This can be confirmed byt running `javac -version`
- Maven. This can be confirmed by running `mvn -version`

Good instructions can be found 
[here](https://www.digitalocean.com/community/tutorials/install-maven-mac-os).
Otherwise, you can follow the instructions below.


### JDK

If JDK is missing or not working (usually it should be installed, but may require some setup to work
in a console):

1. If `java -version`  works and `javac` works then the JDK is properly installed already. Nothing
   more to do.
2. Make sure that `brew` is installed, see for example
   [here](https://www.digitalocean.com/community/tutorials/how-to-install-and-use-homebrew-on-macos).
3. Check with `brew search jdk` weather a JDK is already installed. If no JDK is installed, install
   one of the options, e.g. `brew install oracle-jdk`.
4. Ensure that `JAVA_HOME` points to the JDK home directory. Check with `echo $JAVA_HOME`.
   Set it with e.g. `export JAVA_HOME=/Library/Java/Java/VirtualMachines/jdk-22.jdk/Contents/Home`
   where `jdk-22.jdk` can vary depending on your JDK version.
   Best add this line to your `.zshrc` file.
5. **TBD** Ensure that `JAVA_HOME` is in the path. Check that `java -version` and `javac -version` are
   correct. If necessary, add it with `export PATH="${JAVA_HOME}/bin:${PATH}"`.    
   Best add this line to your `.zshrc` file.

### Maven

Maven is usually not installed. You can follow the
instructions [here](https://www.digitalocean.com/community/tutorials/install-maven-mac-os))
or install it with brew:

- Make sure Xcode is installed
- Install maven with `brew install maven`

### Running the demos

1. Checkout the source code: `git clone https://github.com/scionproto-contrib/jpan.git`
2. Compile with `mvn install -DskipTests=true`
3. Run with `mvn exec:java -Dexec.mainClass="org.scion.jpan.demo.ScmpEchoDemo"`
