package com.jamc.worship.ui.components;

import com.jamc.worship.app.AppContext;
import com.jamc.worship.bible.BibleDisplay;
import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.TypographySettings;
import com.jamc.worship.database.MigrationManager;
import com.jamc.worship.media.MediaHub;
import com.jamc.worship.model.ContentKind;
import com.jamc.worship.model.Theme;
import com.jamc.worship.output.ObsOutput;
import com.jamc.worship.service.BackupService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Shortcuts;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Settings page: General, Output, Presentation, Media, Database, Advanced and Keyboard Shortcuts. */
public class SettingsPanel extends JPanel {

    public static final String KEY_REOPEN_SERVICE = "app.reopenService";
    public static final String KEY_OPEN_OUTPUT = "app.openOutputOnStartup";
    public static final String KEY_VOLUME = "media.volume";
    public static final String KEY_VIDEO_END = "media.videoEnd";
    public static final String KEY_AUTOPLAY = "media.autoplay";
    /** Double-clicking a slide (cards, thumbnails, presentation grid) sends it live instead of only to Preview. */
    public static final String KEY_DOUBLE_CLICK_LIVE = "live.doubleClickTakes";
    /** Clicking a song lyrics slide sends it straight to Program (Bible, PowerPoint and media still use Preview + TAKE). */
    public static final String KEY_SONG_CLICK_LIVE = "live.songClickGoesLive";
    /** Clicking a PowerPoint slide sends it straight to Program, like song lyrics. */
    public static final String KEY_PRESENTATION_CLICK_LIVE = "live.presentationClickGoesLive";
    /** Clicking a scene switches Program at once (Direct Switch) instead of Preview + TAKE. */
    public static final String KEY_SCENE_DIRECT = "scenes.directSwitch";
    public static final String KEY_VLC_PATH = "media.vlcPath";
    public static final String KEY_BIBLE_THEME = "bible.theme";
    public static final String KEY_BIBLE_REF_POSITION = "bible.referencePosition";
    public static final String KEY_BIBLE_SHOW_VERSION = "bible.showVersion";
    public static final String KEY_BIBLE_UPPERCASE = "bible.uppercaseReference";
    public static final String KEY_BIBLE_AUTOFIT = "bible.autoFit";

    /** What Program shows when a live video ends. */
    public enum VideoEnd {
        BLACK("Go to black"), HOLD("Hold the last frame"), LOOP("Loop the video");

        private final String label;

        VideoEnd(String label) {
            this.label = label;
        }

