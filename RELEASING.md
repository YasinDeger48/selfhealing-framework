# Releasing

Coordinates: `io.github.yasindeger48:healer-core | healer-playwright | healer-selenium | healer-testng |
healer-cucumber | healer-claude` (+ the parent `healer-parent`). Published versions:
https://repo1.maven.org/maven2/io/github/yasindeger48/

Pushing a tag `vX.Y.Z` starts the *Release to Maven Central* workflow: it checks that the tag matches the version in
the poms, builds, adds source and javadoc jars, signs everything with GPG and uploads the bundle to the
[Central Portal](https://central.sonatype.com) (account: the one that owns the namespace - signed in with Google).
The upload waits there until you press **Publish**.

## Releasing a new version

**1. Pick the version number** ([semantic versioning](https://semver.org)):

| Change | Example | New version |
|---|---|---|
| Bug fix, nothing changes for users | a heal picked the wrong element | 2.0.0 -> **2.0.1** |
| New feature, existing tests keep working | a new setting, a new adapter method | 2.0.1 -> **2.1.0** |
| Users must change their code or settings | a method renamed or removed | 2.1.0 -> **3.0.0** |

**2. Set it in all poms** (parent and every module, one command):

```bash
mvn versions:set -DnewVersion=2.0.1 -DgenerateBackupPoms=false
```

Update the version in README.md examples too (search for the old number).

**3. Check and commit:**

```bash
mvn clean install                  # all tests must pass
git commit -am "Version 2.0.1"
git push origin main               # CI runs on main: wait for the green check on GitHub
```

**4. Tag and push the tag** - this starts the release:

```bash
git tag -a v2.0.1 -m "Release 2.0.1"
git push origin v2.0.1
```

**5. Publish:** GitHub -> *Actions* -> *Release to Maven Central* must finish green. Then in the Central Portal
(signed in with the account that owns the namespace): *Publish* -> *Deployments* -> the new deployment is
**VALIDATED** -> press **Publish**. After about 10-30 minutes it is on Maven Central; search.maven.org can take a few
hours longer.

**6. Tell users:** they change `<version>` in their pom (the example projects: the `healer.version` property).

A published version can never be changed or deleted. If something is wrong, release the next patch version.

## If the release workflow fails

The run page (*Actions* -> the failed run) shows the reason as red annotations:

| Message | Meaning / fix |
|---|---|
| `Tag vX does not match project version Y` | Tag and pom versions differ: delete the tag (`git tag -d vX`, `git push origin :refs/tags/vX`), fix, tag again |
| `Namespace '...' is not allowed` | The Central token belongs to an account that does not own the namespace: create a token in the right account and update the secrets |
| `401` / `Unauthorized` | Token wrong or expired: generate a new user token, update `CENTRAL_TOKEN_USERNAME` / `CENTRAL_TOKEN_PASSWORD` |
| `gpg: signing failed` / `No secret key` | `GPG_PRIVATE_KEY` or `GPG_PASSPHRASE` secret wrong |
| `Invalid signature` / `Could not find a public key` | The public GPG key is not on keys.openpgp.org (or not yet): send it again, wait, re-tag |

A tag whose upload failed was never published, so it may be moved: delete it locally and remotely, then tag again.
Drop failed deployments in the portal (*Deployments* -> *Drop*).

## One-time setup (done)

1. Central Portal account that owns `io.github.yasindeger48` (verified).
2. User token from that account -> GitHub secrets `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`.
3. GPG key (public part on keys.openpgp.org) -> GitHub secrets `GPG_PRIVATE_KEY` (armored private key),
   `GPG_PASSPHRASE`.

The token and the GPG key expire eventually (token: check the portal, key: the expiry chosen when it was created).
Renew them and update the secrets when a release fails with 401 or a key error.

## Releasing from your machine instead

```bash
export CENTRAL_TOKEN_USERNAME=...  CENTRAL_TOKEN_PASSWORD=...  MAVEN_GPG_PASSPHRASE=...
mvn -Prelease -DskipTests deploy
```

Check the release build without uploading or signing: `mvn -Prelease -Dgpg.skip -DskipTests install`.
