package com.singleskickball.manager.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Manages the general-purpose MP3 files played while teams switch sides.
 *
 * <p>Files live under {@code <uploads-root>/songs} and are served through the
 * existing {@code /uploads/**} resource mapping. Playback order is alphabetical
 * by filename. Each game keeps an in-memory index so the next at-bat change uses
 * the next song instead of replaying the previous one.</p>
 *
 * <p>The index intentionally is not stored in the database. It is temporary
 * game-session state and safely resets when the application restarts.</p>
 */
@Service
public class BetweenAtBatSongService {

    private final Path songsDirectory;

    /** Next alphabetical song index for each game week. */
    private final ConcurrentHashMap<Long, AtomicInteger> nextIndexByGameWeek =
            new ConcurrentHashMap<>();

    public BetweenAtBatSongService(
            @Value("${app.uploads.root-path:${APP_UPLOADS_ROOT_PATH:/app/uploads}}")
            String uploadRoot) {
        this.songsDirectory = Path.of(uploadRoot)
                .toAbsolutePath()
                .normalize()
                .resolve("songs")
                .normalize();
    }

    /**
     * Returns uploaded filenames in case-insensitive alphabetical order.
     */
    public List<String> listSongs() {
        ensureDirectoryExists();

        try (Stream<Path> files = Files.list(songsDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(this::isMp3Filename)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read the between-at-bat songs folder.", ex);
        }
    }

    /**
     * Returns the next song URL for this game and advances its in-memory index.
     *
     * <p>If songs are added or removed, modulo arithmetic automatically keeps
     * the index inside the current list.</p>
     */
    public String nextSongUrl(Long gameWeekId) {
        List<String> songs = listSongs();
        if (songs.isEmpty()) {
            return null;
        }

        AtomicInteger counter = nextIndexByGameWeek.computeIfAbsent(
                gameWeekId,
                ignored -> new AtomicInteger(0));

        int index = Math.floorMod(counter.getAndIncrement(), songs.size());
        return "/uploads/songs/" + songs.get(index);
    }

    /**
     * Uploads or replaces an MP3 using a filesystem-safe version of its name.
     */
    public String upload(MultipartFile file) {
        validateMp3(file);
        ensureDirectoryExists();

        String safeFilename = sanitizeFilename(file.getOriginalFilename());
        Path target = songsDirectory.resolve(safeFilename).normalize();
        ensureInsideSongsDirectory(target);

        try (InputStream input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to save the MP3 file.", ex);
        }

        return safeFilename;
    }

    /**
     * Deletes one managed song. The filename is validated against traversal.
     */
    public void delete(String filename) {
        String safeFilename = sanitizeFilename(filename);
        Path target = songsDirectory.resolve(safeFilename).normalize();
        ensureInsideSongsDirectory(target);

        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to delete " + safeFilename + ".", ex);
        }
    }

    /** Creates the persistent Railway/local folder when needed. */
    private void ensureDirectoryExists() {
        try {
            Files.createDirectories(songsDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create the between-at-bat songs folder.", ex);
        }
    }

    private void validateMp3(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose an MP3 file to upload.");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !isMp3Filename(filename)) {
            throw new IllegalArgumentException("Only .mp3 files are supported.");
        }
    }

    private boolean isMp3Filename(String filename) {
        return filename != null
                && filename.toLowerCase(Locale.ROOT).endsWith(".mp3");
    }

    /**
     * Keeps letters, numbers, spaces, dots, dashes, and underscores. Other
     * characters become underscores so the URL remains safe and predictable.
     */
    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("A filename is required.");
        }

        String basename = Path.of(original).getFileName().toString().trim();
        String safe = basename.replaceAll("[^A-Za-z0-9._ -]", "_");

        if (!isMp3Filename(safe)) {
            throw new IllegalArgumentException("Only .mp3 files are supported.");
        }

        return safe;
    }

    private void ensureInsideSongsDirectory(Path target) {
        if (!target.startsWith(songsDirectory)) {
            throw new IllegalArgumentException("Invalid song filename.");
        }
    }
}