        public static VideoEnd parse(String v) {
            try {
                return v == null ? HOLD : valueOf(v);
            } catch (IllegalArgumentException e) {
                return HOLD;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private record ThemeChoice(Long id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private final AppContext context;
    private final Shortcuts shortcuts;
    private final MediaHub hub;
    private final BackupService backups;
    private final Runnable restart;
    private final JRadioButton dark = new JRadioButton("Dark (recommended for live use)");
    private final JRadioButton light = new JRadioButton("Light");
    private final JComboBox<ThemeChoice> defaultTheme = new JComboBox<>();
    private final JComboBox<ThemeChoice> bibleTheme = new JComboBox<>();
    private final JLabel dbInfo = Ui.secondary("…");
    private final JLabel vlcStatus = Ui.secondary(" ");
    private final JLabel cameraStatus = Ui.secondary(" ");
    private final JPanel shortcutRows = Ui.transparent(new GridBagLayout());
    private boolean loadingThemes;
    private TypographyEditor lyricsTypography;
    private TypographyEditor bibleTypography;
    private final List<TypographyEditor> otherEditors = new java.util.ArrayList<>();

    public SettingsPanel(AppContext context, Consumer<String> onThemeChange, Shortcuts shortcuts, ObsOutput obs,
                         MediaHub hub, Runnable restart, Runnable manageBibleVersions) {
        super(new BorderLayout());
        this.context = context;
        this.shortcuts = shortcuts;
        this.hub = hub;
        this.backups = new BackupService(context.paths(), context.database());
        this.restart = restart;
        setOpaque(false);
        AppConfig cfg = context.config();

        JPanel content = Ui.transparent(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(20, 28, 20, 28));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 18, 0);
        content.add(Ui.label("Settings", Palette::text, "h2"), c);
        c.gridy++;

        // GENERAL
        ButtonGroup group = new ButtonGroup();
        group.add(dark);
        group.add(light);
        (AppConfig.THEME_LIGHT.equals(cfg.uiTheme()) ? light : dark).setSelected(true);
        dark.addActionListener(e -> onThemeChange.accept(AppConfig.THEME_DARK));
        light.addActionListener(e -> onThemeChange.accept(AppConfig.THEME_LIGHT));
        JCheckBox reopen = checkbox("Reopen the last service when the app starts", KEY_REOPEN_SERVICE, true);
        JCheckBox openOutput = checkbox("Open the projector output when the app starts", KEY_OPEN_OUTPUT, false);
        JCheckBox autoUpdate = checkbox("Check for a new version when the app starts (needs internet)",
                com.jamc.worship.update.UpdateChecker.KEY_AUTO_CHECK, true);
        JButton checkUpdates = Ui.button("Check for Updates", "refresh");
        checkUpdates.addActionListener(e -> updateCheck.run());
        String version = com.jamc.worship.update.UpdateChecker.currentVersion();
        JPanel general = rows(flow(Ui.small("Application theme:"), dark, light), reopen, openOutput,
                Ui.label("Songs, slides, themes and services save automatically as you work.", Palette::mutedText, "small"),
                autoUpdate, flow(checkUpdates, Ui.small("Version " + (version == null ? "(development)" : version))));
        content.add(section("General", "Look and startup behaviour", general), c);
        c.gridy++;

        // BRANDING: the application's own name and icon
        if (com.jamc.worship.ui.BrandingManager.get() != null) {
            content.add(section("Branding", "The application name and icon / logo",
                    new BrandingSettingsPanel(com.jamc.worship.ui.BrandingManager.get())), c);
            c.gridy++;
        }

        // OUTPUT / OBS
        JComboBox<String> res = new JComboBox<>(new String[]{"1920 × 1080", "1280 × 720"});
        res.setSelectedIndex(obs.width() == 1280 ? 1 : 0);
        JCheckBox borderless = new JCheckBox("Borderless window", obs.borderless());
        JCheckBox green = new JCheckBox("Green background for chroma key (only text and graphics over #00FF00)", obs.green());
        Runnable applyObs = () -> obs.configure(res.getSelectedIndex() == 1 ? 1280 : 1920,
                res.getSelectedIndex() == 1 ? 720 : 1080, borderless.isSelected(), green.isSelected());
        res.addActionListener(e -> applyObs.run());
        borderless.addActionListener(e -> applyObs.run());
        green.addActionListener(e -> applyObs.run());
        JButton obsButton = Ui.button("Open / Close OBS Output", "monitor");
        obsButton.addActionListener(e -> obs.toggle());
        JComboBox<com.jamc.worship.output.PresentationWindow.CursorMode> cursor =
                new JComboBox<>(com.jamc.worship.output.PresentationWindow.CursorMode.values());
        cursor.setSelectedItem(com.jamc.worship.output.PresentationWindow.CursorMode.parse(cfg.get(AppConfig.KEY_PROJECTOR_CURSOR, null)));
        cursor.setToolTipText("The mouse still moves freely between the control screen and the projector");
        cursor.addActionListener(e -> {
            cfg.set(AppConfig.KEY_PROJECTOR_CURSOR, ((com.jamc.worship.output.PresentationWindow.CursorMode) cursor.getSelectedItem()).name());
            cfg.save();
        });
        JPanel output = rows(
                Ui.label("Choose the projector display and fullscreen/windowed mode on the Output page.", Palette::secondaryText, null),
                flow(Ui.small("Projector cursor:"), cursor),
                flow(Ui.small("OBS window size:"), res, obsButton), borderless, green,
                Ui.label("<html>In OBS add a <b>Window Capture</b> source and pick \"" + ObsOutput.WINDOW_TITLE
                        + "\". With the green option, add a Chroma Key filter. OBS handles streaming, recording and audio.</html>",
                        Palette::mutedText, "small"));
        content.add(section("Output & OBS", "Projector and livestream picture", output), c);
        c.gridy++;

        // PRESENTATION
        defaultTheme.addActionListener(e -> {
            if (!loadingThemes && defaultTheme.getSelectedItem() instanceof ThemeChoice tc && tc.id() != null) {
                BackgroundTask.run("Set default theme", () -> {
                    context.themeService().setDefault(tc.id());
                    return true;
                }, ok -> { }, err -> Dialogs.showError(this, "Could not change the default theme.", err));
            }
        });
        bibleTheme.addActionListener(e -> {
            if (!loadingThemes && bibleTheme.getSelectedItem() instanceof ThemeChoice tc) {
                cfg.set(KEY_BIBLE_THEME, tc.id() == null ? null : String.valueOf(tc.id()));
                cfg.save();
            }
        });
        JPanel presentation = rows(flow(Ui.small("Default theme for songs and slides:"), defaultTheme),
                flow(Ui.small("Theme for Bible verses:"), bibleTheme),
                checkbox("Song lyrics: clicking a slide sends it live at once (no Preview step)", KEY_SONG_CLICK_LIVE, true),
                checkbox("Presentations (PowerPoint): clicking a slide sends it live at once (no Preview step)",
                        KEY_PRESENTATION_CLICK_LIVE, true),
                sceneSwitchRow(),
                checkbox("Double-click a slide to send it live (otherwise double-click only previews; use TAKE)",
                        KEY_DOUBLE_CLICK_LIVE, false),
                Ui.label("Default transition and duration are chosen next to the TAKE button and remembered.",
                        Palette::mutedText, "small"));
        content.add(section("Presentation", "Default look", presentation), c);
        c.gridy++;

        // TYPOGRAPHY (one profile per kind of content)
        TypographySettings typo = context.themeService().typography();
        if (typo != null) {
            lyricsTypography = new TypographyEditor(typo, ContentKind.LYRICS);
            // one setup for every song: songs that were given their own font settings follow these again
            JButton everySong = Ui.primaryButton("Use for Every Song", "music");
            everySong.setToolTipText("Remove the font settings saved on single songs, so every song uses this typography");
            everySong.addActionListener(e -> {
                if (!Dialogs.confirmDestructive(this, "Use for every song", "Every song will use this Lyrics Typography.\n\n"
                        + "Font, size, thickness, case and alignment saved on single songs (\"This Song Only\") are removed.\n"
                        + "The lyrics themselves are not changed.", "Use for Every Song")) {
                    return;
                }
                com.jamc.worship.ui.BackgroundTask.run("Use global typography", () -> context.songService().useGlobalTypographyForAll(), n -> {
                    if (!typo.isGlobal(ContentKind.LYRICS)) {
                        typo.setGlobal(ContentKind.LYRICS, true);
                    }
                    songsReset.run();
                    Dialogs.showInfo(this, "Lyrics Typography", n == 0 ? "Every song already uses this typography."
                            : n + (n == 1 ? " song now uses" : " songs now use") + " this typography.");
                }, err -> Dialogs.showError(this, "Could not update the songs.", err));
            });
            JPanel lyricsBody = Ui.transparent(new BorderLayout(0, 10));
            lyricsBody.add(lyricsTypography, BorderLayout.CENTER);
            lyricsBody.add(flow(everySong, Ui.small("Set it up once here. In a song, APPLY TO \"All Songs\" changes these same settings.")),
                    BorderLayout.SOUTH);
            content.add(section("Lyrics Typography", "Presentation → Lyrics: one look for every song. "
                    + "Lyrics are stored as typed; UPPERCASE is applied only on screen.", lyricsBody), c);
            c.gridy++;
            bibleTypography = new TypographyEditor(typo, ContentKind.BIBLE);
            content.add(section("Bible Typography", "Presentation → Bible: separate from lyrics, so verses never turn "
                    + "into capitals because songs are", bibleTypography), c);
            c.gridy++;
            JComboBox<ContentKind> otherKind = new JComboBox<>(new ContentKind[]{ContentKind.ANNOUNCEMENTS, ContentKind.CUSTOM_SLIDES});
            java.awt.CardLayout otherCards = new java.awt.CardLayout();
            JPanel otherHolder = Ui.transparent(otherCards);
            for (ContentKind k : new ContentKind[]{ContentKind.ANNOUNCEMENTS, ContentKind.CUSTOM_SLIDES}) {
                TypographyEditor ed = new TypographyEditor(typo, k);
                otherEditors.add(ed);
                otherHolder.add(ed, k.name());
            }
            otherKind.addActionListener(e -> otherCards.show(otherHolder, ((ContentKind) otherKind.getSelectedItem()).name()));
            JPanel other = Ui.transparent(new BorderLayout(0, 10));
            other.add(flow(Ui.small("Profile:"), otherKind), BorderLayout.NORTH);
            other.add(otherHolder, BorderLayout.CENTER);
            other.add(Ui.label("<html>Lower thirds keep the font chosen in each lower third, and PowerPoint slides show "
                    + "exactly as PowerPoint drew them.</html>", Palette::mutedText, "small"), BorderLayout.SOUTH);
            content.add(section("Other Typography", "Announcements, sermon notes and custom slide decks", other), c);
            c.gridy++;

            // INTERFACE
            JCheckBox cardsCase = new JCheckBox("Apply presentation uppercase to Action Cards", typo.actionCardsUseCase());
            cardsCase.addActionListener(e -> typo.setActionCardsUseCase(cardsCase.isSelected()));
            JPanel iface = rows(cardsCase, Ui.label("<html>Off: the Lyrics & Slides cards show the words as typed "
                    + "(easier to read), while Preview, Program, the projector and OBS use the letter case above.</html>",
                    Palette::mutedText, "small"));
            content.add(section("Interface", "Action Cards", iface), c);
            c.gridy++;
        }

        // BIBLE
        JComboBox<BibleDisplay.ReferencePosition> refPos = new JComboBox<>(BibleDisplay.ReferencePosition.values());
        refPos.setSelectedItem(BibleDisplay.ReferencePosition.parse(cfg.get(KEY_BIBLE_REF_POSITION, null)));
        refPos.addActionListener(e -> {
            cfg.set(KEY_BIBLE_REF_POSITION, ((BibleDisplay.ReferencePosition) refPos.getSelectedItem()).name());
            cfg.save();
        });
        JButton versions = Ui.button("Manage Bible Versions…", "book");
        versions.addActionListener(e -> manageBibleVersions.run());
        JPanel bibleRows = rows(flow(Ui.small("Verse reference (e.g. JOHN 3:16):"), refPos),
                checkbox("Show the version abbreviation (e.g. NIV) on the slide", KEY_BIBLE_SHOW_VERSION, true),
                checkbox("Show the reference in capital letters", KEY_BIBLE_UPPERCASE, true),
                checkbox("Fit several short verses on one slide and split long verses across slides", KEY_BIBLE_AUTOFIT, true),
                flow(versions),
                Ui.label("<html>Background, video background, font, size, color, alignment, shadow and outline come from the "
                        + "Bible theme chosen above (edit it under Themes). NLT, ESV, NIV and Ang Pulong Sa Dios are copyrighted: "
                        + "connect them with your own licensed API key or an imported licensed file in Bible Versions.</html>",
                        Palette::mutedText, "small"));
        content.add(section("Bible Presentation", "How verses appear on screen", bibleRows), c);
        c.gridy++;

        // MEDIA
        JSlider volume = new JSlider(0, 150, parseInt(cfg.get(KEY_VOLUME, "100"), 100));
        volume.addChangeListener(e -> {
            if (!volume.getValueIsAdjusting()) {
                cfg.set(KEY_VOLUME, String.valueOf(volume.getValue()));
                hub.setProgramVideoVolume(volume.getValue());
                cfg.save();
            }
        });
        JComboBox<VideoEnd> end = new JComboBox<>(VideoEnd.values());
        end.setSelectedItem(VideoEnd.parse(cfg.get(KEY_VIDEO_END, null)));
        end.addActionListener(e -> {
            cfg.set(KEY_VIDEO_END, ((VideoEnd) end.getSelectedItem()).name());
            cfg.save();
        });
        JCheckBox autoplay = checkbox("Play videos automatically when taken live", KEY_AUTOPLAY, true);
        JButton locate = Ui.button("Locate VLC Folder…", "folder");
        locate.addActionListener(e -> locateVlc());
        JButton detect = Ui.button("Detect Cameras", "video");
        detect.addActionListener(e -> detectCameras());
        JPanel media = rows(vlcStatus, flow(locate), flow(Ui.small("Default volume:"), volume),
                flow(Ui.small("When a live video ends:"), end), autoplay, flow(detect), cameraStatus);
        content.add(section("Media & Camera", "Video, audio and camera playback use VLC media player", media), c);
        c.gridy++;
        content.add(section("Audio", "Where Program and Preview sound goes", new AudioSettingsPanel(hub.routing(), cfg)), c);
        c.gridy++;

        // INTEGRATIONS → LYRICS
        content.add(section("Integrations → Lyrics", "Where SEARCH LYRICS looks. Lyrics are reviewed before saving and "
                + "then stay on this computer (they work offline).", lyricsIntegrations(context)), c);
        c.gridy++;

        // DATABASE
        JCheckBox includeMedia = new JCheckBox("Include media files (larger backup)");
        JButton backup = Ui.primaryButton("Backup Library…", "copy");
        backup.addActionListener(e -> backup(includeMedia.isSelected()));
        JButton restore = Ui.button("Restore Backup…", "folder");
        restore.addActionListener(e -> restore());
        JPanel database = rows(flow(backup, includeMedia), flow(restore),
                Ui.label("<html>A backup holds songs, Bible, slides, themes, services, scenes and settings. Before a restore "
                        + "the current data is saved to a safety backup automatically.</html>", Palette::mutedText, "small"));
        content.add(section("Database", "Backup and restore", database), c);
        c.gridy++;

        // ADVANCED
        JPanel advanced = rows(pathRow("Data folder", context.paths().root()), pathRow("Database", context.paths().databaseFile()),
                pathRow("Logs", context.paths().logsDir()), pathRow("Backups", context.paths().backupsDir()), dbInfo);
        content.add(section("Advanced", "Everything is stored locally on this computer", advanced), c);
        c.gridy++;

        // SHORTCUTS
        JButton reset = Ui.button("Reset to Defaults", null);
        reset.addActionListener(e -> {
            shortcuts.resetAll();
            buildShortcutRows();
        });
        JPanel keys = Ui.transparent(new BorderLayout(0, 8));
        keys.add(shortcutRows, BorderLayout.CENTER);
        keys.add(flow(reset), BorderLayout.SOUTH);
        buildShortcutRows();
        content.add(section("Keyboard Shortcuts", "Live keys work anywhere except while typing in a text field", keys), c);
        c.gridy++;

        c.weighty = 1;
        content.add(Ui.transparent(new BorderLayout()), c);
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);
    }

    public void setTheme(String theme) {
        (AppConfig.THEME_LIGHT.equals(theme) ? light : dark).setSelected(true);
    }

    private Runnable songsReset = () -> { };
    private Runnable updateCheck = () -> { };

    /** Called after every song was set to follow the Lyrics Typography (the open song reloads). */
    public void setSongsResetHandler(Runnable handler) {
        songsReset = handler == null ? () -> { } : handler;
    }

    /** General → Check for Updates. */
    public void setUpdateCheckHandler(Runnable handler) {
        updateCheck = handler == null ? () -> { } : handler;
    }

    /** Refreshes database, theme, VLC and camera information (queries run in the background). */
    public void refreshInfo() {
        vlcStatus.setText(hub.available() ? "VLC is ready: video, audio and camera are available."
                : "<html><font color='#F59E0B'>" + hub.problem() + "</font></html>");
        List<String> cams = hub.cameras().cameras();
        cameraStatus.setText(cams.isEmpty() ? "No camera detected yet." : "Cameras: " + String.join(", ", cams));
        BackgroundTask.run("Read settings info", () -> {
            int version = new MigrationManager(context.database()).currentVersion();
            int songs = context.songService().count();
            List<Theme> themes = context.themeService().list();
            Long def = context.themeService().defaultThemeId();
            return new Object[]{"Schema version " + version + "  ·  " + songs + (songs == 1 ? " song" : " songs"), themes, def};
        }, r -> {
            dbInfo.setText((String) r[0]);
            @SuppressWarnings("unchecked")
            List<Theme> themes = (List<Theme>) r[1];
            Long def = (Long) r[2];
            loadingThemes = true;
            try {
                defaultTheme.removeAllItems();
                bibleTheme.removeAllItems();
                bibleTheme.addItem(new ThemeChoice(null, "Theme named \"Bible\" (or default)"));
                String bibleId = context.config().get(KEY_BIBLE_THEME, null);
                for (Theme t : themes) {
                    ThemeChoice tc = new ThemeChoice(t.getId(), t.getName());
                    defaultTheme.addItem(tc);
                    bibleTheme.addItem(tc);
                    if (t.getId().equals(def)) {
                        defaultTheme.setSelectedItem(tc);
                    }
                    if (String.valueOf(t.getId()).equals(bibleId)) {
                        bibleTheme.setSelectedItem(tc);
                    }
                }
            } finally {
                loadingThemes = false;
            }
            // samples are drawn on the default theme's background (the Bible one for Bible)
            Theme defTheme = themes.stream().filter(t -> t.getId().equals(def)).findFirst().orElse(null);
            String bibleThemeId = context.config().get(KEY_BIBLE_THEME, null);
            Theme bibleT = themes.stream().filter(t -> String.valueOf(t.getId()).equals(bibleThemeId)).findFirst()
                    .or(() -> themes.stream().filter(t -> t.getName().equalsIgnoreCase("Bible")).findFirst()).orElse(defTheme);
            if (lyricsTypography != null) {
                lyricsTypography.load(); // shows changes made from a song's quick bar (APPLY TO: All Songs)
                lyricsTypography.setBaseStyle(defTheme == null ? null : defTheme.getStyle());
                bibleTypography.setBaseStyle(bibleT == null ? null : bibleT.getStyle());
                otherEditors.forEach(ed -> ed.setBaseStyle(defTheme == null ? null : defTheme.getStyle()));
            }
        }, err -> dbInfo.setText("Could not read database information."));
    }

    // ------------------------------------------------------------------ actions

    private void backup(boolean includeMedia) {
        JFileChooser chooser = new JFileChooser(context.paths().backupsDir().toFile());
        chooser.setSelectedFile(backups.defaultBackupFile().toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Backup (*.zip)", "zip"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (!target.getFileName().toString().toLowerCase().endsWith(".zip")) {
            target = target.resolveSibling(target.getFileName() + ".zip");
        }
        Path file = target;
        JDialog progress = progressDialog("Creating backup…");
        BackgroundTask.run("Backup", () -> backups.backup(file, includeMedia, msg -> { }), p -> {
            progress.dispose();
            Dialogs.showInfo(this, "Backup complete", "Backup saved to:\n" + p);
        }, err -> {
            progress.dispose();
            Dialogs.showError(this, "The backup failed.", err);
        });
        progress.setVisible(true);
    }

    private void restore() {
        JFileChooser chooser = new JFileChooser(context.paths().backupsDir().toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Backup (*.zip)", "zip"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        if (!Dialogs.confirmDestructive(this, "Restore backup", "Replace the current library with this backup?\n\n"
                + file.getFileName() + "\n\nA safety backup of the current data is made first. The app restarts afterwards.",
                "Restore")) {
            return;
        }
        JDialog progress = progressDialog("Restoring backup…");
        BackgroundTask.run("Restore", () -> backups.restore(file, msg -> { }), safety -> {
            progress.dispose();
            Dialogs.showInfo(this, "Restore complete", "The backup was restored.\nThe previous data was saved to:\n" + safety
                    + "\n\nThe application will now restart.");
            restart.run();
        }, err -> {
            progress.dispose();
            Dialogs.showError(this, "The restore failed. Your current data was not changed.", err);
        });
        progress.setVisible(true);
    }

