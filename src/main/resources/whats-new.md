# What's new
<!--
  The new features and fixes of the NEXT release. Write it before pushing to master: the app shows it
  in "Update available" and once after updating, and the release workflow puts it on the GitHub release.
  Replace the list for every release (only what changed since the last release, not the whole history).
  Format: "## Heading", "- item", **bold**, `code`.
-->


## Voice in sync with the camera: Sync Test
- **Audio Mixer → MIC → Properties → SYNC TEST (CLAP)…** Put the camera on Program, press START and clap 3 times when it says CLAP NOW. The app measures how late (or early) the voice is in the recording and offers the right **Sync Offset**: one click to use it.
- Use it on each laptop and camera setup: the 450 ms the microphone waited by default was measured on one setup and can make the voice late on another.

## Settings: Save Changes and Apply
- **Every Settings card has Save Changes and Apply**, inside the card under its settings. Both start greyed out. Change something → **Save Changes** turns on. Save → **Apply** turns on. Apply → Preview and the live Program show it at once, no TAKE needed.
- Lyrics / Bible / Other Typography, Bible Design, Lyrics Lower Third / Bars, Branding and the Remote port are **drafts** until Save Changes: only the card's own sample changes while you choose, never the live picture.
- Branding's own SAVE CHANGES and the Remote's Apply Port button were removed (the card's buttons do it).

## Bible
- **One place for the Bible font:** Settings → Bible Typography. Edit Scene no longer has Text position / Text size for a Bible layer; old scenes follow Settings too.
- **Bible card in Edit Scene** (Design, Yellow words, Yellow color) uses the Bible Typography font, size, color, thickness, italic and capitals, also in the Edit Scene preview.
- **The card's reference follows Settings → Bible Presentation**: capitals or not, with or without the version (e.g. MATTHEW 6:33 · NIV).
- **Scene Lyrics source shows songs only** by default ("Scene Lyrics source also shows Bible verses" is now unticked for new setups).

## Preaching scene and lower thirds
- New built-in **Preaching** scene: camera, Bible card with a yellow reference tag and yellow words, a name tag, a title banner, and a **LIVE** badge that shows only while streaming.
- **Several lower thirds in one scene:** a Lower Third layer can show one saved lower third (always on while the scene is up). **HIDE THIS ONE** hides only that one; the red HIDE hides all.
- Lower thirds: new **Badge** design, a top line, **width** and **text alignment**.
- Designed layers: color shapes with a gradient, rounded corners and a border; text tags with a background; *marked* words in a color.

## Cameras
- **Camera page shows the picture** of a camera that is ON or LIVE. Every camera still starts OFF; switch one on to see it. With several cameras in Multi-View the pictures refresh 15 times a second, so the app stays light.
- **Scene thumbnails show a still of the camera** (refreshed every few seconds) when it is running. A thumbnail never turns on an OFF camera.
- **Edit Scene → camera layer → REFRESH**: restarts only that camera when it is stuck, black or frozen.

## Fixes
- Less memory use while a Bible card or lower third is on screen (fonts are made once and reused).
