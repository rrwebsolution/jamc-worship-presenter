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
