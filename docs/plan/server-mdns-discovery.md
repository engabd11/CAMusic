# Automatic server discovery — Implementation Plan

> Replace typing a LAN IP address by hand with a picker, everywhere CAMusic
> asks for one — the way Music Assistant's "Speakers" step already works,
> extended to the step where the *server itself* gets added.

## Why this instead of WLED

This replaces WLED as the next priority. Light Sync stays Hue-only for now —
`docs/plan/wled-light-backend.md`'s Phase 0 (the `LightBridge` extraction) is
done and shelved; its Phase 1+ (the WLED backend itself) is paused, not
cancelled. Nothing here touches `hue/`.

## Current state

Every server kind is added the same way, in two places, and both are pure
manual entry:

- **Onboarding** (`ui/screens/OnboardingWizard.kt`'s "Connect to `${kind.label}`"
  step, ~line 422): `if (kind.needsAddress) OutlinedTextField(value = url, ...,
  placeholder = kind.urlHint)` — a blank field with a hint like
  `http://192.168.0.10:8096`, and the user copies an IP off their router or
  server dashboard into it.
- **Settings → Libraries → Add a server** (`ui/screens/settings/LibrariesSettings.kt`,
  `ServerDetail`, line 624): the identical shape, `OledField(url, ..., "Server
  address", config.kind.urlHint, accent)`.

This is the wrong amount of typing for something the LAN mostly already
announces. CAMusic already has the machinery to do better than this in one
specific case and doesn't use it there either: **`MaDiscovery`**
(`discovery/MaDiscovery.kt`) finds Music Assistant servers via mDNS
(`_mass._tcp`) right now, but the only place it's wired up is the
post-connect "Speakers" step (`OnboardingWizard.kt`'s `SpeakersStep`,
line 826) — registering *this phone as a player* on an MA server the user
has already typed an address for. The server-address step itself never
calls it.

NsdManager-based mDNS is otherwise a proven, repeated pattern in this
codebase — `MaDiscovery`, `discovery/SendspinServerDiscovery.kt`
(`_sendspin-server._tcp`), `audio/AirPlayDiscovery.kt` (`_raop._tcp`/
`_airplay._tcp`, with the most complete TXT-record parsing of the three) —
and `hue/HueBridgeClient.kt` layers a second pattern worth copying: **mDNS
first, a well-known cloud registry as fallback, manual entry always last**
(`discoverViaCloud()` against `discovery.meethue.com`, rate-limited and
cached, merged with mDNS results preferring the mDNS ones).

## Not every backend actually speaks mDNS

Worth being precise about, since "automatic discovery" reads as one feature
and is really per-backend:

| Kind | Discovery mechanism | Notes |
|---|---|---|
| **Music Assistant** | mDNS, `_mass._tcp` | Already built (`MaDiscovery`) — needs wiring, not writing. |
| **Jellyfin** | UDP broadcast beacon, port 7359 | Jellyfin's own auto-discovery protocol, not Bonjour/mDNS — a one-line JSON request broadcast, JSON replies (`Address`, `Id`, `Name`). New, small discovery class needed. |
| **Emby** | Same UDP beacon, port 7359 | Emby forked from the same MediaBrowser lineage and kept the wire format — one discovery class covers both, distinguished by which port the resulting server answers Jellyfin's vs Emby's API on. |
| **Plex** | plex.tv's own resource-listing API, *not* LAN discovery | Plex sign-in (`plex/PlexAuth.kt`) already gets an access token from plex.tv before any server address is involved — its own doc comment says so. `GET https://plex.tv/api/v2/resources` with that token lists every Plex Media Server on the account, LAN and remote alike. No new sign-in step; one more authenticated GET after the PIN flow already completes. Plex does also have GDM (LAN UDP multicast, ports 32410-32414) as a from-scratch alternative — lower priority, since the resources API needs nothing new from the user and also reaches a remote server, which GDM cannot. |
| **Navidrome, Subsonic-compatible, foobar2000/Beefweb** | None standard | These don't advertise themselves by any common protocol. Stays manual. An active LAN port-scan could theoretically stand in, but it's slow, noisy, and unreliable enough that it's out of scope here rather than a good trade for "less typing." |
| **MPD** | None reliable | Some Avahi-fronted installs add `_mpd._tcp`, but it's not part of MPD itself and can't be depended on. Stays manual. |
| **Spotify, Qobuz, Tidal** | N/A | `cloudAccount = true` — no address, sign-in only. Already has no address field to fill. |

