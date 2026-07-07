package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.WalkUpSongAdminRow;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.repository.PlayerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Handles manager uploads for intro clips and walk-up song clips.
 *
 * Storage design:
 * - Root directory is configurable using APP_UPLOADS_ROOT_PATH.
 * - Railway production should mount a persistent volume at /app/uploads.
 * - Intro clips are saved as walkup-intros/{playerId}.mp3.
 * - Walk-up songs are saved as walkup-songs/player-{id}-{name}-{timestamp}.mp3.
 *
 * Database design:
 * - Walk-up songs update players.walk_up_song_file_path because the filename is
 *   generated and may change whenever a manager replaces the song.
 * - Intro clips do not need a new database column. The app finds them by the
 *   player-id naming convention.
 */
@Service
public class WalkUpSongUploadService {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PlayerRepository playerRepository;
    private final Path uploadRoot;

    public WalkUpSongUploadService(PlayerRepository playerRepository,
                                   @Value("${app.uploads.root-path:${APP_UPLOADS_ROOT_PATH:/app/uploads}}") String uploadsRootPath) {
        this.playerRepository = playerRepository;
        this.uploadRoot = Path.of(uploadsRootPath).toAbsolutePath().normalize();
    }

    /**
     * Builds the manager upload page rows with current intro/song status for
     * every active player.
     */
    public List<WalkUpSongAdminRow> getUploadRows() {
        return playerRepository.findByActiveTrueOrderByNameAsc()
                .stream()
                .map(this::toAdminRow)
                .toList();
    }

    /**
     * Saves an uploaded walk-up song MP3 file for a player and updates that
     * player's database row so the game dashboard can play it later.
     */
    @Transactional
    public void uploadWalkUpSong(Long playerId, MultipartFile file) {
        validateMp3(file);

        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found."));

        Path walkupDirectory = uploadRoot.resolve("walkup-songs").normalize();
        ensureDirectoryExists(walkupDirectory);

        String safeFilename = buildSafeWalkUpFilename(player);
        Path targetFile = walkupDirectory.resolve(safeFilename).normalize();
        ensureTargetWithinDirectory(targetFile, walkupDirectory);

        copyUpload(file, targetFile);

        player.setWalkUpSongFilePath("/uploads/walkup-songs/" + safeFilename);
        playerRepository.save(player);
    }

    /**
     * Saves a player intro MP3 using the player-id convention.
     *
     * Example:
     *   Player id 26 -> /app/uploads/walkup-intros/26.mp3
     */
    public void uploadIntro(Long playerId, MultipartFile file) {
        validateMp3(file);

        playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found."));

        Path introDirectory = uploadRoot.resolve("walkup-intros").normalize();
        ensureDirectoryExists(introDirectory);

        Path targetFile = introDirectory.resolve(playerId + ".mp3").normalize();
        ensureTargetWithinDirectory(targetFile, introDirectory);

        copyUpload(file, targetFile);
    }

    /**
     * Clears the walk-up song database link. If the linked file is one of our
     * managed upload files, we also attempt to delete it to avoid clutter.
     */
    @Transactional
    public void clearWalkUpSongFile(Long playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found."));

        deleteManagedWalkUpFileIfPresent(player.getWalkUpSongFilePath());
        player.setWalkUpSongFilePath(null);
        playerRepository.save(player);
    }

    /**
     * Deletes the convention-based intro MP3 if it exists.
     */
    public void clearIntro(Long playerId) {
        playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found."));

        Path introFile = uploadRoot
                .resolve("walkup-intros")
                .resolve(playerId + ".mp3")
                .normalize();

        try {
            Files.deleteIfExists(introFile);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to delete intro MP3 file.", ex);
        }
    }

    private WalkUpSongAdminRow toAdminRow(Player player) {
        WalkUpSongAdminRow row = new WalkUpSongAdminRow();
        row.setPlayerId(player.getId());
        row.setPlayerName(player.getName());
        row.setDisplayName(displayName(player));
        row.setRequestedArtist(player.getWalkUpSongArtist());
        row.setRequestedTitle(player.getWalkUpSongTitle());
        row.setRequestedSongLabel(requestedSongLabel(player));

        String introFilename = player.getId() + ".mp3";
        Path introPath = uploadRoot.resolve("walkup-intros").resolve(introFilename).normalize();
        boolean introExists = Files.exists(introPath) && Files.isRegularFile(introPath);
        row.setIntroFilename(introFilename);
        row.setIntroUploaded(introExists);
        row.setIntroUrl(introExists ? "/uploads/walkup-intros/" + introFilename : null);

        String walkUpPath = player.getWalkUpSongFilePath();
        boolean hasWalkUp = walkUpPath != null && !walkUpPath.isBlank();
        row.setWalkUpSongUploaded(hasWalkUp);
        row.setWalkUpSongUrl(hasWalkUp ? toBrowserUrl(walkUpPath) : null);
        row.setWalkUpSongFilename(hasWalkUp ? filenameFromPath(walkUpPath) : null);

        return row;
    }

    private void validateMp3(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose an MP3 file to upload.");
        }

        String originalFilename = file.getOriginalFilename() == null ? "audio.mp3" : file.getOriginalFilename();
        String lowerName = originalFilename.toLowerCase(Locale.ROOT);

        if (!lowerName.endsWith(".mp3")) {
            throw new IllegalArgumentException("Only .mp3 files are supported right now.");
        }
    }

    private void ensureDirectoryExists(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create upload directory: " + directory, ex);
        }
    }

    private void ensureTargetWithinDirectory(Path targetFile, Path directory) {
        if (!targetFile.startsWith(directory)) {
            throw new IllegalArgumentException("Invalid upload file path.");
        }
    }

    private void copyUpload(MultipartFile file, Path targetFile) {
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to save uploaded MP3 file.", ex);
        }
    }

    private void deleteManagedWalkUpFileIfPresent(String browserPath) {
        if (browserPath == null || !browserPath.startsWith("/uploads/walkup-songs/")) {
            return;
        }

        String filename = browserPath.substring("/uploads/walkup-songs/".length());
        Path file = uploadRoot.resolve("walkup-songs").resolve(filename).normalize();
        Path directory = uploadRoot.resolve("walkup-songs").normalize();

        if (!file.startsWith(directory)) {
            return;
        }

        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Clearing the DB link is more important than failing the request
            // because an old file could not be removed.
        }
    }

    private String buildSafeWalkUpFilename(Player player) {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP);
        String safePlayerName = displayName(player).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");

        if (safePlayerName.isBlank()) {
            safePlayerName = "player";
        }

        return "player-" + player.getId() + "-" + safePlayerName + "-" + timestamp + ".mp3";
    }

    private String displayName(Player player) {
        return player.getNickname() != null && !player.getNickname().isBlank()
                ? player.getNickname()
                : player.getName();
    }

    private String requestedSongLabel(Player player) {
        String artist = player.getWalkUpSongArtist();
        String title = player.getWalkUpSongTitle();

        boolean hasArtist = artist != null && !artist.isBlank();
        boolean hasTitle = title != null && !title.isBlank();

        if (!hasArtist && !hasTitle) {
            return "None entered yet";
        }
        if (hasArtist && hasTitle) {
            return artist + " - " + title;
        }
        return hasArtist ? artist : title;
    }

    private String toBrowserUrl(String filePath) {
        if (filePath.startsWith("http://") || filePath.startsWith("https://") || filePath.startsWith("/")) {
            return filePath;
        }
        return "/uploads/walkup-songs/" + filePath;
    }

    private String filenameFromPath(String filePath) {
        String normalized = filePath.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    }
}
