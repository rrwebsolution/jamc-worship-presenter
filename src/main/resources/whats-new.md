# What's new
<!--
  The new features and fixes of the NEXT release. Write it before pushing to master: the app shows it
  in "Update available" and once after updating, and the release workflow puts it on the GitHub release.
  Replace the list for every release (only what changed since the last release, not the whole history).
  Format: "## Heading", "- item", **bold**, `code`.
-->


## Voice and camera together
- Recordings and the livestream: the **voice now waits for the camera**. The MICROPHONE starts with a **Sync offset of 450 ms** (Audio Mixer → MICROPHONE → ⋯ → Properties), on top of the delay the app adds by itself when Program is drawn late. Change it there if your camera is faster or slower.
- An offset you set yourself is kept.

## Recordings
- A recording becomes a **normal MP4** when you stop it, so Windows Media Player and Photos play it. Older recordings in the recordings folder are fixed the next time LiveCat opens.
- **DROP** and **Health** no longer turn red when nothing is wrong (extra frames that are skipped on purpose were counted as dropped).

## TV and projector
- The **lower third / bars** lyrics now show on the TV and projector too, when the scene's Lyrics layout is Lower third or Centered bars.

## Smoother camera
- A camera on **Auto** that gives fewer than 20 pictures a second at 1080p (an Iriun phone over WiFi, a busy laptop) now switches by itself to **1280×720 at 30 fps** after 15 seconds, for a smooth picture. A size you choose in Camera settings is never changed.
