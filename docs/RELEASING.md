# Releasing OwnDemand

Release APKs are signed with the project's release key and published as
GitHub Release assets. Nothing secret lives in the repository.

## One-time setup: the signing key

The release key is permanent: every future release must be signed with the
same key, or Android refuses to install it as an update. Create it once,
keep it outside the repository, and back it up (keystore file **and**
password) somewhere off the build machine.

```bash
mkdir -p ~/.owndemand && chmod 700 ~/.owndemand
keytool -genkeypair -v -keystore ~/.owndemand/owndemand-release.jks \
  -alias owndemand -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=OwnDemand"
```

The certificate's name (`-dname`) is visible to anyone who inspects the
APK, so keep it to the project name.

Point Gradle at it from `~/.gradle/gradle.properties` (never the project's
`gradle.properties`), then `chmod 600` that file:

```properties
owndemand.release.storeFile=/absolute/path/to/owndemand-release.jks
owndemand.release.storePassword=…
owndemand.release.keyAlias=owndemand
owndemand.release.keyPassword=…
```

PKCS12 keystores (keytool's default) use one password for the store and
the key, so both password lines hold the same value. Without these
properties `assembleRelease` still works but produces an unsigned APK.

## Each release

1. **Bump the version** in `app/build.gradle.kts`: `versionCode` (+1, an
   integer Android compares) and `versionName` (e.g. `0.2.0`). Keep
   `PlexHeaders.PRODUCT_VERSION` and the `PlexHeadersInterceptor` default
   in step with `versionName`.
2. **Test**: `./gradlew testDebugUnitTest`.
3. **Build**: `./gradlew clean assembleRelease` →
   `app/build/outputs/apk/release/app-release.apk`.
4. **Verify the signature** (expect `CN=OwnDemand` and the same certificate
   SHA-256 as previous releases):

   ```bash
   $ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs \
     app/build/outputs/apk/release/app-release.apk
   ```

5. **Smoke-test the release build on a device.** The release build is
   shrunk by R8, which can break things that work in debug builds. Sign in,
   browse Home and Channels, play and seek a video, use the miniplayer, and
   open *Settings → Open-source licences*. A debug build on the device must
   be uninstalled first (different signing key).
6. **Name and checksum** the APK:

   ```bash
   cp app/build/outputs/apk/release/app-release.apk OwnDemand-<version>.apk
   shasum -a 256 OwnDemand-<version>.apk
   ```

7. **Tag and publish** (from the commit the APK was built from):

   ```bash
   git tag -a v<version> -m "OwnDemand <version>"
   git push origin v<version>
   gh release create v<version> OwnDemand-<version>.apk \
     --title "OwnDemand <version>" --notes-file release-notes.md
   ```

   Put the SHA-256 checksum and a short change list in the release notes.

## Licences

The APK must carry the licence texts (Apache 2.0 §4). They ship as
`app/src/main/assets/licenses/LICENSE.txt` and `NOTICE.txt`, copies of the
repository's `LICENSE` and `NOTICE`; `LicenseAssetsTest` fails if they
drift. When adding a dependency, check its licence is compatible and add
it to `NOTICE` (then re-copy the asset).
