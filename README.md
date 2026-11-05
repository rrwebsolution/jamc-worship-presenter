# LiveCat Multimedia

**Present. Produce. Go Live.**

Local-first worship presentation and live-production desktop app for Windows
(Java 21, Swing + FlatLaf, SQLite, VLCJ). Works fully offline.

## Build and run

Requires a JDK 21 or newer. Maven does not need to be installed; the wrapper downloads it.

```powershell
.\mvnw.cmd clean package
java --enable-native-access=ALL-UNNAMED -jar target\jamc-worship-presenter-0.1.0-all.jar
```

`clean package` compiles, runs the tests and produces the runnable `-all.jar` (all dependencies,
including the SQLite native library and the public-domain KJV Bible, are bundled).

### Video, audio and camera: install VLC

Video playback, background audio, looping video backgrounds and cameras use **VLC media player**
(free, 64-bit) through VLCJ. Install it from videolan.org, or put a portable VLC in a `vlc` folder
inside the data folder or next to the jar, or pick the folder in *Settings → Media & Camera*.
Without VLC everything else (songs, Bible, slides, themes, scenes, projector, OBS window) works.

### Bible versions and licensing

Only the King James Version (public domain) ships with the app, as a data file, not in code.
NLT, ESV, NIV and Ang Pulong Sa Dios are copyrighted, so they are listed but have no text until
the church connects a licensed source in **Bible → Versions** (or Settings → Bible Presentation):

| Source | How |
|--------|-----|
| Imported file | A Bible file you are licensed to use (scrollmapper JSON, Zefania XML, CSV/TSV): select the version, *Import Licensed File…*. Works offline; supports word search. |
| ESV API | Your key from api.esv.org |
| NLT API | Your key from api.nlt.to |
| API.Bible | Your key from scripture.api.bible plus the Bible ID your account is licensed for (e.g. for NIV or APSD, if available to you) |

Online text is cached in memory only and never written to disk. Online versions need internet;
import a licensed file for offline use during services. Show the attribution each publisher requires.

### Main screen

The operator needs four ideas and one action: **LIBRARY** (what content: Songs, Bible, Presentations,
Images, Videos (with a Backgrounds tab), Audio — Custom Slides, Themes, Lower Thirds, Favorite Songs and All Media are
under *More*), **SERVICE** (the schedule on the right: add, remove, drag to reorder, Save, Save As, Open),
**PREVIEW** (what goes live next) and **PROGRAM** (what the audience sees, marked ● LIVE), with **TAKE →**
between them. The top centre is the presentation area (Preview · TAKE · Program, with Black, Blank,
Lyrics, Background and Projector). Below it are the current item's cards (lyrics, verses, PowerPoint
thumbnails) and its details; the background bar runs along the bottom. Clicking a schedule item loads it
but never sends it live.

### Scenes (OBS Studio Mode, simplified)

**SCENES** (top right, above the service schedule) are saved output layouts: Worship, Camera, Camera + Lyrics,
Bible, Sermon, Announcement, Fullscreen Video, Logo / Welcome and Blank (standard scenes cannot be
deleted; duplicate one to make your own). Each card shows a live thumbnail and ● LIVE / ▶ PREVIEW. A click
puts the scene in Preview; Program changes on TAKE (Settings → Presentation → Scene switching can make a
click switch Program at once). The scene is the layout, the Library item is the content: Camera + Lyrics
shows any song, smaller and at the bottom over a readability overlay. **TAKE** is intelligent: the Preview
monitor says *NEXT: Camera + Lyrics · Scene change* or *NEXT: Amazing Grace – Verse 2 · Content change*.
A content change (every lyrics click) keeps the live scene, so the camera never restarts and only the
lyrics layer changes. Right-click a scene: Preview, Send to Program Now, Rename, Duplicate, **Edit Layout…**
(layers: move, resize, show/hide, fit, lyrics position and size, overlay darkness), Set as Default (the scene
Program starts with), Delete. **Separate Preview layout (sync lyrics)** is for livestreams: Preview (e.g. the
TV in church, routed with DISPLAY) keeps its own scene such as Worship while Program (Facebook) keeps
Camera + Lyrics; the same lyrics go to both, TAKE changes only the content and **TAKE SCENE** changes
Program's layout.

### Preview → TAKE → Program

Everything chosen goes to **Preview** first, except song lyrics, which go live on click (see below). **TAKE →** (Space, or Enter while the slides, the cards or a presentation has focus) puts
Preview on Program in one transition (Cut / Fade / Crossfade). Program, the projector and OBS all paint one Program state, and the
Program monitor shows exactly what the audience sees, including **BLACK OUTPUT ACTIVE** with what returns
when Black is switched off. Auto-take (skip Preview) is off by default. Lower thirds keep their own
SHOW / HIDE, and the video background its own TAKE BACKGROUND.

