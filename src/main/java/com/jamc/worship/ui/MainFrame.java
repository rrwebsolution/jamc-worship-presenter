package com.jamc.worship.ui;

import com.jamc.worship.app.AppContext;
import com.jamc.worship.background.GlobalBackgroundManager;
import com.jamc.worship.bible.BibleDisplay;
import com.jamc.worship.bible.BiblePassage;
import com.jamc.worship.ui.dialogs.BibleVersionsDialog;
import com.jamc.worship.camera.CameraManager;
import com.jamc.worship.config.AppConfig;
import com.jamc.worship.media.MediaHub;
import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.Presentation;
import com.jamc.worship.model.ContentKind;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Service;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.model.Theme;
import com.jamc.worship.output.DisplayManager;
import com.jamc.worship.output.LiveContent;
import com.jamc.worship.output.ObsOutput;
import com.jamc.worship.output.OutputService;
import com.jamc.worship.output.ProgramOutput;
import com.jamc.worship.output.Transition;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.service.PresentationService;
import com.jamc.worship.service.SlideGenerator;
import com.jamc.worship.ui.components.ActionOrderPanel;
import com.jamc.worship.ui.components.BibleDetailPanel;
import com.jamc.worship.ui.components.BiblePanel;
import com.jamc.worship.ui.components.GlobalBackgroundPanel;
import com.jamc.worship.ui.components.LiveDock;
import com.jamc.worship.ui.components.LowerThirdEditorPanel;
import com.jamc.worship.ui.components.LowerThirdsPanel;
import com.jamc.worship.ui.components.MediaOutputPanel;
import com.jamc.worship.ui.components.MediaLibraryPanel;
import com.jamc.worship.ui.components.OutputPanel;
import com.jamc.worship.ui.components.ScenesDock;
import com.jamc.worship.ui.components.PresentationDetailPanel;
import com.jamc.worship.ui.components.PresentationLibraryPanel;
import com.jamc.worship.ui.components.ScenesPanel;
import com.jamc.worship.ui.components.ServiceSchedulePanel;
import com.jamc.worship.ui.components.ServicesPanel;
import com.jamc.worship.ui.components.SettingsPanel;
import com.jamc.worship.ui.components.Sidebar;
import com.jamc.worship.ui.components.SlideDeckLibraryPanel;
import com.jamc.worship.ui.components.SlideEditorPanel;
import com.jamc.worship.ui.components.SlideThumbnailStrip;
import com.jamc.worship.ui.components.SongDetailPanel;
import com.jamc.worship.ui.components.SongLibraryPanel;
import com.jamc.worship.ui.components.StatusBar;
import com.jamc.worship.ui.components.StyleForm;
import com.jamc.worship.ui.components.ThemeDetailPanel;
import com.jamc.worship.ui.components.ThemeLibraryPanel;
import com.jamc.worship.ui.components.TopBar;
import com.jamc.worship.ui.components.TransportControls;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.ui.theme.Themes;
import com.jamc.worship.util.FileUtils;
import com.jamc.worship.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Operator window. Layout:
 * <pre>
 *  ┌──────────────────────────── top bar ────────────────────────────┐
 *  │ sidebar │ library list │   selected item + preview   │ schedule │
 *  │         │              │                             │ audio    │
 *  ├─────────┴──────────────┴──────────────┬──────────────┴──────────┤
 *  │ slide thumbnails                      │ preview │TAKE│ program │ scenes │
 *  └───────────────────────────── status bar ────────────────────────┘
 * </pre>
 * This class wires the panels together; the panels and services hold the behaviour.
 */
