package com.jamc.worship.service;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.MediaSourceType;
import com.jamc.worship.repository.MediaRepository;
import com.jamc.worship.youtube.YouTubeRef;
import com.jamc.worship.youtube.YouTubeVideo;
import com.jamc.worship.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/** Media library use cases. Blocking: call from background workers. */
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaRepository repository;
    private final AppPaths paths;

    public MediaService(MediaRepository repository, AppPaths paths) {
        this.repository = repository;
        this.paths = paths;
    }

    public List<MediaItem> find(List<MediaCategory> categories, String text, boolean favoritesOnly) {
        return repository.find(categories, text, favoritesOnly);
    }

    public Optional<MediaItem> load(long id) {
        return repository.findById(id);
    }

    /**
     * Adds a file to the library.
     *
     * @param copy       copy the file into the application's media folder (recommended: the library
     *                   keeps working if the original is moved or a USB stick is removed); otherwise
     *                   the original location is referenced
     * @param category   library category, or null to derive it from the file type
     */
    public MediaItem importFile(Path file, boolean copy, MediaCategory category) throws IOException {
        return importFile(file, copy, category, null, null);
    }

    /**
     * Like {@link #importFile(Path, boolean, MediaCategory)}, with the kind decided by the caller (a file
     * with an unusual extension that VLC played is a video) and the picture size if already known.
     */
    public MediaItem importFile(Path file, boolean copy, MediaCategory category, MediaKind knownKind,
                                java.awt.Dimension videoSize) throws IOException {
        MediaKind kind = knownKind != null ? knownKind : MediaKind.of(file)
                .orElseThrow(() -> new ValidationException("\"" + file.getFileName() + "\" is not a supported image, video or audio file."));
        MediaCategory cat = category == null ? MediaCategory.forKind(kind) : category;
        if (cat == MediaCategory.BACKGROUND && kind == MediaKind.AUDIO) {
            throw new ValidationException("Audio files cannot be used as backgrounds.");
        }
        if (!Files.isRegularFile(file)) {
            throw new ValidationException("File not found: " + file);
        }
        String stored = copy ? FileUtils.importInto(paths, file, folderFor(kind, cat))
                : FileUtils.toStoredPath(paths, file);
        MediaItem m = new MediaItem();
        m.setName(stripExtension(file.getFileName().toString()));
        m.setKind(kind);
        m.setCategory(cat);
        m.setPath(stored);
        m.setCopied(copy);
        m.setFileSize(Files.size(file));
        if (kind == MediaKind.IMAGE) {
            readImageSize(file, m);
        } else if (kind == MediaKind.VIDEO && videoSize != null && videoSize.width > 0) {
            m.setWidth(videoSize.width);
            m.setHeight(videoSize.height);
        }
        MediaItem saved = repository.insert(m);
        log.info("Imported {} '{}' ({}, {})", kind, saved.getName(), copy ? "copied" : "referenced", stored);
        return saved;
    }

    /**
     * Adds a YouTube video to the Video Library as a reference (id, title, channel, duration, thumbnail).
     * Nothing is downloaded. Adding the same video again returns the existing item.
     */
    public MediaItem addYouTube(YouTubeVideo video) {
        if (video == null || !YouTubeRef.isVideoId(video.id())) {
            throw new ValidationException("That is not a YouTube video.");
        }
        Optional<MediaItem> existing = repository.findByYouTubeId(video.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        MediaItem m = new MediaItem();
        m.setName(video.title() == null || video.title().isBlank() ? video.id() : video.title().strip());
        m.setKind(MediaKind.VIDEO);
        m.setCategory(MediaCategory.VIDEO);
        m.setPath(video.path());
        m.setCopied(false);
        m.setDurationMs(video.durationMs());
        m.setSourceType(MediaSourceType.YOUTUBE);
        m.setYoutubeVideoId(video.id());
        m.setChannelName(video.channel());
        m.setThumbnailUrl(video.thumbnailUrl());
        MediaItem saved = repository.insert(m);
        log.info("Added YouTube video {} '{}' to the video library", video.id(), saved.getName());
        return saved;
    }

    /**
     * Adds a Pixabay video to the library as a reference (nothing is downloaded): the path is its 1080p (or best)
     * Pixabay address, streamed by VLC. {@code background} files it under Backgrounds, else under Videos. Adding the
     * same video again returns the item already there (moved to Backgrounds when asked).
     */
    public MediaItem addPixabay(com.jamc.worship.pixabay.PixabayVideo video, boolean background) {
        if (video == null || video.playback() == null) {
            throw new ValidationException("That Pixabay video has no playable version.");
        }
        String id = String.valueOf(video.id());
        Optional<MediaItem> existing = repository.findBySource(MediaSourceType.PIXABAY, id);
        if (existing.isPresent()) {
            MediaItem m = existing.get();
            if (background && m.getCategory() != MediaCategory.BACKGROUND) {
                repository.setCategory(m.getId(), MediaCategory.BACKGROUND);
                m.setCategory(MediaCategory.BACKGROUND);
            }
            return m;
        }
        var r = video.playback();
        MediaItem m = new MediaItem();
        m.setName(video.title());
        m.setKind(MediaKind.VIDEO);
        m.setCategory(background ? MediaCategory.BACKGROUND : MediaCategory.VIDEO);
        m.setPath(r.url());
        m.setCopied(false);
        m.setFileSize(r.size() > 0 ? r.size() : null);
        m.setDurationMs(video.durationSec() > 0 ? video.durationSec() * 1000L : null);
        m.setWidth(r.width() > 0 ? r.width() : null);
        m.setHeight(r.height() > 0 ? r.height() : null);
        m.setSourceType(MediaSourceType.PIXABAY);
        m.setYoutubeVideoId(id);
        m.setChannelName(video.user());
        m.setThumbnailUrl(r.thumbnail() != null ? r.thumbnail() : video.poster());
        MediaItem saved = repository.insert(m);
        com.jamc.worship.pixabay.PixabayThumbnails.remember(saved.getPath(), saved.getThumbnailUrl());
        log.info("Added Pixabay video {} '{}' to the library ({})", id, saved.getName(), saved.getCategory());
        return saved;
    }

    private final java.util.List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Told (on the thread that changed it) when an item is added outside a library list, e.g. a download that finished. */
    public void addChangeListener(Runnable l) {
        changeListeners.add(l);
    }

    /**
     * A Pixabay video the operator downloaded: a local video file (Library → Videos → Local) that plays offline, with
     * the Pixabay title and creator. The file stays where the download put it (data/pixabay/videos), so it is not
     * stored twice and the Pixabay address keeps playing it too. Downloading the same file again returns that item.
     */
    public MediaItem addDownloaded(Path file, String name, String creator, Integer width, Integer height, Long durationMs)
            throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new ValidationException("The downloaded video is missing.");
        }
        String stored = FileUtils.toStoredPath(paths, file);
        for (MediaItem m : repository.find(List.of(MediaCategory.VIDEO), null, false)) {
            if (stored.equals(m.getPath()) && m.getSourceType() == MediaSourceType.LOCAL) {
                return m;
            }
        }
        MediaItem m = new MediaItem();
        m.setName(name == null || name.isBlank() ? stripExtension(file.getFileName().toString()) : name);
        m.setKind(MediaKind.VIDEO);
        m.setCategory(MediaCategory.VIDEO);
        m.setPath(stored);
        m.setCopied(true); // in LiveCat's own data folder: never "missing" when a USB stick is removed
        m.setFileSize(Files.size(file));
        m.setWidth(width);
        m.setHeight(height);
        m.setDurationMs(durationMs);
        m.setSourceType(MediaSourceType.LOCAL);
        m.setChannelName(creator); // credit: "by X via Pixabay"
        MediaItem saved = repository.insert(m);
        log.info("Downloaded video '{}' is a local video ({})", saved.getName(), stored);
        changeListeners.forEach(Runnable::run);
        return saved;
    }

    /** Points a Pixabay item at the address Pixabay gives now (an old address stopped playing). */
    public void updatePixabayAddress(MediaItem item, com.jamc.worship.pixabay.PixabayVideo fresh) {
        if (item == null || !item.isPixabay() || fresh == null || fresh.playback() == null) {
            return;
        }
        String url = fresh.playback().url();
        if (!url.equals(item.getPath())) {
            repository.setPath(item.getId(), url);
            item.setPath(url);
            com.jamc.worship.pixabay.PixabayThumbnails.remember(url, item.getThumbnailUrl());
            log.info("Pixabay video {} has a new address", item.getYoutubeVideoId());
        }
    }

    /** The library item for this YouTube video, if it was added. */
    public Optional<MediaItem> findYouTube(String videoId) {
        return repository.findByYouTubeId(videoId);
    }

    public void rename(long id, String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Please enter a name.");
        }
        repository.rename(id, name.strip());
    }

    public void setCategory(long id, MediaCategory category) {
        repository.setCategory(id, category);
    }

    public void setFavorite(long id, boolean favorite) {
        repository.setFavorite(id, favorite);
    }

    public void recordDuration(long id, long durationMs) {
        repository.setDuration(id, durationMs);
    }

    /**
     * Removes the item from the library. A copied file is deleted from the media folder only when
     * {@code deleteFile} is set and no other library item uses it; referenced originals are never deleted.
     */
    public void delete(MediaItem item, boolean deleteFile) {
        repository.delete(item.getId());
        if (deleteFile && item.isCopied() && repository.countByPath(item.getPath()) == 0) {
            try {
                Files.deleteIfExists(resolve(item));
            } catch (IOException e) {
                log.warn("Could not delete media file {}", item.getPath(), e);
            }
        }
        log.info("Removed media #{} '{}' from the library", item.getId(), item.getName());
    }

    public Path resolve(MediaItem item) {
        return resolve(item.getPath());
    }

    /**
     * The file behind a stored path. A YouTube reference ({@code youtube:ID}) has no file: it resolves to a
     * path that never exists, so file checks fail safely and the YouTube player is used instead.
     */
    public Path resolve(String storedPath) {
        if (YouTubeRef.is(storedPath)) {
            return paths.dataDir().resolve("youtube").resolve("not-a-file").resolve(YouTubeRef.id(storedPath));
        }
        if (com.jamc.worship.media.OnlineMedia.isUrl(storedPath)) {
            // an online video (Pixabay) is streamed, never a file: a path that never exists, like YouTube's
            return paths.dataDir().resolve("online").resolve("not-a-file").resolve(Integer.toHexString(storedPath.hashCode()));
        }
        Path p = Path.of(storedPath);
        return p.isAbsolute() ? p : paths.root().resolve(p);
    }

    /** Where YouTube DOWNLOAD saves its .mp4 copies. */
    public Path youTubeDownloadsDir() {
        return paths.dataDir().resolve("youtube").resolve("videos");
    }

    /** A local video file that YouTube DOWNLOAD saved (data/youtube/videos): shown with a DOWNLOADED badge in Local. */
    public boolean isDownloadedYouTube(MediaItem item) {
        if (item == null || item.getKind() != MediaKind.VIDEO || item.isYouTube() || item.isPixabay()
                || item.getPath() == null || com.jamc.worship.media.OnlineMedia.isUrl(item.getPath())) {
            return false;
        }
        try {
            return resolve(item).toAbsolutePath().normalize().startsWith(youTubeDownloadsDir().toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false; // a stored path Windows does not accept
        }
    }

    /** The file is on this computer; an online (Pixabay) video always counts as there (its player reports failures). */
    public boolean exists(MediaItem item) {
        if (com.jamc.worship.media.OnlineMedia.isUrl(item.getPath())) {
            return true;
        }
        return Files.isRegularFile(resolve(item));
    }

    private Path folderFor(MediaKind kind, MediaCategory cat) {
        if (cat == MediaCategory.BACKGROUND) {
            return paths.backgroundsDir();
        }
        return switch (kind) {
            case IMAGE -> paths.imagesDir();
            case VIDEO -> paths.videosDir();
            case AUDIO -> paths.audioDir();
        };
    }

    private static void readImageSize(Path file, MediaItem m) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                return;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (readers.hasNext()) {
                ImageReader r = readers.next();
                try {
                    r.setInput(in);
                    m.setWidth(r.getWidth(0));
                    m.setHeight(r.getHeight(0));
                } finally {
                    r.dispose();
                }
            }
        } catch (IOException e) {
            log.debug("Could not read image size of {}", file, e);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
