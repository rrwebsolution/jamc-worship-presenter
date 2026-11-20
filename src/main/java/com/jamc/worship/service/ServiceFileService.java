package com.jamc.worship.service;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.model.LowerThird;
import com.jamc.worship.model.LyricsSourceType;
import com.jamc.worship.model.LyricsSync;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.MediaSourceType;
import com.jamc.worship.model.Presentation;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.SectionType;
import com.jamc.worship.model.Service;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideDeck;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongQuery;
import com.jamc.worship.model.SongSection;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.model.Theme;
import com.jamc.worship.util.FileUtils;
import com.jamc.worship.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A service saved as one file ({@code .livecat-service}) to open on another laptop.
 *
 * <p>Service items only point at library rows, whose ids differ on every laptop, so the file carries what the
 * items show: the songs' lyrics, slide decks, lower thirds, the picture / video / audio files, the PowerPoint
 * files (with their rendered slides as a fallback) and online video references. Opening the file adds what the
 * other library does not have yet (things already there, with the same content, are reused, never duplicated)
 * and creates the service. Bible items carry their passage; scenes are matched by name.
 *
 * <p>The file is a zip: {@code service.json} plus {@code files/...}. Blocking: call from a background worker.
 */
public class ServiceFileService {

    private static final Logger log = LoggerFactory.getLogger(ServiceFileService.class);
    public static final String EXTENSION = "livecat-service";
    static final String MANIFEST = "service.json";
    static final int FORMAT = 1;

    // ------------------------------------------------------------------ file contents

    public record Manifest(int format, String app, String created, String name, String playlist, String date,
                           String notes, List<ItemData> items, Map<String, SongData> songs,
                           Map<String, DeckData> decks, Map<String, MediaData> media,
                           Map<String, PresentationData> presentations, Map<String, LowerThirdData> lowerThirds,
                           Map<String, String> scenes) {
    }

    /** {@code ref} is a key into one of the manifest's maps (by type), or null. */
    public record ItemData(String type, String title, String payload, String tag, String ref) {
    }

    public record SongData(String title, String author, String copyright, String ccliNumber, String tags,
                           String arrangement, String fontFamily, Integer fontSize, StyleOverrides typography,
                           LyricsSync sync, String theme, String youtubeVideoId, String youtubeUrl, String sourceType,
                           String sourceProvider, String sourceReference, List<SectionData> sections) {
    }

    public record SectionData(String type, String label, String content) {
    }

    public record DeckData(String name, String theme, List<SlideData> slides) {
    }

    public record SlideData(String label, String text, String caption, StyleOverrides overrides) {
    }

    /** {@code file}: zip entry of a local file; null for online videos (YouTube / Pixabay). */
    public record MediaData(String name, String kind, String category, String sourceType, String path,
                            String providerId, String channelName, String thumbnailUrl, Long durationMs,
                            Integer width, Integer height, Long fileSize, String file) {
    }

    /**
     * {@code source}: zip entries of the original (one file, or the images of a slide-image folder);
     * {@code slides}: zip entries of the rendered slide pictures, used when the original cannot be rendered.
     */
    public record PresentationData(String name, String fileName, boolean folder, long sourceSize,
                                   List<String> source, List<String> slides) {
    }

    /** The lower third as stored ({@code json}), with its logo picture in the file when it has one. */
    public record LowerThirdData(String json, String logoFile) {
    }

    /** What happened while opening a file. */
    public record ImportResult(Service service, int added, int reused, List<String> warnings) {
    }

    /** What was written. */
    public record ExportResult(Path file, long bytes, List<String> warnings) {
    }

    // ------------------------------------------------------------------

    private final AppPaths paths;
    private final ServicePlanService plans;
    private final SongService songs;
    private final SlideDeckService decks;
    private final MediaService media;
    private final PresentationService presentations;
    private final SceneService scenes;
    private final ThemeService themes;

    public ServiceFileService(AppPaths paths, ServicePlanService plans, SongService songs, SlideDeckService decks,
                              MediaService media, PresentationService presentations, SceneService scenes,
                              ThemeService themes) {
        this.paths = paths;
        this.plans = plans;
        this.songs = songs;
        this.decks = decks;
        this.media = media;
        this.presentations = presentations;
        this.scenes = scenes;
        this.themes = themes;
    }

