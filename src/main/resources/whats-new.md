# What's new
<!--
  The new features and fixes of the NEXT release. Write it before pushing to master: the app shows it
  in "Update available" and once after updating, and the release workflow puts it on the GitHub release.
  Replace the list for every release (only what changed since the last release, not the whole history).
  Format: "## Heading", "- item", **bold**, `code`.
-->

## Camera and voice stay together
- **After a short camera hiccup the picture catches up at once.** Before, one busy moment could leave the camera up to a second behind the voice for the rest of the service (the preacher's mouth after the words). Now the app always shows the newest camera picture, so lips and voice stay together for the whole service.
- **Smoother recordings and livestream with a 720p camera** (for example Iriun at 1280x720): the picture is enlarged to 1080p before it reaches the app, so a recording no longer stutters at about 10 pictures a second while the camera gives 30.

## Phone microphone (AUX) in sync
- The **AUX** microphone (for example the Iriun phone microphone) now waits **80 ms** by default, so the voice matches the mouth. This was measured from claps in a test recording. A Sync Offset you already set is kept.
- **Audio Mixer → AUX → Properties → SYNC TEST (CLAP)…** is now there too, not only on MIC: clap in front of the camera and the app sets the exact offset for your phone and room.
