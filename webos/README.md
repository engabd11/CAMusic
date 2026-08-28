# CAMusic for webOS TV

Music streaming app for LG webOS TVs. Multi-library playback (Navidrome,
Subsonic, Jellyfin, Music Assistant) with direct Hue Entertainment
light sync.

## Architecture

```
┌─────────────────────────────────────┐
│         webOS TV App (Web)           │
│  ┌─────────┐  ┌────────┐  ┌───────┐ │
│  │ Library  │  │ Player │  │ Hue   │ │
│  │ Browser  │  │Controls│  │Setup  │ │
│  └────┬────┘  └───┬────┘  └──┬────┘ │
│       │           │          │      │
│  ┌────┴───────────┴──────────┴────┐ │
│  │     Service Layer (JS)         │ │
│  │  SubsonicAPI │ MaAPI │ HueCtl  │ │
│  └────┬──────────┴──────┬─────────┘ │
│       │                │           │
└───────┼────────────────┼───────────┘
        │ HTTP/WS         │ Luna IPC
        ▼                ▼
┌───────────┐    ┌──────────────┐
│ Navidrome │    │  JS Service  │
│  :4533    │    │  (Node.js)   │
└───────────┘    └──────┬───────┘
                 UDP   │
                       ▼
                ┌─────────────┐
                │  Hue Bridge │
                │  (20 ch)    │
                └─────────────┘
```

### Components

| Component | Tech | Role |
|-----------|------|------|
| **Web App** | HTML5 + CSS3 + JS | UI, audio playback, API clients |
| **JS Service** | Node.js (webos-service) | Hue Entertainment UDP streaming |
| **Audio** | Single `<audio>` element | Music playback (webOS constraint) |
| **Subsonic Client** | fetch API | Navidrome/Subsonic browsing & streaming |
| **MA Client** | WebSocket | Music Assistant control |
| **Hue Service** | Luna IPC → Node.js → UDP | Direct bridge connection |

### Key Constraints (from LG webOS TV docs)

- Only ONE `<audio>` element per app (hardware decoder conflict)
- Back button keyCode is 461 (not 8)
- Must pause audio on `visibilitychange` (app suspend)
- Every focusable element needs visible selection effect
- Raw UDP not available from web layer — requires JS Service
- webOS TV 26 uses Chromium 132; webOS 25 uses Chromium 120

## Directory Structure

```
webos/
├── appinfo.json              # App manifest
├── index.html                # Entry point
├── css/
│   └── style.css             # 10-foot UI styles
├── js/
│   └── app.js                 # Main application
├── services/
│   └── hueservice/
│       ├── services.json      # JS Service manifest
│       └── hueservice.js      # Hue Entertainment service
└── assets/
    ├── icon80.png             # App icon (80x80)
    ├── icon520.png            # Large icon (520x400)
    └── splash.png             # Splash screen
```

## Development

### Prerequisites
- Node.js (for webOS CLI)
- webOS CLI (`ares-cli`): `npm install -g @webosose/ares-cli`
- LG TV with Developer Mode enabled, or webOS TV Simulator

### Install & Test on TV

```bash
# Package the app
ares-package ./webos -o dist/

# Install on TV (requires Developer Mode + device setup)
ares-install dist/com.abdullah.camusic_0.0.1_all.ipk lg-tv

# Launch
ares-launch com.abdullah.camusic lg-tv

# Debug (Chrome DevTools)
ares-inspect com.abdullah.camusic lg-tv
```

### Configure Servers

1. Launch CAMusic on the TV
2. Navigate to Settings tab (D-pad Right to tabs, OK on Settings)
3. Select "Add Server"
4. Choose server type (Navidrome, Subsonic, Jellyfin, or Music Assistant)
5. Enter server URL, username, and password
6. Save and return to Library

### Configure Hue Sync

1. Navigate to Hue Sync tab
2. Enter Hue Bridge IP, username token, and entertainment group ID
3. Select "Connect to Bridge"
4. Select "Toggle Light Sync" to start/stop

## Server Setup

### Navidrome (recommended)
- Default URL: `http://192.168.0.210:4533`
- Uses Subsonic API with OpenSubsonic extensions
- Supports: browse, search, stream, scrobble

### Music Assistant
- Default URL: `http://192.168.0.48:8095`
- Uses WebSocket JSON-RPC for playback control
- Does NOT stream audio to the TV — controls MA's server-side players

### Subsonic-compatible
- Any Subsonic/OpenSubsonic server (Gonic, Airsonic, etc.)

### Jellyfin
- Uses Jellyfin REST API for browsing
- Streams original files via direct HTTP

## Publishing to LG Content Store

1. Register at https://seller.lge.com (LG Seller Lounge)
2. Pass the App Self Checklist
3. Submit signed .ipk package (1920x1080 resolution)
4. LG QA review (2-4 weeks)

### Mandatory QA Requirements
- 4-way D-pad navigation on all selectable UIs
- Visible selection effects on ALL focusable elements
- Back button works (exits to Home on entry page)
- Audio pauses on app suspend
- Handles network disconnection gracefully
- No crash after 30 min continuous use

## License

MIT — same as the main CAMusic project
