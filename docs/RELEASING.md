# Releasing

Releases are signed APKs published on this repository's
[Releases](../../releases) page by the *Release* GitHub Actions workflow
(`.github/workflows/release.yml`).

## Signing key

| | |
|---|---|
| Keystore | `~/.android/signing/video-live-wallpaper-release.jks` (PKCS12, RSA 4096, valid until 2054), outside the repository and outside Dropbox |
| Alias | `videowallpaper` |
| Certificate | `CN=Video Live Wallpaper (fork by jpribil)` |
| Certificate SHA-256 | `8c9ae5fa883cfff543f7a1446f6bb2b4f5f576914c4fa423a5e5dde636fdc15e` |
| Password | in `~/.gradle/gradle.properties` (`vlwStorePassword`, same as `vlwKeyPassword`) |

**Back up the keystore file and the password** (for example in a password
manager). Android only installs an update if it is signed with the same key
as the installed app. If the key is lost, nobody can update installed copies
any more; users would have to uninstall and lose their settings.

The certificate fingerprint is also hard-coded in the release workflow, which
refuses to publish an APK signed with anything else.

### Local signed builds

`app/build.gradle.kts` reads the signing settings from Gradle properties:

```
# ~/.gradle/gradle.properties
vlwStoreFile=C:/Users/<you>/.android/signing/video-live-wallpaper-release.jks
vlwStorePassword=…
vlwKeyAlias=videowallpaper
vlwKeyPassword=…
```

With these present, `./gradlew assembleRelease` produces a signed
`app/build/outputs/apk/release/app-release.apk`. Without them (for example on
the normal *Build* workflow, or for other contributors) the release APK is
unsigned.

### GitHub Secrets

The *Release* workflow gets the same values from repository secrets
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `VLW_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `VLW_STORE_PASSWORD` | keystore password |
| `VLW_KEY_ALIAS` | `videowallpaper` |
| `VLW_KEY_PASSWORD` | key password |

To (re)set them from a machine that has the key, with the `gh` CLI (Git Bash):

```bash
R=jpribil/video-live-wallpaper
GP=~/.gradle/gradle.properties
get() { grep "^$1=" "$GP" | cut -d= -f2-; }
base64 -w0 "$(get vlwStoreFile)"       | gh secret set VLW_KEYSTORE_BASE64 -R $R
get vlwStorePassword | tr -d '\n'      | gh secret set VLW_STORE_PASSWORD  -R $R
get vlwKeyAlias      | tr -d '\n'      | gh secret set VLW_KEY_ALIAS       -R $R
get vlwKeyPassword   | tr -d '\n'      | gh secret set VLW_KEY_PASSWORD    -R $R
```

## Making a release

1. In `app/build.gradle.kts`, raise `versionCode` by one and set `versionName`.
2. Write the release notes:
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and
   `fastlane/metadata/android/cs-CZ/changelogs/<versionCode>.txt`
   (plain text, under 500 characters).
3. Commit on `main` and push.
4. Tag and push the tag:
   ```bash
   git tag v2.0.1
   git push origin v2.0.1
   ```
5. The *Release* workflow runs the unit tests, builds the signed APK, checks
   the signing certificate and creates the GitHub Release
   `video-live-wallpaper-<version>.apk` plus a `.sha256` file, with the notes
   from step 2.

The workflow fails (and publishes nothing) if the tag doesn't match
`versionName`, a changelog file is missing, the secrets are missing, a test
fails, or the APK isn't signed with the expected key.

**Dry run:** Actions → *Release* → *Run workflow* builds and checks
everything and attaches the signed APK to the run, without creating a
release.

## Installing and updating (for users)

- Download `video-live-wallpaper-<version>.apk` from the Releases page and
  open it on the phone (Android asks once to allow installs from the browser
  or file manager).
- For automatic update checks, add this repository's URL to
  [Obtainium](https://github.com/ImranR98/Obtainium).
- The app ID is `cz.pribil.videowallpaper`; it installs next to the original
  app from F-Droid and doesn't replace it.
- To check the APK's origin, compare its signing certificate with the SHA-256
  above (for example with the AppVerifier app, or
  `apksigner verify --print-certs`).