### Displays: Preview or Program on any screen

No screen is hard-wired. Right-click Preview or Program (or use their **DISPLAY ▾** button) and choose
Display 2, Display 3, *Windowed Output* or *Stop Display Output*. A display shows one source at a time:
routing the other source to it asks first ("Display 2 is currently showing Preview. Switch Display 2 to
Program?"). Several displays may show the same source, and Program keeps feeding OBS and recording
whatever the displays show. **PREVIEW | PROGRAM** next to the Projector button flips what the projector
shows at once: Program for a normal service, Preview when Program goes to the livestream. The routing is
remembered; a missing display is reported and its output returns when it is reconnected.

### Stream page: recording and livestream (FFmpeg)

Recording and livestreaming always take their frames from Program (the same painting code as the Program
monitor), so viewers see exactly Program. FFmpeg does the encoding as a separate program: install it once
with `winget install Gyan.FFmpeg`, then click **Refresh** on the Stream page (or *Locate FFmpeg…*). The
page tests which H.264 encoders really work on this computer (NVIDIA, Intel Quick Sync, AMD, Media
Foundation) and falls back to software x264. It has presets (720p30, 1080p30, 1080p60), a simple audio mixer
(microphone, a second input such as camera/capture audio, media and master; volume and mute. Media is
"This app (Program sound)" by default: only this app's Program sound through Windows process loopback, Windows 10
version 2004 or newer, so other programs on the PC are never recorded), **● START RECORDING** (fragmented MP4 in `recordings\`, playable even after a crash;
"● REC" shows in the top bar) and the destination: Facebook Live (server and persistent stream key
from Live Producer → Streaming software) or Custom RTMP. Stream keys are stored encrypted for the Windows
user (DPAPI) in `config\secrets.properties`, shown masked, and never written to logs. **Test Connection**
only checks that the server answers (the key is not sent). **START STREAM** runs the pre-stream check
(camera, microphone, Program, encoder, configuration, internet) and asks for confirmation; sending the
stream itself is switched on in the next step, after recording has been tested on the church computer.

### Global background (video or image)

The **background bar** along the bottom of the window (BACKGROUND · file · ● ACTIVE · Preview · Change ·
Restart · Pause · ON/OFF · Loop · Mute · Overlay) runs one looping video or one still image behind everything on Program,
projector and OBS until the operator turns it off, pauses it, restarts it or takes another one. Songs,
Bible verses, slides, announcements, service items and library browsing never stop, reload or rewind it;
changing slides fades only the text above it. **Change…** (or *Preview as Background* on a library
video or image) puts it in Preview while the old one stays live; **TAKE BACKGROUND** switches with Cut,
Fade or Crossfade and no black gap. PowerPoint slides, full-screen images, service videos and camera scenes
cover the background while it keeps playing underneath, and it reappears at its current position.
Black covers it too (optionally pausing it). Options: loop, mute (default on), overlay, Fit / Fill. Each
theme chooses *Global video*: Use global background (default), Override with its own, or Hide. The top bar
shows **VIDEO BACKGROUND** while it is on. The chosen video and settings are remembered; it always starts OFF.

### Lyrics & Slides cards

Above the slide strip, **Lyrics & Slides** shows one rounded card per slide with its actual words (lyrics,
wrapped Bible text with the version, announcement text) or, for PowerPoint, a thumbnail and title; the section
("VERSE 1 · 1/2") and slide number are small labels. Click a card for Preview (Program waits for TAKE);
Up/Down or the ↑ ↓ buttons step through Preview; after TAKE the LIVE card and the next one stay in view.
Double-click goes live only if enabled in Settings → Presentation (default off; the same applies to
thumbnails and presentation slides). A section sung twice appears twice. It is separate from the service schedule (which decides *what*
comes next) and from the library sidebar. The list and the strip are synchronized: click an entry to
select it and update Preview; cards show ✓ SHOWN, ● LIVE (red, with an edge bar) and ▶ PREVIEW (blue).
Right-click for Send to Preview / Live, Move Up / Down, Duplicate, Remove (whole sections); drag a card to
move its section. A song's order is saved with the song (its *arrangement*, e.g. Verse 1 · Chorus ·
Verse 2 · Chorus · Bridge · Chorus) without copying lyrics, and *Reset to Written Order* undoes it; for
Bible and PowerPoint the order applies to the current showing and the files are never changed. Drag the
divider to resize the list, or collapse it to give the space back to the strip.

**Song lyrics and PowerPoint slides go live on click (on by default).** Clicking a lyrics card, a PowerPoint
slide or its thumbnail sends it straight to Program, with Preview showing the same slide (the header shows a red
"Click = LIVE"); Bible, images and media keep Preview → TAKE. Each can be switched off in Settings → Presentation. `=` / `-` send
the next / previous slide live. Opening a song never puts anything live by itself.

### Typography (lyrics in capitals, Bible as written)

Settings has one typography profile per kind of content: **Lyrics**, **Bible**, **Announcements & Sermon
Notes** and **Custom Slides**. Each holds font family, style (Light … SemiBold … Black), size, text transform
(Original, UPPERCASE, lowercase, Title Case), color, alignment, vertical position, line spacing, shadow
(+ strength) and outline (+ size, color), and is saved in `settings.properties` (`lyrics.fontFamily`,
`lyrics.textTransform`, `bible.fontSize`, …). First run: lyrics use their profile — UPPERCASE, centered,
white, shadow and outline on — so every song appears in capitals on Preview, Program, the projector and OBS
without editing any song; the other profiles start switched off (their themes look as before) and Bible
text stays **Original**.

The letter case is applied only while drawing: lyrics and verses are stored exactly as typed, so switching
back to Original restores their capitals. Which settings win, highest first: a slide's own style → the
song's quick typography → a theme marked *"Use this theme's own text settings"* (Theme editor) → the global
profile. The quick bar above the song preview (FONT · SIZE · STYLE · CASE "AA" · ALIGN · Use Global)
changes only that song. Every change shows in Preview at once; Program changes on the next TAKE (or, for
lyrics, the next click). Action cards show words as typed unless Settings → Interface → *Apply presentation
uppercase to Action Cards* is on. Lower thirds keep their own font; PowerPoint slides show as PowerPoint
drew them. Fonts with separate weight faces (e.g. "Montserrat SemiBold", "Segoe UI Semibold") are used
when installed; a missing font family falls back to Segoe UI.

### Backgrounds: images, video, color, gradient

Backgrounds (themes, songs, slides, Bible, announcements) can be a solid color, a two-color gradient, an
image (JPG, PNG, WebP, GIF, BMP) or a looping video. Video is not limited to MP4: MOV, MKV, AVI, WebM,
MPEG/MPG, M4V, WMV, TS/MTS/M2TS, FLV and anything else VLC decodes can be used — choose *All Files* in
the file dialog for unusual extensions. The app does not trust the extension: VLC must decode a picture
before a video is accepted, otherwise it says *"Unable to play this video format."* Video backgrounds are
muted, loop, keep running while slides change (Verse → Chorus → Verse never restarts them), honour the dim
overlay, and can **Fill** (crop edges) or **Fit** (whole picture, bars in the background color). Videos in
the library carry a VIDEO badge. Recommended for smooth playback: MP4, H.264 video, AAC audio, 1920×1080,
30 FPS. Service videos (Media → video item) are a separate mode: with sound, timeline and
play/pause/stop, ending normally unless looping is chosen in Settings.

### PowerPoint presentations

**Presentations → Import PowerPoint** (or drop .pptx / .ppt files on the list, or use the import button
in Slides). Every slide is rendered once into a local cache and shown as thumbnails while the import
runs; after that the presentation opens instantly. Click a slide for Preview, double-click or TAKE (Space)
for Program; ← / → move through the slides. Presentations can be added to a service; selecting one in the
schedule shows its slides in the bottom strip.

| Renderer | When | Fidelity |
|----------|------|----------|
| Microsoft PowerPoint | used automatically if PowerPoint is installed | exact (PowerPoint exports each slide) |
| Built-in (Apache POI) | everywhere else | text, shapes, pictures, tables and backgrounds; animations are not played, charts/SmartArt may be simplified, missing fonts are substituted, video shows its poster |
| Slide images | import a folder of slides exported from PowerPoint as PNG/JPG | exact |

What may look different is listed per slide (*Details* in the presentation header) and in the log. The
original file is never modified or deleted; if it changes, the app offers **Refresh Presentation**, and if
it was moved, **Locate File…**. The cache is `datapresentations<id>` (`presentation.json`,
`slidesslide-001.png` at 1920×1080, `thumbnailsslide-001.png`).

## Data location

`%LOCALAPPDATA%\JAMCWorshipPresenter\` by default:

```
data\worship.db      SQLite database (created and migrated automatically)
media\{audio,videos,images,backgrounds}
bible\  backups\  logs\  config\settings.properties
```

Override with `-Djamc.home=D:\Somewhere` or the `JAMC_HOME` environment variable (portable USB use).
Before any schema upgrade a snapshot is written to `backups\`.

The folder, the database file and the `jamc.home` / `JAMC_HOME` names are internal and predate the LiveCat name.
They are unchanged on purpose, so an update keeps every church's songs, settings and media.

## Branding

The supplied artwork is in `src/public/LiveCat logo`. `java packaging/MakeBrandingAssets.java` makes everything
else from it: `src/main/resources/branding` (app icon, window icon, header, About and splash), `packaging/app.ico`
(installer and desktop shortcut) and the remote's icons in `../jamc-worship-remote/public/icons`.
The default name and tagline are in `BrandingManager`; a church can still set its own in Settings → Branding.

## Features

| Area | What it does |
|------|--------------|
| Songs | Library with search (title, author, tags, lyrics), favorites, sort, duplicate; sections from headings (Verse 1, Chorus, Bridge, [Tag]); blank line = new slide; per-song theme, font, background |
| Presentations | Import .pptx / .ppt (or slide-image folders) with progress; thumbnail grid and strip; Preview / TAKE / LIVE markers; library with rename, refresh, locate, remove; add to service |
| Slides & Themes | Shared renderer (1920×1080, auto-fit text, shadow, outline, image/video backgrounds, dim overlay); theme library with live editor; custom slide decks with per-slide styling, drag-to-reorder, Save as Theme |
| Bible | Versions with quick buttons (KJV, NLT, ESV, NIV, APSD + your own); switching version keeps the same reference; type references (`jn 3:16-18`) or search words; book/chapter/verse pickers with previous/next chapter and verse; Preview, Live and Add to Service; slides show the reference (above/below/hidden) and version, pack short verses and split long ones |
| Service | Persistent rundowns that autosave; songs, Bible, slides, images, videos, audio, announcements, sermon notes, lower thirds, blank; drag & drop, reorder, duplicate, rename; templates; reopen last service on start |
| Media | Images, videos, audio, backgrounds; copy into library or link to original; preview with transport; favorites; missing files reported, never crash |
| Live | Preview → TAKE → Program with Cut / Fade / Crossfade (0.3–2 s); Auto-take; Black, Blank, Lyrics, Background switches; videos play on Program (auto-play, end behaviour); background audio with fade in/out that keeps playing between slides |
| Output | Projector on any display (fullscreen, never takes keyboard focus; or windowed); Identify Displays; auto-reconnect when the projector is replugged |
| Scenes | OBS-style scenes of layered sources (slide background/media, lyrics, Bible, camera, image, church logo, looping video, lower third, text, clock); visibility and order per layer; position/size/fit/opacity; live edits |
| Lower thirds | Name/title graphics with colors, logo, position and slide/fade animation; SHOW / HIDE |
| OBS | "LiveCat - OBS Output" window, exact 1920×1080 or 1280×720 pixels, optional borderless and green (#00FF00) chroma-key mode — capture with OBS *Window Capture* |
| Settings | Theme, startup, default themes, media and video behaviour, VLC location, cameras, backup / restore (with automatic safety backup), data paths, customizable keyboard shortcuts |

### Live keyboard shortcuts (defaults, customizable in Settings)

Space TAKE · ←/→ slides · PgUp/PgDn service items · B black · W blank · L lyrics · H hide lower third ·
F5 projector on/off · Esc close fullscreen projector · Ctrl+F search · Ctrl+S save · Ctrl+N new song

## Code layout

```
app/         Main, AppContext
config/      AppPaths, AppConfig, DatabaseConfig
database/    DatabaseManager, MigrationManager, Schema, SeedData        (schema v11)
model/       Song, Slide, SlideStyle, Theme, Service, ServiceItem, MediaItem, Scene, Source, LowerThird...
repository/  all SQL (songs, themes, decks, services, media, scenes, lower thirds, Bible, settings)
service/     use cases: songs, themes, decks, service plans, media, Bible, scenes, backup
presentation/ PresentationRenderer (PowerPoint export, Apache POI, slide images), PresentationRenderers
bible/       BibleVersion, BibleProvider (Local + ESV/NLT/API.Bible), BibleSlideBuilder, BibleDisplay,
             BibleBooks, ReferenceParser, BibleImporter, BiblePassage
render/      SlideRenderer, TextRenderer, LowerThirdRenderer, ImageCache, LiveFrames
output/      ProgramOutput (live state), Compositor (scenes), OutputCanvas, PresentationWindow,
             OutputService, ObsOutput, DisplayManager
media/       MediaEngine (VLC), VideoPlayer (callback frames), AudioPlayer, VideoPool, MediaHub
camera/      CameraManager (DirectShow via VLC; pluggable CameraProvider)
ui/          MainFrame, Shortcuts, components/*, dialogs/*, theme/*
```

Database and file work never runs on the Swing event thread (`BackgroundTask`). Program state is
EDT-confined in `ProgramOutput`; every output surface paints the same `Compositor` frame.
`src/test/java/.../tools/ScreenshotTour` renders every page off-screen for visual checks.