    /** "Sunday Service — Nov 22, 2026.livecat-service" with characters Windows refuses replaced. */
    public static String fileNameFor(Service s) {
        String base = s.getName() == null || s.getName().isBlank() ? "Service" : s.getName().strip();
        return base.replaceAll("[\\\\/:*?\"<>|]", "_") + "." + EXTENSION;
    }

    // ================================================================== save

    public ExportResult export(Service service, Path target, Consumer<String> progress) throws IOException {
        List<String> warnings = new ArrayList<>();
        Map<String, SongData> songMap = new LinkedHashMap<>();
        Map<String, DeckData> deckMap = new LinkedHashMap<>();
        Map<String, MediaData> mediaMap = new LinkedHashMap<>();
        Map<String, PresentationData> presMap = new LinkedHashMap<>();
        Map<String, LowerThirdData> ltMap = new LinkedHashMap<>();
        Map<String, String> sceneMap = new LinkedHashMap<>();
        /** zip entry -> file on this laptop */
        Map<String, Path> files = new LinkedHashMap<>();
        Map<Long, String> themeNames = new LinkedHashMap<>();
        themes.list().forEach(t -> themeNames.put(t.getId(), t.getName()));

        List<ItemData> items = new ArrayList<>();
        for (ServiceItem it : service.getItems()) {
            Long ref = it.getRefId();
            String key = null;
            if (ref != null) {
                key = it.getType().name().toLowerCase(Locale.ROOT) + "-" + ref;
                boolean found = switch (it.getType()) {
                    case SONG -> exportSong(ref, key, songMap, themeNames);
                    case SLIDE -> exportDeck(ref, key, deckMap, themeNames);
                    case IMAGE, VIDEO, AUDIO -> exportMedia(ref, key, mediaMap, files, warnings);
                    case PRESENTATION -> exportPresentation(ref, key, presMap, files, warnings);
                    case LOWER_THIRD -> exportLowerThird(ref, key, ltMap, files);
                    case SCENE -> exportScene(ref, key, sceneMap);
                    default -> true;
                };
                if (!found) {
                    warnings.add("\"" + it.getTitle() + "\" is no longer in the library and was saved without its content.");
                    key = null;
                }
            }
            items.add(new ItemData(it.getType().name(), it.getTitle(), it.getPayload(), it.getTag(), key));
        }

        Manifest manifest = new Manifest(FORMAT, "LiveCat Multimedia", Instant.now().toString(), service.getName(),
                service.getPlaylist(), service.getDate() == null ? null : service.getDate().toString(), service.getNotes(),
                items, songMap, deckMap, mediaMap, presMap, ltMap, sceneMap);

        Files.createDirectories(target.toAbsolutePath().getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(part))) {
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(Json.write(manifest).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            int n = 0;
            for (Map.Entry<String, Path> f : files.entrySet()) {
                progress.accept("Adding file " + (++n) + " of " + files.size() + ": " + f.getValue().getFileName() + "…");
                zip.putNextEntry(new ZipEntry(f.getKey()));
                try (InputStream in = Files.newInputStream(f.getValue())) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        long bytes = Files.size(target);
        log.info("Saved service '{}' to {} ({} items, {} files, {} bytes)", service.getName(), target, items.size(),
                files.size(), bytes);
        return new ExportResult(target, bytes, warnings);
    }

    private boolean exportSong(long id, String key, Map<String, SongData> out, Map<Long, String> themeNames) {
        if (out.containsKey(key)) {
            return true;
        }
        Optional<Song> found = songs.load(id);
        if (found.isEmpty()) {
            return false;
        }
        Song s = found.get();
        List<SectionData> sections = s.getSections().stream()
                .map(x -> new SectionData(x.getType().name(), x.getLabel(), x.getContent())).toList();
        out.put(key, new SongData(s.getTitle(), s.getAuthor(), s.getCopyright(), s.getCcliNumber(), s.getTags(),
                s.getArrangement(), s.getFontFamily(), s.getFontSize(), s.getTypography(), s.getSync(),
                themeNames.get(s.getThemeId()), s.getYoutubeVideoId(), s.getYoutubeUrl(),
                s.getSourceType() == null ? null : s.getSourceType().name(), s.getSourceProvider(), s.getSourceReference(),
                sections));
        return true;
    }

    private boolean exportDeck(long id, String key, Map<String, DeckData> out, Map<Long, String> themeNames) {
        if (out.containsKey(key)) {
            return true;
        }
        Optional<SlideDeck> found = decks.load(id);
        if (found.isEmpty()) {
            return false;
        }
        SlideDeck d = found.get();
        out.put(key, new DeckData(d.getName(), themeNames.get(d.getThemeId()), d.getSlides().stream()
                .map(s -> new SlideData(s.getLabel(), s.getText(), s.getCaption(), s.getOverrides())).toList()));
        return true;
    }

    private boolean exportMedia(long id, String key, Map<String, MediaData> out, Map<String, Path> files,
                                List<String> warnings) {
        if (out.containsKey(key)) {
            return true;
        }
        Optional<MediaItem> found = media.load(id);
        if (found.isEmpty()) {
            return false;
        }
        MediaItem m = found.get();
        boolean online = m.getSourceType() != MediaSourceType.LOCAL;
        String entry = null;
        if (!online) {
            Path file = media.resolve(m);
            if (Files.isRegularFile(file)) {
                entry = "files/" + key + "/" + file.getFileName();
                files.put(entry, file);
            } else {
                warnings.add("The file of \"" + m.getName() + "\" was not found on this laptop: " + file);
            }
        }
        out.put(key, new MediaData(m.getName(), m.getKind() == null ? null : m.getKind().name(),
                m.getCategory() == null ? null : m.getCategory().name(), m.getSourceType().name(),
                online ? m.getPath() : null, m.getYoutubeVideoId(), m.getChannelName(), m.getThumbnailUrl(),
                m.getDurationMs(), m.getWidth(), m.getHeight(), m.getFileSize(), entry));
        return true;
    }

    private boolean exportPresentation(long id, String key, Map<String, PresentationData> out, Map<String, Path> files,
                                       List<String> warnings) throws IOException {
        if (out.containsKey(key)) {
            return true;
        }
        Optional<Presentation> found = presentations.find(id);
        if (found.isEmpty()) {
            return false;
        }
        Presentation p = found.get();
        Path source = p.sourcePath() == null ? null : Path.of(p.sourcePath());
        List<String> sourceEntries = new ArrayList<>();
        boolean folder = source != null && Files.isDirectory(source);
        if (source != null && Files.isRegularFile(source)) {
            String entry = "files/" + key + "/source/" + source.getFileName();
            files.put(entry, source);
            sourceEntries.add(entry);
        } else if (folder) {
            try (Stream<Path> list = Files.list(source)) {
                for (Path f : list.filter(Files::isRegularFile).sorted().toList()) {
                    String entry = "files/" + key + "/source/" + f.getFileName();
                    files.put(entry, f);
                    sourceEntries.add(entry);
                }
            }
        }
        List<String> slideEntries = new ArrayList<>();
        for (PresentationService.PresentationSlide s : presentations.slides(p)) {
            if (s.image() != null && Files.isRegularFile(s.image())) {
                String entry = "files/" + key + "/slides/" + s.image().getFileName();
                files.put(entry, s.image());
                slideEntries.add(entry);
            }
        }
        if (sourceEntries.isEmpty() && slideEntries.isEmpty()) {
            warnings.add("The slides of \"" + p.name() + "\" were not found on this laptop.");
        } else if (sourceEntries.isEmpty()) {
            warnings.add("The original file of \"" + p.name() + "\" was not found; its slides were saved as pictures.");
        }
        out.put(key, new PresentationData(p.name(), p.sourceFileName(), folder, p.sourceSize(), sourceEntries, slideEntries));
        return true;
    }

    private boolean exportLowerThird(long id, String key, Map<String, LowerThirdData> out, Map<String, Path> files) {
        if (out.containsKey(key)) {
            return true;
        }
        Optional<LowerThird> found = scenes.lowerThird(id);
        if (found.isEmpty()) {
            return false;
        }
        LowerThird lt = found.get();
        String logoEntry = null;
        if (lt.getLogoPath() != null && !lt.getLogoPath().isBlank()) {
            Path logo = media.resolve(lt.getLogoPath());
            if (Files.isRegularFile(logo)) {
                logoEntry = "files/" + key + "/" + logo.getFileName();
                files.put(logoEntry, logo);
            }
        }
        out.put(key, new LowerThirdData(Json.write(lt), logoEntry));
        return true;
    }

    private boolean exportScene(long id, String key, Map<String, String> out) {
        if (out.containsKey(key)) {
            return true;
        }
        Optional<Scene> found = scenes.scene(id);
        found.ifPresent(s -> out.put(key, s.getName()));
        return found.isPresent();
    }

    // ================================================================== open

    /** Reads only the service name and item count, e.g. to show before opening. */
    public Manifest readManifest(Path file) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (MANIFEST.equals(e.getName())) {
                    Manifest m = Json.readOrNull(new String(zip.readAllBytes(), StandardCharsets.UTF_8), Manifest.class);
                    if (m == null || m.items() == null) {
                        break;
                    }
                    if (m.format() > FORMAT) {
                        throw new ValidationException("This service file was made by a newer version of LiveCat Multimedia."
                                + " Update the app on this laptop first.");
                    }
                    return m;
                }
            }
        } catch (java.util.zip.ZipException e) {
            throw new ValidationException("This is not a LiveCat service file.");
        }
        throw new ValidationException("This is not a LiveCat service file.");
    }

    /** Adds what the library is missing and creates the service (always a new one). */
    public ImportResult importFile(Path file, Consumer<String> progress) throws IOException {
        Manifest m = readManifest(file);
        Path work = Files.createTempDirectory("livecat-service-");
        try {
            progress.accept("Unpacking…");
            BackupService.extract(file, work);
            Counter count = new Counter();
            List<String> warnings = new ArrayList<>();
            Map<String, Long> ids = new LinkedHashMap<>();
            Map<String, Long> themeIds = new LinkedHashMap<>();
            for (Theme t : themes.list()) {
                themeIds.putIfAbsent(t.getName().toLowerCase(Locale.ROOT), t.getId());
            }
            // a folder of its own for this file's media / PowerPoints, so names never clash with the library's
            Path filesDir = paths.mediaDir().resolve("services")
                    .resolve(FileUtils.uniqueTarget(paths.mediaDir().resolve("services"), safe(m.name())).getFileName());

            for (Map.Entry<String, SongData> e : orEmpty(m.songs()).entrySet()) {
                progress.accept("Song: " + e.getValue().title() + "…");
                ids.put(e.getKey(), importSong(e.getValue(), themeIds, count));
            }
            for (Map.Entry<String, DeckData> e : orEmpty(m.decks()).entrySet()) {
                progress.accept("Slides: " + e.getValue().name() + "…");
                ids.put(e.getKey(), importDeck(e.getValue(), themeIds, count));
            }
            for (Map.Entry<String, MediaData> e : orEmpty(m.media()).entrySet()) {
                progress.accept("Media: " + e.getValue().name() + "…");
                Long id = importMedia(e.getValue(), work, filesDir, count, warnings);
                if (id != null) {
                    ids.put(e.getKey(), id);
                }
            }
            for (Map.Entry<String, PresentationData> e : orEmpty(m.presentations()).entrySet()) {
                progress.accept("Presentation: " + e.getValue().name() + " (making the slides)…");
                Long id = importPresentation(e.getValue(), work, filesDir, count, warnings);
                if (id != null) {
                    ids.put(e.getKey(), id);
                }
            }
            for (Map.Entry<String, LowerThirdData> e : orEmpty(m.lowerThirds()).entrySet()) {
                Long id = importLowerThird(e.getValue(), work, filesDir, count);
                if (id != null) {
                    ids.put(e.getKey(), id);
                }
            }
            List<Scene> sceneList = orEmpty(m.scenes()).isEmpty() ? List.of() : scenes.scenes();
            for (Map.Entry<String, String> e : orEmpty(m.scenes()).entrySet()) {
                Optional<Scene> match = sceneList.stream().filter(s -> s.getName() != null
                        && s.getName().equalsIgnoreCase(e.getValue())).findFirst();
                if (match.isPresent()) {
                    ids.put(e.getKey(), match.get().getId());
                } else {
                    warnings.add("Scene \"" + e.getValue() + "\" is not on this laptop (scenes are not carried in the file).");
                }
            }

            progress.accept("Creating the service…");
            Service s = new Service();
            s.setName(m.name());
            s.setPlaylist(m.playlist());
            try {
                s.setDate(m.date() == null ? null : LocalDate.parse(m.date()));
            } catch (RuntimeException ignored) {
                s.setDate(null);
            }
            s.setNotes(m.notes());
            List<ServiceItem> items = new ArrayList<>();
            for (ItemData d : m.items()) {
                ServiceItem.Type type = ServiceItem.Type.parse(d.type());
                ServiceItem item = new ServiceItem(type, d.title(), d.ref() == null ? null : ids.get(d.ref()));
                item.setPayload(d.payload());
                item.setTag(d.tag());
                items.add(item);
            }
            s.setItems(items);
            Service saved = plans.save(s);
            log.info("Opened service file {} as service #{} '{}' ({} added, {} reused, {} warnings)", file,
                    saved.getId(), saved.getName(), count.added, count.reused, warnings.size());
            return new ImportResult(saved, count.added, count.reused, warnings);
        } finally {
            BackupService.deleteTree(work);
        }
    }

    private long importSong(SongData d, Map<String, Long> themeIds, Counter count) {
        List<SectionData> sections = d.sections() == null ? List.of() : d.sections();
        for (Song candidate : songs.search(new SongQuery(d.title(), false, SongQuery.Sort.TITLE_ASC))) {
            if (!candidate.getTitle().equalsIgnoreCase(d.title())) {
                continue;
            }
            Optional<Song> full = songs.load(candidate.getId());
            if (full.isPresent() && sameLyrics(full.get().getSections(), sections)) {
                count.reused++;
                return candidate.getId();
            }
        }
        Song s = new Song();
        s.setTitle(d.title());
        s.setAuthor(d.author());
        s.setCopyright(d.copyright());
        s.setCcliNumber(d.ccliNumber());
        s.setTags(d.tags());
        s.setFontFamily(d.fontFamily());
        s.setFontSize(d.fontSize());
        s.setTypography(d.typography());
        s.setSync(d.sync());
        s.setThemeId(d.theme() == null ? null : themeIds.get(d.theme().toLowerCase(Locale.ROOT)));
        s.setYoutubeVideoId(d.youtubeVideoId());
        s.setYoutubeUrl(d.youtubeUrl());
        if (d.sourceType() != null) {
            try {
                s.setSourceType(LyricsSourceType.valueOf(d.sourceType()));
            } catch (IllegalArgumentException ignored) {
                // a newer kind: provenance only
            }
        }
        s.setSourceProvider(d.sourceProvider());
        s.setSourceReference(d.sourceReference());
        s.setSections(sections.stream().map(x -> new SongSection(sectionType(x.type()), x.label(),
                x.content() == null ? "" : x.content())).toList());
        Song saved = songs.save(s);
        if (d.arrangement() != null && !d.arrangement().isBlank()) {
            songs.setArrangement(saved.getId(), List.of(d.arrangement().split("\\|")));
        }
        count.added++;
        return saved.getId();
    }

    private long importDeck(DeckData d, Map<String, Long> themeIds, Counter count) {
        List<SlideData> slides = d.slides() == null ? List.of() : d.slides();
        String name = d.name() == null || d.name().isBlank() ? "Slides" : d.name().strip();
        String candidateName = name;
        for (int n = 2; ; n++) {
            String finalName = candidateName;
            Optional<SlideDeck> existing = decks.search(candidateName).stream()
                    .filter(x -> x.getName().equalsIgnoreCase(finalName)).findFirst();
            if (existing.isEmpty()) {
                break;
            }
            Optional<SlideDeck> full = decks.load(existing.get().getId());
            if (full.isPresent() && sameSlides(full.get().getSlides(), slides)) {
                count.reused++;
                return existing.get().getId();
            }
            candidateName = name + " (" + n + ")";
        }
        SlideDeck deck = new SlideDeck();
        deck.setName(candidateName);
        deck.setThemeId(d.theme() == null ? null : themeIds.get(d.theme().toLowerCase(Locale.ROOT)));
        deck.setSlides(slides.stream().map(x -> {
            Slide s = new Slide(x.label(), x.text(), x.caption());
            s.setOverrides(x.overrides());
            return s;
        }).toList());
        count.added++;
        return decks.save(deck).getId();
    }

    private Long importMedia(MediaData d, Path work, Path filesDir, Counter count, List<String> warnings) throws IOException {
        MediaSourceType source = MediaSourceType.parse(d.sourceType());
        MediaKind kind = MediaKind.parse(d.kind(), MediaKind.VIDEO);
        MediaCategory category = MediaCategory.parse(d.category(), MediaCategory.forKind(kind));
        if (source != MediaSourceType.LOCAL) {
            MediaItem m = new MediaItem();
            m.setName(d.name());
            m.setKind(kind);
            m.setCategory(category);
            m.setPath(d.path());
            m.setSourceType(source);
            m.setYoutubeVideoId(d.providerId());
            m.setChannelName(d.channelName());
            m.setThumbnailUrl(d.thumbnailUrl());
            m.setDurationMs(d.durationMs());
            m.setWidth(d.width());
            m.setHeight(d.height());
            m.setFileSize(d.fileSize());
            boolean had = media.find(List.of(), d.name(), false).stream().anyMatch(x -> x.getSourceType() == source
                    && Objects.equals(x.getYoutubeVideoId(), d.providerId()));
            MediaItem saved = media.addOnlineReference(m);
            if (had) {
                count.reused++;
            } else {
                count.added++;
            }
            return saved.getId();
        }
        Path extracted = d.file() == null ? null : work.resolve(d.file());
        if (extracted == null || !Files.isRegularFile(extracted)) {
            warnings.add("\"" + d.name() + "\": the file was missing when the service was saved.");
            return null;
        }
        long size = Files.size(extracted);
        for (MediaItem x : media.find(List.of(category), d.name(), false)) {
            if (x.getSourceType() == MediaSourceType.LOCAL && x.getKind() == kind && Objects.equals(x.getName(), d.name())
                    && media.exists(x) && Files.size(media.resolve(x)) == size) {
                count.reused++;
                return x.getId();
            }
        }
        Files.createDirectories(filesDir);
        Path dest = FileUtils.uniqueTarget(filesDir, extracted.getFileName().toString());
        Files.copy(extracted, dest);
        // already inside the data folder: stored as is (relative), not copied a second time
        MediaItem saved = media.importFile(dest, true, category, kind,
                d.width() != null && d.height() != null ? new java.awt.Dimension(d.width(), d.height()) : null);
        if (d.name() != null && !d.name().equals(saved.getName())) {
            media.rename(saved.getId(), d.name());
        }
        if (d.durationMs() != null) {
            media.recordDuration(saved.getId(), d.durationMs());
        }
        count.added++;
        return saved.getId();
    }

    private Long importPresentation(PresentationData d, Path work, Path filesDir, Counter count, List<String> warnings) {
        for (Presentation p : presentations.list(null)) {
            if (p.name().equalsIgnoreCase(d.name()) && p.sourceSize() == d.sourceSize() && presentations.isCached(p)) {
                count.reused++;
                return p.id();
            }
        }
        PresentationService.Progress quiet = new PresentationService.Progress() {
            @Override
            public void started(String name, int slideCount, String rendererName) {
            }

            @Override
            public void slideReady(PresentationService.PresentationSlide slide) {
            }
        };
        String folderName = FileUtils.uniqueTarget(filesDir, safe(d.name())).getFileName().toString();
        Path dir = filesDir.resolve(folderName);
        // the original PowerPoint (or slide pictures folder), made into slides on this laptop
        List<String> source = d.source() == null ? List.of() : d.source();
        if (!source.isEmpty()) {
            try {
                Path target = copyEntries(work, source, d.folder() ? dir.resolve("slides") : dir);
                Path toImport = d.folder() ? target : target.resolve(Path.of(source.get(0)).getFileName().toString());
                Presentation p = presentations.importFile(toImport, quiet);
                rename(p, d.name());
                count.added++;
                return p.id();
            } catch (IOException | RuntimeException e) {
                log.warn("Could not render presentation '{}' from its original; using its saved slide pictures", d.name(), e);
            }
        }
        List<String> slides = d.slides() == null ? List.of() : d.slides();
        if (!slides.isEmpty()) {
            try {
                Path target = copyEntries(work, slides, dir.resolve("rendered"));
                Presentation p = presentations.importFile(target, quiet);
                rename(p, d.name());
                if (!source.isEmpty()) {
                    warnings.add("\"" + d.name() + "\" could not be opened here, so its slides were added as pictures.");
                }
                count.added++;
                return p.id();
            } catch (IOException | RuntimeException e) {
                log.warn("Could not add the slide pictures of presentation '{}'", d.name(), e);
            }
        }
        warnings.add("Presentation \"" + d.name() + "\" could not be added.");
        return null;
    }

    private void rename(Presentation p, String name) {
        if (name != null && !name.isBlank() && !name.equals(p.name())) {
            presentations.rename(p.id(), name);
        }
    }

    private Long importLowerThird(LowerThirdData d, Path work, Path filesDir, Counter count) throws IOException {
        LowerThird lt = Json.readOrNull(d.json(), LowerThird.class);
        if (lt == null) {
            return null;
        }
        lt.setId(null);
        for (LowerThird x : scenes.lowerThirds()) {
            if (Objects.equals(x.getName(), lt.getName()) && Objects.equals(x.getTitle(), lt.getTitle())
                    && Objects.equals(x.getSubtitle(), lt.getSubtitle())) {
                count.reused++;
                return x.getId();
            }
        }
        lt.setLogoPath(null);
        if (d.logoFile() != null && Files.isRegularFile(work.resolve(d.logoFile()))) {
            Files.createDirectories(filesDir);
            Path dest = FileUtils.uniqueTarget(filesDir, work.resolve(d.logoFile()).getFileName().toString());
            Files.copy(work.resolve(d.logoFile()), dest);
            lt.setLogoPath(FileUtils.toStoredPath(paths, dest));
        }
        count.added++;
        return scenes.saveLowerThird(lt).getId();
    }

    // ------------------------------------------------------------------ helpers

    private static Path copyEntries(Path work, List<String> entries, Path dir) throws IOException {
        Files.createDirectories(dir);
        for (String e : entries) {
            Path from = work.resolve(e);
            if (Files.isRegularFile(from)) {
                Files.copy(from, dir.resolve(from.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return dir;
    }

    static boolean sameLyrics(List<SongSection> have, List<SectionData> want) {
        if (have.size() != want.size()) {
            return false;
        }
        for (int i = 0; i < have.size(); i++) {
            if (!norm(have.get(i).getContent()).equals(norm(want.get(i).content()))) {
                return false;
            }
        }
        return true;
    }

    static boolean sameSlides(List<Slide> have, List<SlideData> want) {
        if (have.size() != want.size()) {
            return false;
        }
        for (int i = 0; i < have.size(); i++) {
            if (!norm(have.get(i).getText()).equals(norm(want.get(i).text()))) {
                return false;
            }
        }
        return true;
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").strip();
    }

    private static SectionType sectionType(String v) {
        try {
            return v == null ? SectionType.VERSE : SectionType.valueOf(v);
        } catch (IllegalArgumentException e) {
            return SectionType.VERSE;
        }
    }

    private static String safe(String name) {
        String n = name == null || name.isBlank() ? "Service" : name.strip();
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static <K, V> Map<K, V> orEmpty(Map<K, V> m) {
        return m == null ? Map.of() : m;
    }

    private static final class Counter {
        int added;
        int reused;
    }
}
