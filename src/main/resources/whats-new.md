# What's new
<!--
  The new features and fixes of the NEXT release. Write it before pushing to master: the app shows it
  in "Update available" and once after updating, and the release workflow puts it on the GitHub release.
  Replace the list for every release (only what changed since the last release, not the whole history).
  Format: "## Heading", "- item", **bold**, `code`.
-->

## Several videos in one source
- **Add Source → Video Playlist (multiple videos)…**: pick 2 or more videos for one box in the scene.
- **Autoplay** is on by default: when the first video ends, the second plays by itself, and so on. The next video is made ready while the first one plays, so it follows **without a blink**.
- Under Preview / Program the playlist shows **which video plays** and its time (for example **VIDEO 2 / 3 · 0:02 / 0:05**), with a bar that moves; Program Media shows the time of the video playing now.
- When all the videos have played, **PLAY** starts again from the first one at once. **Loop** is off unless you turn it on.
- **Edit Videos…** opens with the videos already in the playlist **ticked**: untick one to take it out, tick others to add them.
- The source's properties list **all its videos** with a picture each, and mark the one **playing now**. Double-click a video there to play it.
- Turn **Autoplay** off in the source's properties to stop after each video; then press **NEXT** under Preview / Program (it shows **VIDEO 2 / 3**) to play the next one. **PREV** goes back.

- In the **scene editor**, a video now has the same controls as Program Media: play / pause / stop, ±10 s and a **progress bar with running time** (a playlist also shows **VIDEO 2 / 3**). **Fixed:** PLAY after the end starts again from the beginning (a playlist from its first video).

## Record one shot on its own
- In **Camera → AUTO DIRECTOR**, click a shot (for example the **Preacher**), then **● RECORD THIS SHOT**. Only that shot is recorded, following the person, into its own file in the recordings folder (for example *Preacher 2026-11-16 10-30-00.mp4*).
- The normal recording of the whole service is separate and keeps going. Click **■ STOP RECORDING** to finish the shot file.
- **Fixed:** a recording with no sound input chosen could report a much longer length than it really had.

## Green background removed (chroma key)
- NDI from other programs (graphics, lower thirds, a presenter on a green screen) often comes on a **green background**. In the scene editor, click the camera or NDI source and tick **Remove green background (chroma key)**: the green becomes see-through, so the layers under it show.
- Choose **Green**, **Blue** or any key color, and adjust **Similarity** and **Smoothness**. **Reduce green edges** takes the green tinge off hair and edges.

## AI Smart Auto Director (one camera, many shots)
- New in the Control Room: **Auto Director**. One camera (your Iriun phone) becomes several **virtual shots**: Wide, Lead Singer, Keyboardist, Drummer and Other Worship Member.
- The shots are cut out of the same camera picture, so the camera and its **sound never restart** or stop when the shot changes.
- **Manual:** keys **1–5** put a shot in Preview, then press TAKE. **Ctrl+1–5** sends a shot straight to LIVE. Click a person in the camera picture to get a shot of that person.
- **AI Assisted:** the AI suggests the next shot in Preview. Nothing goes live until you press TAKE. Use **Reject** or **Next** for another suggestion.
- **Full Auto:** the AI switches the shots by itself and goes back to the wide shot from time to time. A shot you TAKE yourself always wins: the AI pauses (**AI Paused — Manual Control**) until you press **RESUME AI**.
- **Esc** pauses the AI. **Ctrl+Shift+A** turns AI Assisted on or off, **Ctrl+Shift+F** Full Auto, and **Ctrl+Shift+R** resumes the AI.
- Digital zoom (**+ / −**, Ctrl + mouse wheel) and pan (**Alt + arrows**, or drag the blue frame). **Home** goes back to the wide shot. Changing Preview never moves the LIVE shot.
- The AI follows each person smoothly and stays on the same person. It never jumps to someone else. Set where the singer, keyboard and drums are with **Stage zones**, and give performers names.
- Add, edit, duplicate and delete shots, set their priority and timing, and keep separate setups per **production profile**.
- **One camera or multiple cameras:** in **Camera setup** choose **One camera** (every shot is cut out of your Iriun) or **Multiple cameras**, then pick a camera for each shot (for example Wide = phone 1, Lead Singer = phone 2). The sound stays on the main camera. A change to another camera fades across.
- The Auto Director is now a tab of **Camera** (CAMERAS | AUTO DIRECTOR), and every shot change glides smoothly in Preview too.
- The AI runs on this computer (no internet needed). It is always **off** (and in **Manual**) when LiveCat opens: turn it on when you need it.

## Fixed
- Clicking a video in **Library → Videos → Backgrounds** (especially a Pixabay background, which streams from the internet) no longer makes Preview and Program freeze for a moment. The video now opens in MEDIA PREVIEW in the background while everything keeps moving.
- **TAKE AS BACKGROUND starts at once.** A background video you click in the Library is made ready right away, so TAKE AS BACKGROUND only has to press play. Changing backgrounds no longer makes Preview and Program stop for a moment.
- **Pixabay backgrounds loop smoothly and use data only once.** The first time you take a Pixabay video as the background, LiveCat saves a copy on this computer while it plays. From then on it loops with no pause (before, it often stopped for about 4 seconds near the end) and works offline. Before, every loop downloaded the whole video again: one 8-second background used about 9.5 GB an hour.
- **Fixed:** turning **Loop** off and on while a background played made every later loop start from that point instead of the beginning.

## Download Pixabay videos
- In **Pixabay Worship Backgrounds**, every video now has a **Download** button. The video goes **straight into Library → Videos → Local**, like your own video files: it plays **offline**, loops smoothly and never uses data again. You can keep browsing while it downloads; when you close the window, Videos → Local opens on it.
- A Pixabay video already in your Library has **DOWNLOAD** in MEDIA PREVIEW: it is then in Videos → Local too, and shows **✓ SAVED (OFFLINE)**.
- Downloaded videos stay for good. Copies saved automatically when you TAKE a Pixabay background are cleared oldest-first only when they pass 3 GB.

## Lyrics and background are separate sources
- A **Lyrics** or **Bible** source in a scene is now **the words only**. It no longer brings a background video along by itself.
- For a background behind the words, add **Add Source → Media** to the scene (or show its Media source with the eye). The song's theme and TAKE AS BACKGROUND then show through that source, and you can move, hide or reorder it like any other source.
- A scene with only a Lyrics or Bible source now shows the words on black: add a Media source to it if you want a background.
- A **Lyrics** source shows both song lyrics and Bible verses, so one source is enough for both. A **Bible** source shows **Bible verses only**: song lyrics never appear in it.
- **Fixed:** LiveCat could freeze for a few seconds while opening, building the font lists in Settings (each font was loaded just to measure the list). The font lists now open instantly.
- The scene source **Media** is now called **Background (TAKE AS BACKGROUND / theme)** in Add Source, with a tooltip saying what it is for. Your scenes keep the names their sources already have.
- Select a **Background** source in the scene editor while the background is a **video**: its playback controls appear under it (play / pause, stop, ±10 s, progress bar with time, mute, loop, volume). They change the background on Preview and Program. With a picture, a color or no background they are hidden.
- In Add Source, **Audio Input** (microphone), **Audio Output Capture** (the PC's sound) and **Application Audio Capture** (one program's sound) now each have their own icon, in the menu and in the scene's source list.
