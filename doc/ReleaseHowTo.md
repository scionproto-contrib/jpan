# Release Instructions

## Release environment

0) Run integration test
    - Run `ShowEchoDemo`
    - Run `ScmpTracerouteDemo`
    - Run `ShowPathsDemo`
    - Checkout SCION proto and Start scionproto topology:
      - `./scion.sh topology -c topology/default.topo`
      - `./scion.sh run`
      - Run `ScmpDemoDefault` in `org.scion.jpan.demo`
    - Run ScionPacket example from https://github.com/netsec-ethz/scion-java-packet-example
    - Run PingAll from https://github.com/netsec-ethz/scion-java-multiping

1) Prepare the environment
    - Make sure to have a valid signing key
    - Make sure to have `~/.m2/settings.xml` configured properly
    - Make sure to use JDK 8: `java` and `javac`!

2) Release preparation, create and merge PR with following changes:
    - Check for updated GitHub actions
    - Run `mvn versions:display-dependency-updates` and fix any outdated dependencies
    - (Run `mvn dependency-check:check -DnvdApiKey=...` (requires Java 11 or later)  )
      (-> OWASP CVE checks)
      -> Currently disabled because the plugin causes NPEs  
    - Prepare CHANGELOG
    - Update README.md with reference to latest `.jar`

3) Release
    - `git checkout master`
    - Run `mvn release:clean`
    - Run `mvn release:prepare`
    - Run `mvn release:perform`
    - Log in to https://central.sonatype.com
        - go to "Publish" in the title bar
        - Press "Release"
    - Confirm that the artifact has become available on https://central.sonatype.com/
      (this may take a few hours).

4) Post Release
    - On GitHub, add the new release to the
      [list of releases](https://github.com/scionproto-contrib/jpan/releases).
    - Announce the release on the appropriate channels.

## Release Environment

### Signing key

Make sure to have a signing key ready.
Consider using `gpg --full-generate-key` and generate a key with infinite lifetime.
See also https://blog.sonatype.com/2010/01/how-to-generate-pgp-signatures-with-maven/

For convenience, print the short key: `gpg --list-signatures --keyid-format 0xshort`

Upload your key:
`gpg --keyserver hkp://pgp.mit.edu --send-keys <KEY>`

## Tips

- To avoid storing a password in the settings file you can get user access tokens
  from https://central.sonatype.com
- To verify your gpg passphrase:
  `echo "1234" | gpg --no-use-agent -o /dev/null --local-user <KEYID> -as - && echo "The passphrase was correct!"`
- To recover from a failed release, use `mvn release:rollback`. This attempts to rollback all
  changes, such as *git tags* and *version information in the .pom* file. Emphasis on *attempts*,
  so please check manually whether it worked.

## Trouble shooting

Problem:
`Failed to deploy artifacts: Could not transfer artifact ... from/to ossrh ... Transfer failed for ... 401 Unauthorized`
Solution: Check your local `~/.m2/settings.xml`
See https://central.sonatype.org/publish/publish-portal-maven/



