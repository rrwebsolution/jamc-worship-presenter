package com.jamc.worship.tools;

import com.jamc.worship.app.AppContext;
import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.render.SlideRenderer;
import com.jamc.worship.repository.BibleRepository;
import com.jamc.worship.repository.LowerThirdRepository;
import com.jamc.worship.repository.MediaRepository;
import com.jamc.worship.repository.SceneRepository;
import com.jamc.worship.repository.ServiceRepository;
import com.jamc.worship.repository.SettingsRepository;
import com.jamc.worship.repository.SlideDeckRepository;
import com.jamc.worship.repository.SongRepository;
import com.jamc.worship.repository.ThemeRepository;
import com.jamc.worship.service.BibleService;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.service.SceneService;
import com.jamc.worship.service.ServicePlanService;
import com.jamc.worship.service.SlideDeckService;
import com.jamc.worship.service.SongService;
import com.jamc.worship.service.ThemeService;
import com.jamc.worship.ui.MainFrame;
import com.jamc.worship.ui.components.Sidebar;
import com.jamc.worship.ui.components.TopBar;
import com.jamc.worship.ui.theme.Themes;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Developer tool: renders the main window off-screen (never shown, no input) against a throwaway
 * data folder and saves a PNG of each page. Usage: ScreenshotTour &lt;output-folder&gt;
 */
public final class ScreenshotTour {

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        AppPaths paths = new AppPaths(Files.createTempDirectory("jamc-tour"));
        paths.ensureDirectories();
        System.setProperty("jamc.logs.dir", paths.logsDir().toString());
        AppConfig config = new AppConfig(paths);
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        Rendering.install(new SlideRenderer(new ImageCache(paths.root())));
        AppContext ctx = new AppContext(paths, config, db, new SongService(new SongRepository(db)),
                new ThemeService(new ThemeRepository(db), new SettingsRepository(db), new com.jamc.worship.config.TypographySettings(config)),
                new SlideDeckService(new SlideDeckRepository(db)), new ServicePlanService(new ServiceRepository(db)),
                new MediaService(new MediaRepository(db), paths), new BibleService(new BibleRepository(db), new com.jamc.worship.repository.BibleVersionRepository(db)),
                new SceneService(new SceneRepository(db), new LowerThirdRepository(db)),
                new com.jamc.worship.service.PresentationService(new com.jamc.worship.repository.PresentationRepository(db),
                        paths.presentationsDir(), com.jamc.worship.presentation.PresentationRenderers.defaults()));
        ctx.bibleService().ensureInstalled();
        com.jamc.worship.model.Song demo = new com.jamc.worship.model.Song();
        demo.setTitle("Placeholder Worship Song");
        demo.setSections(com.jamc.worship.service.LyricsParser.parse("""
                Verse 1
                First placeholder line
                Second placeholder line

                Third placeholder line

                Chorus
                Chorus placeholder line

                Verse 2
                Verse two placeholder

                Bridge
                Bridge placeholder

                Outro
                Outro placeholder
                """));
        demo = ctx.songService().save(demo);
        ctx.songService().setArrangement(demo.getId(), java.util.List.of("Verse 1", "Chorus", "Verse 2", "Chorus", "Bridge", "Chorus", "Outro"));
        long demoId = demo.getId();

