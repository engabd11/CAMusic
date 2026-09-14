package com.engabd.sendpin.library

/**
 * One server a discovery source found — on the LAN (mDNS, a UDP beacon) or
 * an account's own server list (Plex's plex.tv resources) — ready to become
 * a [ServerConfig.url] the moment it's tapped.
 *
 * Shared by every discovery source so the add-a-server screens
 * (`OnboardingWizard.kt`'s `ConfigStep`, `LibrariesSettings.kt`'s
 * `ServerDetail`) render one picker shape regardless of which kind found it
 * or how. See `docs/plan/server-mdns-discovery.md`.
 */
data class DiscoveredServer(
    val kind: ServerKind,
    /** For the picker row — e.g. "Living Room (192.168.0.12:8096)". */
    val name: String,
    /** Ready to drop straight into [ServerConfig.url]. */
    val url: String,
)
