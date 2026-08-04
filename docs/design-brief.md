# CAMusic (Android) — Design Brief

## 1. What this is

**CAMusic** is a native Android **music player** for **Music Assistant** (and a direct
**Navidrome/OpenSubsonic** mode). The phone becomes a Sendspin *player* — Music Assistant streams
lossless audio to it — and it's also a full on-device controller: browse and search your library,
play, and **download tracks for offline**. It's part of a larger "Sendspin" ecosystem whose Home
Assistant dashboard cards ("synco") already have an established look we want to echo.

**Audience:** people with a self-hosted music library (Music Assistant / Navidrome) who care about
**lossless / hi-res audio**. The mood is *audiophile but calm* — premium, quiet, music-first.

**Platform:** phone-first, Android 12+, Jetpack Compose + **Material 3**. Dark theme is primary;
support light too. Design for one-handed phone use; note a tablet/landscape adaptation but don't block
on it. (Android TV/Auto are future, out of scope here.)

## 2. Visual DNA to inherit (the "synco" look)

The HA cards establish the family look — please carry it into the app, adapted for a phone player:

- **Deep navy, near-black base.** Soft, low-contrast surfaces; nothing pure-white.
- **Album art drives the palette.** The dominant colours of the current cover become the accent
  (buttons, highlights, progress, glows). A **blurred, darkened album-art wash** bleeds into the
  Now-Playing backdrop so the whole screen is tinted by the music.
- **Glassy, translucent panels** with gentle blur and a 1px inner hairline; soft shadows/gloss that
  use the album accent rather than gray.
- **Calm motion** — slow drifts, gentle fades, a subtle "gloss" on the artwork. A "lava-lamp"
  ambient feel when idle. Always respect `prefers-reduced-motion`.
- **Marquee** track title/artist that scrolls only when text overflows.

Think: a quiet, dark, premium now-playing surface that feels lit *by the album*, not by chrome.

## 3. Design principles

1. **Music first, chrome last.** The cover art and the track are the hero; controls recede.
2. **One glance = status.** Codec/quality, connection, offline availability should read instantly.
3. **Lossless pride, tastefully.** A small **quality badge** (e.g. `FLAC · 48kHz · 16-bit`) — an
   audiophile cue, not a billboard.
4. **Two backends, one surface.** Music Assistant and Navidrome share the same browse/search UI; the
   difference is a subtle toggle, not two different apps.
5. **Offline is first-class.** Downloaded state and offline playback are visible and reassuring.

## 4. Information architecture & navigation

Bottom navigation, three destinations:

- **Now Playing** — the hero player.
- **Library** — browse / search / downloads (backend-switchable).
- **Settings** — server connection, credentials, format prefs, about.

(Downloads live inside Library as a section, not a 4th tab — but the designer may propose promoting it.)

## 5. Screens

### 5.1 Now Playing (hero)

The signature screen. Content it must express:

- **Large album cover**, centred, with the blurred cover bleeding into the full-screen backdrop and a
  faint gloss.
- **Title + artist + album** (title marquees on overflow).
- **Quality/format badge**: codec + sample rate + bit depth (e.g. `FLAC 48/16`). This is a selling
  point — make it feel special but small.
- **Transport**: large centred play/pause, prev/next. *(Note: for a Sendspin player, play/pause is
  largely server-driven — design the controls, but they can read as "remote"/secondary; volume is the
  primary on-device control.)*
- **Scrubber / progress** with elapsed / duration (a seek affordance; may be non-interactive in some
  modes — design a graceful "position only" state too).
