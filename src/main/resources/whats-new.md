# What's new
<!--
  The new features and fixes of the NEXT release. Write it before pushing to master: the app shows it
  in "Update available" and once after updating, and the release workflow puts it on the GitHub release.
  Replace the list for every release. Format: "## Heading", "- item", **bold**, `code`.
-->

## Production Health Monitor
- The **footer** now shows CPU, RAM, output FPS, stream bitrate, dropped frames, cameras (e.g. **CAM 4/4**), stream / recording and the overall health: **Healthy**, **Warning** or **Critical**. Everything is measured for real; **N/A** means there is no reading (FPS, bitrate and dropped frames are measured while streaming or recording).
- Hover a reading for more. Click **Health** for the details: livestream, recording, each camera, CPU, memory, network and the **recent alerts**.
- Each problem is announced once: CPU high for a while, many dropped frames, a camera disconnected, a livestream or recording that stopped by itself. Stopping the livestream yourself is never a problem. Thresholds: **Settings → Health Monitor**.
- On a small window the footer keeps Health, CPU, the stream and the most important readings; the rest are behind **+N**.

## Order window
- **New look:** one card per slide with a drag handle, its number, the section (VERSE 1 · 1/2), the words, a green **SHOWN** for slides already presented, a red **LIVE** card for the slide on Program and a blue **PREVIEW**. Edit and ⋮ (more actions) on each card, ▲ / ▼ to move the selected section, and Previous / Next with "Current / Live / Total" at the bottom.
- Works for **everything in the Library that has slides**: lyrics, Bible verses, presentations, service text items and now also **custom slide decks** (reorder, duplicate, remove and **Add Slide** change the deck itself).

## Videos
- **Fixed:** when a video plays to its end, the position bar now goes all the way to the end and the time shows its full length (it used to stop short and show 0:00 / 0:00).
- **Fixed:** a YouTube video selected in Library → Videos no longer starts playing by itself when you go to another library page. It only plays when you press PLAY.

## Windows
- **Minimize** on a LiveCat window (YouTube, Pixabay, Edit Song…) now sends it to the **Windows taskbar** with its own button, while LiveCat and the projector keep running. Click the button to bring it back.

## Lyrics
- Clicking a **song title** in Library → Lyrics now puts its slide **straight on Program**, like clicking a lyrics card (when Settings → Separate Lyrics is off). With Separate Lyrics on it goes to Preview first, as before.

## Lower-Third Lyrics on the Livestream
- The **livestream, recording, OBS window and Program monitor** now show song lyrics as a broadcast **lower third** over the camera: white bold capitals, each line on its own 75 % black bar, near the bottom.
- The **church projector always keeps the big full-screen lyrics** (with your background video and Spotify-style transition). Same song, same NEXT / TAKE.
- **Each scene picks its own lyrics layout:** Edit Scene → its Lyrics layer → **Lyrics layout** (**Full lyrics** is the default; or Lower third, Centered bars, Full screen dimmed). For example "Camera + Lyrics" = Lower third and "Lyrics and Bible" = Full lyrics. **Preview shows exactly what TAKE puts live.**
- The bars' look (font, size, capitals, colors, opacity, width, padding, spacing, position, maximum lines, fade, transparent overlay) are in **Settings → Lyrics Appearance**, with a sample picture.
- **Save Settings** keeps your changes; only **Apply to Program** changes what is live.
- **Different lyrics on Preview and Program:** tick **Separate Layouts** under the Scenes. Preview keeps its scene (e.g. full lyrics) and Program its own (e.g. camera + lower third); TAKE sends the lyrics, drawn in each scene's layout. If Program's scene has no Lyrics layer turned on, a warning says so.

## Pixabay Worship Backgrounds
- **Library → Videos (or Backgrounds) → + → Pixabay Worship Backgrounds…** browses free Pixabay videos with ready searches: **Fast Praise, Slow Worship, Anniversary Worship, Nature Worship** and your own words.
- The pictures stay **still**. Rest the mouse on one and that video plays right there (muted, looping, like on Pixabay); move away and the picture comes back.
- **Use as Background** puts it in Library → Backgrounds and opens it in MEDIA PREVIEW; press **TAKE AS BACKGROUND** to put it live. **Add to Videos** adds it to Library → Videos (new **Pixabay** tab).
- Nothing is downloaded: the videos stream from Pixabay, so they need the internet during the service. Each video credits its creator "via Pixabay".
- Add the church's free Pixabay key once in **Settings → Integrations → Pixabay**.
- **Fixed:** TAKE AS BACKGROUND now plays on Preview and Program right away, even before any lyrics are selected (a lyrics scene showed black until a lyrics card was clicked).
- The scene pictures on the Control page now show a **still** picture of the background video (TAKE AS BACKGROUND) instead of a choppy playing one. Preview, Program and the projector keep playing it.

## Spotify-Style Lyrics
- **New lyrics transition:** the current lyrics sit big and bright in the middle, the previous lyrics dimmed above and the next lyrics dimmed below. **NEXT** smoothly scrolls them up; **PREVIOUS** scrolls them down. It works on the projector, OBS, the stream, Preview and Program, and from LiveCat Remote.
- Turn it on in **Settings → Lyrics Transition → Transition type**: None (the Program transition, as before), **Smooth Fade**, or **Spotify-Style Lyrics**.
- Choose the duration (200–1500 ms), font size, how dim the previous/next lyrics are, spacing, highlight color, alignment, easing, and whether previous/next lyrics show.
- **Preview Animation** plays sample lyrics right in Settings. It never changes Preview or Program, and a change in Settings never changes the lyrics already live; it applies from the next lyrics shown.
- Pressing NEXT quickly is safe: the lyrics continue from where they are and never jump. Another song or a far jump crossfades.

## New Control page layout
- **Audio Mixer always on screen:** it is now under the Scenes on the right, like OBS, so the sound can be watched while recording or streaming. No more MIXER button.
- **Service schedule on the left,** under the Library.
- **More** in the Library is folded to save room: click **⋯** (or "More") to open Custom Slides, Themes, Lower Thirds, Favorite Lyrics and All Media.

## Audio
- Each mixer channel (Mic, Camera, Media, the Program scene's inputs, Master) has a vertical fader, a level meter (green / yellow / red), its dB and a mute button.
- Choose the microphone and camera audio input right in the mixer. Double-click a fader to set it back to 0 dB.
- Settings → Recording & Livestream → "Show the Audio Mixer under the Scenes" hides or shows it (on by default).

## Footer
- The video background ("BG: file name") is now shown in the footer at the bottom instead of the top bar, so a long name can't push the Settings navigation off screen. Click it to open the background controls.

## Updates
- The update window now lists what is new before you update, and the app shows "What's new" once after an update. See it any time in Help → What's New.
