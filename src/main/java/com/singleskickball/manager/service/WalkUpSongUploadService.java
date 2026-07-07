package com.singleskickball.manager.service;

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
import java.util.Locale;

/**
 * Stores uploaded walk-up song MP3 files and links them to players.
 *
 * The physical file is stored on the Railway volume. The Player table stores a
 * browser URL path such as:
 *   /uploads/walkup-songs/player-26-20260707-151500.mp3
 *
 * Keeping only the URL path in the database makes the code portable: the same
 * value works under the Railway URL and under app.singlessportssocial.com.
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
     * Saves an uploaded MP3 file for a player and updates the database field the
     * manager dashboard already reads when playing walk-up songs.
     */
    @Transactional
    public void uploadWalkUpSong(Long playerId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose an MP3 file to upload.");
        }

        String originalFilename = file.getOriginalFilename() == null ? "walkup.mp3" : file.getOriginalFilename();
        String lowerName = originalFilename.toLowerCase(Locale.ROOT);

        // Keep this intentionally strict for now. We can allow M4A later if we
        // want, but MP3 gives us the best cross-browser behavior.
        if (!lowerName.endsWith(".mp3")) {
            throw new IllegalArgumentException("Only .mp3 files are supported right now.");
        }

        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found."));

        Path walkupDirectory = uploadRoot.resolve("walkup-songs").normalize();
        ensureDirectoryExists(walkupDirectory);

        String safeFilename = buildSafeFilename(player, originalFilename);
        Path targetFile = walkupDirectory.resolve(safeFilename).normalize();

        // Protect against path traversal even though we generate the target name
        // ourselves. This keeps the method safe if it is changed later.
        if (!targetFile.startsWith(walkupDirectory)) {
            throw new IllegalArgumentException("Invalid upload file path.");
        }

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to save uploaded MP3 file.", ex);
        }

        player.setWalkUpSongFilePath("/uploads/walkup-songs/" + safeFilename);
        playerRepository.save(player);
    }

    /**
     * Clears only the file link. Artist/title remain because players may have
     * already entered those values and we do not want to overwrite them.
     */
    @Transactional
    public void clearWalkUpSongFile(Long playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found."));

        player.setWalkUpSongFilePath(null);
        playerRepository.save(player);
    }

    private void ensureDirectoryExists(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create upload directory: " + directory, ex);
        }
    }

    private String buildSafeFilename(Player player, String originalFilename) {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP);
        String displayName = player.getNickname() != null && !player.getNickname().isBlank()
                ? player.getNickname()
                : player.getName();

        String safePlayerName = displayName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");

        if (safePlayerName.isBlank()) {
            safePlayerName = "player";
        }

        // The original extension was already validated to be .mp3.
        return "player-" + player.getId() + "-" + safePlayerName + "-" + timestamp + ".mp3";
    }
}
