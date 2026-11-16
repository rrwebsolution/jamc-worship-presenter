# What's new
<!--
  The new features and fixes of the NEXT release. Write it before pushing to master: the app shows it
  in "Update available" and once after updating, and the release workflow puts it on the GitHub release.
  Replace the list for every release (only what changed since the last release, not the whole history).
  Format: "## Heading", "- item", **bold**, `code`.
-->


## Smoother camera
- **Fixed: the camera lagged** with the Direct engine (an Iriun phone gave only about 3 pictures a second at 1080p). The pictures now reach LiveCat the fast way: the full 30 a second.
- **Only the LIVE camera runs.** Other cameras show **STANDBY** on the Camera page and are not opened, so the laptop has more power for the recording and livestream. In Studio Mode, the Preview camera is opened too, so TAKE is instant.
- **Iriun:** only the Iriun cameras that have a phone are listed. Iriun always installs four (Iriun Webcam, #2, #3, #4); the ones showing "Looking for the phone" are left out, and appear by themselves when a phone connects.
- **Auto Director → MIRROR:** flips a camera left to right (for example a phone's front camera). It applies to the Auto Director page, the shots on Preview and LIVE, and RECORD THIS SHOT files. Kept per camera.
- **Mirror is one setting per camera**, shared by the Camera page and the Auto Director (MIRROR): tick it in either place.
- **Fixed: two windowed outputs covered each other.** Windowed outputs that open on top of each other are placed side by side, the same size. A window you moved keeps its place.
- **ORDER opens in front at once**, in the middle of LiveCat, every time you click it: also when it was minimized, behind LiveCat, or under a fullscreen output.
- **Windowed outputs open where you can see them**: on the screen where you had them last (first time: the LiveCat screen), in front at once. Clicking an output that is already open brings it to the front.
- **Window Capture shows a picture of each window** in Select Window, so the right one is easy to find. Minimized windows show the icon only.
- **Make your own lyrics layout:** Settings → Lyrics Appearance → **My layouts → New Layout…**. Drag a box anywhere on the picture (side, top, corner), choose a bar per line, one box or no background, name it and save. Then pick it in Edit Scene → Lyrics layer → Lyrics layout.