public class MainFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);

    private static final String PAGE_WORKSPACE = "workspace";
    private static final String CENTER_SONG = "song";
    private static final String CENTER_DECK = "deck";
    private static final String CENTER_BIBLE = "bible";
    private static final String CENTER_ITEM = "item";
    private static final String CENTER_SCENE = "scene";
    /** Workspace for a scene item from the service schedule. */
    private final com.jamc.worship.ui.components.SceneWorkspacePanel sceneWorkspace =
            new com.jamc.worship.ui.components.SceneWorkspacePanel(this::sceneClicked, this::sendSceneToProgram);
    private static final String CENTER_PRESENTATION = "presentation";
    private static final String KEY_CURRENT_SERVICE = "service.current";
    private static final String KEY_LIVE_SCENE = "live.scene";
    /** Scene Program starts with (right-click a scene, Set as Default). */
    /** One host for the scene cards on the SCENE page and in the Library. */
    private final SceneHost sceneHost = new SceneHost();
    private com.jamc.worship.ui.components.ScenesHomePanel scenesHome;
    /** The Program scene whose video sources were last synced (see the Program listener). */
    private Scene syncedProgramScene;
    private Scene syncedPreviewScene;
    /** The scenes' IMAGE SLIDESHOW sources, with separate Preview and Program positions. */
    private final com.jamc.worship.output.Slideshows slideshows;
    private final com.jamc.worship.media.MediaStates mediaStates;
    private static final String KEY_DEFAULT_SCENE = "scenes.default";
    /** Livestream mode: Preview keeps its own scene (e.g. Worship) while Program keeps another; TAKE moves content only. */
    private static final String KEY_SEPARATE_LAYOUTS = "scenes.separatePreviewLayout";
    private static final String KEY_PREVIEW_SCENE = "scenes.previewScene";
    private static final String KEY_PREVIEW_FIRST = "live.previewFirst";
    private static final String KEY_LYRICS_CLICK_LIVE_RESET = "live.songClickGoesLive.reset2";

    /** What the bottom strip is showing. */
    private enum StripSource { NONE, SONG, BIBLE, DECK, ITEM, PRESENTATION }

    private final AppContext context;
    private final Shortcuts shortcuts;
    private final BrandingManager branding;
    private final TopBar topBar;
    private final Sidebar sidebar = new Sidebar();
    private final SlideThumbnailStrip thumbnails = new SlideThumbnailStrip();
    private final ProgramOutput program = new ProgramOutput();
    /** What TAKE puts live next: its own state, composed like Program but never shown to the audience. */
    private final ProgramOutput previewState = new ProgramOutput();
    /** A scene chosen in the dock that waits in Preview until TAKE. */
    private Scene pendingScene;
    /** Library lyrics cards: Grid view (several per row) instead of Cards (one per row). */
    private static final String KEY_LYRICS_GRID = "ui.lyricsCards.grid";
    /** A scene card clicked (selected) since the last TAKE: the next TAKE puts it live. */
    private Scene selectedScene;
    private com.jamc.worship.ui.components.ScenesDock scenesDock;
    private JSplitPane scenesSplit;
    /** Preview · TAKE · Program: shown in the Library workspace, and under the scene on the SCENE page. */
    private JPanel livePanel;
    private Scene lastProgramScene;
    private final DisplayManager displays = new DisplayManager();
    private final CameraManager cameras = new CameraManager();
    private com.jamc.worship.camera.CameraNames cameraNames;
    /** The page on screen (leaving SCENE with unsaved scene changes asks Save / Discard / Cancel first). */
    private TopBar.Page currentPage = TopBar.Page.LIBRARY; // the app opens on the Library
    private com.jamc.worship.camera.CameraSettings cameraSettings;
    private final com.jamc.worship.stream.StreamManager streamManager;
    private final com.jamc.worship.remote.RemoteManager remoteManager;
    private final com.jamc.worship.ui.components.StreamPanel streamPanel;
    private final MediaHub hub;
    /** The operator's global video background: lives for the whole session, independent of all content. */
    private final GlobalBackgroundManager globalBackground;
    private final com.jamc.worship.media.AudioRouting audioRouting;
    private final com.jamc.worship.presentation.LibraryPresentations libraryPresentations;
    private final GlobalBackgroundPanel backgroundPanel;
    private final OutputService output;
    private final ObsOutput obs;
    private final LiveDock liveDock;
    private final StatusBar statusBar = new StatusBar();

    private final SongLibraryPanel songLibrary;
    private final SongDetailPanel songDetail;
    private final ThemeLibraryPanel themeLibrary;
    private final ThemeDetailPanel themeDetail;
    private final SlideDeckLibraryPanel deckLibrary;
    private final SlideEditorPanel slideEditor;
    private final BiblePanel biblePanel;
    private final BibleDetailPanel bibleDetail;
    private final BibleDetailPanel itemDetail;
    private final MediaLibraryPanel mediaLibrary;
    private final MediaLibraryPanel videoLibrary;
    private final MediaLibraryPanel imageLibrary;
    private final MediaLibraryPanel audioLibrary;
    private final MediaLibraryPanel backgroundLibrary;
    /** Library → Videos: the videos and backgrounds under one sidebar item, with tabs. */
    private final com.jamc.worship.ui.components.VideoLibraryPanel videoTabs;
    private final MediaOutputPanel mediaDetail;
    private final com.jamc.worship.ui.components.CameraWorkspacePanel cameraWorkspace;
    /** Which whole workspace (slides, media, camera…) is under the library list and the monitors. */
    private final LibraryWorkspaceController workspaces = new LibraryWorkspaceController();
    private final LowerThirdsPanel lowerThirds;
    private final LowerThirdEditorPanel lowerThirdEditor;
    private final PresentationLibraryPanel presentationLibrary;
    private final PresentationDetailPanel presentationDetail;
    private final ServiceSchedulePanel schedule;
    private final ServicesPanel servicesPanel;
    private final ScenesPanel scenesPanel;
    private final SettingsPanel settingsPanel;
    private final TransportControls audioControls = new TransportControls(true);
    private final JLabel audioTitle = Ui.small("No audio loaded");
    private final JPanel audioBar = Ui.transparent(new BorderLayout(0, 2));

    private final CardLayout pageCards = new CardLayout();
    private final JPanel pages = Ui.transparent(pageCards);
    private final CardLayout libraryCards = new CardLayout();
    private final JPanel libraryHolder = Ui.panel(libraryCards, Palette::panelBackground);
    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerHolder = Ui.transparent(centerCards);

    private Slide previewSlide;
    private SlideStyle previewStyle;
    private String previewTitle;
    private String previewVideoPath;
    private List<Slide> songSlides = List.of();
    private SlideStyle songStyle;
    private SlideStyle bibleStyle;
    private BiblePassage biblePassage;
    private SlideStyle itemStyle;
    private ServiceItem currentTextItem;
    private String itemTitle;
    private StripSource stripSource = StripSource.SONG;
    private boolean themesLoaded;
    private boolean decksLoaded;
    private boolean syncingPlaylist = true;
    private List<Scene> scenes = List.of();
    private String playingProgramVideo;
    private Presentation currentPresentation;
    private List<Slide> presentationSlides = List.of();
    private List<PresentationService.PresentationSlide> presentationInfo = List.of();
    private boolean presentationsLoaded;
    private boolean importInStrip;
    /** REFRESH of the open presentation: the slide the operator was on, to open it there again (not slide 1). */
    private Long refreshedPresentationId;
    /** A presentation just refreshed: Preview and Program get its new slides once they are loaded. */
    private Long outputsWaitForRefresh;
    private int refreshedSlideIndex = -1;
    private boolean suppressAutoTake;
    private Sidebar.Category activeCategory = Sidebar.Category.SONGS;
    /** What was last sent to Program from a slide strip, to mark it LIVE. */
    private LiveContent takenContent;
    private String takenKey;
    private List<Slide> takenSlides;
    private int takenIndex = -1;

    private final Map<String, JSplitPane> splits;
    private final JSplitPane sidebarSplit;
    private final JSplitPane librarySplit;
    private final JSplitPane scheduleSplit;
    private final JSplitPane verticalSplit;
    private final JSplitPane bottomSplit;
    /** The LIBRARY navigation above the SERVICE schedule (left column). */
    private final JSplitPane navSplit;
    /** The always-on AUDIO MIXER, under the Scenes (right column). */
    private JPanel mixerRegion;
    private final ActionOrderPanel actionOrder;
    /** Slide object that was last sent to Program from the strip (to find it again after reordering). */
    private Slide takenSlide;

    public MainFrame(AppContext context) {
        super(BrandingManager.DEFAULT_NAME);
        this.context = context;
        // the app's name and icon (Settings → Branding) for every window, header and dialog
        this.branding = BrandingManager.install(new BrandingManager(context.config(), context.paths()));
        setTitle(branding.applicationName());
        this.shortcuts = new Shortcuts(context.config());
        this.topBar = new TopBar(this::showPage);
        // where Program and Preview media sound goes (speakers, monitor headphones) and the media mixer
        this.audioRouting = new com.jamc.worship.media.AudioRouting(context.config());
        this.hub = new MediaHub(path -> context.mediaService().resolve(path), cameras, audioRouting);
        // camera numbers and nicknames ("CAM 2 · PREACHER CAMERA"), the operator's audio input per camera
        this.cameraNames = new com.jamc.worship.camera.CameraNames(context.config());
        this.cameraSettings = new com.jamc.worship.camera.CameraSettings(context.config());
        cameras.setAudioOverride(cameraNames::audioInput);
        // the operator's mute / volume / loop per video, audio file and scene source (kept across visits and restarts)
        this.mediaStates = new com.jamc.worship.media.MediaStates(context.config());
        hub.sceneVideos().setStates(mediaStates);
        Rendering.installFrames(hub);
        this.globalBackground = new GlobalBackgroundManager(context.config(), path -> context.mediaService().resolve(path));
        Rendering.installGlobalBackground(globalBackground);
        // Presentation scene sources reference library presentations by id; their cached slides are looked up here
        this.libraryPresentations = new com.jamc.worship.presentation.LibraryPresentations(context.presentationService());
        Rendering.installPresentations(libraryPresentations);
        this.slideshows = new com.jamc.worship.output.Slideshows(id -> context.mediaService().load(id)
                .filter(m -> context.mediaService().exists(m)).map(MediaItem::getPath).orElse(null));
        com.jamc.worship.output.Compositor.installSlideshows(slideshows);
        this.backgroundPanel = new GlobalBackgroundPanel(globalBackground,
                file -> FileUtils.importInto(context.paths(), file, context.paths().backgroundsDir()));
        StyleForm.ImageImporter importer = file ->
                FileUtils.importInto(context.paths(), file, context.paths().backgroundsDir());
        List<Image> icons = new ArrayList<>(branding.windowIcons());
        branding.addListener(() -> SwingUtilities.invokeLater(() -> {
            setTitle(branding.applicationName());
            icons.clear(); // the projector windows opened from now on get the new icon too
            icons.addAll(branding.windowIcons());
            setIconImages(icons);
            topBar.refreshBranding();
        }));

        schedule = new ServiceSchedulePanel(context.servicePlans());
        songLibrary = new SongLibraryPanel(context.songService(), context.themeService(), importer);
        // Lyrics → Import from YouTube saves LYRICS; Videos → Import from YouTube adds a VIDEO (two separate workflows)
        songLibrary.setYouTubeSupport(context.mediaService(), context.config());
        songDetail = new SongDetailPanel(songLibrary::editSelected, this::addCurrentSongToService);
        if (context.themeService().typography() != null) {
            context.themeService().typography().addListener(this::typographyChanged);
        }
        themeLibrary = new ThemeLibraryPanel(context.themeService(), importer);
        themeDetail = new ThemeDetailPanel(themeLibrary::editSelected, themeLibrary::duplicateSelected);
        deckLibrary = new SlideDeckLibraryPanel(context.slideDeckService());
        slideEditor = new SlideEditorPanel(context.slideDeckService(), context.themeService(), importer, thumbnails);
        biblePanel = new BiblePanel(context.bibleService());
        bibleDetail = new BibleDetailPanel(() -> biblePanel.step(-1), () -> biblePanel.step(1), () -> {
            BiblePassage p = biblePanel.selectedPassage();
            if (p != null) schedule.addItem(BiblePanel.serviceItem(p));
        });
        itemDetail = new BibleDetailPanel(thumbnails::selectPrevious, thumbnails::selectNext, () -> { });
        mediaLibrary = new MediaLibraryPanel(context.mediaService(), "Media", List.of(), null);
        videoLibrary = new MediaLibraryPanel(context.mediaService(), "Videos", List.of(MediaCategory.VIDEO), MediaCategory.VIDEO);
        videoLibrary.setYouTubeSupport(context.config());
        imageLibrary = new MediaLibraryPanel(context.mediaService(), "Images", List.of(MediaCategory.IMAGE), MediaCategory.IMAGE);
        audioLibrary = new MediaLibraryPanel(context.mediaService(), "Audio", List.of(MediaCategory.AUDIO), MediaCategory.AUDIO);
        backgroundLibrary = new MediaLibraryPanel(context.mediaService(), "Backgrounds", List.of(MediaCategory.BACKGROUND),
                MediaCategory.BACKGROUND);
        videoTabs = new com.jamc.worship.ui.components.VideoLibraryPanel(videoLibrary, backgroundLibrary);
        // the one MEDIA PREVIEW monitor for every media library; the background's controls sit under it
        mediaDetail = new MediaOutputPanel(context.mediaService(), hub, globalBackground, backgroundPanel, new MediaOutputPanel.Actions() {
            @Override
            public void sendToPreview(MediaItem item) {
                previewMedia(item);
            }

            @Override
            public boolean sendsToProgram(MediaItem item) {
                return mediaGoesLive(item);
            }

            @Override
            public void playAudio(MediaItem item) {
                MainFrame.this.playAudio(item);
            }

            @Override
            public void addToService(ServiceItem item) {
                schedule.addItem(item);
                statusBar.showMessage("\"" + item.getTitle() + "\" added to the service. Preview and Program are unchanged.");
            }

            @Override
            public void toggleFavorite(MediaItem item) {
                BackgroundTask.run("Favorite", () -> {
                    context.mediaService().setFavorite(item.getId(), !item.isFavorite());
                    return true;
                }, ok -> activeMediaLibrary().refresh(item.getId()),
                        err -> Dialogs.showError(MainFrame.this, "Could not update favorites.", err));
            }
        });
        mediaDetail.setMediaStates(mediaStates);
        cameraWorkspace = new com.jamc.worship.ui.components.CameraWorkspacePanel(cameras, cameraNames, cameraSettings,
                context.config(), new com.jamc.worship.ui.components.CameraWorkspacePanel.Actions() {
                    @Override
                    public void sendToPreview(String camera, com.jamc.worship.model.FitMode fit, boolean mirror) {
                        previewCamera(camera, fit, mirror, false);
                    }

                    @Override
                    public void switchProgram(String camera, com.jamc.worship.model.FitMode fit, boolean mirror) {
                        previewCamera(camera, fit, mirror, true);
                    }

                    @Override
                    public Scene programScene() {
                        return program.scene();
                    }

                    @Override
                    public Scene previewScene() {
                        return previewState.scene();
                    }
                });
        lowerThirds = new LowerThirdsPanel(context.sceneService());
        lowerThirdEditor = new LowerThirdEditorPanel(context.sceneService(), program, importer);
        presentationLibrary = new PresentationLibraryPanel(context.presentationService());
        presentationDetail = new PresentationDetailPanel(new PresentationDetailPanel.Actions() {
            @Override
            public void select(int index) {
                thumbnails.select(index); // also the slide already selected, like the first slide of a new presentation
            }

            @Override
            public void take(int index) {
                if (!separatePresentation()) {
                    return; // TAKE puts the selected slide on Preview and Program
                }
                // double-click on a slide here: Preview (TAKE puts it live), like the slide list
                thumbnails.setSelectedIndex(index);
                previewSelectedPresentationSlide();
                if (doubleClickTakes()) {
                    MainFrame.this.take();
                }
            }

            @Override
            public void previous() {
                stepSlide(false);
            }

            @Override
            public void next() {
                stepSlide(true);
            }

            @Override
            public void preview() {
                previewSelectedPresentationSlide();
            }

            @Override
            public void takeSelected() {
                previewSelectedPresentationSlide();
                MainFrame.this.take();
            }

            @Override
            public void addToService() {
                if (currentPresentation != null) schedule.addItem(PresentationLibraryPanel.serviceItem(currentPresentation));
            }

            @Override
            public void refresh() {
                presentationLibrary.refreshSelected(null);
            }

            @Override
            public void locate() {
                presentationLibrary.locateSelected();
            }

            @Override
            public void importPowerPoint() {
                presentationLibrary.importPowerPoint();
            }

            @Override
            public void showNotes() {
                showPresentationNotes();
            }
        });
        servicesPanel = new ServicesPanel(context.servicePlans());
        scenesPanel = new ScenesPanel(context.sceneService(), program, cameras, importer, context.presentationService(),
                context.mediaService());
        this.output = new OutputService(program, previewState, displays, context.config(), icons, this::operatorDevice);
        this.obs = new ObsOutput(program, context.config(), icons);
        // recording and livestreaming always take their frames from Program
        this.streamManager = new com.jamc.worship.stream.StreamManager(context.config(), context.paths().root(), program,
                new com.jamc.worship.stream.SecretStore(context.paths().settingsFile().resolveSibling("secrets.properties")),
                cameras::cameras);
        this.streamPanel = new com.jamc.worship.ui.components.StreamPanel(streamManager, msg -> statusBar.showMessage(msg));
        streamManager.addListener(this::updateRecordingIndicator);
        topBar.setRecordActions(streamPanel::toggleRecording, streamPanel::startStream);
        streamManager.refreshCapabilities(); // known before the first RECORD or STREAM press
        // while a video or music is heard on Program, the room microphones are muted in recordings / the livestream
        streamManager.setProgramMediaPlaying(hub::programMediaHeard);
        new javax.swing.Timer(1000, e -> updateRecordingIndicator()).start();
        this.remoteManager = new com.jamc.worship.remote.RemoteManager(context.config(),
                context.paths().configDir().resolve("remote-devices.json"));
        BackgroundTask.run("Start the remote server", () -> {
            remoteManager.startIfEnabled();
            return true;
        }, ok -> { }, err -> log.warn("Remote server did not start", err));
        this.settingsPanel =new SettingsPanel(context, this::changeTheme, shortcuts, obs, hub, this::restartApplication,
                this::manageBibleVersions);
        settingsPanel.setUpdateCheckHandler(() -> checkForUpdates(true));
        settingsPanel.setWhatsNewHandler(this::showWhatsNew);
        settingsPanel.setCameraSwitchFadeHandler(ms -> {
            program.setCameraSwitchFade(ms);
            previewState.setCameraSwitchFade(ms);
        });
        settingsPanel.setSongsResetHandler(() -> {
            songLibrary.reloadCurrent(); // the open song drops its own font settings too
            typographyChanged();
        });
        // Preview animates with the same transition as Program, so the operator sees the change before TAKE
        this.liveDock = new LiveDock(program, previewState, new LiveDock.Actions() {
            @Override
            public void take() {
                MainFrame.this.take();
            }

            @Override
            public void toggleOutput() {
                output.toggle(MainFrame.this);
            }

            @Override
            public void transitionChanged(Transition transition, int durationMs) {
                program.setTransition(transition, durationMs);
                previewState.setTransition(transition, durationMs);
                context.config().set(AppConfig.KEY_TRANSITION, transition.name());
                context.config().set(AppConfig.KEY_TRANSITION_MS, String.valueOf(durationMs));
                context.config().save();
            }

            @Override
            public void takeScene() {
                Scene s = previewState.scene();
                if (s != null) {
                    setLiveScene(s, true);
                }
            }

            @Override
            public void autoTakeChanged(boolean autoTake) {
                SwingUtilities.invokeLater(MainFrame.this::updateClickHint);
                context.config().set(AppConfig.KEY_AUTO_TAKE, String.valueOf(autoTake));
                context.config().save();
                statusBar.showMessage(autoTake ? "Auto-take on: selecting a slide sends it live."
                        : "Auto-take off: select a slide, then press TAKE or Space.");
            }
        });
        loadLiveSettings();
        liveDock.setOutputRouter(output, displays, () -> this);
        settingsPanel.installLiveSettings(new SettingsPanel.LiveHooks() {
            @Override
            public OutputService output() {
                return output;
            }

            @Override
            public DisplayManager displays() {
                return displays;
            }

            @Override
            public com.jamc.worship.stream.StreamManager stream() {
                return streamManager;
            }

            @Override
            public com.jamc.worship.remote.RemoteManager remote() {
                return remoteManager;
            }

            @Override
            public Transition transition() {
                return Transition.parse(context.config().get(AppConfig.KEY_TRANSITION, null), Transition.FADE);
            }

            @Override
            public int transitionMs() {
                return parseInt(context.config().get(AppConfig.KEY_TRANSITION_MS, "500"), 500);
            }

            @Override
            public void setTransition(Transition transition, int durationMs) {
                // the same as choosing it next to TAKE, and the controls there follow
                program.setTransition(transition, durationMs);
                previewState.setTransition(transition, durationMs);
                liveDock.setTransition(transition, durationMs);
                context.config().set(AppConfig.KEY_TRANSITION, transition.name());
                context.config().set(AppConfig.KEY_TRANSITION_MS, String.valueOf(durationMs));
                context.config().save();
            }

            @Override
            public boolean autoTake() {
                return liveDock.isAutoTake();
            }

            @Override
            public void setAutoTake(boolean on) {
                liveDock.setAutoTake(on);
                context.config().set(AppConfig.KEY_AUTO_TAKE, String.valueOf(on));
                context.config().save();
                SwingUtilities.invokeLater(MainFrame.this::updateClickHint);
            }

            @Override
            public boolean separateLayouts() {
                return MainFrame.this.separateLayouts();
            }

            @Override
            public void setSeparateLayouts(boolean on) {
                sceneHost.setSeparateLayouts(on);
            }

            @Override
            public void contentSeparationChanged() {
                SwingUtilities.invokeLater(MainFrame.this::updateClickHint);
            }

            @Override
            public void audioMixerVisibilityChanged() {
                SwingUtilities.invokeLater(MainFrame.this::applyMixerVisible);
            }

            @Override
            public List<Scene> scenes() {
                return scenes;
            }

            @Override
            public Long defaultSceneId() {
                return sceneHost.defaultSceneId();
            }

            @Override
            public void setDefaultScene(Scene scene) {
                if (scene != null) {
                    sceneHost.setDefaultScene(scene);
                    return;
                }
                context.config().set(KEY_DEFAULT_SCENE, null);
                context.config().save();
                scenesDock.sync();
                if (scenesHome != null) scenesHome.sync();
            }
        });
        liveDock.setMonitorListener(on -> {
            audioRouting.setPreviewMonitor(on);
            statusBar.showMessage(on ? "Preview audio ON: Preview videos play on the monitor device ("
                    + audioRouting.nameOf(audioRouting.monitorDevice()) + ")." : "Preview audio off: only Program is heard.");
        });
        hub.cameraAudio().setSink(streamManager::setLiveInputs);
        hub.sceneVideos().addListener(this::updateProgramMedia);
        new javax.swing.Timer(1000, e -> updateProgramMediaNote()).start();

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setIconImages(icons);
        setJMenuBar(buildMenuBar());

        // ---- library column (one card per sidebar category)
        libraryHolder.add(songLibrary, Sidebar.Category.SONGS.name());
        libraryHolder.add(biblePanel, Sidebar.Category.BIBLE.name());
        libraryHolder.add(mediaLibrary, Sidebar.Category.MEDIA.name());
        libraryHolder.add(videoTabs, Sidebar.Category.VIDEOS.name());
        libraryHolder.add(imageLibrary, Sidebar.Category.IMAGES.name());
        libraryHolder.add(audioLibrary, Sidebar.Category.AUDIO.name());
        libraryHolder.add(deckLibrary, Sidebar.Category.SLIDES.name());
        libraryHolder.add(presentationLibrary, Sidebar.Category.PRESENTATIONS.name());
        libraryHolder.add(themeLibrary, Sidebar.Category.THEMES.name());
        libraryHolder.add(lowerThirds, Sidebar.Category.LOWER_THIRDS.name());
        libraryHolder.add(cameraWorkspace.libraryPanel(), Sidebar.Category.CAMERA.name());

        // ---- details beside the slide cards (Songs, Bible, Presentations, Custom Slides, service items)
        centerHolder.add(songDetail, CENTER_SONG);
        centerHolder.add(slideEditor, CENTER_DECK);
        centerHolder.add(bibleDetail, CENTER_BIBLE);
        centerHolder.add(itemDetail, CENTER_ITEM);
        centerHolder.add(presentationDetail, CENTER_PRESENTATION);
        centerHolder.add(sceneWorkspace, CENTER_SCENE);

        JPanel sidebarPanel = region(sidebar);
        JPanel detailPanel = region(centerHolder);
        JPanel scheduleColumn = Ui.panel(new BorderLayout(), Palette::panelBackground);
        scheduleColumn.add(schedule, BorderLayout.CENTER);
        scheduleColumn.add(buildAudioBar(), BorderLayout.SOUTH);
        libraryHolder.setMinimumSize(new Dimension(240, 0));
        detailPanel.setMinimumSize(new Dimension(320, 0));
        scheduleColumn.setMinimumSize(new Dimension(210, 0));

        // LIBRARY navigation | Library | PRESENTATION AREA (Preview · TAKE · Program) | Scenes + Audio Mixer
        livePanel = region(liveDock);
        livePanel.setMinimumSize(new Dimension(480, 0));
        // AUDIO MIXER: always on screen under the Scenes (like OBS), so the sound can be watched while recording
        // or streaming; Settings → Recording & Livestream can switch it off
        mixerRegion = region(new com.jamc.worship.ui.components.AudioMixerPanel(audioRouting, streamManager, hub.cameraAudio()));
        mixerRegion.setMinimumSize(new Dimension(0, 150));
        mixerRegion.setVisible(context.config().getBoolean(SettingsPanel.KEY_SHOW_MIXER, true));
        // SCENES (how the output is arranged) above the audio mixer
        scenesDock = new com.jamc.worship.ui.components.ScenesDock(sceneHost);
        JPanel scenesPanelRegion = region(scenesDock);
        scenesPanelRegion.setBorder(Ui.line(false, false, true, false));
        scenesPanelRegion.setMinimumSize(new Dimension(0, 120));
        scenesSplit = split(JSplitPane.VERTICAL_SPLIT, scenesPanelRegion, mixerRegion, 0.5);
        scenesSplit.setMinimumSize(new Dimension(250, 0));
        // the SERVICE schedule under the LIBRARY navigation on the left
        sidebarPanel.setMinimumSize(new Dimension(0, 120));
        navSplit = split(JSplitPane.VERTICAL_SPLIT, sidebarPanel, scheduleColumn, 0.5);
        navSplit.setMinimumSize(new Dimension(210, 0));

        librarySplit = split(JSplitPane.HORIZONTAL_SPLIT, libraryHolder, livePanel, 0.0);

        JPanel thumbsPanel = region(thumbnails);
        thumbsPanel.setMinimumSize(new Dimension(260, 110));
        // ONE horizontal strip is the content selector: lyrics cards (songs, Bible) or rendered pages
        // (PowerPoint, custom slides). The order list opens from its ORDER button, not as a second list.
        actionOrder = new ActionOrderPanel(thumbnails, new OrderHost());
        thumbnails.setTextCase(() -> new OrderHost().cardsUseTextCase());
        thumbnails.addHeaderAction(clearToggle());
        actionOrder.addHeaderAction(clearToggle());
        JButton order = Ui.button("Order", "list");
        order.setToolTipText("Change the order (a song's arrangement)");
        order.addActionListener(e -> showOrderEditor());
        thumbnails.addHeaderAction(order);
        // lyrics cards as Cards (one per row) or Grid (several per row): the operator's choice is remembered
        thumbnails.setGridView(context.config().getBoolean(KEY_LYRICS_GRID, false));
        thumbnails.addViewListener(grid -> {
            context.config().set(KEY_LYRICS_GRID, String.valueOf(grid));
            context.config().save();
        });
        thumbnails.addSlidesChangedListener(this::applyStripMode);
        // CURRENT CONTENT: the lyrics / page cards, beside the selected item's details
        bottomSplit = split(JSplitPane.HORIZONTAL_SPLIT, thumbsPanel, detailPanel, 0.6);
        bottomSplit.setMinimumSize(new Dimension(0, 150));

        // DYNAMIC LIBRARY WORKSPACE: one whole view per Library category, switched as a unit (CardLayout)
        workspaces.add(LibraryWorkspaceController.Workspace.SLIDES, bottomSplit);
        workspaces.add(LibraryWorkspaceController.Workspace.MEDIA, region(mediaDetail));
        workspaces.add(LibraryWorkspaceController.Workspace.CAMERA, region(cameraWorkspace));
        workspaces.add(LibraryWorkspaceController.Workspace.THEME, region(themeDetail));
        workspaces.add(LibraryWorkspaceController.Workspace.LOWER_THIRD, region(lowerThirdEditor));
        workspaces.container().setMinimumSize(new Dimension(0, 150));
        verticalSplit = split(JSplitPane.VERTICAL_SPLIT, librarySplit, workspaces.container(), 0.5);
        // LIBRARY navigation + SERVICE run the full height on the left, SCENES + AUDIO MIXER on the right
        scheduleSplit = split(JSplitPane.HORIZONTAL_SPLIT, verticalSplit, scenesSplit, 1.0);
        sidebarSplit = split(JSplitPane.HORIZONTAL_SPLIT, navSplit, scheduleSplit, 0.0);
        // ".v2"/".v4": the panes moved, so old saved positions no longer fit
        splits = Map.of("sidebar.v2", sidebarSplit, "library", librarySplit, "schedule.v3", scheduleSplit, "scenes.v4", scenesSplit,
                "vertical.v2", verticalSplit, "bottom.v2", bottomSplit, "nav", navSplit);

        JPanel workspace = Ui.transparent(new BorderLayout());
        // no permanent background bar: its controls live under MEDIA PREVIEW, with a small "BG:" pill in the top bar
        workspace.add(sidebarSplit, BorderLayout.CENTER);
        pages.add(workspace, PAGE_WORKSPACE);
        pages.add(region(servicesPanel), TopBar.Page.SERVICE.name());
        // Scenes and sources stay in the rendering engine (the live scene still composes Program) but are not
        // part of the normal worship UI: OBS handles cameras and production scenes.
        pages.add(region(new OutputPanel(displays, output, this::operatorDevice)), TopBar.Page.OUTPUT.name());
        pages.add(region(streamPanel), TopBar.Page.STREAM.name());
        // SCENE: the homepage (scene cards; one scene open with its sources and + ADD SOURCE)
        scenesHome = new com.jamc.worship.ui.components.ScenesHomePanel(sceneHost, region(scenesPanel), s -> scenesPanel.edit(s.getId()));
        scenesHome.setLeaveEditor(scenesPanel::confirmLeave); // unsaved scene changes: Save / Discard / Cancel
        scenesPanel.setCameraNames(cameraNames);
        scenesPanel.setSongs(context.songService());
        scenesPanel.setSlideshows(slideshows);
        scenesPanel.setServiceItems(() -> schedule.current() == null ? List.of() : schedule.current().getItems());
        scenesPanel.setOpenSong(id -> {
            topBar.selectPage(TopBar.Page.LIBRARY);
            showPage(TopBar.Page.LIBRARY);
            sidebar.selectCategory(Sidebar.Category.SONGS);
            songLibrary.refresh(id);
        });
        pages.add(scenesHome, TopBar.Page.SCENE.name());
        pages.add(region(settingsPanel), TopBar.Page.SETTINGS.name());

        // MAIN NAVIGATION is in the top bar; the page uses the full width (the Library sidebar is inside LIBRARY)
        JPanel body = Ui.transparent(new BorderLayout());
        body.add(pages, BorderLayout.CENTER);
        JPanel root = Ui.panel(new BorderLayout(), Palette::windowBackground);
        root.add(topBar, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        root.add(statusBar, BorderLayout.SOUTH);
        setContentPane(root);

        wireEvents();
        installShortcuts();

        statusBar.setDataDirectory(context.paths().root().toString());
        statusBar.setDatabaseStatus(true, "Database OK");

        // the app opens on the Library, on its last category (Lyrics on the first launch); nothing goes to Program
        sidebar.selectCategory(savedLibraryCategory());
        openLibrary();
        restoreWindowBounds();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                restoreDividers();
                songLibrary.refresh(null);
                startup();
                SwingUtilities.invokeLater(songLibrary::focusSearch);
            }

            @Override
            public void windowClosing(WindowEvent e) {
                scenesPanel.saveOnExit();
                shutdown();
            }
        });
    }

    // ------------------------------------------------------------------ startup

    private void startup() {
        loadScenes();
        lowerThirds.refresh(null);
        openStartupService();
        watchAudioDevices();
        BackgroundTask.run("Detect cameras", cameras::detect, cams -> { }, err -> log.warn("Camera detection failed", err));
        BackgroundTask.run("Clean presentation cache", context.presentationService()::cleanUp, n -> { },
                err -> log.warn("Presentation cache clean-up failed", err));
        if (context.config().getBoolean(SettingsPanel.KEY_OPEN_OUTPUT, false)) {
            output.restore(this); // the remembered Preview / Program routing
        }
        BackgroundTask.run("Remove installed update files", () -> {
            java.nio.file.Path dir = context.paths().root().resolve("updates"); // the installer already ran
            if (java.nio.file.Files.isDirectory(dir)) {
                try (var files = java.nio.file.Files.list(dir)) {
                    for (java.nio.file.Path f : files.toList()) {
                        java.nio.file.Files.deleteIfExists(f);
                    }
                }
            }
            return null;
        }, x -> { }, err -> log.debug("Update files not removed", err));
        showWhatsNewAfterUpdate();
        if (context.config().getBoolean(com.jamc.worship.update.UpdateChecker.KEY_AUTO_CHECK, true)) {
            javax.swing.Timer later = new javax.swing.Timer(8000, e -> checkForUpdates(false)); // after startup settles
            later.setRepeats(false);
            later.start();
        }
    }

    // ------------------------------------------------------------------ updates

    /** The first start of a new version (after an update): shows once what is new in it. */
    private void showWhatsNewAfterUpdate() {
        String current = com.jamc.worship.update.UpdateChecker.currentVersion();
        if (current == null) {
            return; // development copy
        }
        String key = com.jamc.worship.update.WhatsNew.KEY_LAST_RUN_VERSION;
        String last = context.config().get(key, null);
        if (last == null && context.config().get(AppConfig.KEY_WINDOW_MAXIMIZED, null) != null) {
            last = "older"; // used before versions were remembered: this start is an update, not a first install
        }
        if (!current.equals(last)) {
            context.config().set(key, current);
            context.config().save();
        }
        if (com.jamc.worship.update.WhatsNew.justUpdated(last, current)) {
            String notes = com.jamc.worship.update.WhatsNew.bundled();
            if (!notes.isBlank()) {
                javax.swing.Timer later = new javax.swing.Timer(1500, e -> showWhatsNew()); // after the window is up
                later.setRepeats(false);
                later.start();
            }
        }
    }

    /** Help → What's New: the new features and fixes of this version. */
    void showWhatsNew() {
        com.jamc.worship.ui.components.WhatsNewView.show(this, branding.applicationName(),
                com.jamc.worship.update.UpdateChecker.currentVersion(), com.jamc.worship.update.WhatsNew.bundled());
    }

    /**
     * Looks for a newer version on GitHub. Automatic checks stay quiet when offline, up to date or the
     * version was skipped; {@code manual} (Settings → Check for Updates) always says what it found.
     */
    void checkForUpdates(boolean manual) {
        String current = com.jamc.worship.update.UpdateChecker.currentVersion();
        if (current == null) {
            if (manual) {
                Dialogs.showInfo(this, "Updates", "This copy runs from the source code (development), so it is not updated "
                        + "from GitHub. Installed copies (the .exe installer) update themselves.");
            }
            return;
        }
        var checker = new com.jamc.worship.update.UpdateChecker();
        BackgroundTask.run("Check for updates", checker::latest, found -> {
            var release = found.orElse(null);
            boolean newer = release != null && com.jamc.worship.update.UpdateChecker.isNewer(release.version(), current);
            String skipped = context.config().get(com.jamc.worship.update.UpdateChecker.KEY_SKIPPED, "");
            if (!newer || (!manual && release.version().equals(skipped))) {
                if (manual) {
                    Dialogs.showInfo(this, "Updates", "You have the latest version (" + current + ").");
                }
                return;
            }
            Object[] options = {"Update Now", "Later", "Skip This Version"};
            String intro = "A new version of <b>" + branding.applicationName().replace("<", "&lt;") + "</b> is available.<br><br>"
                    + "Installed: " + current + "<br>New: <b>" + release.version() + "</b>"
                    + String.format("  (%.0f MB download)", release.size() / 1_048_576.0)
                    + "<br><br>Update Now downloads it, saves your work, closes the app and opens the installer. "
                    + "Songs, services and settings are kept.";
            int choice = javax.swing.JOptionPane.showOptionDialog(this,
                    com.jamc.worship.ui.components.WhatsNewView.panel(intro, release.notes()),
                    "Update available", javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.INFORMATION_MESSAGE,
                    null, options, options[0]);
            if (choice == 0) {
                installUpdate(checker, release);
            } else if (choice == 2) {
                context.config().set(com.jamc.worship.update.UpdateChecker.KEY_SKIPPED, release.version());
                context.config().save();
            }
        }, err -> {
            log.info("Update check skipped: {}", err.getMessage()); // offline is normal
            if (manual) {
                Dialogs.showInfo(this, "Updates", "Could not check for updates. Check the internet connection.\n("
                        + err.getMessage() + ")");
            }
        });
    }

    private void installUpdate(com.jamc.worship.update.UpdateChecker checker,
                               com.jamc.worship.update.UpdateChecker.Release release) {
        if (streamManager.live().active() || streamManager.recording().active()) {
            Dialogs.showInfo(this, "Update", "Stop the livestream / recording first, then choose Update Now again "
                    + "(Settings → General → Check for Updates).");
            return;
        }
        javax.swing.JProgressBar bar = new javax.swing.JProgressBar(0, 1000);
        bar.setStringPainted(true);
        bar.setString("Downloading " + release.installerName() + "…");
        javax.swing.JDialog progress = new javax.swing.JDialog(this, "Downloading update", true);
        javax.swing.JPanel box = new javax.swing.JPanel(new java.awt.BorderLayout(0, 10));
        box.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16));
        box.add(new javax.swing.JLabel("Downloading version " + release.version() + "…"), java.awt.BorderLayout.NORTH);
        box.add(bar, java.awt.BorderLayout.CENTER);
        javax.swing.JButton cancel = new javax.swing.JButton("Cancel");
        javax.swing.JPanel south = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        south.add(cancel);
        box.add(south, java.awt.BorderLayout.SOUTH);
        progress.setContentPane(box);
        progress.setSize(420, 150);
        progress.setLocationRelativeTo(this);
        progress.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        java.nio.file.Path dir = context.paths().root().resolve("updates");
        Thread worker = Thread.ofVirtual().name("update-download").start(() -> {
            try {
                java.nio.file.Path exe = checker.download(release, dir, got -> SwingUtilities.invokeLater(() -> {
                    if (release.size() > 0) {
                        bar.setValue((int) (got * 1000 / release.size()));
                        bar.setString(String.format("%.0f / %.0f MB", got / 1_048_576.0, release.size() / 1_048_576.0));
                    }
                }));
                SwingUtilities.invokeLater(() -> {
                    progress.dispose();
                    runInstaller(exe);
                });
            } catch (InterruptedException e) {
                SwingUtilities.invokeLater(progress::dispose);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    progress.dispose();
                    Dialogs.showError(this, "The update could not be downloaded.", e);
                });
            }
        });
        cancel.addActionListener(e -> worker.interrupt());
        progress.setVisible(true);
    }

    /** Saves everything, starts the installer and closes the app (the installer replaces the old version). */
    private void runInstaller(java.nio.file.Path exe) {
        log.info("Installing update {}", exe.getFileName());
        saveEverything();
        try {
            new ProcessBuilder("cmd", "/c", "start", "", exe.toString()).start();
        } catch (java.io.IOException e) {
            Dialogs.showError(this, "The installer could not be started. It was saved in " + exe.getParent() + ".", e);
            return;
        }
        releaseResources();
        dispose();
        System.exit(0);
    }

    /**
     * Lists the audio outputs once VLC is ready and again every 20 s, so headphones, HDMI audio or a USB
     * interface plugged in or out is noticed; a missing chosen device falls back to the Windows default.
     */
    private void watchAudioDevices() {
        Runnable check = () -> BackgroundTask.run("Audio devices", () -> {
            for (int i = 0; i < 60 && !hub.available(); i++) {
                Thread.sleep(500); // VLC starts in the background
            }
            return audioRouting.refreshDevices();
        }, devices -> {
            String chosen = audioRouting.programDevice();
            boolean missing = !chosen.isEmpty() && !audioRouting.available(chosen);
            if (missing && !audioDeviceWarned) {
                statusBar.showMessage("⚠ Program audio device unavailable: using the Windows default output (Settings → Audio).");
                log.warn("Program audio device {} unavailable; using the default", chosen);
            }
            audioDeviceWarned = missing;
        }, err -> log.warn("Audio device check failed", err));
        check.run();
        new javax.swing.Timer(20_000, e -> check.run()).start();
    }

    private boolean audioDeviceWarned;

    private void openStartupService() {
        boolean reopen = context.config().getBoolean(SettingsPanel.KEY_REOPEN_SERVICE, true);
        String saved = context.config().get(KEY_CURRENT_SERVICE, null);
        BackgroundTask.run("Open service", () -> {
            if (reopen && saved != null) {
                try {
                    Optional<Service> s = context.servicePlans().load(Long.parseLong(saved));
                    if (s.isPresent()) {
                        return s.get();
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to the newest Sunday service
                }
            }
            String playlist = Sidebar.PLAYLISTS.getFirst();
            return context.servicePlans().latest(playlist).orElseGet(() -> context.servicePlans().create(playlist));
        }, s -> {
            schedule.open(s);
            syncingPlaylist = false;
            statusBar.showMessage("Opened \"" + s.getName() + "\"");
        }, err -> {
            syncingPlaylist = false;
            Dialogs.showError(this, "The service could not be opened.", err);
        });
    }

    private void loadScenes() {
        BackgroundTask.run("Load scenes", () -> context.sceneService().scenes(), list -> {
            scenes = list;
            // Program starts with the default scene; else the last live scene when it shows content (a
            // "Camera" or "Blank" scene left live last week should not hide this week's lyrics); else Worship.
            String defaultId = context.config().get(KEY_DEFAULT_SCENE, null);
            String savedId = context.config().get(KEY_LIVE_SCENE, null);
            Scene live = list.stream().filter(s -> String.valueOf(s.getId()).equals(defaultId)).findFirst()
                    .or(() -> list.stream().filter(s -> String.valueOf(s.getId()).equals(savedId)).filter(MainFrame::showsContent).findFirst())
                    .or(() -> list.stream().filter(s -> s.getName().equalsIgnoreCase("Worship")).filter(MainFrame::showsContent).findFirst())
                    .orElse(null); // null = the built-in composition: background, content, lower third
            program.setScene(live, false);
            if (separateLayouts()) {
                String previewId = context.config().get(KEY_PREVIEW_SCENE, null);
                list.stream().filter(s -> String.valueOf(s.getId()).equals(previewId)).findFirst()
                        .ifPresent(s -> previewState.setScene(s, false));
            }
            scenesDock.setScenes(list);
            if (scenesHome != null) scenesHome.setScenes(list);
            liveDock.setTakeSceneVisible(separateLayouts());
            updatePreviewNote();
        }, err -> log.warn("Could not load scenes", err));
    }

    /** True if the scene draws slide content (lyrics, verses, slides) on Program. */
    static boolean showsContent(Scene scene) {
        return scene.getSources().stream().anyMatch(s -> s.isVisible()
                && (s.getType() == com.jamc.worship.model.SourceType.LYRICS || s.getType() == com.jamc.worship.model.SourceType.BIBLE));
    }

    // ------------------------------------------------------------------ wiring

    private void wireEvents() {
        sidebar.addCategoryListener(category -> {
            showPage(TopBar.Page.LIBRARY);
            topBar.selectPage(TopBar.Page.LIBRARY);
            showCategory(category);
            // the Library reopens on the category used last (also after a restart)
            if (!category.name().equals(context.config().get(KEY_LIBRARY_CATEGORY, null))) {
                context.config().set(KEY_LIBRARY_CATEGORY, category.name());
                context.config().save();
            }
        });
        // clicking the category already selected (e.g. Lyrics) leaves a service item's own slides (Blank, …)
        sidebar.addCategoryReselectListener(category -> leaveItemWorkspace());
        sidebar.addPlaylistListener(this::openPlaylist);

        songLibrary.setStatusHandler(statusBar::showMessage);
        songLibrary.setAddToServiceHandler(song -> schedule.addItem(ServiceItem.ofSong(song)));
        songLibrary.addSelectionListener(this::onSongLoaded);
        songLibrary.setRowClickHandler(this::leaveItemWorkspace);

        schedule.setStatusHandler(statusBar::showMessage);
        // one click on a service item: it shows in the workspace and becomes active in its library (Preview and
        // Program do not change; a slide clicked in the workspace goes there as usual)
        schedule.setActivationHandler(this::presentItem);
        schedule.setRemovalHandler(removed -> {
            if (removed == currentTextItem) {
                leaveItemWorkspace(); // the removed item is no longer shown: back to the library's active content
            }
        });
        schedule.setOpenServicesHandler(() -> {
            topBar.selectPage(TopBar.Page.SERVICE);
            showPage(TopBar.Page.SERVICE);
        });
        schedule.setServiceChangedHandler(s -> {
            if (s.getId() != null) {
                context.config().set(KEY_CURRENT_SERVICE, String.valueOf(s.getId()));
                context.config().save();
            }
            if (s.getPlaylist() != null) {
                boolean was = syncingPlaylist;
                syncingPlaylist = true;
                sidebar.selectPlaylist(s.getPlaylist());
                syncingPlaylist = was;
            }
        });
        schedule.setLowerThirdChoices(() -> lowerThirds.items().stream().map(LowerThirdsPanel::serviceItem).toList());
        servicesPanel.setStatusHandler(statusBar::showMessage);
        servicesPanel.setOpenHandler(s -> {
            schedule.open(s);
            topBar.selectPage(TopBar.Page.LIBRARY);
            showPage(TopBar.Page.LIBRARY);
        });

        themeLibrary.setStatusHandler(statusBar::showMessage);
        themeLibrary.addSelectionListener(themeDetail::setSelection);
        themeLibrary.addThemesChangedListener(() -> {
            songLibrary.reloadCurrent();
            slideEditor.reloadThemes();
            bibleStyle = null;
        });

        deckLibrary.setStatusHandler(statusBar::showMessage);
        deckLibrary.addSelectionListener(deck -> slideEditor.open(deck == null ? null : deck.getId()));
        deckLibrary.setBeforeDelete(slideEditor::discard);
        deckLibrary.setAddToServiceHandler(deck ->
                schedule.addItem(new ServiceItem(ServiceItem.Type.SLIDE, deck.getName(), deck.getId())));
        slideEditor.setStatusHandler(statusBar::showMessage);
        slideEditor.setSummaryListener(deckLibrary::updateSummary);
        slideEditor.setThemesChangedHandler(() -> {
            if (themesLoaded) {
                themeLibrary.refresh(null);
            }
        });
        slideEditor.setCurrentSlideListener(slide -> setPreview(slide, slideEditor.baseStyle(), slideEditor.deckName()));

        deckLibrary.setImportPowerPointHandler(() -> {
            sidebar.selectCategory(Sidebar.Category.PRESENTATIONS);
            presentationLibrary.importPowerPoint();
        });
        presentationLibrary.setStatusHandler(statusBar::showMessage);
        presentationLibrary.setAddToServiceHandler(schedule::addItem);
        presentationLibrary.addSelectionListener(this::openPresentation);
        presentationLibrary.setImportListener(new PresentationLibraryPanel.ImportListener() {
            @Override
            public void importStarted(String name, int slideCount, String renderer, Long refreshingId) {
                // REFRESH of the open presentation: reopen it on the slide the operator is on
                boolean same = refreshingId != null && currentPresentation != null
                        && refreshingId.equals(currentPresentation.id()) && stripSource == StripSource.PRESENTATION;
                refreshedPresentationId = same ? refreshingId : null;
                refreshedSlideIndex = same ? thumbnails.getSelectedIndex() : -1;
                // Show the slides as they arrive, unless the operator is working with something else
                boolean show = activeCategory == Sidebar.Category.PRESENTATIONS;
                importInStrip = show;
                if (!show) {
                    return;
                }
                currentPresentation = null;
                presentationSlides = new ArrayList<>();
                presentationInfo = new ArrayList<>();
                stripSource = StripSource.PRESENTATION;
                centerCards.show(centerHolder, CENTER_PRESENTATION);
                presentationDetail.showImporting(name, slideCount, renderer);
                suppressAutoTake = true;
                thumbnails.setTitle("Importing " + name);
                thumbnails.setEmptyMessage("Rendering slides…");
                thumbnails.setSlides(List.of(), SlideStyle.fallback());
                suppressAutoTake = false;
            }

            @Override
            public void importSlide(PresentationService.PresentationSlide s) {
                if (!importInStrip || stripSource != StripSource.PRESENTATION) {
                    return;
                }
                Slide slide = presentationSlide(s, null, presentationSlides.size()); // no library id until the import ends
                presentationSlides.add(slide);
                presentationInfo.add(s);
                thumbnails.appendSlides(List.of(slide));
                presentationDetail.appendSlide(slide);
            }

            @Override
            public void importEnded(Presentation p, boolean refreshed) {
                importInStrip = false;
                outputsWaitForRefresh = refreshed && p != null ? p.id() : null;
                // the library reloads and selects the new presentation, which opens it
            }
        });

        biblePanel.setStatusHandler(statusBar::showMessage);
        biblePanel.setActions(new BiblePanel.Actions() {
            @Override
            public void preview() {
                previewBible();
            }

            @Override
            public void live() {
                liveBible();
            }

            @Override
            public void addToService(ServiceItem item) {
                schedule.addItem(item);
            }

            @Override
            public void manageVersions() {
                manageBibleVersions();
            }
        });
        biblePanel.setPassageListener(this::onPassage);

        for (MediaLibraryPanel lib : List.of(mediaLibrary, videoLibrary, imageLibrary, audioLibrary, backgroundLibrary)) {
            lib.setStatusHandler(statusBar::showMessage);
            lib.setAddToServiceHandler(schedule::addItem);
            lib.addSelectionListener(mediaDetail::show);
            lib.addSelectionListener(m -> {
                if (m != null && showingSceneItem() && !isSceneLibraryItem(m.getId()) && isMedia(activeCategory)) {
                    leaveItemWorkspace(); // other media chosen in the Library: it replaces the scene in the workspace
                }
            });
        }
        videoTabs.setSwitchListener(lib -> {
            if (activeCategory == Sidebar.Category.VIDEOS) {
                lib.refresh(null);
                mediaDetail.show(lib.selected()); // this tab's own selection, as when changing category
            }
        });

        lowerThirds.setStatusHandler(statusBar::showMessage);
        lowerThirds.setAddToServiceHandler(schedule::addItem);
        lowerThirds.setSelectionListener(lowerThirdEditor::edit);
        lowerThirdEditor.setSavedListener(lowerThirds::updated);

        scenesPanel.setStatusHandler(statusBar::showMessage);
        scenesPanel.setScenesChanged(list -> {
            scenes = list;
            scenesDock.setScenes(list);
            if (scenesHome != null) scenesHome.setScenes(list);
            Scene shown = sceneWorkspace.scene();
            if (shown != null) { // the workspace shows the saved version (or nothing, when the scene was deleted)
                sceneWorkspace.setScene(list.stream().filter(s -> Objects.equals(s.getId(), shown.getId())).findFirst().orElse(null));
            }
            // SAVE only updates the scene library: Preview and Program change only at TAKE
        });
        scenesPanel.setPreviewChosen(this::sendSceneToPreview);
        scenesPanel.setSceneSaved(saved -> {
            // the saved scene waits as the selected scene: nothing shows until the operator presses TAKE
            selectedScene = saved;
            liveDock.setPreviewReady(true);
            statusBar.showMessage("Scene \"" + saved.getName() + "\" saved. Press TAKE to show it.");
        });

        thumbnails.addSelectionListener(index -> {
            Slide slide = thumbnails.getSelectedSlide();
            switch (stripSource) {
                case SONG -> {
                    songDetail.showSlide(slide, songStyle);
                    Song song = songLibrary.currentSong();
                    stripToPreview(slide, songStyle, song == null ? null : song.getTitle());
                }
                case BIBLE -> {
                    bibleDetail.showSlide(slide, bibleStyle);
                    stripToPreview(slide, bibleStyle, biblePassage == null ? "Bible" : biblePassage.referenceWithTranslation());
                }
                case ITEM -> {
                    if (showingSceneItem()) {
                        return; // a scene's card only shows the scene; double-click or Send to Preview sends it
                    }
                    itemDetail.showSlide(slide, itemStyle);
                    stripToPreview(slide, itemStyle, itemTitle);
                }
                case PRESENTATION -> {
                    // a click only selects (white ring, like a scene card). Separated: double-click sends it to
                    // Preview, TAKE to Program. Otherwise TAKE puts the selected slide on both.
                    presentationDetail.setSelectedIndex(index);
                    presentationPicked = slide != null && !suppressAutoTake;
                    if (showingSceneItem()) {
                        // a scene's PowerPoint slide clicked: the workspace shows it in the scene (Preview / Program wait for TAKE)
                        sceneWorkspace.setContent(slide == null ? null
                                : LiveContent.snapshot(slide, SlideStyle.fallback(), presentationTitle()));
                    }
                    if (slide != null) {
                        preloadPresentationSlide(index);
                        preloadPresentationSlide(index + 1);
                    }
                    if (slide != null && !suppressAutoTake && liveDock.isAutoTake()
                            && !(slide == takenSlide && program.content() == takenContent)) {
                        previewSelectedPresentationSlide();
                        take(false);
                    }
                    return;
                }
                default -> {
                    // DECK: the slide editor already updated Preview
                }
            }
            // the live slide clicked again after a typography change goes live again with the new look
            boolean alreadyLive = slide != null && slide == takenSlide && program.content() == takenContent
                    && Objects.equals(takenContent.style(), previewStyle);
            boolean lyricsGoLive = clickGoesLive() && !alreadyLive;
            if ((liveDock.isAutoTake() || lyricsGoLive) && index >= 0 && !suppressAutoTake) {
                take(false);
            }
        });
        thumbnails.addActivationListener(index -> {
            if (stripSource == StripSource.ITEM && showingSceneItem()) {
                Scene shown = sceneWorkspace.scene();
                if (shown != null) {
                    sceneClicked(shown); // double-click on a scene's card: Send to Preview
                }
                return;
            }
            if (stripSource == StripSource.PRESENTATION) {
                if (!separatePresentation()) {
                    return; // TAKE puts the selected slide on Preview and Program
                }
                previewSelectedPresentationSlide(); // double-click: Preview
                if (doubleClickTakes()) {
                    take();
                } else if (liveDock.isAutoTake()) {
                    take(false);
                }
                return;
            }
            if (doubleClickTakes()) {
                take();
            }
        });
        // presentations: selecting a slide and putting it in Preview are separate (lyrics: one and the same)
        thumbnails.setSelectionIsPreview(() -> stripSource != StripSource.PRESENTATION && !showingSceneItem());
        thumbnails.addSlidesChangedListener(this::refreshLiveMarker);
        thumbnails.addSlidesChangedListener(this::updateClickHint);
        remoteManager.setDesk(new RemoteDeskImpl());

        program.addListener(this::refreshLiveMarker);
        program.addListener(this::followProgramScene);
        program.addListener(() -> { scenesDock.sync(); if (scenesHome != null) scenesHome.sync(); updatePreviewNote(); });
        previewState.addListener(() -> { scenesDock.sync(); if (scenesHome != null) scenesHome.sync(); });
        // scene VIDEO sources: Program plays them with sound; Preview silently unless MONITOR is on
        // only when the scene itself changes: a lyrics / slide TAKE (and every frame of its transition) leaves
        // the scene's video players, their position and their sound completely alone
        program.addListener(() -> {
            if (program.scene() != syncedProgramScene) {
                syncedProgramScene = program.scene();
                hub.sceneVideos().syncProgram(syncedProgramScene);
                slideshows.syncProgram(syncedProgramScene); // IMAGE SLIDESHOW sources: Program's own position
                // Program's cameras stay open (even hidden PiPs), and only its camera sources with AUDIO on are heard
                cameras.keepOpen("program", cameraDevices(syncedProgramScene));
                hub.cameraAudio().syncProgram(syncedProgramScene);
            }
        });
        previewState.addListener(() -> {
            hub.sceneVideos().syncPreview(previewState.scene());
            if (previewState.scene() != syncedPreviewScene) {
                syncedPreviewScene = previewState.scene();
                slideshows.syncPreview(syncedPreviewScene); // Preview steps its slideshows without moving Program
                // Preview's cameras are opened now, so TAKE never waits for a camera to start
                cameras.keepOpen("preview", cameraDevices(syncedPreviewScene));
            }
        });
        program.setCameraSwitchFade(cameraSettings.switchFadeMs());
        previewState.setCameraSwitchFade(cameraSettings.switchFadeMs());
        program.addListener(this::updateLiveIndicators);
        // a slideshow moved in Preview (NEXT / PREVIOUS / Auto Play): TAKE becomes available
        slideshows.addListener(() -> liveDock.setPreviewReady(previewSlide != null || previewVideoPath != null
                || pendingScene != null || slideshows.takePending()));
        // PREV / NEXT under the monitors when the scene there has an image slideshow
        slideshows.addListener(() -> {
            liveDock.showImageNav(false, slideshows.summary(false));
            liveDock.showImageNav(true, slideshows.summary(true));
        });
        liveDock.setImageStep((onProgram, delta) -> {
            var step = delta < 0 ? com.jamc.worship.output.Slideshows.Control.PREVIOUS : com.jamc.worship.output.Slideshows.Control.NEXT;
            if (onProgram) {
                slideshows.controlProgram(step); // the operator chose to step the live image directly
            } else {
                slideshows.controlPreview(step); // Program follows at TAKE
            }
        });
        program.addListener(this::syncProgramVideo);
        program.addListener(() -> globalBackground.blackChanged(program.isBlack()));
        // Lyrics off applies to Preview too (and to any display showing Preview)
        program.addListener(() -> {
            if (previewState.isLyricsHidden() != program.isLyricsHidden()) {
                previewState.setLyricsHidden(program.isLyricsHidden());
            }
        });
        backgroundPanel.setStatusHandler(statusBar::showMessage);
        backgroundPanel.setBrowseHandler(this::showBackgroundsLibrary);
        // "BG: Loop.mp4" in the footer (not the top bar: a long name pushed the navigation off screen)
        statusBar.setBackgroundClick(this::showBackgroundsLibrary);
        globalBackground.addListener(this::updateBackgroundIndicator);
        updateBackgroundIndicator();
        output.addListener(this::updateLiveIndicators);
        output.setNotifier(message -> {
            statusBar.showMessage(message);
            log.warn(message);
        });
        displays.startMonitoring();
        updateLiveIndicators();
    }

    private void showCategory(Sidebar.Category category) {
        activeCategory = category;
        // a Library category replaces a service item (Blank, scene, …) shown in the workspace
        currentTextItem = null;
        sceneLibraryRef = null;
        boolean decks = category == Sidebar.Category.SLIDES;
        if (stripSource == StripSource.DECK && !decks) {
            slideEditor.detach();
            stripSource = StripSource.NONE;
        }
        if (!isMedia(category)) {
            mediaDetail.stopPreview();
        }
        // the whole workspace beside the monitors switches to this category (Preview / Program are not touched)
        workspaces.select(category);
        switch (category) {
            case SONGS, FAVORITES -> {
                songLibrary.setFavoritesOnly(category == Sidebar.Category.FAVORITES);
                libraryCards.show(libraryHolder, Sidebar.Category.SONGS.name());
                centerCards.show(centerHolder, CENTER_SONG);
                restoreStrip(this::showSongSlides); // back on Songs: the strip returns, Preview is left alone
            }
            case BIBLE -> {
                libraryCards.show(libraryHolder, Sidebar.Category.BIBLE.name());
                centerCards.show(centerHolder, CENTER_BIBLE);
                biblePanel.activate();
                showBibleSlides();
            }
            case MEDIA, VIDEOS, IMAGES, AUDIO -> {
                libraryCards.show(libraryHolder, category.name());
                MediaLibraryPanel lib = activeMediaLibrary();
                lib.refresh(null);
                mediaDetail.show(lib.selected()); // this library's own selection, never the previous category's
            }
            case CAMERA -> {
                libraryCards.show(libraryHolder, Sidebar.Category.CAMERA.name());
                cameraWorkspace.activate();
            }
            case THEMES -> {
                libraryCards.show(libraryHolder, Sidebar.Category.THEMES.name());
                if (!themesLoaded) {
                    themesLoaded = true;
                    themeLibrary.refresh(null);
                }
            }
            case SLIDES -> {
                libraryCards.show(libraryHolder, Sidebar.Category.SLIDES.name());
                centerCards.show(centerHolder, CENTER_DECK);
                if (stripSource != StripSource.DECK) {
                    stripSource = StripSource.DECK;
                    slideEditor.attach();
                }
                if (!decksLoaded) {
                    decksLoaded = true;
                    deckLibrary.refresh(null);
                }
            }
            case PRESENTATIONS -> {
                libraryCards.show(libraryHolder, Sidebar.Category.PRESENTATIONS.name());
                centerCards.show(centerHolder, CENTER_PRESENTATION);
                if (!importInStrip) {
                    restoreStrip(this::showPresentationSlides);
                }
                if (!presentationsLoaded) {
                    presentationsLoaded = true;
                    presentationLibrary.refresh(null);
                }
            }
            case LOWER_THIRDS -> {
                libraryCards.show(libraryHolder, Sidebar.Category.LOWER_THIRDS.name());
                lowerThirds.refresh(null);
            }
        }
    }

    private MediaLibraryPanel activeMediaLibrary() {
        return switch (activeCategory == null ? Sidebar.Category.MEDIA : activeCategory) {
            case VIDEOS -> videoTabs.active();
            case IMAGES -> imageLibrary;
            case AUDIO -> audioLibrary;
            default -> mediaLibrary;
        };
    }

    /** Library → Videos → Backgrounds, where the global background is chosen and controlled. */
    private void showBackgroundsLibrary() {
        topBar.selectPage(TopBar.Page.LIBRARY);
        showPage(TopBar.Page.LIBRARY);
        videoTabs.showBackgrounds();
        sidebar.selectCategory(Sidebar.Category.VIDEOS);
    }

    private static boolean isMedia(Sidebar.Category c) {
        return c == Sidebar.Category.MEDIA || c == Sidebar.Category.VIDEOS || c == Sidebar.Category.IMAGES
                || c == Sidebar.Category.AUDIO;
    }

    // ------------------------------------------------------------------ presenting content

    private void onSongLoaded(SongLibraryPanel.LoadedSong loaded) {
        songDetail.setSong(loaded == null ? null : loaded.song());
        songDetail.setThemeName(loaded == null ? null : loaded.themeName());
        songSlides = loaded == null ? List.of() : SlideGenerator.fromSong(loaded.song());
        songStyle = loaded == null ? null : loaded.style();
        if (stripSource == StripSource.SONG) {
            showSongSlides();
        } else if (stripSource == StripSource.ITEM && loaded != null && !isSceneLibraryItem(loaded.song().getId())
                && (activeCategory == Sidebar.Category.SONGS || activeCategory == Sidebar.Category.FAVORITES)) {
            leaveItemWorkspace(); // a song picked while a service item (Blank, …) was showing: the lyrics return
        }
    }

    /**
     * Stops showing a service item's own slides (Blank, announcement, sermon) and shows the library's active
     * category again. Preview and Program are left as they are.
     */
    private void leaveItemWorkspace() {
        if (stripSource != StripSource.ITEM && !showingSceneItem()) {
            return;
        }
        currentTextItem = null;
        stripSource = StripSource.NONE;
        Sidebar.Category category = activeCategory != null ? activeCategory : sidebar.selectedCategory();
        showCategory(category != null ? category : Sidebar.Category.SONGS);
    }

    /** A global typography profile changed in Settings: restyle what is showing; Program waits for TAKE. */
    private void typographyChanged() {
        bibleStyle = null; // recomputed (with the new Bible typography) the next time verses are prepared
        Song song = songLibrary.currentSong();
        if (song != null) {
            BackgroundTask.run("Apply lyrics typography", () -> context.themeService().styleForSong(song), style -> {
                Song current = songLibrary.currentSong();
                if (current != null && Objects.equals(current.getId(), song.getId())) {
                    restyleSong(style);
                }
            }, err -> log.warn("Could not apply the lyrics typography", err));
        }
        if (stripSource == StripSource.BIBLE && biblePassage != null) {
            onPassage(biblePassage); // fit and splitting depend on the font
        }
        if (stripSource == StripSource.ITEM && currentTextItem != null && currentTextItem.getType() != ServiceItem.Type.SCENE) {
            ServiceItem item = currentTextItem;
            BackgroundTask.run("Apply typography", () -> context.themeService().styleFor(ContentKind.ANNOUNCEMENTS,
                    item.getType() == ServiceItem.Type.BLANK ? context.themeService().defaultTheme()
                            : themeNamed(item.getType() == ServiceItem.Type.SERMON ? "Sermon" : "Announcement")), style -> {
                if (stripSource != StripSource.ITEM || currentTextItem != item) {
                    return;
                }
                itemStyle = style;
                thumbnails.setStyle(style);
                Slide selected = thumbnails.getSelectedSlide();
                itemDetail.showSlide(selected, style);
                if (previewSlide != null && previewSlide == selected) {
                    setPreview(selected, style, itemTitle);
                }
            }, err -> log.warn("Could not apply the typography", err));
        }
        slideEditor.reloadThemes();
    }

    private void restyleSong(SlideStyle style) {
        songStyle = style;
        if (stripSource == StripSource.SONG) {
            thumbnails.setStyle(style);
            Slide selected = thumbnails.getSelectedSlide();
            songDetail.showSlide(selected, style);
            if (previewSlide != null && previewSlide == selected) {
                Song song = songLibrary.currentSong();
                setPreview(selected, style, song == null ? previewTitle : song.getTitle());
            }
        }
    }

    private void showSongSlides() {
        stripSource = StripSource.SONG;
        Song titled = songLibrary.currentSong();
        thumbnails.setTitle(titled == null ? "Lyrics" : "Lyrics – " + titled.getTitle());
        thumbnails.setEmptyMessage("Select lyrics to see them here.");
        suppressAutoTake = true; // opening a song never puts a slide live by itself; clicking one does
        try {
            thumbnails.setSlides(songSlides, songStyle);
        } finally {
            suppressAutoTake = false;
        }
        updateClickHint();
    }

    /** True while a workspace refills the slide strip on a category switch: Preview keeps what it has. */
    private boolean restoringStrip;
    /** The next Bible passage load only restores the Bible workspace (returning to Library → Bible). */
    private boolean restoringBible;

    /** A strip slide was selected: it goes to Preview, unless the strip is only being restored. */
    private void stripToPreview(Slide slide, SlideStyle style, String title) {
        if (restoringStrip) {
            return;
        }
        // the lyrics Preview already shows (a second event for one click, or a click on the PREVIEW card):
        // no second transition of the same words
        LiveContent shown = previewState.content();
        if (slide != null && slide == previewSlide && shown != null && shown.slide() == slide
                && Objects.equals(style, previewStyle) && Objects.equals(title, previewTitle) && previewVideoPath == null) {
            return;
        }
        setPreview(slide, style, title);
    }

    /** Refills the strip for the category the operator switched to, without touching Preview or Program. */
    private void restoreStrip(Runnable fill) {
        boolean was = restoringStrip;
        restoringStrip = true;
        try {
            fill.run();
        } finally {
            restoringStrip = was;
        }
    }

    private void onPassage(BiblePassage p) {
        boolean picked = biblePanel.takePicked();
        if (picked && p != null && p.equals(liveBiblePassage) && stripSource == StripSource.BIBLE
                && program.content() == takenContent && program.content() != null) {
            return; // the verse already on Program, clicked again: no second transition of the same words
        }
        biblePassage = p;
        if (p == null) {
            bibleDetail.setPassage(null, 0);
            if (stripSource == StripSource.BIBLE) {
                thumbnails.setSlides(List.of(), bibleStyle);
            }
            return;
        }
        BibleDisplay shown = bibleDisplay();
        // a chapter opened from a remote is one card per verse, like lyrics cards: no verses packed onto one slide
        BibleDisplay display = p.equals(remoteBibleChapter)
                ? new BibleDisplay(shown.referencePosition(), shown.showVersion(), shown.uppercaseReference(), false) : shown;
        BackgroundTask.run("Prepare Bible slides", () -> {
            SlideStyle style = resolveBibleStyle();
            List<Slide> slides = context.bibleService().slides(p, display, style,
                    (heading, body, caption, s) -> Rendering.renderer().measure(heading, body, caption, s).fontSize());
            return new Object[]{slides, style};
        }, r -> {
            if (biblePassage != p) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Slide> slides = (List<Slide>) r[0];
            bibleStyle = (SlideStyle) r[1];
            bibleDetail.setPassage(p, slides.size());
            stripSource = StripSource.BIBLE;
            thumbnails.setTitle(p.referenceWithTranslation());
            thumbnails.setEmptyMessage("Select verses to see them here.");
            if (remoteBibleVerse != null && p.equals(remoteBibleChapter)) {
                int verse = remoteBibleVerse;
                remoteBibleVerse = null;
                restoringBible = false;
                suppressAutoTake = true; // the tap below decides, once the verse is in Preview
                try {
                    thumbnails.setSlides(slides, bibleStyle, cardOfVerse(slides, verse));
                } finally {
                    suppressAutoTake = false;
                }
                remoteBibleTapped();
            } else if (restoringBible) {
                restoringBible = false;
                restoreStrip(() -> thumbnails.setSlides(slides, bibleStyle)); // back on Bible: Preview stays as it is
            } else {
                suppressAutoTake = !picked; // opening a passage never puts a verse live by itself; picking one does
                try {
                    thumbnails.setSlides(slides, bibleStyle);
                } finally {
                    suppressAutoTake = false;
                }
                if (picked && clickGoesLive()) {
                    liveBiblePassage = p;
                }
            }
            if (pendingBibleLive) {
                pendingBibleLive = false;
                take();
            }
        }, err -> {
            pendingBibleLive = false;
            Dialogs.showError(this, "Could not prepare the verses.", err);
        });
    }

    /** The chapter a remote opened, shown one card per verse. */
    private BiblePassage remoteBibleChapter;
    /** A presentation slide was clicked and nothing has gone to Preview since: TAKE sends that slide. */
    private boolean presentationPicked;
    /** The verses a pick last put on Program. */
    private BiblePassage liveBiblePassage;
    /** The verse tapped on the remote, waiting for that chapter's cards. */
    private Integer remoteBibleVerse;

    /** A remote's tap on a Bible card follows the same setting as a click here. */
    private boolean remoteBibleGoesLive() {
        return !context.config().getBoolean(SettingsPanel.KEY_SEPARATE_BIBLE, false) || liveDock.isAutoTake();
    }

    /** The tapped verse is in Preview: on to Program too, unless it is already there. */
    private void remoteBibleTapped() {
        Slide slide = thumbnails.getSelectedSlide();
        boolean alreadyLive = slide != null && slide == takenSlide && program.content() == takenContent;
        if (remoteBibleGoesLive() && !alreadyLive) {
            take(false);
        }
    }

    private static int cardOfVerse(List<Slide> slides, int verse) {
        for (int i = 0; i < slides.size(); i++) {
            String label = slides.get(i).getLabel();
            if (label != null && label.endsWith(":" + verse)) {
                return i;
            }
        }
        return Math.clamp(verse - 1L, 0, Math.max(0, slides.size() - 1));
    }

    /** Reference position, version label and auto-fit, from Settings. */
    private BibleDisplay bibleDisplay() {
        AppConfig c = context.config();
        return new BibleDisplay(BibleDisplay.ReferencePosition.parse(c.get(SettingsPanel.KEY_BIBLE_REF_POSITION, null)),
                c.getBoolean(SettingsPanel.KEY_BIBLE_SHOW_VERSION, true), c.getBoolean(SettingsPanel.KEY_BIBLE_UPPERCASE, true),
                c.getBoolean(SettingsPanel.KEY_BIBLE_AUTOFIT, true));
    }

    /** Bible "Preview": show the selected verses' first slide in Preview. */
    private void previewBible() {
        stripSource = StripSource.BIBLE;
        centerCards.show(centerHolder, CENTER_BIBLE);
        if (!thumbnails.getSlides().isEmpty()) {
            suppressAutoTake = true; // this button is Preview only, whatever a click does
            try {
                thumbnails.setSelectedIndex(-1);
                thumbnails.setSelectedIndex(0);
            } finally {
                suppressAutoTake = false;
            }
        }
        statusBar.showMessage("Verse in Preview. Press TAKE (Space) to send it live.");
    }

    /** Bible "Live": send the selected verses straight to Program (once their slides are ready). */
    private void liveBible() {
        BiblePassage p = biblePanel.selectedPassage();
        if (p == null) {
            return;
        }
        if (biblePassage != null && biblePassage.equals(p) && stripSource == StripSource.BIBLE && !thumbnails.getSlides().isEmpty()) {
            previewBible();
            take();
        } else {
            pendingBibleLive = true;
            onPassage(p);
        }
    }

    private void manageBibleVersions() {
        if (BibleVersionsDialog.show(this, context.bibleService(), context.config())) {
            biblePanel.versionsChanged();
        }
    }

    private boolean pendingBibleLive;

    private void showBibleSlides() {
        stripSource = StripSource.BIBLE;
        restoringBible = biblePanel.selectedPassage() != null;
        onPassage(biblePanel.selectedPassage());
    }

    /** Bible theme: the one chosen in Settings, else a theme named "Bible", else the default. Blocking. */
    private SlideStyle resolveBibleStyle() {
        if (bibleStyle != null) {
            return bibleStyle;
        }
        String id = context.config().get(SettingsPanel.KEY_BIBLE_THEME, null);
        if (id != null) {
            try {
                return context.themeService().styleFor(ContentKind.BIBLE, context.themeService().resolve(Long.parseLong(id)));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return context.themeService().styleFor(ContentKind.BIBLE, themeNamed("Bible"));
    }

    /** The theme with this name, else the default theme. Blocking. */
    private Theme themeNamed(String name) {
        return context.themeService().list().stream().filter(t -> t.getName().equalsIgnoreCase(name)).findFirst()
                .orElseGet(() -> context.themeService().defaultTheme());
    }

    /**
     * Shows a service item: the Library page opens (its tab active at the top) on the item's category, and its
     * lyrics / slides go to the workspace cards, ready for Preview and TAKE. The same from any page, e.g. the
     * service list under the Scenes.
     */
    private void presentItem(ServiceItem item) {
        if (item.getType() == ServiceItem.Type.SCENE) {
            presentSceneItem(item);
            return;
        }
        topBar.selectPage(TopBar.Page.LIBRARY);
        showPage(TopBar.Page.LIBRARY);
        switch (item.getType()) {
            case ANNOUNCEMENT, SERMON, BLANK -> { }
            // library content after a Blank / text item: its category may already be selected (no event), so leave first
            default -> leaveItemWorkspace();
        }
        switch (item.getType()) {
            case SONG -> {
                if (item.getRefId() != null) {
                    sidebar.selectCategory(Sidebar.Category.SONGS);
                    songLibrary.showSong(item.getRefId());
                }
            }
            case SLIDE -> {
                if (item.getRefId() != null) {
                    sidebar.selectCategory(Sidebar.Category.SLIDES);
                    slideEditor.open(item.getRefId());
                    deckLibrary.refresh(item.getRefId());
                }
            }
            case BIBLE -> {
                BiblePassage p = Json.readOrNull(item.getPayload(), BiblePassage.class);
                if (p != null) {
                    sidebar.selectCategory(Sidebar.Category.BIBLE);
                    biblePanel.goTo(p);
                }
            }
            case IMAGE, VIDEO, AUDIO -> {
                if (item.getRefId() != null) {
                    selectMediaInLibrary(item.getType(), item.getRefId());
                }
            }
            case LOWER_THIRD -> {
                if (item.getRefId() != null) {
                    sidebar.selectCategory(Sidebar.Category.LOWER_THIRDS);
                    lowerThirds.select(item.getRefId());
                }
            }
            case ANNOUNCEMENT, SERMON, BLANK -> presentTextItem(item);
            case PRESENTATION -> {
                if (item.getRefId() != null) {
                    boolean first = !presentationsLoaded;
                    presentationsLoaded = true;
                    sidebar.selectCategory(Sidebar.Category.PRESENTATIONS);
                    if (first || currentPresentation == null || !Objects.equals(currentPresentation.id(), item.getRefId())) {
                        presentationLibrary.select(item.getRefId());
                    }
                }
            }
            case SCENE -> presentSceneItem(item); // handled at the top already; listed so every type has a case
        }
    }

    /** A service media item: its own library (Images, Videos or Audio) opens with the file selected. */
    private void selectMediaInLibrary(ServiceItem.Type type, long id) {
        switch (type) {
            case IMAGE -> {
                sidebar.selectCategory(Sidebar.Category.IMAGES);
                imageLibrary.select(id);
            }
            case VIDEO -> {
                boolean background = context.mediaService().load(id)
                        .map(m -> m.getCategory() == MediaCategory.BACKGROUND).orElse(false);
                sidebar.selectCategory(Sidebar.Category.VIDEOS);
                if (background) {
                    videoTabs.selectBackground(id);
                } else {
                    videoTabs.selectVideo(id);
                }
            }
            default -> {
                sidebar.selectCategory(Sidebar.Category.AUDIO);
                audioLibrary.select(id);
            }
        }
    }

    // ------------------------------------------------------------------ presentations

    /** Loads a presentation's cached slides into the strip and the grid (selected in the library or the schedule). */
    private void openPresentation(Presentation p) {
        if (presentationLibrary.isImporting() && importInStrip) {
            return; // the import is filling the strip; the finished presentation is opened afterwards
        }
        if (p == null) {
            currentPresentation = null;
            presentationSlides = List.of();
            presentationInfo = List.of();
            presentationDetail.setPresentation(null, List.of(), null, true);
            if (stripSource == StripSource.PRESENTATION) {
                thumbnails.setTitle("Presentation");
                thumbnails.setEmptyMessage("Select a presentation to see its slides here.");
                thumbnails.setSlides(List.of(), SlideStyle.fallback());
            }
            return;
        }
        PresentationService service = context.presentationService();
        BackgroundTask.run("Open presentation", () -> new Object[]{service.slides(p), service.sourceState(p), service.isCached(p)}, r -> {
            if (presentationLibrary.selected() != null && !Objects.equals(presentationLibrary.selected().id(), p.id())) {
                return; // another presentation was chosen meanwhile
            }
            @SuppressWarnings("unchecked")
            List<PresentationService.PresentationSlide> info = (List<PresentationService.PresentationSlide>) r[0];
            List<Slide> slides = new ArrayList<>();
            libraryPresentations.invalidate(); // the library changed (opened after an import, refresh or rename)
            for (PresentationService.PresentationSlide s : info) {
                slides.add(presentationSlide(s, p.id(), slides.size()));
            }
            currentPresentation = p;
            presentationInfo = info;
            presentationSlides = slides;
            if (Objects.equals(p.id(), outputsWaitForRefresh)) {
                outputsWaitForRefresh = null;
                showRefreshedSlides(p, slides);
            }
            presentationDetail.setPresentation(p, slides, (PresentationService.SourceState) r[1], (Boolean) r[2]);
            if (activeCategory == Sidebar.Category.PRESENTATIONS && isSceneLibraryItem(p.id())) {
                showScenePresentationSlides(); // the scene's own presentation: its slides beside the scene view
                return;
            }
            if (activeCategory == Sidebar.Category.PRESENTATIONS && showingSceneItem()) {
                leaveItemWorkspace(); // another presentation chosen in the Library: it replaces the scene
            } else if (activeCategory == Sidebar.Category.PRESENTATIONS) {
                centerCards.show(centerHolder, CENTER_PRESENTATION);
                showPresentationSlides();
            }
        }, err -> Dialogs.showError(this, "Could not open the presentation.", err));
    }

    /** Puts the open presentation in the bottom strip, keeping the LIVE marker if one of its slides is live. */
    private void showPresentationSlides() {
        if (stripSource == StripSource.DECK) {
            slideEditor.detach();
        }
        stripSource = StripSource.PRESENTATION;
        String key = currentPresentation == null ? null : "presentation:" + currentPresentation.id() + ":" + currentPresentation.folder();
        if (key != null && key.equals(thumbnails.getSourceKey())) {
            return; // already showing it; keep the operator's position
        }
        thumbnails.setTitle(currentPresentation == null ? "Slides" : currentPresentation.name());
        thumbnails.setEmptyMessage("Select a presentation to see its slides here.");
        int start = key != null && key.equals(takenKey) && takenIndex >= 0 ? takenIndex : 0;
        if (currentPresentation != null && Objects.equals(currentPresentation.id(), refreshedPresentationId)
                && refreshedSlideIndex >= 0) {
            start = Math.min(refreshedSlideIndex, Math.max(0, presentationSlides.size() - 1)); // stay after REFRESH
            refreshedPresentationId = null;
            refreshedSlideIndex = -1;
        }
        suppressAutoTake = true; // opening a presentation never sends a slide live by itself
        try {
            thumbnails.setSlides(presentationSlides, SlideStyle.fallback(), start);
        } finally {
            suppressAutoTake = false;
        }
        thumbnails.setSourceKey(key);
        // the slide of this presentation waiting in Preview keeps its PREVIEW mark when the presentation is reopened
        Slide waiting = previewSlide;
        thumbnails.setPreviewIndex(waiting != null && currentPresentation != null
                && Objects.equals(waiting.getPresentationId(), currentPresentation.id()) ? waiting.getPresentationIndex() : -1);
        refreshLiveMarker();
    }

    /** A cached slide picture as a presentable slide: the full image as the background, no text. */
    /**
     * REFRESH of a presentation: a slide of it on Preview or Program shows its new version at once, so what was changed
     * in the file is seen everywhere. A slide that no longer exists gives the last one.
     */
    private void showRefreshedSlides(Presentation p, List<Slide> slides) {
        if (slides.isEmpty()) {
            return;
        }
        LiveContent inPreview = previewState.content();
        if (isSlideOf(inPreview, p)) {
            Slide fresh = slides.get(Math.min(inPreview.slide().getPresentationIndex(), slides.size() - 1));
            previewSlide = fresh;
            previewState.updateContent(LiveContent.snapshot(fresh, inPreview.style(), inPreview.title()));
        }
        LiveContent onProgram = program.content();
        if (isSlideOf(onProgram, p)) {
            int index = Math.min(onProgram.slide().getPresentationIndex(), slides.size() - 1);
            Slide fresh = slides.get(index);
            LiveContent content = LiveContent.snapshot(fresh, onProgram.style(), onProgram.title());
            takenContent = content;
            takenSlide = fresh;
            takenSlides = slides;
            takenIndex = index;
            program.updateContent(content);
        }
    }

    private static boolean isSlideOf(LiveContent content, Presentation p) {
        return content != null && content.slide() != null && Objects.equals(content.slide().getPresentationId(), p.id());
    }

    private static Slide presentationSlide(PresentationService.PresentationSlide s, Long presentationId, int index) {
        StyleOverrides o = new StyleOverrides();
        o.backgroundType = BackgroundType.IMAGE;
        o.backgroundValue = s.image().toString();
        o.overlayOpacity = 0.0;
        Slide slide = new Slide(s.label(), "", null);
        slide.setOverrides(o);
        slide.setThumbnail(s.thumbnail().toString());
        slide.setCoversBackground(true); // a PowerPoint slide is full-screen: the global video keeps running under it
        slide.setPresentation(presentationId, index); // a scene's Presentation source shows it in its own box instead
        return slide;
    }

    private String presentationTitle() {
        return currentPresentation != null ? currentPresentation.name() : "Presentation";
    }

    /** Decodes a slide's full image in the background so taking it live is instant. */
    private void preloadPresentationSlide(int index) {
        List<Slide> slides = thumbnails.getSlides();
        if (index >= 0 && index < slides.size() && slides.get(index).getOverrides() != null) {
            Rendering.renderer().images().get(slides.get(index).getOverrides().backgroundValue);
        }
    }

    // ------------------------------------------------------------------ action / order list

    /** Lyrics, Bible verses and text items show their words on the cards; PowerPoint and custom slides show pages. */
    private void applyStripMode() {
        switch (stripSource) {
            case SONG, BIBLE, ITEM -> {
                if (showingSceneItem()) {
                    thumbnails.setCardMode(false, "card"); // a scene's card is a picture of its layout, not words
                } else {
                    thumbnails.setCardMode(true, "card");
                }
            }
            case PRESENTATION -> thumbnails.setCardMode(false, "page");
            default -> thumbnails.setCardMode(false, "slide");
        }
    }

    private javax.swing.JDialog orderDialog;

    /**
     * The order list (songs: their arrangement) opens on demand from the strip's ORDER button; it is no
     * longer a second permanent list beside the horizontal lyrics cards.
     */
    /**
     * CLEAR beside the lyrics cards and in the Order dialog: the same switch as CLEAR under Program
     * (hides the lyrics, keeps the background and video); red while the lyrics are cleared.
     */
    private javax.swing.JToggleButton clearToggle() {
        javax.swing.JToggleButton b = new javax.swing.JToggleButton("Clear");
        b.setToolTipText("Clear: hide the lyrics on the screen until pressed again (L)");
        b.setFocusable(false);
        b.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE,
                "font: bold -1; margin: 3,9,3,9; selectedBackground: #EF4444; selectedForeground: #FFFFFF");
        b.setSelected(program.isLyricsHidden());
        b.addActionListener(e -> program.setLyricsHidden(b.isSelected()));
        program.addListener(() -> b.setSelected(program.isLyricsHidden()));
        return b;
    }

    private void showOrderEditor() {
        if (orderDialog == null) {
            orderDialog = new javax.swing.JDialog(this, "Order", false);
            orderDialog.setContentPane(region(actionOrder));
            orderDialog.setSize(460, 620);
        }
        orderDialog.setTitle("Order – " + thumbnails.getTitle());
        orderDialog.setLocationRelativeTo(thumbnails);
        orderDialog.setVisible(true);
    }

    /** Settings: clicking a song lyrics slide sends it live at once (no Preview step). On by default. */
    private boolean songClickGoesLive() {
        return context.config().getBoolean(SettingsPanel.KEY_SONG_CLICK_LIVE, true);
    }

    /**
     * Lyrics and Bible go to Preview and Program together on a click, unless Settings → Live & Scenes separates them
     * (Preview first, TAKE sends it to Program). A presentation slide is only selected by a click; see {@link #take()}.
     */
    private boolean clickGoesLive() {
        return switch (stripSource) {
            case SONG -> songClickGoesLive();
            case BIBLE -> !context.config().getBoolean(SettingsPanel.KEY_SEPARATE_BIBLE, false);
            default -> false;
        };
    }

    private boolean separatePresentation() {
        return context.config().getBoolean(SettingsPanel.KEY_SEPARATE_PRESENTATION, false);
    }

    /**
     * What remotes see and do. Commands go through the operator's own paths (take, sendSceneToPreview, previewCamera,
     * the slide strip...), so a remote's action appears in the real Preview and Program. The Scene Editor's draft is
     * never touched: scenes come from the saved list.
     */
    private final class RemoteDeskImpl implements com.jamc.worship.remote.RemoteDesk {
        private static final int REMOTE_LIST_LIMIT = 200;
        /** Large enough for the big display on a phone: the same picture serves the row and the display. */
        private static final int CARD_WIDTH = 640;
        private static final int CARD_HEIGHT = 360;
        private static final int LARGE_SLIDE_WIDTH = 960;
        private static final int CAMERA_PICTURE_WIDTH = 320;
        private static final int PICTURE_WIDTH = 320;
        private static final int PICTURE_HEIGHT = 180;
        private com.jamc.worship.output.OutputCanvas previewCanvas;
        private com.jamc.worship.output.OutputCanvas programCanvas;

        @Override
        public Map<String, Object> snapshot() {
            Map<String, Object> s = new HashMap<>();
            s.put("monitors", monitors());
            s.put("lyrics", stripSource == StripSource.SONG && songLibrary.currentSong() != null
                    ? cards(songLibrary.currentSong().getTitle(), null) : null);
            s.put("bible", stripSource == StripSource.BIBLE && biblePassage != null
                    ? cards(biblePassage.reference(), biblePassage.translation()) : null);
            s.put("presentation", presentation());
            s.put("service", service());
            s.put("scenes", scenes());
            s.put("cameras", cameraList());
            s.put("media", media());
            s.put("projector", Map.of("black", output.isProjectorBlack(), "open", output.isOpen()));
            s.put("stream", session(streamManager.live()));
            s.put("recording", session(streamManager.recording()));
            return s;
        }

        private Map<String, Object> monitors() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("preview", monitor(previewState));
            m.put("program", monitor(program));
            m.put("canTake", canTake());
            return m;
        }

        private boolean canTake() {
            return previewSlide != null || previewVideoPath != null || pendingScene != null || selectedScene != null;
        }

        private Map<String, Object> monitor(ProgramOutput state) {
            LiveContent c = state.content();
            Scene scene = state.scene();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", c == null ? (scene == null ? "EMPTY" : "SCENE") : c.isVideo() ? "VIDEO" : "CONTENT");
            m.put("title", c == null ? null : c.title());
            m.put("label", c == null || c.slide() == null ? null : c.slide().getLabel());
            m.put("scene", scene == null ? null : scene.getName());
            m.put("cameras", scene == null ? List.of() : scene.getSources().stream()
                    .filter(src -> src.isVisible() && src.getType() == com.jamc.worship.model.SourceType.CAMERA)
                    .map(src -> cameraName(src.getConfig().camera)).distinct().toList());
            m.put("black", state.isBlack());
            return m;
        }

        /** The strip's cards, for what the operator has open: a song or a Bible passage. */
        private Map<String, Object> cards(String title, String translation) {
            List<Map<String, Object>> cards = new ArrayList<>();
            int preview = -1;
            List<Slide> slides = thumbnails.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                Slide slide = slides.get(i);
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("label", slide.getLabel());
                card.put("text", slide.getText());
                cards.add(card);
                if (slide == previewSlide) {
                    preview = i;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("revision", Objects.hash(title, cards));
            m.put("title", title);
            m.put("translation", translation);
            if (translation != null && biblePassage != null) {
                m.put("book", biblePassage.book());
                m.put("chapter", biblePassage.chapter());
            }
            m.put("cards", cards);
            m.put("previewIndex", preview);
            m.put("liveIndex", thumbnails.getLiveIndex());
            m.put("clickGoesLive", clickGoesLive() || liveDock.isAutoTake());
            return m;
        }

        private Map<String, Object> presentation() {
            if (stripSource != StripSource.PRESENTATION || currentPresentation == null) {
                return null;
            }
            List<Map<String, Object>> slides = new ArrayList<>();
            int preview = -1;
            List<Slide> strip = thumbnails.getSlides();
            for (int i = 0; i < strip.size(); i++) {
                Slide slide = strip.get(i);
                slides.add(Map.of("number", slide.getPresentationIndex() + 1, "label", slide.getLabel() == null ? "" : slide.getLabel()));
                if (slide == previewSlide) {
                    preview = i;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", currentPresentation.id());
            m.put("title", presentationTitle());
            m.put("index", thumbnails.getSelectedIndex());
            m.put("total", strip.size());
            m.put("slides", slides);
            m.put("previewIndex", preview);
            m.put("liveIndex", thumbnails.getLiveIndex());
            m.put("mode", !separatePresentation() ? "LIVE_CONTROL" : "PREVIEW_FIRST");
            return m;
        }

        private Map<String, Object> service() {
            Service plan = schedule.current();
            if (plan == null) {
                return null;
            }
            List<Map<String, Object>> items = new ArrayList<>();
            int current = schedule.selectedIndex();
            List<ServiceItem> all = plan.getItems();
            for (int i = 0; i < all.size(); i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", all.get(i).getTitle());
                item.put("type", all.get(i).getType().name());
                item.put("status", i == current ? "CURRENT" : i < current ? "DONE" : "UPCOMING");
                items.add(item);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", plan.getName());
            m.put("items", items);
            return m;
        }

        private List<Map<String, Object>> scenes() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Scene scene : MainFrame.this.scenes == null ? List.<Scene>of() : MainFrame.this.scenes) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", scene.getId());
                m.put("name", scene.getName());
                m.put("summary", scene.getSources().stream().filter(com.jamc.worship.model.Source::isVisible)
                        .map(src -> src.getType().name()).distinct().toList());
                m.put("preview", previewState.scene() != null && Objects.equals(previewState.scene().getId(), scene.getId()));
                m.put("live", program.scene() != null && Objects.equals(program.scene().getId(), scene.getId()));
                list.add(m);
            }
            return list;
        }

        private String cameraName(String device) {
            return cameraNames == null || device == null ? (device == null ? "Camera" : device) : cameraNames.full(device);
        }

        private List<Map<String, Object>> cameraList() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (String device : cameras.cameras()) {
                com.jamc.worship.camera.CameraStatus status = cameras.status(device);
                boolean offline = status == com.jamc.worship.camera.CameraStatus.DISCONNECTED
                        || status == com.jamc.worship.camera.CameraStatus.ERROR;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", device);
                m.put("name", cameraName(device));
                m.put("status", offline ? "OFFLINE" : uses(program.scene(), device) ? "LIVE" : uses(previewState.scene(), device) ? "PREVIEW" : "READY");
                list.add(m);
            }
            return list;
        }

        private boolean uses(Scene scene, String device) {
            return scene != null && scene.getSources().stream().anyMatch(src -> src.isVisible()
                    && src.getType() == com.jamc.worship.model.SourceType.CAMERA && device.equals(src.getConfig().camera));
        }

        private com.jamc.worship.media.Transport mediaTransport() {
            if (programMediaTransport != null) {
                return programMediaTransport;
            }
            return hub.available() && hub.audio().hasMedia() ? hub.audio() : null;
        }

        private Map<String, Object> media() {
            com.jamc.worship.media.Transport t = mediaTransport();
            if (t == null) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", programMediaTransport != null ? programMediaTitle : hub.audio().title());
            m.put("kind", programMediaTransport != null ? "VIDEO" : "AUDIO");
            m.put("playing", t.isPlaying());
            m.put("seconds", t.time() / 1000);
            m.put("lengthSeconds", t.length() / 1000);
            m.put("muted", t.isMuted());
            m.put("volume", t.volume());
            m.put("loop", t.isLoop());
            return m;
        }

        /** Stream or recording status. Never keys or addresses. */
        private Map<String, Object> session(com.jamc.worship.stream.StreamManager.Session session) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", session == null ? "IDLE" : session.state().name());
            boolean active = session != null && session.active();
            m.put("seconds", active && session.startedMillis() > 0 ? (System.currentTimeMillis() - session.startedMillis()) / 1000 : 0);
            m.put("kbps", active && session.stats() != null ? Math.round(session.stats().bitrateKbps()) : null);
            m.put("fps", active && session.stats() != null ? Math.round(session.stats().fps()) : null);
            m.put("file", active && session.file() != null ? session.file().getFileName().toString() : null);
            return m;
        }

        @Override
        public java.awt.image.BufferedImage[] monitorPictures() {
            if (previewCanvas == null) {
                previewCanvas = new com.jamc.worship.output.OutputCanvas(previewState, false, true);
                programCanvas = new com.jamc.worship.output.OutputCanvas(program);
                previewCanvas.setSize(PICTURE_WIDTH, PICTURE_HEIGHT);
                programCanvas.setSize(PICTURE_WIDTH, PICTURE_HEIGHT);
            }
            return new java.awt.image.BufferedImage[]{picture(previewCanvas), picture(programCanvas)};
        }

        private java.awt.image.BufferedImage picture(com.jamc.worship.output.OutputCanvas canvas) {
            var image = new java.awt.image.BufferedImage(PICTURE_WIDTH, PICTURE_HEIGHT, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = image.createGraphics();
            try {
                canvas.paint(g);
            } finally {
                g.dispose();
            }
            return image;
        }

        /** The card the device was looking at must still be there, so words from an older list never reach the screen. */
        private boolean cardStillThere(com.fasterxml.jackson.databind.JsonNode payload, String title, String translation) {
            int card = payload.path("card").asInt(-1);
            return payload.path("revision").asInt() == (int) cards(title, translation).get("revision")
                    && card >= 0 && card < thumbnails.getSlides().size();
        }

        /**
         * Empties Preview and Program; the scene and background stay. Nothing stays selected in the strip, so a tap
         * on the same card shows it again. A video on Program only goes with {@code video}: that takes Media.
         */
        private String clearPreviewAndProgram(boolean video) {
            LiveContent live = program.content();
            boolean clearLive = live != null && (video || !live.isVideo());
            if (!clearLive && previewSlide == null && previewVideoPath == null) {
                return "nothing_to_clear";
            }
            if (stripSource != StripSource.DECK) {
                suppressAutoTake = true;
                try {
                    thumbnails.setSelectedIndex(-1);
                } finally {
                    suppressAutoTake = false;
                }
                thumbnails.setPreviewIndex(-1);
            }
            setPreview(null, null, null);
            if (clearLive) {
                program.clear();
            }
            return null;
        }

        private String presentationTo(int index) {
            if (stripSource != StripSource.PRESENTATION || thumbnails.getSlides().isEmpty()) {
                return "no_presentation";
            }
            int before = thumbnails.getSelectedIndex();
            thumbnails.setSelectedIndex(Math.clamp(index, 0, thumbnails.getSlides().size() - 1));
            previewSelectedPresentationSlide();
            if (thumbnails.getSelectedIndex() != before && !separatePresentation()) {
                take(false); // LIVE CONTROL, as on the desktop: content only, Program's scene stays
            }
            return null;
        }

        /**
         * Opens something from the library as the operator's own service list does. The desktop follows it to that
         * category, because it has one slide strip; opening never goes live by itself.
         */
        private String open(ServiceItem item, boolean mayGoLive) {
            if (currentPage == TopBar.Page.SCENE) {
                return "desktop_editing"; // opening leaves the page, and the Scene Editor may be open there
            }
            if (liveDock.isAutoTake() && !mayGoLive) {
                return "auto_take_on"; // what opens is loaded later, and Auto-take would send it live
            }
            presentItem(item);
            if (item.getRefId() != null && (item.getType() == ServiceItem.Type.IMAGE || item.getType() == ServiceItem.Type.VIDEO
                    || item.getType() == ServiceItem.Type.AUDIO)) {
                context.mediaService().load(item.getRefId()).ifPresent(MainFrame.this::previewMedia);
            }
            return null;
        }

        @Override
        public Object query(com.jamc.worship.remote.RemoteQuery query, com.fasterxml.jackson.databind.JsonNode params) throws Exception {
            String search = params.path("search").asText("");
            List<Map<String, Object>> list = new ArrayList<>();
            switch (query) {
                case SONGS -> {
                    for (Song song : context.songService().search(new com.jamc.worship.model.SongQuery(search, false,
                            com.jamc.worship.model.SongQuery.Sort.TITLE_ASC))) {
                        list.add(Map.of("id", song.getId(), "title", song.getTitle()));
                        if (list.size() >= REMOTE_LIST_LIMIT) {
                            break;
                        }
                    }
                }
                case BIBLE_VERSIONS -> {
                    var bible = context.bibleService();
                    bible.ensureInstalled();
                    for (var v : bible.versions()) {
                        if (v.enabled() && bible.isReady(v)) {
                            list.add(Map.of("code", v.abbreviation(), "name", v.name()));
                        }
                    }
                    List<Map<String, Object>> books = new ArrayList<>();
                    for (var book : com.jamc.worship.bible.BibleBooks.all()) {
                        books.add(Map.of("number", book.number(), "name", book.name(),
                                "chapters", com.jamc.worship.bible.BibleBooks.chapterCount(book.number())));
                    }
                    return Map.of("versions", list, "books", books);
                }
                case BIBLE_VERSES -> {
                    var bible = context.bibleService();
                    var version = bible.version(params.path("version").asText("")).orElse(null);
                    if (version == null) {
                        throw new SecurityException("stale");
                    }
                    for (var verse : bible.verses(version, params.path("book").asInt(), params.path("chapter").asInt())) {
                        list.add(Map.of("verse", verse.verse(), "text", verse.text()));
                    }
                }
                case PRESENTATIONS -> {
                    for (var p : context.presentationService().list(search)) {
                        list.add(Map.of("id", p.id(), "title", p.name(), "slides", p.slideCount()));
                        if (list.size() >= REMOTE_LIST_LIMIT) {
                            break;
                        }
                    }
                }
                case PRESENTATION_THUMBNAIL -> {
                    var presentation = context.presentationService().find(params.path("id").asLong(-1)).orElse(null);
                    int number = params.path("number").asInt(-1);
                    if (presentation == null || number < 1) {
                        throw new SecurityException("stale");
                    }
                    if (params.path("large").asBoolean(false)) {
                        return largeSlidePicture(presentation, number);
                    }
                    java.nio.file.Path file = context.presentationService().thumbnail(presentation, number);
                    if (!java.nio.file.Files.isRegularFile(file)) {
                        return Map.of("src", "");
                    }
                    return Map.of("src", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(file)));
                }
                case MEDIA_ITEMS -> {
                    for (var item : context.mediaService().find(List.of(com.jamc.worship.model.MediaCategory.VIDEO,
                            com.jamc.worship.model.MediaCategory.AUDIO, com.jamc.worship.model.MediaCategory.IMAGE), search, false)) {
                        list.add(Map.of("id", item.getId(), "title", item.getName(), "kind", item.getKind().name()));
                        if (list.size() >= REMOTE_LIST_LIMIT) {
                            break;
                        }
                    }
                }
                case SERVICES -> {
                    for (Service plan : context.servicePlans().services(null)) {
                        list.add(Map.of("id", plan.getId(), "title", plan.getName()));
                    }
                }
                case LYRICS_THUMBNAIL -> {
                    return cardPicture(StripSource.SONG, params);
                }
                case BIBLE_THUMBNAIL -> {
                    return cardPicture(StripSource.BIBLE, params);
                }
                case CAMERA_PICTURE -> {
                    return cameraPicture(params.path("id").asText(""));
                }
                case SERVICE_SONGS -> {
                    return savedInService(Set.of(ServiceItem.Type.SONG));
                }
                case SERVICE_PRESENTATIONS -> {
                    return savedInService(Set.of(ServiceItem.Type.PRESENTATION));
                }
                case SERVICE_MEDIA -> {
                    return savedInService(Set.of(ServiceItem.Type.VIDEO, ServiceItem.Type.AUDIO, ServiceItem.Type.IMAGE));
                }
                case SERVICE_BIBLE -> {
                    return savedBibleInService();
                }
            }
            return list;
        }

        /** A card as the desktop draws it. The strip is read on the UI thread, which owns it; the drawing is not done there. */
        private Map<String, Object> cardPicture(StripSource kind, com.fasterxml.jackson.databind.JsonNode params) throws Exception {
            Slide[] slide = new Slide[1];
            SlideStyle[] style = new SlideStyle[1];
            SwingUtilities.invokeAndWait(() -> {
                boolean song = kind == StripSource.SONG;
                boolean open = stripSource == kind && (song ? songLibrary.currentSong() != null : biblePassage != null);
                int card = params.path("card").asInt(-1);
                if (open && card >= 0 && card < thumbnails.getSlides().size()) {
                    Object revision = song ? cards(songLibrary.currentSong().getTitle(), null).get("revision")
                            : cards(biblePassage.reference(), biblePassage.translation()).get("revision");
                    if (Objects.equals(revision, params.path("revision").asInt())) {
                        slide[0] = thumbnails.getSlides().get(card);
                        style[0] = song ? songStyle : bibleStyle;
                    }
                }
            });
            if (slide[0] == null) {
                throw new SecurityException("stale");
            }
            return jpeg(Rendering.renderer().render(slide[0], style[0], CARD_WIDTH, CARD_HEIGHT), CARD_WIDTH, CARD_HEIGHT);
        }

        /** A slide sharp enough for the large display on a phone, far smaller than its full picture. */
        private Map<String, Object> largeSlidePicture(com.jamc.worship.model.Presentation presentation, int number) throws Exception {
            for (PresentationService.PresentationSlide s : context.presentationService().slides(presentation)) {
                if (s.number() == number && java.nio.file.Files.isRegularFile(s.image())) {
                    java.awt.image.BufferedImage full = javax.imageio.ImageIO.read(s.image().toFile());
                    if (full != null) {
                        return jpeg(full, LARGE_SLIDE_WIDTH, Math.max(1, full.getHeight() * LARGE_SLIDE_WIDTH / Math.max(1, full.getWidth())));
                    }
                }
            }
            return Map.of("src", "");
        }

        /**
         * A connected camera's latest picture for the remote's multiview; empty while it starts. Asking keeps the camera
         * open, and it closes by itself once nobody asks. The frame is read on the UI thread, which owns the cameras.
         */
        private Map<String, Object> cameraPicture(String device) throws Exception {
            java.awt.image.BufferedImage[] frame = new java.awt.image.BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> {
                if (cameras.cameras().contains(device)) {
                    frame[0] = cameras.frame(device);
                }
            });
            if (frame[0] == null) {
                return Map.of("src", "");
            }
            return jpeg(frame[0], CAMERA_PICTURE_WIDTH, Math.max(1, frame[0].getHeight() * CAMERA_PICTURE_WIDTH / Math.max(1, frame[0].getWidth())));
        }

        private Map<String, Object> jpeg(java.awt.image.BufferedImage image, int width, int height) throws java.io.IOException {
            // JPEG has no transparency: without this copy the writer quietly writes nothing
            var flat = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = flat.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, width, height, null);
            g.dispose();
            var out = new java.io.ByteArrayOutputStream(32_000);
            javax.imageio.ImageIO.write(flat, "jpg", out);
            return Map.of("src", "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(out.toByteArray()));
        }

        /** The open service's name and a copy of its items, read on the UI thread, which owns them. */
        private Object[] openService() throws Exception {
            Object[] found = new Object[2];
            SwingUtilities.invokeAndWait(() -> {
                Service plan = schedule.current();
                found[0] = plan == null ? "" : plan.getName();
                found[1] = plan == null ? List.<ServiceItem>of() : new ArrayList<>(plan.getItems());
            });
            return found;
        }

        /** Items of these kinds saved in the open service, in its order. An item used twice is listed once. */
        private Map<String, Object> savedInService(Set<ServiceItem.Type> types) throws Exception {
            Object[] open = openService();
            @SuppressWarnings("unchecked")
            List<ServiceItem> items = (List<ServiceItem>) open[1];
            Set<Long> seen = new java.util.HashSet<>();
            List<Map<String, Object>> list = new ArrayList<>();
            for (ServiceItem item : items) {
                if (types.contains(item.getType()) && item.getRefId() != null && seen.add(item.getRefId())) {
                    list.add(Map.of("id", item.getRefId(), "title", item.getTitle() == null ? "" : item.getTitle(), "kind", item.getType().name()));
                }
            }
            return Map.of("service", open[0], "items", list);
        }

        /** Passages saved in the open service, with what opening one as a chapter of cards needs. */
        private Map<String, Object> savedBibleInService() throws Exception {
            Object[] open = openService();
            @SuppressWarnings("unchecked")
            List<ServiceItem> items = (List<ServiceItem>) open[1];
            var bible = context.bibleService();
            List<Map<String, Object>> list = new ArrayList<>();
            for (ServiceItem item : items) {
                BiblePassage p = item.getType() == ServiceItem.Type.BIBLE ? Json.readOrNull(item.getPayload(), BiblePassage.class) : null;
                var version = p == null ? null : bible.version(p.translation()).orElse(null);
                if (version == null) {
                    continue;
                }
                List<? extends com.jamc.worship.bible.BibleProvider.Verse> verses;
                try {
                    verses = bible.verses(version, p.book(), p.chapter());
                } catch (java.io.IOException e) {
                    continue; // an online version that cannot be reached right now
                }
                if (!verses.isEmpty()) {
                    list.add(Map.of("id", list.size(), "title", p.referenceWithTranslation(), "version", p.translation(), "book", p.book(),
                            "chapter", p.chapter(), "verse", p.fromVerse(), "lastVerse", verses.getLast().verse()));
                }
            }
            return Map.of("service", open[0], "items", list);
        }

        @Override
        public String execute(com.jamc.worship.remote.RemoteCommand command, com.fasterxml.jackson.databind.JsonNode payload,
                              Set<com.jamc.worship.remote.RemotePermission> permissions) {
            boolean mayGoLive = permissions.contains(com.jamc.worship.remote.RemotePermission.TAKE);
            switch (command) {
                case TAKE_PREVIEW -> {
                    if (!canTake()) {
                        return "nothing_in_preview";
                    }
                    take();
                }
                case SET_SCENE_PREVIEW -> {
                    long id = payload.path("id").asLong(-1);
                    Scene scene = (MainFrame.this.scenes == null ? List.<Scene>of() : MainFrame.this.scenes).stream()
                            .filter(s -> s.getId() != null && s.getId() == id).findFirst().orElse(null);
                    if (scene == null) {
                        return "scene_unavailable";
                    }
                    sendSceneToPreview(scene);
                }
                case SET_CAMERA_PREVIEW -> {
                    String device = payload.path("id").asText("");
                    if (!cameras.cameras().contains(device)) {
                        return "camera_unavailable";
                    }
                    if (MainFrame.this.scenes == null || MainFrame.this.scenes.stream().noneMatch(s -> s.has(com.jamc.worship.model.SourceType.CAMERA))) {
                        return "no_camera_scene";
                    }
                    previewCamera(device, com.jamc.worship.model.FitMode.FILL, false, false);
                }
                case PRESENTATION_FIRST -> {
                    return presentationTo(0);
                }
                case PRESENTATION_PREVIOUS -> {
                    return presentationTo(thumbnails.getSelectedIndex() - 1);
                }
                case PRESENTATION_NEXT -> {
                    return presentationTo(thumbnails.getSelectedIndex() + 1);
                }
                case PRESENTATION_LAST -> {
                    return presentationTo(thumbnails.getSlides().size() - 1);
                }
                case PRESENTATION_SELECT -> {
                    return presentationTo(payload.path("index").asInt(thumbnails.getSelectedIndex()));
                }
                case LYRICS_PREVIOUS, LYRICS_NEXT -> {
                    if (stripSource != StripSource.SONG || songLibrary.currentSong() == null) {
                        return "no_song";
                    }
                    // lyrics do what a click does on the desktop: with "click = live", Preview and Program together
                    if (command == com.jamc.worship.remote.RemoteCommand.LYRICS_NEXT) {
                        thumbnails.selectNext();
                    } else {
                        thumbnails.selectPrevious();
                    }
                }
                case LYRICS_CLEAR, BIBLE_CLEAR, PRESENTATION_CLEAR -> {
                    return clearPreviewAndProgram(false);
                }
                case MEDIA_CLEAR -> {
                    return clearPreviewAndProgram(true);
                }
                case LYRICS_SELECT -> {
                    if (stripSource != StripSource.SONG || songLibrary.currentSong() == null) {
                        return "no_song";
                    }
                    if (!cardStillThere(payload, songLibrary.currentSong().getTitle(), null)) {
                        return "stale";
                    }
                    thumbnails.select(payload.path("card").asInt());
                }
                case BIBLE_PREVIOUS, BIBLE_NEXT -> {
                    if (stripSource != StripSource.BIBLE) {
                        return "no_passage";
                    }
                    suppressAutoTake = true;
                    try {
                        if (command == com.jamc.worship.remote.RemoteCommand.BIBLE_NEXT) {
                            thumbnails.selectNext();
                        } else {
                            thumbnails.selectPrevious();
                        }
                    } finally {
                        suppressAutoTake = false;
                    }
                    remoteBibleTapped();
                }
                case BIBLE_SELECT -> {
                    if (stripSource != StripSource.BIBLE || biblePassage == null) {
                        return "no_passage";
                    }
                    if (!cardStillThere(payload, biblePassage.reference(), biblePassage.translation())) {
                        return "stale";
                    }
                    suppressAutoTake = true;
                    try {
                        thumbnails.select(payload.path("card").asInt());
                    } finally {
                        suppressAutoTake = false;
                    }
                    remoteBibleTapped();
                }
                case OPEN_SONG -> {
                    long id = payload.path("id").asLong(-1);
                    return open(new ServiceItem(ServiceItem.Type.SONG, payload.path("title").asText(""), id), mayGoLive);
                }
                case OPEN_BIBLE -> {
                    int verse = payload.path("verse").asInt(1);
                    BiblePassage chapter = new BiblePassage(payload.path("version").asText(""), payload.path("book").asInt(),
                            payload.path("chapter").asInt(), 1, Math.max(verse, payload.path("lastVerse").asInt(verse)));
                    if (stripSource == StripSource.BIBLE && chapter.equals(biblePassage) && chapter.equals(remoteBibleChapter)
                            && !thumbnails.getSlides().isEmpty()) {
                        // that chapter's cards are already out: only the verse changes
                        suppressAutoTake = true;
                        try {
                            thumbnails.select(cardOfVerse(thumbnails.getSlides(), verse));
                        } finally {
                            suppressAutoTake = false;
                        }
                        remoteBibleTapped();
                        return null;
                    }
                    remoteBibleChapter = chapter;
                    remoteBibleVerse = verse;
                    String problem = open(BiblePanel.serviceItem(chapter), mayGoLive || remoteBibleGoesLive());
                    if (problem != null) {
                        remoteBibleVerse = null;
                    }
                    return problem;
                }
                case OPEN_PRESENTATION -> {
                    long id = payload.path("id").asLong(-1);
                    return open(new ServiceItem(ServiceItem.Type.PRESENTATION, payload.path("title").asText(""), id), mayGoLive);
                }
                case OPEN_MEDIA -> {
                    var item = context.mediaService().load(payload.path("id").asLong(-1)).orElse(null);
                    if (item == null) {
                        return "stale";
                    }
                    ServiceItem.Type type = item.getKind() == MediaKind.VIDEO ? ServiceItem.Type.VIDEO
                            : item.getKind() == MediaKind.AUDIO ? ServiceItem.Type.AUDIO : ServiceItem.Type.IMAGE;
                    return open(new ServiceItem(type, item.getName(), item.getId()), mayGoLive);
                }
                case OPEN_SERVICE -> {
                    Service plan = context.servicePlans().load(payload.path("id").asLong(-1)).orElse(null);
                    if (plan == null) {
                        return "stale";
                    }
                    schedule.open(plan);
                }
                case SET_SERVICE_PREVIEW -> {
                    Service plan = schedule.current();
                    int index = payload.path("index").asInt(-1);
                    if (plan == null || index < 0 || index >= plan.getItems().size()) {
                        return "stale";
                    }
                    String problem = open(plan.getItems().get(index), mayGoLive);
                    if (problem != null) {
                        return problem;
                    }
                    schedule.selectIndex(index);
                }
                case BLACK_PROJECTOR_ON -> output.setProjectorBlack(true);
                case RESTORE_PROJECTOR -> output.setProjectorBlack(false);
                case MEDIA_PLAY, MEDIA_PAUSE, MEDIA_STOP, MEDIA_SEEK, MEDIA_MUTE, MEDIA_SET_VOLUME, MEDIA_SET_LOOP -> {
                    com.jamc.worship.media.Transport t = mediaTransport();
                    if (t == null) {
                        return "no_media";
                    }
                    switch (command) {
                        case MEDIA_PLAY -> t.playOrResume();
                        case MEDIA_PAUSE -> t.pause();
                        case MEDIA_STOP -> t.stop();
                        case MEDIA_SEEK -> t.seek((float) Math.clamp(payload.path("position").asDouble(0), 0, 1));
                        case MEDIA_MUTE -> t.setMuted(payload.path("muted").asBoolean(!t.isMuted()));
                        case MEDIA_SET_VOLUME -> t.setVolume(Math.clamp(payload.path("volume").asInt(t.volume()), 0, 100));
                        case MEDIA_SET_LOOP -> t.setLoop(payload.path("loop").asBoolean(!t.isLoop()));
                        default -> { }
                    }
                }
            }
            return null;
        }
    }

    /** Tells the operator above the lyrics cards what a click does for the content shown. */
    private void updateClickHint() {
        if (stripSource == StripSource.ITEM && showingSceneItem()) {
            thumbnails.setHint("Double-click = Send scene to Preview", false);
            return;
        }
        boolean live = liveDock.isAutoTake() || clickGoesLive();
        if (stripSource == StripSource.PRESENTATION && !live) {
            thumbnails.setHint(separatePresentation() ? "Click = Select · Double-click = Preview · TAKE = Live"
                    : "Click = Select · TAKE = Preview + Program", false);
            return;
        }
        thumbnails.setHint(live ? "Click = LIVE" : "Click = Preview · TAKE = Live", live);
    }

    /**
     * NEXT / PREVIOUS slide. Presentations: Preview moves to that slide and Program follows at once, unless Settings →
     * Live & Scenes separates presentations; then TAKE puts it live.
     * Only the content changes: Program keeps its scene. Lyrics as before.
     */
    private void stepSlide(boolean next) {
        int before = thumbnails.getSelectedIndex();
        boolean presentation = stripSource == StripSource.PRESENTATION;
        suppressAutoTake = presentation; // below, once: the strip's own click rule would send it live twice
        try {
            if (next) {
                thumbnails.selectNext();
            } else {
                thumbnails.selectPrevious();
            }
        } finally {
            suppressAutoTake = false;
        }
        if (presentation && thumbnails.getSelectedIndex() >= 0) {
            boolean moved = thumbnails.getSelectedIndex() != before;
            previewSelectedPresentationSlide();
            if (moved && !separatePresentation()) {
                take(false); // content only: Program's scene (camera, logo...) stays
            }
        }
    }

    /** Settings: a double-click on a slide goes live; by default it only previews and TAKE goes live. */
    private boolean doubleClickTakes() {
        return context.config().getBoolean(SettingsPanel.KEY_DOUBLE_CLICK_LIVE, false);
    }

    /**
     * The Action / Order list edits the order of what the strip shows. Songs keep the new order (their
     * arrangement); for Bible passages, PowerPoint and text items the order applies until they are
     * opened again, and the original files are never changed. Slide decks are reordered in their editor.
     */
    private final class OrderHost implements ActionOrderPanel.Host {
        @Override
        public boolean cardsUseTextCase() {
            var typo = context.themeService().typography();
            return typo != null && typo.actionCardsUseCase();
        }

        @Override
        public void select(int slideIndex) {
            thumbnails.select(slideIndex);
        }

        @Override
        public void take(int slideIndex) {
            thumbnails.setSelectedIndex(slideIndex);
            if (stripSource == StripSource.PRESENTATION) {
                previewSelectedPresentationSlide();
            }
            MainFrame.this.take();
        }

        @Override
        public boolean doubleClickTakes() {
            return MainFrame.this.doubleClickTakes();
        }

        @Override
        public boolean canEditOrder() {
            return stripSource != StripSource.DECK && stripSource != StripSource.NONE && !thumbnails.getSlides().isEmpty()
                    && !(stripSource == StripSource.PRESENTATION && importInStrip);
        }

        @Override
        public void applyOrder(List<List<Slide>> entries) {
            List<Slide> flat = new ArrayList<>();
            entries.forEach(flat::addAll);
            Slide selected = thumbnails.getSelectedSlide();
            int select = Math.max(0, indexOf(flat, selected));
            String key = thumbnails.getSourceKey();
            suppressAutoTake = true;
            try {
                thumbnails.setSlides(flat, thumbnails.getBaseStyle(), select);
            } finally {
                suppressAutoTake = false;
            }
            thumbnails.setSourceKey(key);
            switch (stripSource) {
                case SONG -> {
                    songSlides = flat;
                    Song song = songLibrary.currentSong();
                    if (song != null && song.getId() != null) {
                        List<String> keys = new ArrayList<>();
                        for (List<Slide> e : entries) {
                            String k = e.getFirst().getSectionKey();
                            if (k != null) {
                                keys.add(k);
                            }
                        }
                        boolean written = keys.equals(SlideGenerator.sectionKeys(song));
                        song.setArrangement(written ? null : String.join("|", keys));
                        BackgroundTask.run("Save song order", () -> {
                            context.songService().setArrangement(song.getId(), written ? null : keys);
                            return true;
                        }, ok -> statusBar.showMessage("Saved the order of \"" + song.getTitle() + "\": "
                                + String.join(" · ", keys)), err -> Dialogs.showError(MainFrame.this,
                                "Could not save the song order.", err));
                    }
                }
                case PRESENTATION -> {
                    presentationSlides = flat;
                    presentationDetail.setPresentation(currentPresentation, flat, null, true);
                    presentationDetail.setSelectedIndex(select);
                    statusBar.showMessage("Slide order changed for this showing. The PowerPoint file is not changed.");
                }
                default -> statusBar.showMessage("Order changed.");
            }
            refreshLiveMarker();
        }

        @Override
        public boolean canResetOrder() {
            Song song = songLibrary.currentSong();
            return stripSource == StripSource.SONG && song != null && song.getArrangement() != null;
        }

        @Override
        public void resetOrder() {
            Song song = songLibrary.currentSong();
            if (song == null || song.getId() == null) {
                return;
            }
            song.setArrangement(null);
            BackgroundTask.run("Reset song order", () -> {
                context.songService().setArrangement(song.getId(), null);
                return true;
            }, ok -> statusBar.showMessage("\"" + song.getTitle() + "\" is back in its written order."),
                    err -> Dialogs.showError(MainFrame.this, "Could not reset the song order.", err));
            songSlides = SlideGenerator.fromSong(song);
            suppressAutoTake = true;
            try {
                showSongSlides();
            } finally {
                suppressAutoTake = false;
            }
        }

        private static int indexOf(List<Slide> slides, Slide s) {
            for (int i = 0; i < slides.size(); i++) {
                if (slides.get(i) == s) {
                    return i;
                }
            }
            return -1;
        }
    }

    private void previewSelectedPresentationSlide() {
        if (stripSource != StripSource.PRESENTATION) {
            showPresentationSlides();
        }
        Slide slide = thumbnails.getSelectedSlide();
        if (slide != null) {
            setPreview(slide, SlideStyle.fallback(), presentationTitle());
            thumbnails.setPreviewIndex(thumbnails.getSelectedIndex());
            preloadPresentationSlide(thumbnails.getSelectedIndex());
            // the Preview scene is kept; a Presentation source in it shows the slide in its own box
            Scene scene = previewState.scene();
            com.jamc.worship.model.Source slot = com.jamc.worship.output.Compositor.presentationSlot(scene, previewState.content());
            boolean hidden = scene != null && slot == null;
            statusBar.showMessage(hidden
                    ? "Slide " + (thumbnails.getSelectedIndex() + 1) + " is in Preview, but the \"" + scene.getName()
                            + "\" scene does not show this presentation (its Presentation source is set to a specific file, "
                            + "or it has none)."
                    : "Slide " + (thumbnails.getSelectedIndex() + 1) + " is in Preview"
                            + (slot != null ? " in the \"" + scene.getName() + "\" scene's " + slot.getName() + " source" : "")
                            + ". Press TAKE (Space) to send it live.");
        }
    }

    private void showPresentationNotes() {
        StringBuilder sb = new StringBuilder();
        for (PresentationService.PresentationSlide s : presentationInfo) {
            if (!s.warnings().isEmpty()) {
                sb.append("Slide ").append(s.number()).append(":\n");
                for (String w : s.warnings()) {
                    sb.append("  • ").append(w).append('\n');
                }
            }
        }
        javax.swing.JTextArea text = new javax.swing.JTextArea(sb.isEmpty() ? "No notes." : sb.toString(), 16, 64);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        javax.swing.JOptionPane.showMessageDialog(this, new javax.swing.JScrollPane(text),
                "What may look different from PowerPoint", javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    /** Marks the strip (and presentation grid) slide that is on Program. */
    private void refreshLiveMarker() {
        int idx = -1;
        if (takenContent != null && program.content() == takenContent) {
            List<Slide> slides = thumbnails.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                if (slides.get(i) == takenSlide) { // still the same slide, wherever it moved in the order
                    idx = i;
                    break;
                }
            }
            boolean sameSource = takenKey != null ? takenKey.equals(thumbnails.getSourceKey()) : slides == takenSlides;
            if (idx < 0 && sameSource) {
                idx = takenIndex;
            }
        }
        thumbnails.setLiveIndex(idx);
        presentationDetail.setLiveIndex(stripSource == StripSource.PRESENTATION ? idx : -1);
    }

    private void presentTextItem(ServiceItem item) {
        String themeName = item.getType() == ServiceItem.Type.SERMON ? "Sermon" : "Announcement";
        BackgroundTask.run("Prepare item", () -> context.themeService().styleFor(ContentKind.ANNOUNCEMENTS,
                item.getType() == ServiceItem.Type.BLANK ? context.themeService().defaultTheme() : themeNamed(themeName)), style -> {
            currentTextItem = item;
            if (stripSource == StripSource.DECK) {
                slideEditor.detach();
            }
            List<Slide> slides = new ArrayList<>();
            if (item.getType() == ServiceItem.Type.BLANK) {
                slides.add(new Slide("Blank", "", null));
            } else {
                int n = 1;
                for (String part : splitParagraphs(item.getPayload())) {
                    slides.add(new Slide(item.getTitle() + " " + n++, part, null));
                }
                if (slides.isEmpty()) {
                    slides.add(new Slide(item.getTitle(), item.getTitle(), null));
                }
            }
            itemStyle = style;
            itemTitle = item.getTitle();
            stripSource = StripSource.ITEM;
            topBar.selectPage(TopBar.Page.LIBRARY);
            showPage(TopBar.Page.LIBRARY);
            centerCards.show(centerHolder, CENTER_ITEM);
            workspaces.show(LibraryWorkspaceController.Workspace.SLIDES); // the item's own slides, not the last category's view
            itemDetail.setHeader(item.getTitle(), item.getType().displayName() + "  ·  " + slides.size()
                    + (slides.size() == 1 ? " slide" : " slides") + "  ·  double-click the item in the schedule to edit");
            thumbnails.setTitle(item.getTitle());
            thumbnails.setSlides(slides, style);
        }, err -> Dialogs.showError(this, "Could not show the item.", err));
    }

    /**
     * A scene item (added or clicked in the schedule) shows in the workspace, like a Blank item: a large view of
     * the scene with Send to Preview / Take Scene Live. Preview and Program do not change by themselves.
     */
    private void presentSceneItem(ServiceItem item) {
        Scene scene = scenes.stream().filter(s -> Objects.equals(s.getId(), item.getRefId())).findFirst().orElse(null);
        if (scene == null) {
            statusBar.showMessage("The scene \"" + item.getTitle() + "\" no longer exists.");
            return;
        }
        currentTextItem = item; // the schedule item the workspace shows (removing it returns to the library)
        if (stripSource == StripSource.DECK) {
            slideEditor.detach();
        }
        stripSource = StripSource.ITEM;
        topBar.selectPage(TopBar.Page.LIBRARY);
        showPage(TopBar.Page.LIBRARY);
        sceneWorkspace.setScene(scene);
        centerCards.show(centerHolder, CENTER_SCENE);
        workspaces.show(LibraryWorkspaceController.Workspace.SLIDES);
        thumbnails.setTitle("Scene – " + scene.getName());
        thumbnails.setEmptyMessage("Preparing the scene…");
        boolean wasRestoring = restoringStrip;
        restoringStrip = true; // the strip empties while the scene is prepared; Preview and Program stay
        suppressAutoTake = true;
        try {
            thumbnails.setSlides(List.of(), SlideStyle.fallback());
        } finally {
            suppressAutoTake = false;
            restoringStrip = wasRestoring;
        }
        showSceneContentInLibrary(scene);
        if (sceneLibraryRef != null && activeCategory == Sidebar.Category.PRESENTATIONS) {
            return; // a PowerPoint scene: the strip shows the presentation's slides (now, or once it is opened)
        }
        showSceneCard(item, scene);
        // pictures used by the scene (e.g. presentation slides) can still be loading: draw the card once more
        javax.swing.Timer again = new javax.swing.Timer(1500, e -> showSceneCard(item, scene));
        again.setRepeats(false);
        again.start();
    }

    /**
     * The scene as one card in the slide strip beside the workspace (a picture of its layout). Showing it changes
     * neither Preview nor Program; a double-click on the card sends the scene to Preview.
     */
    private void showSceneCard(ServiceItem item, Scene scene) {
        if (stripSource != StripSource.ITEM || currentTextItem != item) {
            return; // the workspace moved on
        }
        java.awt.image.BufferedImage full = sceneWorkspace.render(scene, 1280, 720);
        java.awt.image.BufferedImage small = sceneWorkspace.render(scene, 320, 180);
        BackgroundTask.run("Prepare scene card", () -> {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "livecat-scenes");
            java.nio.file.Files.createDirectories(dir);
            try (var old = java.nio.file.Files.newDirectoryStream(dir, "scene-" + scene.getId() + "-*.png")) {
                for (java.nio.file.Path p : old) {
                    java.nio.file.Files.deleteIfExists(p); // earlier pictures of this scene
                }
            }
            String stamp = scene.getId() + "-" + System.nanoTime(); // a new name each time: the image cache keys on it
            java.nio.file.Path image = dir.resolve("scene-" + stamp + ".png");
            java.nio.file.Path thumb = dir.resolve("scene-" + stamp + "-t.png");
            javax.imageio.ImageIO.write(full, "png", image.toFile());
            javax.imageio.ImageIO.write(small, "png", thumb.toFile());
            return new java.nio.file.Path[]{image, thumb};
        }, paths -> {
            if (stripSource != StripSource.ITEM || currentTextItem != item) {
                return;
            }
            StyleOverrides o = new StyleOverrides();
            o.backgroundType = BackgroundType.IMAGE;
            o.backgroundValue = paths[0].toString();
            o.overlayOpacity = 0.0;
            Slide card = new Slide(scene.getName(), "", null);
            card.setOverrides(o);
            card.setThumbnail(paths[1].toString());
            card.setCoversBackground(true);
            boolean wasRestoring = restoringStrip;
            restoringStrip = true; // only shown: Preview and Program stay as they are
            suppressAutoTake = true;
            try {
                thumbnails.setSlides(List.of(card), SlideStyle.fallback());
            } finally {
                suppressAutoTake = false;
                restoringStrip = wasRestoring;
            }
        }, err -> log.warn("Could not draw the scene card", err));
    }

    /** The library item (song, presentation, media) that the Library shows for the scene item in the workspace. */
    private Long sceneLibraryRef;

    /**
     * The Library (beside the output) follows a scene item: when the scene has a presentation, lyrics or media
     * layer, that library opens with it selected. Only the Library changes: the workspace keeps the scene, and
     * Preview and Program stay as they are. Choosing something else in the Library leaves the scene.
     */
    private void showSceneContentInLibrary(Scene scene) {
        sceneLibraryRef = null;
        com.jamc.worship.model.Source found = null;
        for (int pass = 0; pass < 3 && found == null; pass++) { // presentation first, then lyrics, then media
            for (com.jamc.worship.model.Source s : scene.getSources()) {
                var c = s.getConfig();
                boolean match = switch (pass) {
                    case 0 -> s.getType() == com.jamc.worship.model.SourceType.PRESENTATION && c.presentationId != null;
                    case 1 -> s.getType() == com.jamc.worship.model.SourceType.LYRICS && c.songId != null;
                    default -> (s.getType() == com.jamc.worship.model.SourceType.IMAGE
                            || s.getType() == com.jamc.worship.model.SourceType.VIDEO
                            || s.getType() == com.jamc.worship.model.SourceType.AUDIO) && c.mediaId != null;
                };
                if (match && s.isVisible()) {
                    found = s;
                    break;
                }
            }
        }
        if (found == null) {
            return; // nothing from a library in this scene: the Library stays as it is
        }
        var c = found.getConfig();
        switch (found.getType()) {
            case PRESENTATION -> {
                sceneLibraryRef = c.presentationId;
                libraryFollowsScene(Sidebar.Category.PRESENTATIONS);
                presentationsLoaded = true;
                if (currentPresentation == null || !Objects.equals(currentPresentation.id(), c.presentationId)) {
                    presentationLibrary.select(c.presentationId); // its slides fill the strip once opened
                } else {
                    showScenePresentationSlides();
                }
            }
            case LYRICS -> {
                sceneLibraryRef = c.songId;
                libraryFollowsScene(Sidebar.Category.SONGS);
                songLibrary.setFavoritesOnly(false);
                songLibrary.showSong(c.songId);
            }
            case IMAGE -> {
                sceneLibraryRef = c.mediaId;
                libraryFollowsScene(Sidebar.Category.IMAGES);
                imageLibrary.select(c.mediaId);
            }
            case AUDIO -> {
                sceneLibraryRef = c.mediaId;
                libraryFollowsScene(Sidebar.Category.AUDIO);
                audioLibrary.select(c.mediaId);
            }
            default -> {
                sceneLibraryRef = c.mediaId;
                libraryFollowsScene(Sidebar.Category.VIDEOS);
                boolean background = context.mediaService().load(c.mediaId)
                        .map(m -> m.getCategory() == MediaCategory.BACKGROUND).orElse(false);
                if (background) {
                    videoTabs.selectBackground(c.mediaId);
                } else {
                    videoTabs.selectVideo(c.mediaId);
                }
            }
        }
    }

    /** Shows this category's library list (and marks it in the sidebar) without switching the workspace. */
    private void libraryFollowsScene(Sidebar.Category category) {
        activeCategory = category; // leaving the scene returns to this library
        sidebar.selectCategoryQuietly(category);
        libraryCards.show(libraryHolder, category.name());
    }

    /** True when something chosen in the Library is the scene's own item (the workspace keeps the scene). */
    private boolean isSceneLibraryItem(Long id) {
        return showingSceneItem() && id != null && Objects.equals(id, sceneLibraryRef);
    }

    /** True while the workspace shows a scene item from the service schedule. */
    private boolean showingSceneItem() {
        return (stripSource == StripSource.ITEM || stripSource == StripSource.PRESENTATION)
                && currentTextItem != null && currentTextItem.getType() == ServiceItem.Type.SCENE;
    }

    /** A scene with a PowerPoint layer: the strip beside the workspace shows that presentation's slides. */
    private void showScenePresentationSlides() {
        showPresentationSlides(); // click = select, TAKE = live, as in Presentations; the scene view stays beside it
        updateClickHint();
    }

    private static List<String> splitParagraphs(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String p : text.replace("\r\n", "\n").split("\\n\\s*\\n")) {
            if (!p.isBlank()) {
                out.add(p.strip());
            }
        }
        return out;
    }

    private void previewMedia(MediaItem item) {
        if (com.jamc.worship.background.BackgroundPresets.isPreset(item)) {
            // a built-in color / gradient as full-screen content (TAKE AS BACKGROUND makes it the global background instead)
            StyleOverrides o = new StyleOverrides();
            boolean gradient = item.getPath().startsWith(GlobalBackgroundManager.GRADIENT_PREFIX);
            o.backgroundType = gradient ? BackgroundType.GRADIENT : BackgroundType.COLOR;
            o.backgroundValue = item.getPath().substring(item.getPath().indexOf(':') + 1);
            o.backgroundMode = com.jamc.worship.model.BackgroundMode.OVERRIDE;
            o.overlayOpacity = 0.0;
            setPreview(new Slide(item.getName(), "", null), o.applyTo(SlideStyle.fallback()), item.getName());
            mediaInPreview(item);
            return;
        }
        String path = context.mediaService().resolve(item).toString();
        if (item.getKind() == MediaKind.IMAGE) {
            StyleOverrides o = new StyleOverrides();
            o.backgroundType = BackgroundType.IMAGE;
            o.backgroundValue = item.getPath();
            o.overlayOpacity = 0.0;
            Slide picture = new Slide(item.getName(), "", null);
            picture.setCoversBackground(true);
            setPreview(picture, o.applyTo(SlideStyle.fallback()), item.getName());
        } else if (item.getKind() == MediaKind.VIDEO) {
            if (item.isYouTube()) {
                // a YouTube video plays through the embedded YouTube player (never VLC); it needs the internet
                var yt = com.jamc.worship.youtube.YouTubeEngine.get();
                var availability = com.jamc.worship.youtube.YouTubeLibrary.availability(item.getYoutubeVideoId());
                if (!yt.isUsable()) {
                    Dialogs.showInfo(this, "YouTube player unavailable", yt.problem());
                    return;
                }
                if (availability == com.jamc.worship.youtube.YouTubeClient.Availability.OFFLINE
                        || availability == com.jamc.worship.youtube.YouTubeClient.Availability.UNAVAILABLE) {
                    Dialogs.showInfo(this, "YouTube video " + availability.label().toLowerCase(java.util.Locale.ROOT),
                            availability == com.jamc.worship.youtube.YouTubeClient.Availability.OFFLINE
                                    ? "\"" + item.getName() + "\" needs the internet, and YouTube cannot be reached right now.\n"
                                    + "Local videos and saved lyrics keep working offline."
                                    : "YouTube no longer plays \"" + item.getName() + "\" (removed, private or not embeddable).");
                    return;
                }
                path = item.getPath();
            } else if (!hub.available()) {
                Dialogs.showInfo(this, "Video unavailable", hub.problem());
                return;
            }
            setPreview(new Slide(item.getName(), "▶  " + item.getName(), "Video"), SlideStyle.fallback(), item.getName());
            previewVideoPath = path;
            previewState.take(LiveContent.video(path, item.getName()));
        }
        mediaInPreview(item);
    }

    /** Videos and images keep Preview + TAKE unless Settings → Live & Scenes has their Separate tick off. */
    private boolean mediaGoesLive(MediaItem item) {
        return item != null && !context.config().getBoolean(item.getKind() == MediaKind.VIDEO
                ? SettingsPanel.KEY_SEPARATE_VIDEOS : SettingsPanel.KEY_SEPARATE_IMAGES, true);
    }

    private void mediaInPreview(MediaItem item) {
        if (mediaGoesLive(item)) {
            take(false); // content only: Program's scene stays
            statusBar.showMessage("\"" + item.getName() + "\" is on Preview and Program.");
        } else {
            statusBar.showMessage("\"" + item.getName() + "\" is in Preview. Press TAKE (Space) to send it live.");
        }
    }

    private void playAudio(MediaItem item) {
        if (!hub.available()) {
            Dialogs.showInfo(this, "Audio unavailable", hub.problem());
            return;
        }
        var audio = hub.audio();
        // this file's loop from last time; it starts with its own sound (100 % as recorded, unmuted)
        String key = com.jamc.worship.media.MediaStates.mediaKey(item.getPath());
        var last = mediaStates.get(key, new com.jamc.worship.media.MediaStates.State(false, 100, false));
        com.jamc.worship.media.MediaStates.apply(audio, new com.jamc.worship.media.MediaStates.State(false, 100, last.loop()));
        audio.play(context.mediaService().resolve(item).toString(), item.getName());
        audioControls.bind(mediaStates.remember(audio, key));
        audioTitle.setText("♪ " + item.getName());
        audioBar.setVisible(true);
        statusBar.showMessage("Playing \"" + item.getName() + "\". Audio keeps playing while slides change.");
    }

    private JComponent buildAudioBar() {
        audioBar.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        audioBar.add(audioTitle, BorderLayout.NORTH);
        audioBar.add(audioControls, BorderLayout.CENTER);
        audioBar.setVisible(false);
        return audioBar;
    }

    private void addCurrentSongToService() {
        Song song = songLibrary.currentSong();
        if (song != null) {
            schedule.addItem(ServiceItem.ofSong(song));
        }
    }

    private void openPlaylist(String playlist) {
        if (syncingPlaylist) {
            return;
        }
        BackgroundTask.run("Open playlist", () -> context.servicePlans().latest(playlist)
                .orElseGet(() -> context.servicePlans().create(playlist)), s -> {
            schedule.open(s);
            statusBar.showMessage("Opened \"" + s.getName() + "\"");
        }, err -> Dialogs.showError(this, "Could not open the playlist.", err));
    }

    // ------------------------------------------------------------------ live

    private void setPreview(Slide slide, SlideStyle style, String title) {
        presentationPicked = false;
        previewSlide = slide;
        previewStyle = style;
        previewTitle = title;
        previewVideoPath = null;
        if (slide != null && style != null) {
            previewState.take(LiveContent.snapshot(slide, style, title));
        } else {
            previewState.clear();
        }
        liveDock.setPreviewReady(slide != null || pendingScene != null);
        updatePreviewNote();
    }

    /** Preview follows Program's scene unless the operator has another scene waiting in Preview. */
    private void followProgramScene() {
        Scene live = program.scene();
        if (separateLayouts()) {
            lastProgramScene = live; // Preview keeps its own layout
            return;
        }
        if (live != lastProgramScene) {
            lastProgramScene = live;
            if (pendingScene == null || sameLayout(live, pendingScene)) {
                pendingScene = null;
                previewState.setScene(live, false);
            }
        }
    }

    /**
     * TAKE: the whole Preview (content and any scene waiting in Preview) becomes Program in one
     * transition. Program, projector, OBS, the LIVE markers and the Action / Order list all follow the
     * single Program state; the global video background is not touched.
     */
    private void take() {
        if (presentationPicked && stripSource == StripSource.PRESENTATION && !separatePresentation()) {
            previewSelectedPresentationSlide(); // the slide just clicked goes to Preview and Program together
        }
        take(true);
    }

    /**
     * Intelligent TAKE. With {@code withScene}, a scene the operator put in Preview goes live together
     * with the content (SCENE TAKE); otherwise only the content changes and Program keeps its scene
     * (CONTENT TAKE): the camera keeps running and only the lyrics layer changes.
     */
    private void take(boolean withScene) {
        if (withScene && selectedScene != null) {
            // a scene card was selected since the last TAKE: it goes live now, with what Preview holds.
            // With separate layouts it becomes the PREVIEW layout only; Program keeps its scene (TAKE SCENE
            // changes that) and only the content below goes to both.
            Scene chosen = selectedScene;
            selectedScene = null;
            sceneClicked(chosen);
        }
        Scene scene = withScene && !separateLayouts() ? pendingScene : null;
        if (scene == null && !separateLayouts() && pendingScene != null && program.scene() != null
                && Objects.equals(program.scene().getId(), pendingScene.getId())) {
            // the saved version of the live scene was sent to Preview: a content TAKE (NEXT slide, lyrics click) brings it too
            scene = pendingScene;
        }
        LiveContent taken = null;
        if (previewVideoPath != null) {
            taken = LiveContent.video(previewVideoPath, previewTitle);
        } else if (previewSlide == null || previewStyle == null) {
            if (scene == null) {
                if (slideshows.takePending()) {
                    // only an image slideshow moved in Preview (NEXT / PREVIOUS): Program goes to that image
                    slideshows.take();
                    statusBar.showMessage("Slideshow image sent to Program.");
                    return;
                }
                statusBar.showMessage("Nothing in Preview. Select a slide first.");
                return;
            }
        } else {
            LiveContent content = LiveContent.snapshot(previewSlide, previewStyle, previewTitle);
            List<Slide> strip = thumbnails.getSlides();
            int index = -1;
            for (int i = 0; i < strip.size(); i++) {
                if (strip.get(i) == previewSlide) {
                    index = i;
                    break;
                }
            }
            takenContent = content;
            takenSlides = strip;
            takenKey = thumbnails.getSourceKey();
            takenIndex = index;
            takenSlide = previewSlide;
            taken = content;
        }
        program.take(taken, scene);
        // IMAGE SLIDESHOWS follow Preview at TAKE (each in its own layer, nothing else is rebuilt)
        slideshows.take();
        if (scene != null) {
            pendingScene = null;
            context.config().set(KEY_LIVE_SCENE, String.valueOf(scene.getId()));
            context.config().save();
            statusBar.showMessage("Scene \"" + scene.getName() + "\" is live");
        }
        liveDock.setPreviewReady(previewSlide != null || previewVideoPath != null || pendingScene != null);
        updatePreviewNote();
        if (!output.isOpen()) {
            statusBar.showMessage("Sent to Program. The projector is off: press F5 or click Projector to show it.");
        }
    }

    private boolean separateLayouts() {
        return context.config().getBoolean(KEY_SEPARATE_LAYOUTS, false);
    }

    private boolean directSceneSwitch() {
        return context.config().getBoolean(SettingsPanel.KEY_SCENE_DIRECT, false);
    }

    /**
     * Library → Camera → SEND TO PREVIEW: the Camera scene, showing this camera with these options, goes to
     * Preview only (TAKE puts it on Program). The saved scene itself is not changed.
     */
    private void previewCamera(String camera, com.jamc.worship.model.FitMode fit, boolean mirror, boolean direct) {
        List<Scene> all = scenes == null ? List.of() : scenes;
        Scene base = all.stream().filter(s -> s.getName().equals("Camera")).findFirst()
                .orElseGet(() -> all.stream().filter(s -> s.has(com.jamc.worship.model.SourceType.CAMERA)).findFirst().orElse(null));
        if (base == null) {
            Dialogs.showInfo(this, "Camera", "There is no scene with a camera. Add a Camera source to a scene first.");
            return;
        }
        Scene s = base.copy();
        for (com.jamc.worship.model.Source src : s.getSources()) {
            if (src.getType() == com.jamc.worship.model.SourceType.CAMERA) {
                src.setVisible(true);
                src.getConfig().camera = camera;
                src.getConfig().fit = fit;
                src.getConfig().mirror = mirror ? Boolean.TRUE : null;
            }
        }
        String name = cameraNames.full(camera);
        if (direct) {
            // Settings → Camera → Direct Camera Switching (advanced, off by default): Program at once, Preview stays
            if (!cameraSettings.directSwitch()) {
                return;
            }
            setLiveScene(s, true);
            updatePreviewNote();
            scenesDock.sync();
            if (scenesHome != null) scenesHome.sync();
            statusBar.showMessage(name + " switched to Program directly.");
            return;
        }
        pendingScene = separateLayouts() ? null : s;
        previewState.setScene(s, false);
        liveDock.setPreviewReady(true);
        updatePreviewNote();
        scenesDock.sync();
        if (scenesHome != null) scenesHome.sync();
        statusBar.showMessage(name + " is in Preview (" + s.getName() + " scene). Press TAKE to put it live.");
    }

    /** Every camera a scene uses, hidden sources included (so hiding a PiP never closes its camera). */
    static List<String> cameraDevices(Scene scene) {
        if (scene == null) {
            return List.of();
        }
        return scene.getSources().stream().filter(s -> s.getType() == com.jamc.worship.model.SourceType.CAMERA)
                .map(s -> s.getConfig().camera == null ? "" : s.getConfig().camera).distinct().toList();
    }

    /** A scene card was clicked: Preview (safe default), or Program at once in Direct Switch mode. */
    private void sceneClicked(Scene scene) {
        selectedScene = null; // the selection was acted on (double-click, PREVIEW, or this TAKE)
        if (directSceneSwitch()) {
            sendSceneToProgram(scene);
            return;
        }
        sendSceneToPreview(scene);
    }

    /** The scene goes to Preview (never Program): from a double-click, PREVIEW, or the scene editor's SEND TO PREVIEW. */
    private void sendSceneToPreview(Scene scene) {
        selectedScene = null;
        if (separateLayouts()) {
            previewState.setScene(scene, false);
            pendingScene = null;
            context.config().set(KEY_PREVIEW_SCENE, String.valueOf(scene.getId()));
            context.config().save();
            Scene live = program.scene();
            statusBar.showMessage("Preview layout: " + scene.getName() + ". Program keeps "
                    + (live == null ? "its layout" : live.getName()) + " (TAKE SCENE changes it).");
        } else {
            Scene live = program.scene();
            if (sameLayout(live, scene)) {
                pendingScene = null;
                previewState.setScene(live, false);
            } else {
                // another scene, or the live scene saved with changes since it was taken: TAKE puts this version live
                pendingScene = scene;
                previewState.setScene(scene, false);
                statusBar.showMessage("Scene \"" + scene.getName() + "\" is in Preview. Press TAKE to put it live.");
            }
        }
        liveDock.setPreviewReady(previewSlide != null || previewVideoPath != null || pendingScene != null);
        updatePreviewNote();
        scenesDock.sync();
        if (scenesHome != null) scenesHome.sync();
    }

    /**
     * True when two scenes are the same scene with the same layers (name, type, visibility and settings of every
     * source). A scene edited and saved after it went live is NOT the same as Program's copy, so sending it to
     * Preview and pressing TAKE puts the new version live. Source ids are ignored (they change on every save).
     */
    static boolean sameLayout(Scene a, Scene b) {
        if (a == null || b == null || !Objects.equals(a.getId(), b.getId())) {
            return false;
        }
        List<com.jamc.worship.model.Source> as = a.getSources();
        List<com.jamc.worship.model.Source> bs = b.getSources();
        if (as.size() != bs.size()) {
            return false;
        }
        for (int i = 0; i < as.size(); i++) {
            com.jamc.worship.model.Source x = as.get(i);
            com.jamc.worship.model.Source y = bs.get(i);
            if (x.getType() != y.getType() || x.isVisible() != y.isVisible() || !Objects.equals(x.getName(), y.getName())
                    || !Objects.equals(com.jamc.worship.util.Json.write(x.getConfig()), com.jamc.worship.util.Json.write(y.getConfig()))) {
                return false;
            }
        }
        return true;
    }

    private void sendSceneToProgram(Scene scene) {
        selectedScene = null;
        setLiveScene(scene, true);
        if (!separateLayouts()) {
            // LIVE changes Program only: Preview keeps what it showed. A different scene there stays the
            // next scene, so TAKE still does what Preview shows.
            Scene inPreview = previewState.scene();
            pendingScene = inPreview == null || sameLayout(scene, inPreview) ? null : inPreview;
        }
        updatePreviewNote();
        scenesDock.sync();
        if (scenesHome != null) scenesHome.sync();
    }

    /** "NEXT: Camera + Lyrics · Scene change" / "NEXT: Amazing Grace – Verse 2 · Content change" on Preview. */
    private void updatePreviewNote() {
        String note = null;
        boolean sceneChange = false;
        if (!separateLayouts() && pendingScene != null) {
            note = "NEXT: " + pendingScene.getName() + " · Scene change";
            sceneChange = true;
        } else {
            boolean contentWaiting = previewVideoPath != null
                    || (previewSlide != null && !(previewSlide == takenSlide && program.content() == takenContent));
            if (contentWaiting) {
                String label = previewSlide == null || previewSlide.getLabel() == null ? "" : " – " + previewSlide.getLabel();
                note = "NEXT: " + (previewTitle == null ? "slide" : previewTitle) + label + " · Content change";
            }
        }
        if (separateLayouts()) {
            Scene p = previewState.scene();
            String layout = "Preview layout: " + (p == null ? "Standard" : p.getName());
            note = note == null ? layout : note + "  ·  " + layout;
        }
        liveDock.setPreviewNote(note, sceneChange);
    }

    /** Scenes dock actions. */
    private final class SceneHost implements com.jamc.worship.ui.components.ScenesDock.Host {
        @Override
        public void sceneClicked(Scene scene) {
            MainFrame.this.sceneClicked(scene);
        }

        @Override
        public void sceneSelected(Scene scene) {
            selectedScene = scene;
            statusBar.showMessage(separateLayouts()
                    ? "Scene \"" + scene.getName() + "\" selected. TAKE makes it the Preview layout; Program keeps its scene "
                            + "(LIVE or TAKE SCENE changes Program)."
                    : "Scene \"" + scene.getName() + "\" selected. TAKE puts it live; double-click or "
                            + "PREVIEW sends it to Preview first.");
        }

        @Override
        public void sendToProgram(Scene scene) {
            sendSceneToProgram(scene);
        }

        @Override
        public void addToService(Scene scene) {
            schedule.addItem(new ServiceItem(ServiceItem.Type.SCENE, scene.getName(), scene.getId()));
        }

        @Override
        public void editLayout(Scene scene) {
            if (!scenesPanel.edit(scene.getId())) {
                scenesPanel.refresh(); // the lists still show a scene just added; the editor keeps its draft
                return; // the scene being edited has unsaved changes and the operator stayed on it
            }
            topBar.selectPage(TopBar.Page.SCENE);
            showPage(TopBar.Page.SCENE);
            scenesHome.showEditor(scene);
            statusBar.showMessage("Editing \"" + scene.getName() + "\": changes stay in the editor until SAVE SCENE; "
                    + "Preview and Program do not change. ALL SCENES goes back.");
        }

        @Override
        public void addScene() {
            Scene current = previewState.scene() != null ? previewState.scene() : program.scene();
            var req = com.jamc.worship.ui.dialogs.CreateSceneDialog.show(MainFrame.this, current == null ? null : current.getName());
            if (req == null) {
                return;
            }
            Long copyFrom = switch (req.start()) {
                case BLANK -> null;
                case DUPLICATE_CURRENT -> current == null ? null : current.getId();
                case TEMPLATE -> scenes.stream().filter(s -> s.getName().equals(req.templateScene())).map(Scene::getId)
                        .findFirst().orElse(null);
            };
            BackgroundTask.run("Add scene", () -> {
                if (copyFrom == null) {
                    return context.sceneService().create(req.name());
                }
                Scene copy = context.sceneService().duplicate(copyFrom);
                copy.setName(req.name());
                return context.sceneService().save(copy);
            }, created -> editLayout(created), // opening it reloads every scene list; Program is not touched
                    err -> Dialogs.showError(MainFrame.this, "Could not add the scene.", err));
        }

        @Override
        public void renameScene(Scene scene) {
            Object name = javax.swing.JOptionPane.showInputDialog(MainFrame.this, "New name:", "Rename Scene",
                    javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, scene.getName());
            if (name == null || name.toString().isBlank()) {
                return;
            }
            Scene copy = scene.copy();
            copy.setName(name.toString().strip());
            // the scene lists reload; Preview and Program keep what they show (loadScenes would reset Program)
            BackgroundTask.run("Rename scene", () -> context.sceneService().save(copy), saved -> scenesPanel.refresh(),
                    err -> Dialogs.showError(MainFrame.this, "Could not rename the scene.", err));
        }

        @Override
        public void duplicateScene(Scene scene) {
            BackgroundTask.run("Duplicate scene", () -> context.sceneService().duplicate(scene.getId()),
                    copy -> scenesPanel.refresh(), err -> Dialogs.showError(MainFrame.this, "Could not duplicate the scene.", err));
        }

        @Override
        public void reorderScenes(List<Scene> order) {
            List<Long> ids = order.stream().map(Scene::getId).toList();
            BackgroundTask.run("Reorder scenes", () -> {
                context.sceneService().reorder(ids);
                return true;
            }, ok -> {
                scenesPanel.refresh(); // both card grids and the editor's list follow the new order
                statusBar.showMessage("Scene order saved.");
            }, err -> {
                Dialogs.showError(MainFrame.this, "Could not save the scene order.", err);
                scenesPanel.refresh(); // back to the saved order
            });
        }

        @Override
        public void setDefaultScene(Scene scene) {
            context.config().set(KEY_DEFAULT_SCENE, String.valueOf(scene.getId()));
            context.config().save();
            scenesDock.sync();
            if (scenesHome != null) scenesHome.sync();
            statusBar.showMessage("\"" + scene.getName() + "\" is now the default scene when the app starts.");
        }

        @Override
        public void deleteScene(Scene scene) {
            if (program.scene() != null && java.util.Objects.equals(program.scene().getId(), scene.getId())) {
                Dialogs.showInfo(MainFrame.this, "Scene is live", "Switch Program to another scene before deleting this one.");
                return;
            }
            if (javax.swing.JOptionPane.showConfirmDialog(MainFrame.this, "Delete the scene \"" + scene.getName() + "\"?",
                    "Delete Scene", javax.swing.JOptionPane.OK_CANCEL_OPTION) != javax.swing.JOptionPane.OK_OPTION) {
                return;
            }
            BackgroundTask.run("Delete scene", () -> {
                context.sceneService().delete(scene.getId());
                return true;
            }, ok -> {
                scenesPanel.refresh(); // every scene list (and the scene editor) drops it; Program stays
            }, err -> Dialogs.showError(MainFrame.this, err.getMessage(), err));
        }

        @Override
        public Scene programScene() {
            return program.scene();
        }

        @Override
        public Scene previewScene() {
            return previewState.scene();
        }

        @Override
        public void showOnProjector(Scene scene) {
            // right-click → Display on Projector: this scene goes live, and the projector output opens if it is off
            sendSceneToProgram(scene);
            if (!output.isOpen()) {
                output.toggle(MainFrame.this);
            }
            statusBar.showMessage("Scene \"" + scene.getName() + "\" is on the projector.");
        }

        @Override
        public List<ScenesDock.Screen> projectorScreens() {
            GraphicsDevice mine = operatorDevice();
            List<ScenesDock.Screen> out = new ArrayList<>();
            for (DisplayManager.Display d : displays.displays()) {
                if (mine != null && (d.device() == mine || d.id().equals(mine.getIDstring()))) {
                    continue; // never cover the controls from a right-click
                }
                boolean showing = output.routes().stream().anyMatch(r -> r.target().equals(d.id())
                        && r.source() == OutputService.Source.PROGRAM && r.showing());
                out.add(new ScenesDock.Screen(d.id(), d.title() + "  ·  " + d.pixelWidth() + "×"
                        + d.pixelHeight(), showing));
            }
            return out;
        }

        @Override
        public void showOnProjector(Scene scene, String screenId) {
            // one projector/TV (or all of them) shows Program, and this scene goes live on it
            sendSceneToProgram(scene);
            List<ScenesDock.Screen> screens = projectorScreens();
            int shown = 0;
            for (ScenesDock.Screen s : screens) {
                if ((ScenesDock.ALL_SCREENS.equals(screenId) || s.id().equals(screenId))
                        && output.route(OutputService.Source.PROGRAM, s.id(), MainFrame.this)) {
                    shown++;
                }
            }
            String where = ScenesDock.ALL_SCREENS.equals(screenId) ? shown + " projectors / TVs"
                    : screens.stream().filter(s -> s.id().equals(screenId)).map(ScenesDock.Screen::label)
                            .findFirst().orElse("the projector");
            statusBar.showMessage("Scene \"" + scene.getName() + "\" is on " + where + ".");
        }

        @Override
        public Long defaultSceneId() {
            String id = context.config().get(KEY_DEFAULT_SCENE, null);
            try {
                return id == null ? null : Long.parseLong(id);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public boolean separateLayouts() {
            return MainFrame.this.separateLayouts();
        }

        @Override
        public void setSeparateLayouts(boolean separate) {
            context.config().set(KEY_SEPARATE_LAYOUTS, String.valueOf(separate));
            context.config().save();
            // Preview stays on its scene either way. Sharing again, a scene other than Program's waits there for TAKE
            Scene inPreview = previewState.scene();
            pendingScene = separate || inPreview == null || sameLayout(program.scene(), inPreview) ? null : inPreview;
            liveDock.setTakeSceneVisible(separate);
            liveDock.setPreviewReady(previewSlide != null || previewVideoPath != null || pendingScene != null);
            updatePreviewNote();
            scenesDock.sync();
            if (scenesHome != null) scenesHome.sync();
            statusBar.showMessage(separate
                    ? "Separate layouts: click a scene to set Preview's layout; TAKE changes only the content, TAKE SCENE changes Program's layout."
                    : "Preview keeps its scene. A scene clicked goes to Preview, TAKE puts it live.");
        }
    }

    private void setLiveScene(Scene scene, boolean animate) {
        program.setScene(scene, animate);
        context.config().set(KEY_LIVE_SCENE, String.valueOf(scene.getId()));
        context.config().save();
        statusBar.showMessage("Scene \"" + scene.getName() + "\" is live");
    }

    /**
     * Whether the taken fullscreen video needs its own Program player in this scene. Not when the scene has no
     * visible Media (background) layer to show it, and not when one of the scene's Video sources already plays
     * the same video: otherwise the video would play twice, PROGRAM MEDIA would drive one copy while the other
     * is silenced, and the play / pause / stop / mute buttons would seem to do nothing.
     */
    private static boolean showsContent(Scene scene, String path) {
        if (scene == null) {
            return true;
        }
        boolean mediaLayer = false;
        for (var s : scene.getSources()) {
            if (!s.isVisible()) {
                continue;
            }
            if (s.getType() == com.jamc.worship.model.SourceType.VIDEO && Objects.equals(s.getConfig().path, path)) {
                return false; // the scene's own source plays it (one player, with sound, under PROGRAM MEDIA)
            }
            mediaLayer |= s.getType() == com.jamc.worship.model.SourceType.BACKGROUND;
        }
        return mediaLayer;
    }

    /** Plays, stops and follows the video that is live on Program. */
    private void syncProgramVideo() {
        LiveContent c = program.content();
        String path = c != null && c.isVideo() && showsContent(program.scene(), c.videoPath()) ? c.videoPath() : null;
        if (Objects.equals(path, playingProgramVideo)) {
            return;
        }
        playingProgramVideo = path;
        if (path == null) {
            hub.stopProgramVideo();
            updateProgramMedia();
            return;
        }
        boolean youtube = com.jamc.worship.youtube.YouTubeRef.is(path);
        if (!youtube && !hub.available()) {
            statusBar.showMessage(hub.problem());
            return;
        }
        // already playing in Preview: it goes on from there. Otherwise a YouTube video plays in the embedded YouTube
        // player and a file in VLC
        com.jamc.worship.media.VideoPlayback running = hub.adoptPreviewVideo(path);
        com.jamc.worship.media.VideoPlayback player = running != null ? running : hub.programPlayer(path);
        SettingsPanel.VideoEnd end = SettingsPanel.VideoEnd.parse(context.config().get(SettingsPanel.KEY_VIDEO_END, null));
        // this video's loop from last time (Settings for a video never changed)
        var state = mediaStates.get(com.jamc.worship.media.MediaStates.mediaKey(path), new com.jamc.worship.media.MediaStates.State(
                false, 100, end == SettingsPanel.VideoEnd.LOOP));
        boolean loop = state.loop();
        // every video starts with its own sound: 100 % as recorded, unmuted (the mixer's MEDIA / MASTER still apply);
        // a volume or mute changed while it plays lasts until it leaves Program
        hub.setProgramVideoVolume(100);
        hub.setProgramVideoMuted(false);
        boolean autoplay = context.config().getBoolean(SettingsPanel.KEY_AUTOPLAY, true);
        if (running != null) {
            player.setLoop(loop);
            if (!autoplay) {
                player.pause();
            }
        } else if (autoplay) {
            player.play(path, loop, false);
        } else {
            player.cue(path, loop);
        }
        updateProgramMedia();
        if (videoEndHooked.add(player)) {
            player.addFinishedListener(() -> {
                SettingsPanel.VideoEnd e = SettingsPanel.VideoEnd.parse(context.config().get(SettingsPanel.KEY_VIDEO_END, null));
                LiveContent now = program.content();
                if (e == SettingsPanel.VideoEnd.BLACK && now != null && now.isVideo()) {
                    program.clear();
                    statusBar.showMessage("Video ended. Program cleared.");
                }
            });
        }
    }

    /** Program players whose end-of-video is already handled; each player from Preview gets its own hook. */
    private final java.util.Set<com.jamc.worship.media.VideoPlayback> videoEndHooked =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** What the PROGRAM MEDIA controls are bound to (a fullscreen video path or a scene video key). */
    private String programMediaKey;
    private String programMediaTitle;
    private com.jamc.worship.media.Transport programMediaTransport;
    private com.jamc.worship.media.VideoPlayback programMediaPlayer;

    /**
     * PROGRAM MEDIA controls follow the video playing on Program: the fullscreen video, else the first
     * video source of the Program scene. Hidden when Program has no video.
     */
    private void updateProgramMedia() {
        String key = null;
        String title = null;
        com.jamc.worship.media.Transport transport = null;
        com.jamc.worship.media.VideoPlayback player = null;
        if (playingProgramVideo != null && hub.hasProgramVideo()) {
            player = hub.activeProgramPlayer();
            key = "program:" + playingProgramVideo;
            // volume and mute go through the hub (the mixer keeps them) and are remembered for this video
            transport = Objects.equals(key, programMediaKey) ? programMediaTransport
                    : mediaStates.remember(hub.programVideoTransport(), com.jamc.worship.media.MediaStates.mediaKey(playingProgramVideo));
            LiveContent live = program.content();
            title = com.jamc.worship.youtube.YouTubeRef.is(playingProgramVideo)
                    ? "YouTube: " + (live != null && live.title() != null ? live.title() : com.jamc.worship.youtube.YouTubeRef.id(playingProgramVideo))
                    : java.nio.file.Path.of(playingProgramVideo).getFileName().toString();
        } else {
            List<com.jamc.worship.media.SceneVideos.Slot> slots = hub.sceneVideos().programSlots();
            if (!slots.isEmpty()) {
                var slot = slots.getFirst();
                player = slot.player();
                key = slot.key();
                title = slot.name() + (slots.size() > 1 ? "  (+" + (slots.size() - 1) + " more)" : "");
                transport = Objects.equals(key, programMediaKey) ? programMediaTransport : hub.sceneVideos().transport(slot);
            }
        }
        if (Objects.equals(key, programMediaKey) && player == programMediaPlayer) {
            updateProgramMediaNote();
            return;
        }
        programMediaKey = key;
        programMediaTitle = title;
        programMediaTransport = transport;
        programMediaPlayer = player;
        liveDock.bindProgramMedia(transport, title, audioNote(player));
    }

    /** "No audio track" once VLC knows the Program video has no sound (not an error). */
    private void updateProgramMediaNote() {
        if (programMediaTransport != null) {
            liveDock.bindProgramMediaLabel(programMediaTitle, audioNote(programMediaPlayer));
        }
    }

    private static String audioNote(com.jamc.worship.media.VideoPlayback p) {
        if (p instanceof com.jamc.worship.youtube.YouTubePlayer yt) {
            return yt.hasError() ? yt.errorMessage() : "YouTube sound: Windows default output";
        }
        return p != null && p.audioTrackCount() == 0 ? "No audio track" : null;
    }


    /** "BG: Loop.mp4" in the footer while there is a global background (Take as Background). */
    private void updateBackgroundIndicator() {
        String name = globalBackground.path() == null ? null : GlobalBackgroundPanel.displayName(globalBackground.path());
        switch (globalBackground.status()) {
            case ACTIVE -> statusBar.setBackgroundState("BG: " + name, Palette::live);
            case PAUSED -> statusBar.setBackgroundState("BG: " + name + " (paused)", Palette::warning);
            default -> statusBar.setBackgroundState(null, null);
        }
    }

    /** "● REC 00:21:15" in the top bar while Program is being recorded. */
    private void updateRecordingIndicator() {
        var r = streamManager.recording();
        topBar.setRecording(!r.active() ? null : r.finishing() ? "SAVING…"
                : "■ STOP REC " + com.jamc.worship.ui.components.StreamPanel.elapsed(r.startedMillis()), r.finishing());
        var l = streamManager.live();
        statusBar.setLiveStatus(l);
        topBar.setStreaming(!l.active() ? null : l.finishing() ? "STOPPING…"
                : l.state() == com.jamc.worship.stream.StreamManager.State.ACTIVE
                ? "■ LIVE " + com.jamc.worship.ui.components.StreamPanel.elapsed(l.startedMillis()) : "CONNECTING…",
                l.state() == com.jamc.worship.stream.StreamManager.State.ACTIVE);
        boolean programOnly = streamManager.capturingAppSound();
        if (programOnly != audioRouting.programOnly()) {
            audioRouting.setProgramOnly(programOnly);
            liveDock.setPreviewAudioBlocked(programOnly);
            if (programOnly) {
                statusBar.showMessage("Only Program's sound is recorded: PREVIEW AUDIO and the Library preview are silent until you stop.");
            }
        }
        // the running time ticks every second while recording / streaming, not only when FFmpeg reports progress
        boolean ticking = r.active() || l.active();
        if (ticking && !recordingClock.isRunning()) {
            recordingClock.start();
        } else if (!ticking) {
            recordingClock.stop();
        }
    }

    private final javax.swing.Timer recordingClock = new javax.swing.Timer(1000, e -> updateRecordingIndicator());

    private void updateLiveIndicators() {
        boolean programShown = output.isRouted(OutputService.Source.PROGRAM);
        topBar.setOutputActive(output.isOpen());
        topBar.setLive(programShown && program.isShowingContent());
        liveDock.setOutputState(output.isOpen(), output.isOpen() ? "On" : output.isWaiting() ? "Waiting for display" : "Off",
                programShown);
    }

    private void loadLiveSettings() {
        AppConfig cfg = context.config();
        Transition t = Transition.parse(cfg.get(AppConfig.KEY_TRANSITION, null), Transition.FADE);
        int ms = parseInt(cfg.get(AppConfig.KEY_TRANSITION_MS, "500"), 500);
        program.setTransition(t, ms);
        previewState.setTransition(t, ms);
        liveDock.setTransition(t, ms);
        if (cfg.get(KEY_PREVIEW_FIRST, null) == null) {
            // Preview → TAKE → Program is the rule: Auto-take (which skips Preview) starts off once for everyone
            cfg.set(AppConfig.KEY_AUTO_TAKE, "false");
            cfg.set(KEY_PREVIEW_FIRST, "true");
            cfg.save();
        }
        if (cfg.get(KEY_LYRICS_CLICK_LIVE_RESET, null) == null) {
            // the operator asked again: song lyrics go live on click (Bible, PowerPoint, media keep Preview + TAKE)
            cfg.set(SettingsPanel.KEY_SONG_CLICK_LIVE, "true");
            cfg.set(KEY_LYRICS_CLICK_LIVE_RESET, "true");
            cfg.save();
        }
        liveDock.setAutoTake(cfg.getBoolean(AppConfig.KEY_AUTO_TAKE, false));
    }

    private GraphicsDevice operatorDevice() {
        return getGraphicsConfiguration() == null ? null : getGraphicsConfiguration().getDevice();
    }

    private void showPage(TopBar.Page page) {
        boolean leavingScenes = currentPage == TopBar.Page.SCENE && page != TopBar.Page.SCENE;
        if (leavingScenes && scenesPanel.hasUnsavedChanges() && !scenesPanel.confirmLeave()) {
            topBar.selectPage(TopBar.Page.SCENE); // Cancel: stay in the scene editor with the unsaved changes
            return;
        }
        currentPage = page;
        // leaving the Scenes page does not touch Preview: a saved scene shows only at TAKE
        placeLiveDock(page == TopBar.Page.SCENE);
        switch (page) {
            case SCENE -> pageCards.show(pages, page.name());
            case LIBRARY -> pageCards.show(pages, PAGE_WORKSPACE);
            case SERVICE -> {
                servicesPanel.refresh();
                pageCards.show(pages, page.name());
            }
            case STREAM -> {
                streamPanel.activate();
                pageCards.show(pages, page.name());
            }
            case SETTINGS -> {
                settingsPanel.refreshInfo();
                pageCards.show(pages, page.name());
            }
            default -> pageCards.show(pages, page.name());
        }
    }

    /**
     * Preview · TAKE · Program is one component: under the scene on the SCENE page, beside the library list
     * everywhere else. Moving it never touches what Preview or Program show.
     */
    private void placeLiveDock(boolean scenePage) {
        if (scenePage) {
            if (livePanel.getParent() != scenesHome.liveHolder()) {
                libraryDivider = librarySplit.getDividerLocation();
                scenesHome.liveHolder().add(livePanel, BorderLayout.CENTER);
                scenesHome.liveHolder().revalidate();
                librarySplit.setRightComponent(new JPanel()); // placeholder while the monitors are on the SCENE page
            }
        } else if (livePanel.getParent() == scenesHome.liveHolder()) {
            scenesHome.liveHolder().remove(livePanel);
            librarySplit.setRightComponent(livePanel);
            if (libraryDivider > 0) {
                librarySplit.setDividerLocation(libraryDivider);
            }
            librarySplit.revalidate();
        }
    }

    private int libraryDivider = -1;

    private static final String KEY_LIBRARY_CATEGORY = "ui.library.category";

    private Sidebar.Category savedLibraryCategory() {
        String saved = context.config().get(KEY_LIBRARY_CATEGORY, Sidebar.Category.SONGS.name());
        try {
            // Backgrounds used to be its own category; it is now a tab under Videos
            return "BACKGROUNDS".equals(saved) ? Sidebar.Category.VIDEOS : Sidebar.Category.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return Sidebar.Category.SONGS;
        }
    }

    /** LIBRARY from the main navigation or the logo: the Library page on its current category. */
    private void openLibrary() {
        topBar.selectPage(TopBar.Page.LIBRARY);
        showPage(TopBar.Page.LIBRARY);
    }

    private void changeTheme(String theme) {
        if (theme.equals(context.config().uiTheme())) {
            return;
        }
        context.config().set(AppConfig.KEY_UI_THEME, theme);
        context.config().save();
        Themes.switchTo(theme);
        settingsPanel.setTheme(theme);
        log.info("Switched UI theme to {}", theme);
    }

    // ------------------------------------------------------------------ menu & shortcuts

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("New Lyrics…", "plus", shortcuts.get(Shortcuts.Action.NEW_SONG), this::newSong));
        file.add(menuItem("Services…", "list", null, () -> {
            topBar.selectPage(TopBar.Page.SERVICE);
            showPage(TopBar.Page.SERVICE);
        }));
        file.add(menuItem("Save", null, shortcuts.get(Shortcuts.Action.SAVE), this::saveAll));
        file.addSeparator();
        file.add(menuItem("Backup / Restore…", "copy", null, () -> {
            topBar.selectPage(TopBar.Page.SETTINGS);
            showPage(TopBar.Page.SETTINGS);
        }));
        file.add(menuItem("Open Data Folder", "folder", null, this::openDataFolder));
        file.addSeparator();
        file.add(menuItem("Exit", null, KeyStroke.getKeyStroke(KeyEvent.VK_F4, KeyEvent.ALT_DOWN_MASK), this::shutdown));
        bar.add(file);

        JMenu view = new JMenu("View");
        view.add(menuItem("Dark Theme", "moon", null, () -> changeTheme(AppConfig.THEME_DARK)));
        view.add(menuItem("Light Theme", "sun", null, () -> changeTheme(AppConfig.THEME_LIGHT)));
        view.addSeparator();
        view.add(menuItem("Find Lyrics", "search", shortcuts.get(Shortcuts.Action.SEARCH), this::focusSongSearch));
        view.addSeparator();
        view.add(menuItem("Projector Output On/Off", "monitor", null, () -> output.toggle(this)));
        view.add(menuItem("OBS Output Window On/Off", "monitor", null, obs::toggle));
        bar.add(view);

        JMenu help = new JMenu("Help");
        help.add(menuItem("Keyboard Shortcuts", null, null, () -> {
            topBar.selectPage(TopBar.Page.SETTINGS);
            showPage(TopBar.Page.SETTINGS);
        }));
        help.add(menuItem("What's New", null, null, this::showWhatsNew));
        help.add(menuItem("About…", null, null, () -> javax.swing.JOptionPane.showMessageDialog(this,
                new Object[]{Ui.label(branding.applicationName(), Palette::text, "h4"),
                        Ui.label(BrandingManager.TAGLINE, Palette::accent, null),
                        "Version " + appVersion(), " ", "Data folder: " + context.paths().root()},
                "About " + branding.applicationName(), javax.swing.JOptionPane.PLAIN_MESSAGE,
                new javax.swing.ImageIcon(new java.awt.image.BaseMultiResolutionImage(branding.icon(96), branding.icon(192))))));
        bar.add(help);
        return bar;
    }

    private static JMenuItem menuItem(String text, String icon, KeyStroke accelerator, Runnable action) {
        JMenuItem item = new JMenuItem(text, icon == null ? null : Icons.get(icon, Icons.SMALL));
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    private void saveAll() {
        schedule.save();
        slideEditor.save();
        statusBar.showMessage("All changes are saved.");
    }

    private void installShortcuts() {
        // Presentation keys are handled once for the whole window (GlobalShortcuts): they work in search
        // boxes too (a TAKE key like ` takes and is never typed), but never interrupt real text editing.
        Predicate<KeyEvent> enterTakes = e -> {
            if (e.getKeyCode() != KeyEvent.VK_ENTER || e.getModifiersEx() != 0) {
                return false;
            }
            // Enter takes the selected slide while the slides or a presentation has focus
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            for (JComponent area : new JComponent[]{thumbnails, presentationDetail}) {
                if (owner != null && SwingUtilities.isDescendingFrom(owner, area)) {
                    take();
                    return true;
                }
            }
            return false;
        };
        new GlobalShortcuts(this::isActive, shortcuts, enterTakes, action -> {
            switch (action) {
                case NEXT_SLIDE -> stepSlide(true);
                case PREVIOUS_SLIDE -> stepSlide(false);
                case SLIDESHOW_NEXT -> slideshows.controlPreview(com.jamc.worship.output.Slideshows.Control.NEXT);
                case SLIDESHOW_PREVIOUS -> slideshows.controlPreview(com.jamc.worship.output.Slideshows.Control.PREVIOUS);
                case TAKE -> take();
                case BLACK -> program.toggleBlack();
                case BLANK -> program.toggleBlank();
                case LYRICS -> program.toggleLyrics();
                case HIDE_LOWER_THIRD -> program.hideLowerThird();
                case NEXT_ITEM -> schedule.selectNext();
                case PREVIOUS_ITEM -> schedule.selectPrevious();
                case TOGGLE_OUTPUT -> output.toggle(this);
                case CLOSE_OUTPUT -> {
                    if (!output.isOpen() || output.mode() != OutputService.Mode.FULLSCREEN) {
                        return false; // let dialogs and popups handle Esc normally
                    }
                    output.close();
                    statusBar.showMessage("Projector output closed (Esc). Press F5 to open it again.");
                }
                default -> {
                    return false;
                }
            }
            return true;
        }).install();
        ClickAwayFocus.install(this, getRootPane()); // a click outside a text box gives the shortcuts back
    }

    private void newSong() {
        focusLibraryPage();
        songLibrary.addSong();
    }

    private void focusSongSearch() {
        focusLibraryPage();
        songLibrary.focusSearch();
    }

    private void focusLibraryPage() {
        Sidebar.Category current = sidebar.selectedCategory();
        if (current != Sidebar.Category.SONGS && current != Sidebar.Category.FAVORITES) {
            sidebar.selectCategory(Sidebar.Category.SONGS);
        }
        topBar.selectPage(TopBar.Page.LIBRARY);
        showPage(TopBar.Page.LIBRARY);
    }

    private void openDataFolder() {
        try {
            Desktop.getDesktop().open(context.paths().root().toFile());
        } catch (Exception e) {
            Dialogs.showError(this, "Could not open the data folder.", e);
        }
    }

    // ------------------------------------------------------------------ layout helpers

    private static JPanel region(JComponent content) {
        JPanel p = Ui.panel(new BorderLayout(), Palette::panelBackground);
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private static JSplitPane split(int orientation, Component a, Component b, double resizeWeight) {
        JSplitPane s = new JSplitPane(orientation, a, b);
        s.setResizeWeight(resizeWeight);
        s.setContinuousLayout(true);
        s.setBorder(null);
        s.setOneTouchExpandable(false);
        return s;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.strip());
        } catch (RuntimeException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ window state

    private void restoreWindowBounds() {
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(1200, usable.width), Math.min(680, usable.height)));
        Rectangle saved = parseBounds(context.config().get(AppConfig.KEY_WINDOW_BOUNDS, null));
        if (saved != null && isOnScreen(saved)) {
            setBounds(saved);
        } else {
            int w = Math.min(1600, usable.width);
            int h = Math.min(940, usable.height);
            setBounds(usable.x + (usable.width - w) / 2, usable.y + (usable.height - h) / 2, w, h);
        }
        if (context.config().getBoolean(AppConfig.KEY_WINDOW_MAXIMIZED, true)) {
            setExtendedState(getExtendedState() | MAXIMIZED_BOTH);
        }
    }

    private void restoreDividers() {
        SwingUtilities.invokeLater(() -> {
            int width = sidebarSplit.getWidth();
            int height = verticalSplit.getHeight();
            setDivider("sidebar.v2", Math.max(230, Math.min(270, (int) (width * 0.16))));
            setDivider("library", Math.max(260, Math.min(330, (int) (width * 0.2))));
            setDivider("schedule.v3", scheduleSplit.getWidth() - Math.max(270, Math.min(310, (int) (width * 0.18))));
            setDivider("scenes.v4", mixerFallback());
            setDivider("vertical.v2", Math.max(300, (int) (height * 0.45)));
            setDivider("bottom.v2", Math.max(420, (int) (bottomSplit.getWidth() * 0.6)));
            // the Library navigation (More closed) on top, the rest of the height for the Service schedule
            setDivider("nav", Math.min(sidebar.getPreferredSize().height + 4, (int) (navSplit.getHeight() * 0.55)));
            keepMixerOnScreen();
        });
    }

    /** The Scenes / Audio Mixer divider by default: the mixer gets about 290 px (its faders and meters). */
    private int mixerFallback() {
        return Math.max(140, scenesSplit.getHeight() - scenesSplit.getDividerSize() - 290);
    }

    /** A saved position from a taller screen (or from while the mixer was off) must not hide the mixer. */
    private void keepMixerOnScreen() {
        if (mixerRegion.isVisible() && scenesSplit.getHeight() > 0
                && scenesSplit.getHeight() - scenesSplit.getDividerLocation() < mixerRegion.getMinimumSize().height) {
            scenesSplit.setDividerLocation(mixerFallback());
        }
    }

    /** Settings → Show the Audio Mixer: shows or hides it under the Scenes. */
    private void applyMixerVisible() {
        boolean show = context.config().getBoolean(SettingsPanel.KEY_SHOW_MIXER, true);
        if (mixerRegion.isVisible() != show) {
            mixerRegion.setVisible(show);
            scenesSplit.revalidate();
            if (show) {
                SwingUtilities.invokeLater(() -> scenesSplit.setDividerLocation(mixerFallback()));
            }
        }
    }

    private void setDivider(String key, int fallback) {
        splits.get(key).setDividerLocation(parseInt(context.config().get("ui.split." + key, String.valueOf(fallback)), fallback));
    }

    private void saveWindowState() {
        AppConfig config = context.config();
        boolean maximized = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        config.set(AppConfig.KEY_WINDOW_MAXIMIZED, String.valueOf(maximized));
        if (!maximized) {
            Rectangle b = getBounds();
            config.set(AppConfig.KEY_WINDOW_BOUNDS, b.x + "," + b.y + "," + b.width + "," + b.height);
        }
        splits.forEach((key, split) -> config.set("ui.split." + key, String.valueOf(split.getDividerLocation())));
        config.save();
    }

    private static Rectangle parseBounds(String value) {
        if (value == null) {
            return null;
        }
        String[] p = value.split(",");
        if (p.length != 4) {
            return null;
        }
        try {
            return new Rectangle(Integer.parseInt(p[0].strip()), Integer.parseInt(p[1].strip()),
                    Integer.parseInt(p[2].strip()), Integer.parseInt(p[3].strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True if the title bar area of {@code r} is visible on some connected monitor. */
    private static boolean isOnScreen(Rectangle r) {
        Rectangle titleBar = new Rectangle(r.x + 40, r.y, Math.max(1, r.width - 80), 30);
        for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().intersects(titleBar)) {
                return true;
            }
        }
        return false;
    }

    /** Saves everything that might be unsaved. Safe to call more than once. */
    private void saveEverything() {
        try {
            saveWindowState();
        } catch (RuntimeException e) {
            log.error("Could not save window state", e);
        }
        try {
            schedule.saveBlocking();
        } catch (RuntimeException e) {
            log.error("Could not save the service while closing", e);
        }
        try {
            slideEditor.saveBlocking();
        } catch (RuntimeException e) {
            log.error("Could not save the slide deck while closing", e);
        }
    }

    private void releaseResources() {
        displays.stopMonitoring();
        output.shutdown();
        streamManager.shutdown();
        remoteManager.shutdown();
        globalBackground.shutdown();
        obs.close();
        try {
            hub.shutdown();
        } catch (RuntimeException e) {
            log.warn("Error releasing media", e);
        }
    }

    private void shutdown() {
        log.info("Shutting down");
        saveEverything();
        releaseResources();
        dispose();
        System.exit(0);
    }

    /** Restarts the application (after a restore or VLC setup). */
    private void restartApplication() {
        log.info("Restarting");
        saveEverything();
        releaseResources();
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            List<String> cmd = new ArrayList<>();
            cmd.add(info.command().orElse("java"));
            info.arguments().ifPresent(args -> cmd.addAll(List.of(args)));
            new ProcessBuilder(cmd).inheritIO().start();
        } catch (Exception e) {
            log.error("Could not restart automatically", e);
            Dialogs.showInfo(this, "Restart", "Please start " + branding.applicationName() + " again.");
        }
        dispose();
        System.exit(0);
    }

    private static String appVersion() {
        String v = MainFrame.class.getPackage().getImplementationVersion();
        return v == null ? "development" : v;
    }

}
