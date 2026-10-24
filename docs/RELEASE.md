# Signing and releasing

Debug APKs from the normal build are fine for testing. A **release** APK must be signed with a key only you hold. Never put the key in the repo (`*.jks` and `*.keystore` are git-ignored).

## One-time setup (all from a phone)

1. Install **Termux** from F-Droid, then in Termux:
   ```
   pkg install openjdk-17
   keytool -genkeypair -v -keystore xc-release.jks -alias xc -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 xc-release.jks > xc-release.b64
   ```
   Choose strong passwords and remember them. **Back up `xc-release.jks` somewhere safe.** If you lose it, you can never publish updates that install over the old version.
2. Open the repo on GitHub, then **Settings > Secrets and variables > Actions > New repository secret**. Add four secrets:
   - `XC_KEYSTORE_BASE64`: the full contents of `xc-release.b64`
   - `XC_KEYSTORE_PASSWORD`: the keystore password
   - `XC_KEY_ALIAS`: `xc` (or the alias you chose)
   - `XC_KEY_PASSWORD`: the key password
3. Delete `xc-release.b64` from your phone once the secret is saved.

## Building a release

- **Actions tab > Release (signed) > Run workflow**, or push a tag such as `v0.2.0` (this also creates a GitHub Release with the APK attached).
- The workflow stops with a clear message if a secret is missing, and checks the signature with `apksigner` before uploading `xc-coditer-release-apk`.

## How the normal CI proves this works

Every push runs a `signing-check` job that creates a throwaway key, builds a signed release APK and verifies it. The throwaway key is discarded and never uploaded. Your real key is only used by the Release workflow.

## Bump the version

Edit `versionCode` (must increase every release) and `versionName` in `app/build.gradle.kts`.
