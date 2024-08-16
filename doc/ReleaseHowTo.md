# Release Instructions

## Release environment

0) Run integration test
   - Checkout SCION proto and Start scionproto topology:
     - `./scion.sh topology -c topology/default.topo`
     - `./scion.sh run`
  - Run `ScmpDemoDefault` in `org.scion.jpan.demo`

1) Prepare the environment
    - Make sure to have a valid signing key
    - Make sure to have `~/.m2/settings.xml` configured properly
    - Make sure to use JDK 8

2) Release preparation
    - Prepare CHANGELOG
    - Update README.md with reference to latest `.jar`

3) Release
    - Run `mvn versions:display-dependency-updates` and fix any outdated dependencies
    - Run `mvn release:clean`
    - Run `mvn release:prepare`. **NOTE** This may take a while to process OWASP CVE.
    - Run `mvn release:perform`
    - Log in to https://s01.oss.sonatype.org,
        - go to "Staging Repositories" (may take a minute + "Refresh" to appear)
        - Inspect the release (may take a minute + "Refresh" to appear)
        - Press "Close" (may take a minute + "Refresh" to be available)
        - Press "Release" (may take a minute + "Refresh" to be available)
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
  from https://s01.oss.sonatype.org/#profile;User%20Token
- To verify your gpg passphrase:
  `echo "1234" | gpg --no-use-agent -o /dev/null --local-user <KEYID> -as - && echo "The passphrase was correct!"`
- To recover from a failed release, use `mvn release:rollback`. This attempts to rollback all
  changes, such as *git tags* and *version information in the .pom* file. Emphasis on *attempts*,
  so please check manually whether it worked.

## Trouble shooting

Problem:
`Failed to deploy artifacts: Could not transfer artifact ... from/to ossrh ... Transfer failed for ... 401 Unauthorized`
Solution: Add the sonatype repo to your local `~/.m2/settings.xml`,
see https://central.sonatype.org/publish/publish-maven/#distribution-management-and-authentication



