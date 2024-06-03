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

If JDK is missing or not working (usually it should be installed, but may require some setup to work
in a console):

1. If `java -version`  works and `javac` works then the JDK is properly installed already. Nothing
   more to do.
2. If `ls -l /usr/bin/java*` shows java/javac then the JDK is installed but may require some setup,
   continue step 3.
   Otherwise please install a java JDK, e.g. with `brew install oracle-jdk`.
3. Ensure that JAVA_HOME points to the JDK home directory. Check with `echo $JAVA_HOME`.
   Set it with e.g. `export JAVA_HOME=/Library/Java/Java/VirtualMachines/jdk-22.jdk/Contents/Home`
   where `jdk-22.jdk` can vary depending on your jdk version.
   Best add this line to your `.zshrc` file.
4. ensure that JAVA_HOME is in the path. Check with `echo $PATH`. Add it with
   `export PATH="${JAVA_HOME}/bin:${PATH}"`.    
   Best add this line to your `.zshrc` file.

Maven is usually not installed. You can follow the
instructions [here](https://www.digitalocean.com/community/tutorials/install-maven-mac-os))
or install it with brew:

- Make sure that `brew` is installed, see for
  example [here](https://www.digitalocean.com/community/tutorials/how-to-install-and-use-homebrew-on-macos).
- Make sure Xcode is installed
- Install maven with `brew install maven`
