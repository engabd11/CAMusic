# Building

Build CAMusic from source. [Back to the README](../README.md).

```bash
git clone https://github.com/engabd11/CAMusic.git
cd CAMusic
./gradlew assembleMobileDebug          # phone and tablet
./gradlew assembleTvDebug              # Android TV
./gradlew testMobileDebugUnitTest      # the unit tests
```

CI runs both flavours' unit tests, `lintMobileDebug` and `lintTvDebug`, and builds the debug
and R8-minified release APKs, so a release-only failure shows up on the pull request. Actions
are pinned by commit SHA and kept current by Dependabot.

Requires JDK 17 or newer and the Android SDK with the NDK. The native audio engine and the
AirPlay sender build through CMake as part of the normal Gradle build; the AirPlay build fetches
Mbed TLS once at configure time, so the first build needs network access. The speed-zone
database is stored in git-lfs, so run `git lfs pull` after cloning. Qobuz and Tidal app
credentials come from gradle properties (`camusic.qobuz.*`, `camusic.tidal.*`); a build that leaves them unset asks for a pair on the account's settings page instead.

Flavours are `mobile` (the default, and the one every release ships as) and `tv`, so Gradle
tasks are flavour qualified. `-PsideBySide` adds a `.freshtest` application id suffix for
installing alongside a release build.