    private void locateVlc() {
        JFileChooser chooser = new JFileChooser("C:\\Program Files");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select the folder that contains libvlc.dll");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path dir = chooser.getSelectedFile().toPath();
        if (!java.nio.file.Files.exists(dir.resolve("libvlc.dll"))) {
            Dialogs.showInfo(this, "VLC not found", "That folder does not contain libvlc.dll.\nChoose the VLC installation folder "
                    + "(usually C:\\Program Files\\VideoLAN\\VLC).");
            return;
        }
        context.config().set(KEY_VLC_PATH, dir.toString());
        context.config().save();
        if (JOptionPane.showConfirmDialog(this, "VLC folder saved. Restart now to enable video, audio and camera?",
                "Restart", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            restart.run();
        }
    }

    private void detectCameras() {
        cameraStatus.setText("Detecting cameras…");
        BackgroundTask.run("Detect cameras", () -> hub.cameras().detect(), cams -> cameraStatus.setText(
                cams.isEmpty() ? "No camera found." : "Cameras: " + String.join(", ", cams)),
                err -> cameraStatus.setText("Camera detection failed."));
    }

    private void buildShortcutRows() {
        shortcutRows.removeAll();
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 0, 4, 16);
        for (Shortcuts.Action a : Shortcuts.Action.values()) {
            g.gridx = 0;
            shortcutRows.add(Ui.label(a.label(), Palette::text, null), g);
            g.gridx = 1;
            JLabel key = Ui.label(Shortcuts.describe(shortcuts.get(a)), Palette::accent, "semibold");
            shortcutRows.add(key, g);
            g.gridx = 2;
            JButton change = new JButton("Change…");
            change.addActionListener(e -> captureKey(a));
            shortcutRows.add(change, g);
            g.gridy++;
        }
        shortcutRows.revalidate();
        shortcutRows.repaint();
    }

