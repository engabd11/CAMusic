# Attributions

Third-party material shipped inside the app, and where it came from.

CAMusic is not monetised. Where a licence asks for credit, this file is that credit,
and it is the list the app's about screen should draw from.

## Ambience beds

The recordings under `app/src/main/assets/ambience/<effect>/bed.m4a`, one per ambience
effect. Each is transcoded to AAC 128 kbit/s, 44.1 kHz.

| Effect | Source | Author | Licence |
| --- | --- | --- | --- |
| `coastal_rain` | [freesound.org/s/718518](https://freesound.org/s/718518/) | ryding | Creative Commons — exact variant to confirm on the sound's page |
| `aurora` | *unrecorded* | | |
| `fireplace` | *unrecorded* | | |
| `fireworks` | *unrecorded* | | |
| `fireworks_2` | *unrecorded* | | |
| `light_train` | *unrecorded* | | |
| `thunderstorm` | *unrecorded* | | |
| `thunderstorm_2` | *unrecorded* | | |
| `underwater` | *unrecorded* | | |

The eight marked *unrecorded* were added in `e626dda` and `45fa3de` without their
provenance being written down anywhere. They need filling in from whatever record
exists of where they were downloaded — the licences cannot be honoured from memory,
and freesound in particular hosts CC0, CC-BY, CC-BY-NC and Sampling+ side by side.

## Code and icons

- Server-kind icons adapted from [dashboard-icons](https://github.com/walkxcode/dashboard-icons) (CC0). See `ServerKindIcon.kt`.
- Protocol references ported or adapted with attribution: massdroid (MIT), Music Assistant mobile-app (Apache 2.0), sendspin-js. See [protocol-alignment.md](protocol-alignment.md).
- Speed-limit pipeline input data: Transport Victoria, CC BY 4.0. See `tools/speed-limit-pipeline/README.md`.
