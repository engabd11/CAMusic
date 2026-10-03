# Good to know

Things that behave in a particular way by design. [Back to the README](../README.md).

- **Settings default to Simple.** Advanced toggles (detailed signal-path readouts, the USB bit-perfect output mode, motion settings, listening-DNA and lyrics timing) sit behind one Advanced switch at the top of Settings, off until you go looking for them.
- **A Hue entertainment area serves one client at a time.** If the Hue app takes the area,
  CAMusic's stream is handed over and stays stopped, deliberately, so its own stop button keeps
  working.
- **Music DNA reads a local track scan**, so it waits while Music Assistant is streaming to the
  phone. The toggle says as much.
- **Per-speaker offsets are positive on the Music Assistant side**, whose config field is
  unsigned. For a phone that needs to move the other way, use the app's own signed latency trim.
- **Releases are signed with a dedicated release key** (from v0.14.0, with a rotation lineage from the earlier key), so updates install cleanly over each other. A build from another source needs an uninstall first. See [signing.md](signing.md).
- **The speed-limit database adds about 39 MB to the phone APK**, the cost of offline speed-limit awareness for Victoria, Australia, stated here up front. The TV build leaves out both the database and the ambience recordings, and is about 15 MB.
- **Your credentials stay yours.** Every server secret, including streaming-service tokens, is
  encrypted under the Keystore; the debug file you might attach to an issue has credentials
  redacted from its log; Android's cloud backup leaves out the library database, downloads and
  the speed database; and media streams obey the same LAN-only rule for plain HTTP as
  everything else, redirects included.