So this plan is three real workstreams, not one: wire up MA (already built),
write one small Jellyfin/Emby UDP-beacon client, and add one Plex API call.
Everything else in `ServerKind` keeps its text field exactly as it is today.

## Design

### A common discovery result, and where it plugs in

```kotlin
// library/ServerDiscovery.kt — new
/** One server a discovery source found, ready to become a ServerConfig.url. */
data class DiscoveredServer(
    val kind: ServerKind,
    val name: String,       // for the picker row — "Living Room (192.168.0.12:8096)"
    val url: String,        // e.g. "http://192.168.0.12:8096" — ready to drop into ServerConfig.url
)
```

Both add-a-server screens get the same small addition above the existing
address field: while a discovery source is running and `kind` has one, show
matches as tappable rows (`ServerRow`, already defined and used by
`SpeakersStep` — reused, not reinvented) that fill `url` on tap; the text
field stays, always, as the fallback it already is for a server that
doesn't answer or a network discovery can't reach (a different subnet, a
VPN, `nsdManager` permission denied). Nothing about `ServerConfig`,
`MusicSource`, or any provider client changes — this only changes how `url`
gets filled in before a normal connect attempt runs.

### Music Assistant: wire the existing class in

No new discovery code. `MaDiscovery` already does exactly this job; it's
only ever started from `SpeakersStep`. Start it (and stop it in
`DisposableEffect`/`onDispose`, matching that step's own lifecycle) from the
MA "Connect to Music Assistant" step in onboarding and from `ServerDetail`
in Settings when `config.kind == ServerKind.MUSIC_ASSISTANT`, and map
`MaServer` → `DiscoveredServer(kind = MUSIC_ASSISTANT, name = displayName,
url = baseUrl)`.

### Jellyfin/Emby: one UDP-beacon discovery class

New file: `discovery/MediaServerDiscovery.kt`. Jellyfin's (and Emby's)
auto-discovery is a UDP broadcast, not NsdManager/mDNS:

```kotlin
class MediaServerDiscovery(private val context: Context) {
    /**
     * One scan: broadcast on UDP 7359, collect replies for [timeoutMs].
     * Both Jellyfin and Emby answer the same beacon — the reply's own
     * `Address` says which port (and so which server) actually answered.
     */
    suspend fun scan(timeoutMs: Long = 1500L): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = 200  // per-receive poll, not the overall budget
        }
        val message = "who is JellyfinServer?".toByteArray()
        socket.send(DatagramPacket(message, message.size, InetAddress.getByName("255.255.255.255"), 7359))
        val found = mutableListOf<DiscoveredServer>()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(1024)
        while (System.currentTimeMillis() < deadline) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                parseReply(String(packet.data, 0, packet.length))?.let { found += it }
            } catch (_: SocketTimeoutException) { /* keep polling until deadline */ }
        }
        socket.close()
        found
    }

    // { "Address": "http://192.168.0.9:8096", "Id": "...", "Name": "My Server" }
    private fun parseReply(json: String): DiscoveredServer? { /* kind = JELLYFIN vs EMBY decided by the caller offering both, or left to the user to pick which row is theirs if both answer the same beacon */ }
}
```

