# Release signing

## History

v0.1.0 to v0.13.x were signed with the Android debug key from the release machine
(`~/.android/debug.keystore`, SHA-256 `21b01f0f…`). That key works, but its password is the
public `android`, so anyone holding a copy of the file could sign an update.

From v0.14 releases are signed with a dedicated **release key** (SHA-256 `fa8be6c3…`,
`CN=CAMusic, O=Cyborg Automation AU`) using APK Signature Scheme v3 **key rotation**. Each
release carries a lineage that says the debug key handed over to the release key. So:

- an installed copy signed with the debug key updates in place, keeping its data;
- the lineage gives the debug key **no rollback capability**, so after a phone has installed a
  rotated release, an APK signed with the debug key alone is refused
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`);
- `minSdk` is 31 and the rotation applies from SDK 31 (`--rotation-min-sdk-version 31`), so
  every supported device uses the release key.

## Where the key lives

Never in the repository. The build reads `~/.camusic-signing/`, or the directory given by
`-Pcamusic.signing.dir=…`:

| File | What it is |
|---|---|
| `release.p12` | The release key (PKCS12, alias `camusic`, RSA 4096) |
| `release.pass` | Its password, on one line. apksigner reads it from this file, so it never appears on a command line |
| `lineage.bin` | The rotation record, debug key → release key |
| `old-debug.keystore` | A copy of the old debug key. Kept here so a regenerated `~/.android/debug.keystore` cannot silently break signing |

Losing `release.p12` or its password means no future build can update an installed CAMusic.
Keep a copy off the machine, with the password in a password manager rather than beside the
file.

## How a release build is signed

AGP's `signingConfig` takes one key and no lineage. So the release build type is still packaged
with the debug key, and `rotationSign<Variant>Apk` (in `app/build.gradle.kts`) then re-signs the
APK with apksigner: old key, `--next-signer` release key, `--lineage`. It runs for
`assembleMobileRelease`, `assembleTvRelease` and `install*Release`, and the result lands at the
usual `app/build/outputs/apk/<flavour>/release/` path.

When the signing directory is missing or incomplete (CI, a fork), the task logs a warning and
leaves the APK debug-signed. The build still succeeds.

Check a build with:

```bash
java -jar $ANDROID_HOME/build-tools/<version>/lib/apksigner.jar verify -v --print-certs app-mobile-release.apk
java -jar $ANDROID_HOME/build-tools/<version>/lib/apksigner.jar lineage --in app-mobile-release.apk --print-certs
```

The signer should be `CN=CAMusic`, and the lineage should list the Android Debug key first.

## Consequences for development

- **Debug builds** (`installMobileDebug`) are still signed with the debug key alone, so they no
  longer install over a phone's rotated release. Use `installMobileRelease -PsideBySide`, which
  installs beside the real app, or uninstall the release first.
- A release-type build made on a machine *without* the signing directory is debug-signed and
  will not update a rotated install either.
