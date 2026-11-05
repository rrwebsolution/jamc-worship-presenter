package com.jamc.worship.service;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.database.MigrationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup and restore of the library (database, settings and themes, optionally media files).
 *
 * <p>The database is copied with {@code VACUUM INTO}, which gives a consistent snapshot even while
 * the application is running. Restore always writes a safety backup of the current data first and
 * validates the backup's database before anything is replaced.
 */
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String DB_ENTRY = "data/worship.db";
    private static final String SETTINGS_ENTRY = "config/settings.properties";

    /** Progress messages for the UI. */
    @FunctionalInterface
    public interface Progress {
        void update(String message);
    }

    private final AppPaths paths;
    private final DatabaseManager db;

    public BackupService(AppPaths paths, DatabaseManager db) {
        this.paths = paths;
        this.db = db;
    }

    public Path defaultBackupFile() {
        return paths.backupsDir().resolve("LiveCat-Backup-" + LocalDateTime.now().format(STAMP) + ".zip");
    }

    /** Writes a backup zip. Themes, songs, services, scenes and Bible text are all in the database. */
    public Path backup(Path target, boolean includeMedia, Progress progress) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path snapshot = Files.createTempFile("jamc-db-", ".db");
        Files.delete(snapshot); // VACUUM INTO requires a non-existent file
        try {
            progress.update("Copying database…");
            db.query(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("VACUUM INTO ?")) {
                    ps.setString(1, snapshot.toString());
                    ps.execute();
                }
                return null;
            });
            Path tmpZip = target.resolveSibling(target.getFileName() + ".part");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmpZip))) {
                addFile(zip, snapshot, DB_ENTRY);
                if (Files.exists(paths.settingsFile())) {
                    addFile(zip, paths.settingsFile(), SETTINGS_ENTRY);
                }
                zip.putNextEntry(new ZipEntry("backup-info.txt"));
                zip.write(("LiveCat Multimedia backup\ncreated=" + Instant.now() + "\nschema="
                        + MigrationManager.latestVersion() + "\nmedia=" + includeMedia + "\n").getBytes());
                zip.closeEntry();
                if (includeMedia && Files.isDirectory(paths.mediaDir())) {
                    try (Stream<Path> files = Files.walk(paths.mediaDir())) {
                        List<Path> list = files.filter(Files::isRegularFile).toList();
                        int i = 0;
                        for (Path f : list) {
                            progress.update("Adding media " + (++i) + " of " + list.size() + "…");
                            addFile(zip, f, paths.root().relativize(f).toString().replace('\\', '/'));
                        }
                    }
                }
            }
            Files.move(tmpZip, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Backup written to {} (media: {})", target, includeMedia);
            return target;
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    /**
     * Restores a backup zip. The current data is backed up to {@code backups/safety-before-restore-*.zip}
     * first. The application must be restarted afterwards.
     *
     * @return the safety backup file
     */
    public Path restore(Path backupZip, Progress progress) throws IOException {
        Path work = Files.createTempDirectory("jamc-restore-");
        try {
            progress.update("Checking backup…");
            boolean hasMedia = extract(backupZip, work);
            Path restoredDb = work.resolve(DB_ENTRY);
            if (!Files.isRegularFile(restoredDb)) {
                throw new ValidationException("This file is not a LiveCat Multimedia backup (no database inside).");
            }
            int version = schemaVersion(restoredDb);
            if (version > MigrationManager.latestVersion()) {
                throw new ValidationException("This backup was made by a newer version of the application.");
            }

            progress.update("Saving a safety backup of the current data…");
            Path safety = backup(paths.backupsDir().resolve("safety-before-restore-" + LocalDateTime.now().format(STAMP) + ".zip"),
                    hasMedia, progress);

            progress.update("Restoring database…");
            Path dbFile = paths.databaseFile();
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-wal"));
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-shm"));
            Files.copy(restoredDb, dbFile, StandardCopyOption.REPLACE_EXISTING);

            Path settings = work.resolve(SETTINGS_ENTRY);
            if (Files.isRegularFile(settings)) {
                Files.copy(settings, paths.settingsFile(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (hasMedia) {
                progress.update("Restoring media…");
                Path mediaSrc = work.resolve("media");
                try (Stream<Path> files = Files.walk(mediaSrc)) {
                    for (Path f : files.filter(Files::isRegularFile).toList()) {
                        Path dest = paths.mediaDir().resolve(mediaSrc.relativize(f).toString());
                        Files.createDirectories(dest.getParent());
                        Files.copy(f, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            log.info("Restored backup {} (safety backup {})", backupZip, safety);
            return safety;
        } finally {
            deleteTree(work);
        }
    }

    /** Extracts the zip into {@code dir}, refusing entries that would escape it. Returns true if media was included. */
    static boolean extract(Path zipFile, Path dir) throws IOException {
        boolean media = false;
        Path root = dir.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Unsafe entry in backup: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                if (e.getName().startsWith("media/")) {
                    media = true;
                }
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out)) {
                    zip.transferTo(os);
                }
            }
        }
        return media;
    }

    private static int schemaVersion(Path dbFile) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(version), 0) FROM schema_version");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new ValidationException("The database in this backup could not be read: " + e.getMessage());
        }
    }

    private static void addFile(ZipOutputStream zip, Path file, String entry) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // temp files; best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