- **Volume** control (the phone's own output volume).
- **Output/route** hint (Phone speaker / headphones / Bluetooth) — small.
- **Connection / player-status** pill: e.g. "Connected to Music Assistant", or "Playing locally
  (Navidrome)", or "Offline".
- **Idle state**: when nothing is playing, a calm ambient screen (slow album/hue drift, or a tasteful
  empty state) rather than a dead panel.

### 5.2 Library

The browse + search surface, shared by both backends.

- **Backend toggle** at the top: `Music Assistant` ⇄ `Navidrome`. Small, clearly secondary (a
  segmented control / chip pair). Switching changes what's browsed.
- **Search field** (full-text): artists, albums, tracks, playlists — results grouped by type.
- **Browse tree** with a back affordance + breadcrumb/title:
  - Root categories: **Artists, Albums, Tracks, Playlists** (+ **Downloads**). (Navidrome root:
    Artists, Recently Added, Playlists, Downloads.)
  - Drill in: Artist → Albums → Tracks; Album/Playlist → Tracks.
- **List rows** (see components) with cover thumbnail, title, subtitle (artist / "N songs" /
  duration), and a trailing action.
- **Per-track actions**: Play, Add to queue; on Navidrome tracks, a **Download** action; on downloaded
  tracks, a **Delete** action + an "available offline" indicator.
- **"Play all"** for an album/playlist/track list.
- **Downloads section**: the offline library — plays even with MA/server off.

Design the **empty / loading / error** states for browse and search (see §8).

### 5.3 Settings

- **Music Assistant server**: discovered-servers list (mDNS; each shows name + host:port + a
  connected checkmark), manual URL entry, connect/disconnect. This connects the **Sendspin player**.
- **Music Assistant login** (username/password) — for library browsing via the MA API.
- **Navidrome / OpenSubsonic**: server URL + username/password (for the direct backend + downloads).
- **Playback / format** preferences (e.g. prefer FLAC, hi-res when available) — audiophile-flavoured.
- **Status**: connection, current format, player id / device name.
- **About**: app name/version, links.

### 5.4 Onboarding / first-run connect

A friendly first-run: find your Music Assistant server (auto-discovered card to tap), or enter a URL
+ login. Keep it to the minimum needed to get playing. This can reuse the Settings connection UI in a
focused, welcoming layout.

## 6. Component inventory (please design)

- **Media list row** (the workhorse): 44–56px cover thumb, title, subtitle, trailing icon/action.
  Variants: browsable (chevron), playable (play + add), downloadable (download), downloaded (offline
  badge + delete), category (chevron, no thumb).
- **Cover art tile** with rounded corners + soft shadow/gloss; placeholder for missing art (per media
  type: artist / album / track / playlist glyph).
- **Transport controls** (large primary play/pause + prev/next).
- **Scrubber / progress bar** with time labels.
- **Quality badge** (codec · rate · depth).
- **Backend toggle** (MA ⇄ Navidrome).
- **Search field** + grouped results headers.
- **Connection pill / status chip** (connected / local / offline / connecting / error).
- **Discovered-server card** (name, host:port, connected state).
- **Connect form** (URL + username + password + button; connecting + error states).
- **Volume control.**
- **Snackbars / toasts** (e.g. "Downloaded", "Added to queue", "Couldn't reach Navidrome").
- **Foreground-service media notification** (track, artwork, play/pause/next/stop) — Android system
  media-style; please spec the artwork + controls.
- **Bottom nav** (3 items) — icons + labels.
- **Empty / error / loading** blocks.

## 7. Color & theming

- **Dark-first**, deep navy base; also deliver a light theme.
- **Accent = extracted album colour** (dynamic per track), used for the play button, progress, focus,
  and glows. Provide a **fallback accent** for when there's no art / no track (a synco-navy accent).
- Consider **Material You dynamic color** as an *option*, but the album-derived accent is the
  signature — specify how the two coexist (album accent wins on Now Playing).
- Define the translucent surface tiers (backdrop → glass panel → row → pressed) and the hairline/glow
  treatment.

## 8. States & edge cases (design all)

- **Connecting** (spinner + "Connecting…"), **connected**, **error** (friendly message + retry).
- **Offline** (MA/server unreachable): the app still plays **downloads**; make this reassuring, not
  alarming. A clear "Offline — showing downloads" mode.
- **Empty**: no servers found; empty library/search; no downloads yet.
- **Loading**: browse/search in progress (skeletons or a slim progress line).
- **Downloading**: per-track progress / queued / done / failed.
- **Idle Now Playing**: nothing playing.
- **Long text**: marquee titles; truncation with ellipsis on rows.

## 9. Accessibility

- Content descriptions on all icon buttons; large enough touch targets (48dp).
- Meets contrast on the tinted/translucent backdrops (album accent can be low-contrast — ensure text
  legibility over the wash).
- Respect `prefers-reduced-motion` (no album gloss / drift when reduced).
- Dynamic type friendly.

## 10. Motion

- Gentle cross-fades on track change (art + palette morph).
- Subtle art gloss / slow ambient drift on Now Playing (reduced-motion off = static).
- Standard Material transitions for nav + list; nothing flashy.

## 11. What exists today (so you can redesign, not invent structure)

The app is already built functionally in plain Compose Material 3: bottom nav (Now Playing / Library /
Settings), a working Library with the backend toggle + browse + search + download, a Settings screen
with discovery + credentials, and a system media notification. **Please treat the current screens as
wireframes** — the structure and data are real; the visual design is the deliverable.

## 12. Deliverables requested

- A **theme** (dark + light): color roles, type scale, elevation/glass treatment, shape/radius,
  iconography direction.
- **High-fidelity screens**: Now Playing (playing + idle), Library (browse + search + downloads,
  both backends), Settings, first-run connect.
- The **component set** from §6 with states.
- Empty / loading / error / offline states.
- The **app-color-from-album** behaviour illustrated on 2–3 different covers.
- (Optional) app icon direction and the media notification.

**Name:** Sendspin. **Tone:** dark, premium, music-lit, calm — an audiophile player that feels lit by
your album art.
