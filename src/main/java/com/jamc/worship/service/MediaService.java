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
        Path p = Path.of(storedPath);
        return p.isAbsolute() ? p : paths.root().resolve(p);
    }

    public boolean exists(MediaItem item) {
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
