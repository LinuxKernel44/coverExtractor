# coverExtractor

Android app that extracts the embedded cover art from any audio file and saves it to your
gallery — pixel for pixel, with zero quality loss. The exact image bytes stored inside the audio
file are the exact bytes written to disk; nothing is decoded, resized, or re-encoded.

## Features

- **Lossless** — the saved picture is byte-for-byte identical to the artwork embedded in the
  audio file.
- **Broad format support** — MP3 (ID3v2), FLAC, M4A/AAC (MP4 `covr` atom), Ogg Vorbis/Opus, WMA,
  and APEv2-tagged files (Monkey's Audio, WavPack).
- **Quick pick** — choose files from an in-app picker (single or multiple at once), or share an
  audio file into coverExtractor from any file manager / music app via "Open with" or "Share".
  Covers are saved automatically to `Pictures/coverExtractor` as soon as they're found.
- **Library** — point coverExtractor at a whole music folder (subfolders included) and browse
  every embedded cover in a grid, with an A-Z index and search. Tracks that share the same album
  art are grouped into a single tile. Tap a cover to preview and save it, or long-press to select
  several and save them all at once.
- **Always dark** — no light mode to worry about.

## Requirements

Android 8.0 (API 26) or newer.

## Building from source

See [`CLAUDE.md`](CLAUDE.md) for the full build, signing, and release process, including the
release keystore credentials (this is a private repository).

```bash
./gradlew :app:assembleDebug
```

## License

Private project — all rights reserved.