        MainFrame[] frame = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            Themes.install("dark");
            frame[0] = new MainFrame(ctx);
            frame[0].setExtendedState(0);
            frame[0].addNotify();
            frame[0].setSize(1600, 900);
            frame[0].validate();
        });
        call(frame[0], "restoreDividers");
        call(frame[0], "startup");
        SwingUtilities.invokeAndWait(() -> frame[0].validate());
        Thread.sleep(2500);

        shot(frame[0], out, "00-start"); // the app opens on the Library
        navigationTest(frame[0], out);
        page(frame[0], TopBar.Page.LIBRARY);
        shot(frame[0], out, "01-library");
        java.util.function.Supplier<String> live = () -> {
            try {
                var f = MainFrame.class.getDeclaredField("program");
                f.setAccessible(true);
                var c = ((com.jamc.worship.output.ProgramOutput) f.get(frame[0])).content();
                return c == null ? "nothing" : c.slide().getLabel();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        };
        SwingUtilities.invokeAndWait(() -> {
            try {
                var f = MainFrame.class.getDeclaredField("songLibrary");
                f.setAccessible(true);
                ((com.jamc.worship.ui.components.SongLibraryPanel) f.get(frame[0])).refresh(demoId);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(2000);
        String[] check = new String[5];
        SwingUtilities.invokeAndWait(() -> check[0] = live.get());
        SwingUtilities.invokeAndWait(() -> strip(frame[0]).select(0));
        SwingUtilities.invokeAndWait(() -> check[1] = live.get());
        SwingUtilities.invokeAndWait(() -> strip(frame[0]).select(2));
        SwingUtilities.invokeAndWait(() -> check[2] = live.get());
        SwingUtilities.invokeAndWait(() -> {
            try {
                var pf = MainFrame.class.getDeclaredField("program");
                pf.setAccessible(true);
                var sf = MainFrame.class.getDeclaredField("previewState");
                sf.setAccessible(true);
                var prog = (com.jamc.worship.output.ProgramOutput) pf.get(frame[0]);
                var prev = (com.jamc.worship.output.ProgramOutput) sf.get(frame[0]);
                prog.toggleLyrics();
                String off = "program " + prog.isLyricsHidden() + " / preview " + prev.isLyricsHidden();
                prog.toggleLyrics();
                System.out.println("lyrics off -> hidden: " + off + " | back on -> preview hidden: " + prev.isLyricsHidden()
                        + " | transition program " + prog.transition() + " / preview " + prev.transition() + " " + prev.durationMs() + " ms");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        System.out.println("song opened -> live: " + check[0] + " | click card 1 -> live: " + check[1]
                + " | click card 3 -> live: " + check[2]);
        String[] cases = new String[3];
        SwingUtilities.invokeAndWait(() -> cases[0] = programCase(frame[0]));
        SwingUtilities.invokeAndWait(() -> ctx.themeService().typography().set(com.jamc.worship.model.ContentKind.LYRICS,
                ctx.themeService().typography().get(com.jamc.worship.model.ContentKind.LYRICS)
                        .withTextTransform(com.jamc.worship.model.TextTransform.ORIGINAL)));
        Thread.sleep(800);
        SwingUtilities.invokeAndWait(() -> cases[1] = previewCase(frame[0]) + " / program " + programCase(frame[0]));
        call(frame[0], "take");
        SwingUtilities.invokeAndWait(() -> cases[2] = programCase(frame[0]));
        System.out.println("live lyrics case: " + cases[0] + " | after Settings -> Original: preview " + cases[1]
                + " | TAKE: program " + cases[2]);
        SwingUtilities.invokeAndWait(() -> ctx.themeService().typography().set(com.jamc.worship.model.ContentKind.LYRICS,
                ctx.themeService().typography().get(com.jamc.worship.model.ContentKind.LYRICS)
                        .withTextTransform(com.jamc.worship.model.TextTransform.UPPERCASE)));
        Thread.sleep(800);
        call(frame[0], "take");
        SwingUtilities.invokeAndWait(() -> strip(frame[0]).setSelectedIndex(0));
        call(frame[0], "take");
        SwingUtilities.invokeAndWait(() -> strip(frame[0]).setSelectedIndex(1));
        call(frame[0], "take");
        SwingUtilities.invokeAndWait(() -> strip(frame[0]).setSelectedIndex(2));
        Thread.sleep(1200);
        shot(frame[0], out, "01b-song-order");
        sceneChecks(frame[0], ctx);
        Thread.sleep(800);
        shot(frame[0], out, "01e-scenes");
        editorCheck(frame[0], out);
        SwingUtilities.invokeAndWait(() -> {
            try {
                var f = MainFrame.class.getDeclaredField("actionOrder");
                f.setAccessible(true);
                Object panel = f.get(frame[0]);
                Method m = panel.getClass().getDeclaredMethod("moveEntry", int.class, int.class);
                m.setAccessible(true);
                m.invoke(panel, 4, 2); // Bridge above Verse 2
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(1500);
        shot(frame[0], out, "01c-reordered");
        SwingUtilities.invokeAndWait(() -> {
            try {
                var f = MainFrame.class.getDeclaredField("program");
                f.setAccessible(true);
                ((com.jamc.worship.output.ProgramOutput) f.get(frame[0])).setBlack(true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(1200);
        shot(frame[0], out, "01d-black");
        SwingUtilities.invokeAndWait(() -> {
            try {
                var f = MainFrame.class.getDeclaredField("program");
                f.setAccessible(true);
                ((com.jamc.worship.output.ProgramOutput) f.get(frame[0])).setBlack(false);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        System.out.println("stored order: " + ctx.songService().load(demoId).orElseThrow().getArrangement());
        category(frame[0], Sidebar.Category.BIBLE);
        Thread.sleep(3000);
        shot(frame[0], out, "02-bible");
        SwingUtilities.invokeAndWait(() -> {
            try {
                var f = MainFrame.class.getDeclaredField("biblePanel");
                f.setAccessible(true);
                ((com.jamc.worship.ui.components.BiblePanel) f.get(frame[0]))
                        .goTo(new com.jamc.worship.bible.BiblePassage("KJV", 43, 3, 16, 18));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(2500);
        SwingUtilities.invokeAndWait(() -> strip(frame[0]).select(1));
        String[] bible = new String[1];
        SwingUtilities.invokeAndWait(() -> bible[0] = live.get());
        System.out.println("bible: click slide 2 -> live still: " + bible[0]);
        SwingUtilities.invokeAndWait(() -> strip(frame[0]).select(0));
        call(frame[0], "take");
        Thread.sleep(1500);
        shot(frame[0], out, "02b-bible-live");
        // ---- PowerPoint: an imported deck, slide 3 live and slide 4 in Preview
        Path deck = sampleDeck(out.resolve("Sunday Sermon.pptx"), 9);
        ctx.presentationService().importFile(deck, new com.jamc.worship.service.PresentationService.Progress() {
            @Override
            public void started(String name, int slideCount, String renderer) {
            }

            @Override
            public void slideReady(com.jamc.worship.service.PresentationService.PresentationSlide slide) {
            }
        });
        category(frame[0], Sidebar.Category.PRESENTATIONS);
        Thread.sleep(2500);
        SwingUtilities.invokeAndWait(() -> strip(frame[0]).setSelectedIndex(2));
        call(frame[0], "take");
        Thread.sleep(800);
        SwingUtilities.invokeAndWait(() -> strip(frame[0]).setSelectedIndex(3));
        String[] ppt = new String[2];
        SwingUtilities.invokeAndWait(() -> ppt[0] = live.get() + " / preview " + previewLabel(frame[0]));
        SwingUtilities.invokeAndWait(() -> strip(frame[0]).select(5));
        SwingUtilities.invokeAndWait(() -> ppt[1] = live.get() + " / preview " + previewLabel(frame[0]));
        System.out.println("presentation: click slide 4 -> live " + ppt[0] + " | click slide 6 -> live " + ppt[1]);
        Thread.sleep(2000);
        shot(frame[0], out, "09-presentation-live");
        // a second import, captured while it is rendering
        Path youth = sampleDeck(out.resolve("Youth Service.pptx"), 24);
        SwingUtilities.invokeAndWait(() -> {
            try {
                var f = MainFrame.class.getDeclaredField("presentationLibrary");
                f.setAccessible(true);
                ((com.jamc.worship.ui.components.PresentationLibraryPanel) f.get(frame[0])).importAll(java.util.List.of(youth));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(2500);
        shot(frame[0], out, "10-presentation-importing");
        Thread.sleep(12000);
        shot(frame[0], out, "11-presentation-ready");
        SwingUtilities.invokeAndWait(() -> strip(frame[0]).setSelectedIndex(5));
        Thread.sleep(2000);
        shot(frame[0], out, "12-presentation-thumbs");
        // the imported presentation as a scene source (by library id): Camera + Presentation in the editor
        long libraryDeck = ctx.presentationService().list("").getFirst().id();
        presentationSourceShot(frame[0], out, libraryDeck);
        slideshowShots(frame[0], ctx, out);

        // scenes on the left are independent of the Library tabs: a click goes to Preview only
        SwingUtilities.invokeAndWait(() -> {
            try {
                var scenesF = MainFrame.class.getDeclaredField("scenes");
                scenesF.setAccessible(true);
                @SuppressWarnings("unchecked")
                var list = (java.util.List<com.jamc.worship.model.Scene>) scenesF.get(frame[0]);
                var bibleScene = list.stream().filter(sc -> sc.getName().equals("Bible")).findFirst().orElseThrow();
                Object programBeforeScene = programScene(frame[0]);
                Method click = MainFrame.class.getDeclaredMethod("sceneClicked", com.jamc.worship.model.Scene.class);
                click.setAccessible(true);
                click.invoke(frame[0], bibleScene);
                var pf = MainFrame.class.getDeclaredField("previewState");
                pf.setAccessible(true);
                var previewScene = ((com.jamc.worship.output.ProgramOutput) pf.get(frame[0])).scene();
                System.out.println("scene click -> Preview scene=" + (previewScene == null ? null : previewScene.getName())
                        + (programScene(frame[0]) == programBeforeScene ? " (Program unchanged)" : " PROGRAM CHANGED"));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(800);
        shot(frame[0], out, "16-scene-preview");

        // the dynamic Library workspace: each category replaces the whole view; Program never changes
        String programBefore = live.get();
        Object sceneBefore = programScene(frame[0]);
        int step = 0;
        for (Sidebar.Category c : java.util.List.of(Sidebar.Category.SONGS, Sidebar.Category.VIDEOS, Sidebar.Category.AUDIO,
                Sidebar.Category.IMAGES, Sidebar.Category.PRESENTATIONS, Sidebar.Category.BIBLE,
                Sidebar.Category.CAMERA, Sidebar.Category.SONGS)) {
            category(frame[0], c);
            Thread.sleep(900);
            shot(frame[0], out, "15-workspace-" + (++step) + "-" + c.name().toLowerCase());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var wf = MainFrame.class.getDeclaredField("workspaces");
                    wf.setAccessible(true);
                    var ws = (com.jamc.worship.ui.LibraryWorkspaceController) wf.get(frame[0]);
                    System.out.println("workspace after " + c + ": " + ws.current()
                            + (live.get().equals(programBefore) && programScene(frame[0]) == sceneBefore ? " (Program unchanged)" : " PROGRAM CHANGED"));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        SwingUtilities.invokeAndWait(() -> {
            try {
                Method s = MainFrame.class.getDeclaredMethod("showBackgroundsLibrary");
                s.setAccessible(true);
                s.invoke(frame[0]);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(800);
        shot(frame[0], out, "03-backgrounds");
        // inspect a built-in background in MEDIA PREVIEW (nothing goes live), then TAKE AS BACKGROUND
        SwingUtilities.invokeAndWait(() -> {
            try {
                var f = MainFrame.class.getDeclaredField("backgroundLibrary");
                f.setAccessible(true);
                ((com.jamc.worship.ui.components.MediaLibraryPanel) f.get(frame[0])).select(-6L);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(800);
        shot(frame[0], out, "03b-background-inspected");
        SwingUtilities.invokeAndWait(() -> clickButton(frame[0].getContentPane(), "TAKE AS BACKGROUND"));
        Thread.sleep(1200);
        shot(frame[0], out, "03c-background-taken");
        category(frame[0], Sidebar.Category.LOWER_THIRDS);
        shot(frame[0], out, "04-lower-thirds");
        page(frame[0], TopBar.Page.SERVICE);
        shot(frame[0], out, "06-services");
        page(frame[0], TopBar.Page.SETTINGS);
        shot(frame[0], out, "07-settings");
        // Settings → Branding: a custom name and logo show at once in the header; then back to the default
        Path logoFile = out.resolve("branding-logo.png");
        java.awt.image.BufferedImage logo = new java.awt.image.BufferedImage(300, 150, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D lg = logo.createGraphics();
        lg.setColor(new java.awt.Color(0xF59E0B));
        lg.fillOval(0, 0, 300, 150);
        lg.dispose();
        javax.imageio.ImageIO.write(logo, "png", logoFile.toFile());
        com.jamc.worship.ui.BrandingManager.get().save("JAMC Tagoloan Worship Presenter", "JAMC Tagoloan", logoFile, false);
        Thread.sleep(800);
        System.out.println("branding title: " + frame[0].getTitle());
        shot(frame[0], out, "07a-branding-custom");
        com.jamc.worship.ui.BrandingManager.get().resetToDefault();
        Thread.sleep(800);
        System.out.println("branding title after reset: " + frame[0].getTitle());
        SwingUtilities.invokeAndWait(() -> {
            var ed = find(frame[0].getContentPane(), com.jamc.worship.ui.components.TypographyEditor.class);
            if (ed != null) {
                javax.swing.JComponent section = (javax.swing.JComponent) ed.getParent();
                section.scrollRectToVisible(new java.awt.Rectangle(0, 0, section.getWidth(), section.getHeight() + 420));
            }
        });
        Thread.sleep(600);
        shot(frame[0], out, "07b-settings-typography");
        SwingUtilities.invokeAndWait(() -> {
            var audio = find(frame[0].getContentPane(), com.jamc.worship.ui.components.AudioSettingsPanel.class);
            if (audio != null) {
                javax.swing.JComponent section = (javax.swing.JComponent) audio.getParent();
                section.scrollRectToVisible(new java.awt.Rectangle(0, 0, section.getWidth(), section.getHeight() + 40));
            }
        });
        Thread.sleep(800);
        shot(frame[0], out, "07c-settings-audio");
        page(frame[0], TopBar.Page.OUTPUT);
        shot(frame[0], out, "08-output");
        page(frame[0], TopBar.Page.STREAM);
        Thread.sleep(1500);
        shot(frame[0], out, "13-stream");
        System.exit(0);
    }

    /** Edit Layout: drag the lyrics box's corner on the off-screen canvas (component events, no desktop input). */
    private static void editorCheck(MainFrame f, Path out) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var scenesF = MainFrame.class.getDeclaredField("scenes");
                scenesF.setAccessible(true);
                @SuppressWarnings("unchecked")
                var list = (java.util.List<com.jamc.worship.model.Scene>) scenesF.get(f);
                var cl = list.stream().filter(sc -> sc.getName().equals("Camera + Lyrics")).findFirst().orElseThrow();
                var panelF = MainFrame.class.getDeclaredField("scenesPanel");
                panelF.setAccessible(true);
                var panel = (com.jamc.worship.ui.components.ScenesPanel) panelF.get(f);
                panel.edit(cl.getId());
                var cardsF = MainFrame.class.getDeclaredField("pageCards");
                cardsF.setAccessible(true);
                var pagesF = MainFrame.class.getDeclaredField("pages");
                pagesF.setAccessible(true);
                ((java.awt.CardLayout) cardsF.get(f)).show((java.awt.Container) pagesF.get(f), "SCENE");
                openSceneEditor(f, cl);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            f.getContentPane().setSize(1600, 900);
            f.getContentPane().validate();
            @SuppressWarnings("unchecked")
            javax.swing.JList<com.jamc.worship.model.Source> sources = find(f.getContentPane(), javax.swing.JList.class, l ->
                    l.getModel().getSize() > 0 && l.getModel().getElementAt(0) instanceof com.jamc.worship.model.Source);
            int lyricsIndex = -1;
            for (int i = 0; i < sources.getModel().getSize(); i++) {
                if (sources.getModel().getElementAt(i).getType() == com.jamc.worship.model.SourceType.LYRICS) {
                    lyricsIndex = i;
                }
            }
            sources.setSelectedIndex(lyricsIndex);
            var lyr = sources.getModel().getElementAt(lyricsIndex);
            javax.swing.JComponent canvas = find(f.getContentPane(), javax.swing.JComponent.class,
                    c -> c.getClass().getSimpleName().equals("ScenePreview"));
            int w = canvas.getWidth();
            int h = (int) Math.round(w * 9 / 16.0);
            if (h > canvas.getHeight()) {
                h = canvas.getHeight();
                w = (int) Math.round(h * 16 / 9.0);
            }
            var img = new BufferedImage(Math.max(1, canvas.getWidth()), Math.max(1, canvas.getHeight()), BufferedImage.TYPE_INT_RGB);
            Graphics2D pg = img.createGraphics();
            canvas.paint(pg); // off-screen: lay the canvas out once, as the real window does when shown
            pg.dispose();
            int fx = (canvas.getWidth() - w) / 2;
            int fy = (canvas.getHeight() - h) / 2;
            int cx = fx + w - 1;
            int cy = fy + h - 1; // bottom-right handle of the full-frame lyrics box
            long t = System.currentTimeMillis();
            canvas.dispatchEvent(new java.awt.event.MouseEvent(canvas, java.awt.event.MouseEvent.MOUSE_PRESSED, t, 0, cx, cy, 1, false, java.awt.event.MouseEvent.BUTTON1));
            canvas.dispatchEvent(new java.awt.event.MouseEvent(canvas, java.awt.event.MouseEvent.MOUSE_DRAGGED, t + 50, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, cx - w / 3, cy - h / 4, 0, false, 0));
            canvas.dispatchEvent(new java.awt.event.MouseEvent(canvas, java.awt.event.MouseEvent.MOUSE_RELEASED, t + 100, 0, cx - w / 3, cy - h / 4, 1, false, java.awt.event.MouseEvent.BUTTON1));
            var c = lyr.getConfig();
            System.out.println("drag lyrics corner -> box x=" + c.x + " y=" + c.y + " w=" + c.w + " h=" + c.h);
        });
        Thread.sleep(600);
        shot(f, out, "14-scene-editor");
        page(f, TopBar.Page.LIBRARY);
    }

    /** Opens Camera + Presentation in the scene editor, points its Presentation source at a library deck, slide 3. */
    private static void presentationSourceShot(MainFrame f, Path out, long presentationId) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var scenesF = MainFrame.class.getDeclaredField("scenes");
                scenesF.setAccessible(true);
                @SuppressWarnings("unchecked")
                var list = (java.util.List<com.jamc.worship.model.Scene>) scenesF.get(f);
                var cp = list.stream().filter(sc -> sc.getName().equals("Camera + Presentation")).findFirst().orElseThrow();
                var panelF = MainFrame.class.getDeclaredField("scenesPanel");
                panelF.setAccessible(true);
                ((com.jamc.worship.ui.components.ScenesPanel) panelF.get(f)).edit(cp.getId());
                var cardsF = MainFrame.class.getDeclaredField("pageCards");
                cardsF.setAccessible(true);
                var pagesF = MainFrame.class.getDeclaredField("pages");
                pagesF.setAccessible(true);
                ((java.awt.CardLayout) cardsF.get(f)).show((java.awt.Container) pagesF.get(f), "SCENE");
                openSceneEditor(f, cp);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            f.getContentPane().setSize(1600, 900);
            f.getContentPane().validate();
            @SuppressWarnings("unchecked")
            javax.swing.JList<com.jamc.worship.model.Source> sources = find(f.getContentPane(), javax.swing.JList.class, l ->
                    l.getModel().getSize() > 0 && l.getModel().getElementAt(0) instanceof com.jamc.worship.model.Source);
            for (int i = 0; i < sources.getModel().getSize(); i++) {
                var s = sources.getModel().getElementAt(i);
                if (s.getType() == com.jamc.worship.model.SourceType.PRESENTATION) {
                    s.getConfig().presentationId = presentationId;
                    s.getConfig().slideIndex = 2;
                    sources.clearSelection();
                    sources.setSelectedIndex(i);
                }
            }
        });
        Thread.sleep(2000);
        shot(f, out, "14b-presentation-source");
        page(f, TopBar.Page.LIBRARY);
    }

    /**
     * IMAGE SLIDESHOW: three library images as one picture-in-picture source in a copy of Worship, on
     * Program (it advances by itself; lyrics and the rest stay), then its properties in the scene editor.
     */
    private static void slideshowShots(MainFrame f, com.jamc.worship.app.AppContext ctx, Path out) throws Exception {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        int n = 0;
        for (java.awt.Color color : new java.awt.Color[]{new java.awt.Color(0xDC2626), new java.awt.Color(0x16A34A), new java.awt.Color(0x7C3AED)}) {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(640, 360, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(color);
            g.fillRect(0, 0, 640, 360);
            g.setColor(java.awt.Color.WHITE);
            g.setFont(new java.awt.Font("Dialog", java.awt.Font.BOLD, 72));
            g.drawString("ANNOUNCEMENT " + (++n), 30, 200);
            g.dispose();
            Path file = out.resolve("announcement-" + n + ".png");
            javax.imageio.ImageIO.write(img, "png", file.toFile());
            ids.add(ctx.mediaService().importFile(file, true, com.jamc.worship.model.MediaCategory.IMAGE).getId());
        }
        var worship = ctx.sceneService().scenes().stream().filter(sc -> sc.getName().equals("Worship")).findFirst().orElseThrow();
        var scene = ctx.sceneService().duplicate(worship.getId());
        scene.setName("Sunday Announcements");
        var show = new com.jamc.worship.model.Source("Sunday Announcements", com.jamc.worship.model.SourceType.SLIDESHOW);
        show.getConfig().uid = java.util.UUID.randomUUID().toString();
        var c = show.getConfig();
        c.imageIds = ids;
        c.slideSeconds = 2;
        c.fit = com.jamc.worship.model.FitMode.FIT;
        c.x = 0.6;
        c.y = 0.58;
        c.w = 0.36;
        c.h = 0.36;
        c.slideBackground = "black";
        scene.getSources().addFirst(show);
        ctx.sceneService().save(scene);
        call(f, "loadScenes");
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            try {
                var pf = MainFrame.class.getDeclaredField("program");
                pf.setAccessible(true);
                // like a scene click + TAKE: the scene as the app loaded it from the database
                var scenesF = MainFrame.class.getDeclaredField("scenes");
                scenesF.setAccessible(true);
                @SuppressWarnings("unchecked")
                var loaded = ((java.util.List<com.jamc.worship.model.Scene>) scenesF.get(f)).stream()
                        .filter(sc -> sc.getId().equals(scene.getId())).findFirst().orElseThrow();
                ((com.jamc.worship.output.ProgramOutput) pf.get(f)).setScene(loaded, false);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        page(f, TopBar.Page.LIBRARY);
        Thread.sleep(800);
        shot(f, out, "17-slideshow-program");
        Thread.sleep(2600);
        shot(f, out, "17b-slideshow-next");
        SwingUtilities.invokeAndWait(() -> {
            try {
                var sf = MainFrame.class.getDeclaredField("slideshows");
                sf.setAccessible(true);
                var shows = (com.jamc.worship.output.Slideshows) sf.get(f);
                var pf = MainFrame.class.getDeclaredField("program");
                pf.setAccessible(true);
                var live = ((com.jamc.worship.output.ProgramOutput) pf.get(f)).scene().getSources().getFirst();
                System.out.println("slideshow on Program: " + shows.status(live));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        // its properties in the scene editor
        SwingUtilities.invokeAndWait(() -> {
            try {
                var panelF = MainFrame.class.getDeclaredField("scenesPanel");
                panelF.setAccessible(true);
                ((com.jamc.worship.ui.components.ScenesPanel) panelF.get(f)).edit(scene.getId());
                var cardsF = MainFrame.class.getDeclaredField("pageCards");
                cardsF.setAccessible(true);
                var pagesF = MainFrame.class.getDeclaredField("pages");
                pagesF.setAccessible(true);
                ((java.awt.CardLayout) cardsF.get(f)).show((java.awt.Container) pagesF.get(f), "SCENE");
                openSceneEditor(f, scene);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(1200);
        SwingUtilities.invokeAndWait(() -> {
            f.getContentPane().setSize(1600, 900);
            f.getContentPane().validate();
            @SuppressWarnings("unchecked")
            javax.swing.JList<com.jamc.worship.model.Source> sources = find(f.getContentPane(), javax.swing.JList.class, l ->
                    l.getModel().getSize() > 0 && l.getModel().getElementAt(0) instanceof com.jamc.worship.model.Source);
            sources.clearSelection();
            sources.setSelectedIndex(0);
        });
        Thread.sleep(1500);
        shot(f, out, "17c-slideshow-props");
        // the properties column on its own, tall enough to show every slideshow setting
        SwingUtilities.invokeAndWait(() -> {
            try {
                var panelF = MainFrame.class.getDeclaredField("scenesPanel");
                panelF.setAccessible(true);
                var propsF = com.jamc.worship.ui.components.ScenesPanel.class.getDeclaredField("props");
                propsF.setAccessible(true);
                javax.swing.JComponent props = (javax.swing.JComponent) propsF.get(panelF.get(f));
                java.awt.Dimension pref = props.getPreferredSize();
                props.setSize(Math.max(380, pref.width), pref.height);
                layoutTree(props);
                var img = new java.awt.image.BufferedImage(props.getWidth(), props.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                var g = img.createGraphics();
                g.setColor(com.jamc.worship.ui.theme.Palette.panelBackground());
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                props.printAll(g);
                g.dispose();
                javax.imageio.ImageIO.write(img, "png", out.resolve("17d-slideshow-properties.png").toFile());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        page(f, TopBar.Page.LIBRARY);
    }

    private static void layoutTree(java.awt.Component c) {
        if (c instanceof java.awt.Container k) {
            k.doLayout();
            for (java.awt.Component child : k.getComponents()) {
                layoutTree(child);
            }
        }
    }

    private static <T> T find(java.awt.Container root, Class<T> type, java.util.function.Predicate<T> test) {
        for (java.awt.Component c : root.getComponents()) {
            if (type.isInstance(c) && test.test(type.cast(c))) {
                return type.cast(c);
            }
            if (c instanceof java.awt.Container cc) {
                T t = find(cc, type, test);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    /** OBS-style scenes: Preview first, TAKE, content takes keep the scene, separate layouts. */
    private static void sceneChecks(MainFrame f, AppContext ctx) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var scenesF = MainFrame.class.getDeclaredField("scenes");
                scenesF.setAccessible(true);
                @SuppressWarnings("unchecked")
                var list = (java.util.List<com.jamc.worship.model.Scene>) scenesF.get(f);
                var progF = MainFrame.class.getDeclaredField("program");
                progF.setAccessible(true);
                var prevF = MainFrame.class.getDeclaredField("previewState");
                prevF.setAccessible(true);
                var prog = (com.jamc.worship.output.ProgramOutput) progF.get(f);
                var prev = (com.jamc.worship.output.ProgramOutput) prevF.get(f);
                Method click = MainFrame.class.getDeclaredMethod("sceneClicked", com.jamc.worship.model.Scene.class);
                click.setAccessible(true);
                Method take = MainFrame.class.getDeclaredMethod("take");
                take.setAccessible(true);
                java.util.function.Function<String, com.jamc.worship.model.Scene> named =
                        n -> list.stream().filter(sc -> sc.getName().equals(n)).findFirst().orElseThrow();
                java.util.function.Function<com.jamc.worship.output.ProgramOutput, String> name =
                        p -> p.scene() == null ? "none" : p.scene().getName();
                System.out.println("scenes: " + list.stream().map(com.jamc.worship.model.Scene::getName).toList());
                click.invoke(f, named.apply("Camera + Lyrics"));
                System.out.println("click Camera + Lyrics -> preview " + name.apply(prev) + " / program " + name.apply(prog));
                take.invoke(f);
                System.out.println("TAKE -> program " + name.apply(prog));
                strip(f).select(1);
                System.out.println("click lyrics card -> program " + name.apply(prog) + " showing " + prog.content().slide().getLabel());
                ctx.config().set("scenes.separatePreviewLayout", "true");
                click.invoke(f, named.apply("Worship"));
                strip(f).select(3);
                System.out.println("separate layouts, Worship clicked, lyrics card 4 -> preview " + name.apply(prev) + " / program "
                        + name.apply(prog) + " showing " + prog.content().slide().getLabel() + " (preview shows "
                        + prev.content().slide().getLabel() + ")");
                ctx.config().set("scenes.separatePreviewLayout", "false");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static <T> T find(java.awt.Container root, Class<T> type) {
        for (java.awt.Component c : root.getComponents()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
            if (c instanceof java.awt.Container cc) {
                T t = find(cc, type);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static String previewLabel(MainFrame f) {
        try {
            var field = MainFrame.class.getDeclaredField("previewState");
            field.setAccessible(true);
            var c = ((com.jamc.worship.output.ProgramOutput) field.get(f)).content();
            return c == null ? "nothing" : c.slide().getLabel();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String programCase(MainFrame f) {
        return caseOf(f, "program");
    }

    private static String previewCase(MainFrame f) {
        try {
            var field = MainFrame.class.getDeclaredField("previewStyle");
            field.setAccessible(true);
            var s = (com.jamc.worship.model.SlideStyle) field.get(f);
            return s == null ? "none" : s.textTransform().name();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String caseOf(MainFrame f, String name) {
        try {
            var field = MainFrame.class.getDeclaredField(name);
            field.setAccessible(true);
            var c = ((com.jamc.worship.output.ProgramOutput) field.get(f)).content();
            return c == null ? "nothing" : c.style().textTransform().name();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static com.jamc.worship.ui.components.SlideThumbnailStrip strip(MainFrame f) {
        try {
            var field = MainFrame.class.getDeclaredField("thumbnails");
            field.setAccessible(true);
            return (com.jamc.worship.ui.components.SlideThumbnailStrip) field.get(f);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A sermon-style deck: colored backgrounds, title, bullets, a shape. No real content. */
    static Path sampleDeck(Path file, int slides) throws java.io.IOException {
        java.awt.Color[] bgs = {new java.awt.Color(0x0F2A4A), new java.awt.Color(0x3B1D4A), new java.awt.Color(0x14532D),
                new java.awt.Color(0x7C2D12), new java.awt.Color(0x1E293B)};
        try (var show = new org.apache.poi.xslf.usermodel.XMLSlideShow()) {
            show.setPageSize(new java.awt.Dimension(960, 540));
            var layout = show.getSlideMasters().getFirst().getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE_AND_CONTENT);
            for (int i = 1; i <= slides; i++) {
                var slide = show.createSlide(layout);
                slide.getBackground().setFillColor(bgs[i % bgs.length]);
                var title = slide.getPlaceholder(0);
                title.setText(i == 1 ? "Sample Sermon Title" : "Point " + (i - 1));
                title.getTextParagraphs().getFirst().getTextRuns().getFirst().setFontColor(java.awt.Color.WHITE);
                var body = slide.getPlaceholder(1);
                body.clearText();
                for (String line : new String[]{"First placeholder bullet", "Second placeholder bullet", "Third line"}) {
                    var p = body.addNewTextParagraph();
                    var r = p.addNewTextRun();
                    r.setText(line);
                    r.setFontColor(new java.awt.Color(0xE2E8F0));
                }
                var shape = slide.createAutoShape();
                shape.setShapeType(org.apache.poi.sl.usermodel.ShapeType.ELLIPSE);
                shape.setAnchor(new java.awt.Rectangle(800, 400, 110, 110));
                shape.setFillColor(new java.awt.Color(0xF59E0B));
            }
            try (var outStream = java.nio.file.Files.newOutputStream(file)) {
                show.write(outStream);
            }
        }
        return file;
    }

    private static void category(MainFrame f, Sidebar.Category c) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Method m = MainFrame.class.getDeclaredMethod("showPage", TopBar.Page.class);
                m.setAccessible(true);
                m.invoke(f, TopBar.Page.LIBRARY);
                Method s = MainFrame.class.getDeclaredMethod("showCategory", Sidebar.Category.class);
                s.setAccessible(true);
                s.invoke(f, c);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(1500);
    }

    /**
     * The required navigation sequence: the Library sidebar belongs only to the LIBRARY page. Presses the
     * main navigation buttons and Library categories programmatically and prints what is showing.
     */
    private static void navigationTest(MainFrame f, Path out) throws Exception {
        navCheck(f, out, 1, "start");
        navClick(f, TopBar.Page.LIBRARY);
        navCheck(f, out, 2, "library");
        navCategory(f, Sidebar.Category.VIDEOS);
        navCheck(f, out, 3, "videos");
        navCategory(f, Sidebar.Category.AUDIO);
        navCheck(f, out, 4, "audio");
        navClick(f, TopBar.Page.OUTPUT);
        navCheck(f, out, 5, "output");
        navClick(f, TopBar.Page.SCENE);
        navCheck(f, out, 6, "scenes");
        navClick(f, TopBar.Page.SERVICE);
        navCheck(f, out, 7, "service");
        navClick(f, TopBar.Page.LIBRARY);
        navCheck(f, out, 8, "library-again");
        navCategory(f, Sidebar.Category.PRESENTATIONS);
        navCheck(f, out, 9, "presentations");
        navCategory(f, Sidebar.Category.SONGS);
    }

    /** Presses a MAIN NAVIGATION button (doClick: no real mouse input). */
    private static void navClick(MainFrame f, TopBar.Page p) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var tf = MainFrame.class.getDeclaredField("topBar");
                tf.setAccessible(true);
                var bf = TopBar.class.getDeclaredField("navButtons");
                bf.setAccessible(true);
                @SuppressWarnings("unchecked")
                var buttons = (java.util.Map<TopBar.Page, javax.swing.JToggleButton>) bf.get(tf.get(f));
                buttons.get(p).doClick();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(900);
    }

    /** Selects a Library category the way a click in the Library sidebar does. */
    private static void navCategory(MainFrame f, Sidebar.Category c) throws Exception {
        SwingUtilities.invokeAndWait(() -> ((Sidebar) field(f, "sidebar")).selectCategory(c));
        Thread.sleep(900);
    }

    private static void navCheck(MainFrame f, Path out, int step, String label) throws Exception {
        String[] showing = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            f.validate();
            showing[0] = java.util.stream.Stream.of("sidebar", "songLibrary", "videoLibrary", "audioLibrary",
                            "presentationLibrary", "servicesPanel", "scenesHome")
                    .filter(n -> shown((java.awt.Component) field(f, n)))
                    .collect(java.util.stream.Collectors.joining(", "));
        });
        System.out.println("NAV " + step + " " + label + " -> showing: " + showing[0]);
        shot(f, out, "20-nav-" + step + "-" + label);
    }

    private static Object field(MainFrame f, String name) {
        try {
            var fl = MainFrame.class.getDeclaredField(name);
            fl.setAccessible(true);
            return fl.get(f);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when the component and every parent up to the window are visible (hidden CardLayout cards are not). */
    private static boolean shown(java.awt.Component c) {
        for (java.awt.Component p = c; p != null && !(p instanceof java.awt.Window); p = p.getParent()) {
            if (!p.isVisible()) {
                return false;
            }
        }
        return c.getParent() != null && c.getWidth() > 0;
    }

    private static void page(MainFrame f, TopBar.Page p) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Method m = MainFrame.class.getDeclaredMethod("showPage", TopBar.Page.class);
                m.setAccessible(true);
                m.invoke(f, p);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(1500);
    }

    private static void call(MainFrame f, String name) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Method m = MainFrame.class.getDeclaredMethod(name);
                m.setAccessible(true);
                m.invoke(f);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** The SCENE page with one scene open in its editor (as a click on its card does, without the Preview step). */
    private static void openSceneEditor(MainFrame f, com.jamc.worship.model.Scene scene) throws ReflectiveOperationException {
        var homeF = MainFrame.class.getDeclaredField("scenesHome");
        homeF.setAccessible(true);
        ((com.jamc.worship.ui.components.ScenesHomePanel) homeF.get(f)).showEditor(scene);
    }

    private static Object programScene(MainFrame f) {
        try {
            var p = MainFrame.class.getDeclaredField("program");
            p.setAccessible(true);
            return ((com.jamc.worship.output.ProgramOutput) p.get(f)).scene();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Presses the first showing button with this text (programmatically: no real mouse input). */
    private static boolean clickButton(java.awt.Container c, String text) {
        for (java.awt.Component child : c.getComponents()) {
            if (!child.isVisible()) {
                continue; // hidden cards (the frame itself is never shown, so isShowing() is always false)
            }
            if (child instanceof javax.swing.JButton b && text.equals(b.getText()) && b.isEnabled()) {
                b.doClick();
                return true;
            }
            if (child instanceof java.awt.Container sub && clickButton(sub, text)) {
                return true;
            }
        }
        return false;
    }

    private static void shot(MainFrame f, Path dir, String name) throws Exception {
        BufferedImage img = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            f.getContentPane().setSize(1600, 900);
            f.getContentPane().validate();
            Graphics2D g = img.createGraphics();
            f.getContentPane().printAll(g);
            g.dispose();
        });
        ImageIO.write(img, "png", dir.resolve(name + ".png").toFile());
        System.out.println("saved " + name);
    }
}