    private void captureKey(Shortcuts.Action a) {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), "Press a key for \"" + a.label() + "\"",
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JLabel l = new JLabel("Press the new key (Esc to cancel)…", JLabel.CENTER);
        l.setBorder(BorderFactory.createEmptyBorder(30, 40, 30, 40));
        d.add(l);
        d.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META) {
                    return;
                }
                d.dispose();
                if (code != KeyEvent.VK_ESCAPE || a == Shortcuts.Action.CLOSE_OUTPUT) {
                    KeyStroke key = KeyStroke.getKeyStroke(code, e.getModifiersEx());
                    var taken = shortcuts.conflict(a, key);
                    if (taken.isPresent()) {
                        // never two actions on one key: Cancel, or move the key here (the other action has none)
                        Object[] options = {"REASSIGN", "CANCEL"};
                        int choice = JOptionPane.showOptionDialog(SettingsPanel.this,
                                "Shortcut already assigned to " + taken.get().label() + ".\n\nReassign "
                                        + Shortcuts.describe(key) + " to " + a.label() + "?",
                                "Shortcut in use", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                                null, options, options[1]);
                        if (choice != 0) {
                            return;
                        }
                        shortcuts.set(taken.get(), null);
                    }
                    shortcuts.set(a, key);
                }
            }
        });
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
        buildShortcutRows();
    }

    // ------------------------------------------------------------------ helpers

    /** Scene Switching: Preview + TAKE (safe default) or Direct Switch. */
    private JComponent sceneSwitchRow() {
        JRadioButton previewTake = new JRadioButton("Preview + TAKE (recommended)");
        JRadioButton direct = new JRadioButton("Direct Switch (a clicked scene goes live at once)");
        ButtonGroup g = new ButtonGroup();
        g.add(previewTake);
        g.add(direct);
        (context.config().getBoolean(KEY_SCENE_DIRECT, false) ? direct : previewTake).setSelected(true);
        java.awt.event.ActionListener save = e -> {
            context.config().set(KEY_SCENE_DIRECT, String.valueOf(direct.isSelected()));
            context.config().save();
        };
        previewTake.addActionListener(save);
        direct.addActionListener(save);
        return flow(Ui.small("Scene switching:"), previewTake, direct);
    }

    private JCheckBox checkbox(String text, String key, boolean def) {
        JCheckBox b = new JCheckBox(text, context.config().getBoolean(key, def));
        b.addActionListener(e -> {
            context.config().set(key, String.valueOf(b.isSelected()));
            context.config().save();
        });
        return b;
    }

    private JDialog progressDialog(String text) {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), "Please wait", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        d.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        javax.swing.JProgressBar bar = new javax.swing.JProgressBar();
        bar.setIndeterminate(true);
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        p.add(new JLabel(text), BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        d.add(p);
        d.pack();
        d.setSize(Math.max(d.getWidth(), 360), d.getHeight());
        d.setLocationRelativeTo(this);
        return d;
    }

    private static JPanel rows(JComponent... rows) {
        JPanel p = Ui.transparent(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 6, 0);
        for (JComponent r : rows) {
            p.add(r, g);
            g.gridy++;
        }
        return p;
    }

    /**
     * LYRICS PROVIDERS: each provider with its status and capabilities, and its setup. Secret keys are never
     * shown again once saved. Only connected providers are offered in FIND LYRICS.
     */
    private JComponent lyricsIntegrations(AppContext context) {
        AppConfig cfg = context.config();
        JPanel list = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 8));
        JPanel body = rows(list);
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            list.removeAll();
            var providers = com.jamc.worship.lyrics.LyricsProviders.create(cfg, context.songService());
            for (var p : providers.all()) {
                JLabel name = Ui.label(p.name(), Palette::text, "semibold");
                JLabel status = Ui.label(p.status().label(), p.status() == com.jamc.worship.lyrics.LyricsProvider.Status.CONNECTED
                        ? Palette::live : Palette::mutedText, "small");
                String caps = p.capabilities().stream().map(com.jamc.worship.lyrics.LyricsProvider.Capability::label)
                        .collect(java.util.stream.Collectors.joining(" · "));
                JPanel text = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 1));
                text.add(flow(name, status));
                text.add(Ui.small(p.description()));
                text.add(Ui.label(caps, Palette::mutedText, "small"));
                JPanel row = Ui.transparent(new BorderLayout(10, 0));
                row.add(text, BorderLayout.CENTER);
                row.add(providerControls(p, cfg, fill[0]), BorderLayout.EAST);
                list.add(row);
            }
            // YouTube (YouTube Sync and the video library): the key itself is never shown again
            boolean hasKey = cfg.get(com.jamc.worship.youtube.YouTubeClient.KEY_API_KEY, null) != null;
            JButton setKey = Ui.button(hasKey ? "Change Key…" : "Set Key…", null);
            setKey.addActionListener(e -> {
                javax.swing.JPasswordField field = new javax.swing.JPasswordField(32);
                if (JOptionPane.showConfirmDialog(this, field, "YouTube Data API key", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION && field.getPassword().length > 0) {
                    cfg.set(com.jamc.worship.youtube.YouTubeClient.KEY_API_KEY, new String(field.getPassword()).strip());
                    cfg.save();
                    fill[0].run();
                }
            });
            JButton removeKey = Ui.button("Remove", null);
            removeKey.setVisible(hasKey);
            removeKey.addActionListener(e -> {
                cfg.set(com.jamc.worship.youtube.YouTubeClient.KEY_API_KEY, null);
                cfg.save();
                fill[0].run();
            });
            JPanel ytText = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 1));
            ytText.add(flow(Ui.label("YouTube Reference", Palette::text, "semibold"),
                    Ui.label(hasKey ? "● Key saved (hidden)" : "○ Links only (no search key)", hasKey ? Palette::live : Palette::mutedText, "small")));
            ytText.add(Ui.small("Identifies songs and is one optional sync reference. Never a source of lyrics text."));
            JPanel yt = Ui.transparent(new BorderLayout(10, 0));
            yt.add(ytText, BorderLayout.CENTER);
            yt.add(flow(setKey, removeKey), BorderLayout.EAST);
            list.add(yt);

            JButton add = Ui.button("ADD PROVIDER", "plus");
            add.addActionListener(e -> {
                javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
                javax.swing.JMenuItem folder = new javax.swing.JMenuItem("Lyrics Folder (the church's TXT / LRC / SRT / VTT files)…");
                folder.addActionListener(ev -> chooseLyricsFolder(cfg, fill[0]));
                javax.swing.JMenuItem licensed = new javax.swing.JMenuItem("Licensed worship lyrics service…");
                licensed.addActionListener(ev -> Dialogs.showInfo(this, "Licensed lyrics provider",
                        "Licensed worship lyrics services (for example CCLI SongSelect) only offer their lyrics to approved\n"
                                + "partner software. JAMC Worship Presenter is built to add such a provider once an agreement and\n"
                                + "API access exist.\n\nUntil then: download the lyrics from your licensed service and use PASTE LYRICS,\n"
                                + "IMPORT FILE, or put the files in a Lyrics Folder so SEARCH LYRICS finds them."));
                menu.add(folder);
                menu.add(licensed);
                menu.show(add, 0, add.getHeight());
            });
            list.add(flow(add));
            list.revalidate();
            list.repaint();
        };
        fill[0].run();
        return body;
    }

    private JComponent providerControls(com.jamc.worship.lyrics.LyricsProvider p, AppConfig cfg, Runnable refresh) {
        switch (p.id()) {
            case com.jamc.worship.lyrics.LrcLibProvider.ID -> {
                JCheckBox on = new JCheckBox("Use", cfg.getBoolean(com.jamc.worship.lyrics.LrcLibProvider.KEY_ENABLED, false));
                on.setOpaque(false);
                on.addActionListener(e -> {
                    if (on.isSelected() && JOptionPane.showConfirmDialog(this, "LRCLIB's lyrics are contributed by its users.\n"
                                    + "Your church must be licensed (e.g. CCLI) to project each song you use from it.\n\nUse LRCLIB in SEARCH LYRICS?",
                            "LRCLIB", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
                        on.setSelected(false);
                        return;
                    }
                    cfg.set(com.jamc.worship.lyrics.LrcLibProvider.KEY_ENABLED, String.valueOf(on.isSelected()));
                    cfg.save();
                    refresh.run();
                });
                return flow(on);
            }
            case com.jamc.worship.lyrics.LyricsFolderProvider.ID -> {
                JButton choose = Ui.button("Choose Folder…", "folder");
                choose.addActionListener(e -> chooseLyricsFolder(cfg, refresh));
                JButton remove = Ui.button("Remove", null);
                remove.setVisible(cfg.get(com.jamc.worship.lyrics.LyricsFolderProvider.KEY_FOLDER, null) != null);
                remove.addActionListener(e -> {
                    cfg.set(com.jamc.worship.lyrics.LyricsFolderProvider.KEY_FOLDER, null);
                    cfg.save();
                    refresh.run();
                });
                return flow(choose, remove);
            }
            default -> {
                return flow();
            }
        }
    }

    private void chooseLyricsFolder(AppConfig cfg, Runnable refresh) {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(cfg.get(com.jamc.worship.lyrics.LyricsFolderProvider.KEY_FOLDER, null));
        chooser.setDialogTitle("Folder with the church's lyrics files (TXT, LRC, SRT, VTT)");
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
            cfg.set(com.jamc.worship.lyrics.LyricsFolderProvider.KEY_FOLDER, chooser.getSelectedFile().getAbsolutePath());
            cfg.save();
            refresh.run();
        }
    }

    private static JPanel flow(JComponent... items) {
        JPanel p = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        for (JComponent i : items) {
            p.add(i);
        }
        return p;
    }

    private static JComponent section(String title, String subtitle, JComponent body) {
        JPanel p = Ui.panel(new BorderLayout(0, 10), Palette::secondaryBackground);
        p.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, true, true, true),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        JPanel head = Ui.transparent(new BorderLayout(0, 2));
        head.add(Ui.label(title, Palette::text, "h4"), BorderLayout.NORTH);
        head.add(Ui.small(subtitle), BorderLayout.SOUTH);
        p.add(head, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    private JComponent pathRow(String label, Path path) {
        JPanel row = Ui.transparent(new BorderLayout(10, 0));
        JLabel name = Ui.label(label, Palette::secondaryText, null);
        name.setPreferredSize(new java.awt.Dimension(110, name.getPreferredSize().height));
        JLabel value = Ui.label(path.toString(), Palette::text, null);
        value.setToolTipText(path.toString());
        JButton open = Ui.button("Open", "folder");
        open.addActionListener(e -> {
            Path target = path.toFile().isDirectory() ? path : path.getParent();
            try {
                Desktop.getDesktop().open(target.toFile());
            } catch (Exception ex) {
                Dialogs.showError(this, "Could not open " + target, ex);
            }
        });
        row.add(name, BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);
        row.add(open, BorderLayout.EAST);
        return row;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
