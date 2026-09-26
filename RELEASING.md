# Releasing to Maven Central

Coordinates: `io.github.yasindeger48:healer-core | healer-playwright | healer-selenium | healer-claude`.
The release build (`-Prelease`) adds source and javadoc jars, signs every artifact with GPG and uploads the
bundle to the [Central Portal](https://central.sonatype.com). By default the upload is validated and then waits
in the portal until you press **Publish** (`-Dcentral.autoPublish=true` publishes immediately).

## One-time setup

1. **Central Portal account** - sign in at https://central.sonatype.com with the GitHub account `YasinDeger48`.
   The namespace `io.github.yasindeger48` is verified automatically for GitHub sign-ins (check *Namespaces*).
2. **User token** - *Account* -> *Generate User Token*. It shows a username and a password (not your login).
3. **GPG key** (signs the artifacts):
   ```bash
   gpg --quick-gen-key "Your Name <your-noreply-or-public-email>" default default 3y
   gpg --list-secret-keys --keyid-format long          # note the key id
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID> # Central checks signatures against public keyservers
   gpg --armor --export-secret-keys <KEY_ID>            # -> GPG_PRIVATE_KEY secret (keep it private)
   ```
4. **GitHub secrets** - repository *Settings* -> *Secrets and variables* -> *Actions*:

   | Secret | Value |
   |---|---|
   | `CENTRAL_TOKEN_USERNAME` | token username from step 2 |
   | `CENTRAL_TOKEN_PASSWORD` | token password from step 2 |
   | `GPG_PRIVATE_KEY` | the armored private key from step 3 |
   | `GPG_PASSPHRASE` | its passphrase |

## Each release

1. Set the version in all poms (no `-SNAPSHOT`), e.g. `2.1.0`, and let CI pass on `main`.
2. Tag and push:
   ```bash
   git tag v2.1.0
   git push origin v2.1.0
   ```
3. The *Release to Maven Central* workflow checks that the tag matches the version, builds, signs and uploads.
4. In the portal (*Deployments*), review the validated bundle and press **Publish**. It appears on
   Maven Central within about 30 minutes; search.maven.org can take a few hours.

A published version can never be changed or deleted - fix mistakes with a new version.

## Releasing from your machine instead

```bash
export CENTRAL_TOKEN_USERNAME=...  CENTRAL_TOKEN_PASSWORD=...  MAVEN_GPG_PASSPHRASE=...
mvn -Prelease -DskipTests deploy
```

Check the release build without uploading or signing: `mvn -Prelease -Dgpg.skip -DskipTests install`.