Needs `CHANGE_WIFI_MULTICAST_STATE` (already in `AndroidManifest.xml` for
mDNS) and a broadcast permission that plain `INTERNET`/`ACCESS_NETWORK_STATE`
(both already present) already cover for a one-shot UDP send — no new
manifest entries expected, to be confirmed once this is actually written
against a real Jellyfin/Emby server, which this environment cannot reach to
verify.

One open question worth settling before writing this rather than after: a
reply gives no reliable field distinguishing "this is Jellyfin" from "this
is Emby" (both fork the same beacon). The pragmatic answer is to run the
scan once per screen (not per-kind) and show every reply under whichever of
the two the user is actually setting up — a false-positive row for the
wrong fork is harmless, since tapping it just fills the address field and
the normal connect attempt (which does know the difference, from each
server's own API) will fail cleanly if it's wrong.

### Plex: one authenticated GET, after the sign-in that already happens

No UDP, no NsdManager. `plex/PlexAuth.kt`'s PIN flow already yields a
plex.tv access token before any server is chosen. Add one call:

```kotlin
// plex/PlexAuth.kt or a new plex/PlexResources.kt
suspend fun listResources(token: String, clientIdentifier: String): List<DiscoveredServer> {
    // GET https://plex.tv/api/v2/resources?includeHttps=1&X-Plex-Token=<token>
    // Filter to provides="server", map each connection to a DiscoveredServer —
    // preferring a local (non-relay) connection's address where the response
    // offers one, falling back to the first reachable connection otherwise.
}
```

Shown the same way as the other two: rows above the address field,
populated the moment the PIN sign-in completes, rather than requiring a
separate "scan" action — the token to ask plex.tv with already exists at
that point in the flow.

## Integration touch points

| File | Change |
|---|---|
| `library/ServerDiscovery.kt` | **New** — `DiscoveredServer` |
| `discovery/MediaServerDiscovery.kt` | **New** — Jellyfin/Emby UDP-beacon scan |
| `plex/PlexResources.kt` (or added to `PlexAuth.kt`) | **New** — `listResources()` |
| `discovery/MaDiscovery.kt` | Unchanged — reused as-is |
| `ui/screens/OnboardingWizard.kt` | The "Connect to `${kind.label}`" step: discovered-server rows above the address field, gated on `kind`; MA's step starts/stops `MaDiscovery` the way `SpeakersStep` already does |
| `ui/screens/settings/LibrariesSettings.kt` | `ServerDetail`: same rows above `OledField(url, ...)` |
| `AndroidManifest.xml` | Confirm existing permissions cover UDP broadcast (expected: yes — `CHANGE_WIFI_MULTICAST_STATE` is already declared for mDNS) |

## Implementation phases

### Phase 1: Music Assistant — wire the existing discovery in

Zero new discovery code. Add the picker UI to both add-a-server screens for
`kind == MUSIC_ASSISTANT`, starting/stopping `MaDiscovery` the same way
`SpeakersStep` does. This alone removes manual IP entry for the one backend
CAMusic already has real LAN discovery for, and proves out the shared
`DiscoveredServer`/picker-row UI the other two reuse.

### Phase 2: Jellyfin/Emby — UDP-beacon discovery

`MediaServerDiscovery` (one-shot `scan()`, not a StateFlow — a broadcast
reply either arrives inside the timeout window or doesn't, unlike mDNS's
ongoing found/lost lifecycle) wired into the same two screens for
`JELLYFIN`/`EMBY`. Needs verification against a real Jellyfin and a real
Emby server — this environment has neither reachable — to confirm the reply
JSON shape and settle the Jellyfin-vs-Emby ambiguity noted above.

### Phase 3: Plex — resources API

`listResources()` called right after `PlexAuth`'s PIN flow succeeds, in
both screens' Plex step. Needs verification against a real plex.tv account
with at least one server to confirm the response shape (particularly the
local-vs-relay connection distinction) — also unreachable from this
environment.

### Phase 4: polish

Once all three are in: a shared "Scanning your network…" / "Nothing found —
enter the address" empty state across all three kinds instead of each
screen growing its own, and confirming the picker behaves under the
Simple/Advanced settings split (`README.md`'s "Settings default to Simple" —
discovery should probably just work in Simple mode rather than being
something to go looking for, since it removes typing rather than adding a
toggle).

## What this environment could not verify

No Music Assistant, Jellyfin, Emby, or Plex server was reachable to test
discovery against, and (as with the WLED plan) this environment has no
Android SDK/NDK and cannot resolve Google's Maven repository, so nothing
here has been built or run. Phase 1 is the lowest-risk place to start
precisely because `MaDiscovery` is already shipped, tested-by-shipping
code — the new work in that phase is UI wiring, not a new network protocol
implementation.

## Status (2026-09-14)

All four phases implemented in one pass, reviewed by hand (no build was
possible — see above), not yet verified against real servers.

- **`library/ServerDiscovery.kt`** — `DiscoveredServer(kind, name, url)`, shared
  by all three sources.
- **Phase 1 (Music Assistant)** — done. `ui/design/DiscoveredServerPicker.kt`'s
  `DiscoveredServerPicker` starts a fresh `MaDiscovery` instance (deliberately
  not reusing `Playback`'s app-wide one — a different question, "which server
  do I add" vs. "which server does this phone register on", and NsdManager
  supports concurrent listeners for the same service type without conflict)
  and shows rows the moment the composable enters. Wired into both
  `OnboardingWizard.kt`'s `ConfigStep` and `LibrariesSettings.kt`'s
  `ServerDetailBody`, immediately above the existing "Server address" field.
- **Phase 2 (Jellyfin/Emby)** — done, unverified. New
  `discovery/MediaServerDiscovery.kt`: a one-shot `scan()` that broadcasts
  `"who is JellyfinServer?"` on UDP port 7359 and collects JSON replies for
  1.5 s. Wired into the same `DiscoveredServerPicker` dispatcher. The
  Jellyfin-vs-Emby ambiguity is resolved as planned — one scan, results shown
  under whichever kind the user is actually setting up.
- **Phase 3 (Plex)** — done, unverified. `PlexAuth.listResources(token,
  clientIdentifier)` calls `GET plex.tv/api/v2/resources?includeHttps=1`,
  filters to `provides` containing "server", and prefers each result's
  `local`+non-`relay` connection. Wired into `LibrariesSettings.kt`'s
  `PlexSignInRow` (onboarding has no Plex sign-in row at all yet — a
  pre-existing gap, not something this pass added) — the moment the PIN flow
  yields a token, the resources call runs and offers rows via the same
  `DiscoveredServerRow` Phase 1/2 use, through a new `onUrl` callback.
- **Phase 4 (polish)** — substantially achieved by construction rather than
  as a separate pass: Phase 1 and 2 already share one `DiscoverySection`
  composable (one "Scanning your network…" / "Nothing found" copy, not
  per-kind variants), and nothing here added a new settings toggle, so it
  should already just work under Simple mode. Not independently re-verified
  against the Simple/Advanced split at runtime.
- **Two things worth a reviewer's attention beyond "does it build":**
  1. Plex's discovered rows appear *below* the sign-in button (inside
     `PlexSignInRow`), while the address field they fill sits *above* it in
     the same card — tapping one scrolls the user's eye up to see the field
     change. This follows the existing layout order (address field before
     auth-specific UI) rather than reordering the card; flagging it in case
     it reads as more confusing in practice than on paper.
  2. `MediaServerDiscovery` sends a raw UDP broadcast rather than going
     through NsdManager. On a phone with an active VPN or a mobile-data
     default route alongside Wi-Fi, a plain `DatagramSocket` broadcast can
     go out the wrong interface — a known Android gotcha this environment
     has no device to check. If real-device testing finds Jellyfin/Emby
     discovery unreliable specifically on such setups, binding the socket to
     the Wi-Fi `Network` via `ConnectivityManager` is the fix.
