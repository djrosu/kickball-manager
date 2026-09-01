package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.WalkUpSongInfo;
import com.singleskickball.manager.model.Gender;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.repository.PlayerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages the shared collection of short intro clips played before each
 * player's existing player-specific intro and walk-up song.
 *
 * <p>Files are stored under {@code <uploads-root>/random-intros}. Each MP3 has
 * a small sidecar properties file containing its male/female eligibility. This
 * avoids a database migration while still allowing the League Supervisor to
 * manage the collection through the application.</p>
 *
 * <h2>Per-game playback sequence</h2>
 *
 * <p>At game start, this service takes one snapshot of the complete intro
 * collection, shuffles it once, and then walks through that single randomized
 * order for the entire game. Gender eligibility is respected by skipping clips
 * that do not apply to the current batter.</p>
 *
 * <p>This is intentionally a <strong>global</strong> game sequence rather than
 * separate male/female decks. A unisex clip that was just used for a male batter
 * is therefore considered used when a female batter comes up, which prevents the
 * same small group of unisex clips from appearing over and over.</p>
 *
 * <p>When the current batter has no unused eligible clips left, a new shuffled
 * cycle is created. Immediate repeats are avoided whenever another eligible clip
 * exists. The sequence is temporary game-session state and intentionally is not
 * stored in the database.</p>
 */
@Service
public class RandomIntroService {

    private static final String META_SUFFIX = ".properties";

    private final Path introDirectory;
    private final PlayerRepository playerRepository;

    /**
     * One independent randomized playback sequence per active game week.
     */
    private final Map<Long, GameIntroSequence> sequencesByGameWeek =
            new ConcurrentHashMap<>();

    public RandomIntroService(
            PlayerRepository playerRepository,
            @Value("${app.uploads.root-path:${APP_UPLOADS_ROOT_PATH:/app/uploads}}")
            String uploadRoot) {
        this.playerRepository = playerRepository;
        this.introDirectory = Path.of(uploadRoot)
                .toAbsolutePath()
                .normalize()
                .resolve("random-intros")
                .normalize();
    }

    /**
     * Creates a fresh randomized sequence for a newly started or restarted game.
     *
     * <p>The current collection is snapshotted at this point. Clips uploaded
     * during an active game become part of that game after the sequence is reset
     * or lazily rebuilt following an application restart.</p>
     */
    public void prepareGame(Long gameWeekId) {
        if (gameWeekId == null) {
            return;
        }

        sequencesByGameWeek.put(
                gameWeekId,
                new GameIntroSequence(listIntros()));
    }

    /**
     * Removes temporary playback state when a game no longer needs it.
     */
    public void clearGame(Long gameWeekId) {
        if (gameWeekId != null) {
            sequencesByGameWeek.remove(gameWeekId);
        }
    }

    /**
     * Adds the next eligible shared intro from this game's randomized sequence
     * to the supplied batter DTO.
     *
     * <p>MALE players use clips marked male; FEMALE players use clips marked
     * female. Players with OTHER/null gender use clips marked for both, which
     * represent the intentionally unisex collection.</p>
     */
    public void applyNextIntro(Long gameWeekId, WalkUpSongInfo info) {
        if (gameWeekId == null || info == null || info.getPlayerId() == null) {
            return;
        }

        Player player =
                playerRepository.findById(info.getPlayerId()).orElse(null);
        if (player == null) {
            clearIntro(info);
            return;
        }

        /*
         * Lazy creation is a safety net for an application restart during an
         * active game or for older non-AJAX start paths. Normal starts explicitly
         * call prepareGame(...).
         */
        GameIntroSequence sequence =
                sequencesByGameWeek.computeIfAbsent(
                        gameWeekId,
                        ignored -> new GameIntroSequence(listIntros()));

        RandomIntroRow selected = sequence.nextFor(player.getGender());
        if (selected == null) {
            clearIntro(info);
            return;
        }

        info.setSharedIntroAudioUrl(selected.url());
        info.setSharedIntroPlayable(true);
    }

    /**
     * Backward-compatible method retained for any older caller.
     *
     * <p>Without a game id, this method cannot preserve a game sequence, so new
     * game-management code should always use {@link #applyNextIntro(Long,
     * WalkUpSongInfo)}.</p>
     */
    public void applyRandomIntro(WalkUpSongInfo info) {
        if (info == null || info.getPlayerId() == null) {
            return;
        }

        Player player =
                playerRepository.findById(info.getPlayerId()).orElse(null);
        if (player == null) {
            clearIntro(info);
            return;
        }

        List<RandomIntroRow> eligible = listIntros().stream()
                .filter(row -> isEligible(row, player.getGender()))
                .toList();

        if (eligible.isEmpty()) {
            clearIntro(info);
            return;
        }

        List<RandomIntroRow> shuffled = new ArrayList<>(eligible);
        Collections.shuffle(shuffled);
        RandomIntroRow selected = shuffled.get(0);

        info.setSharedIntroAudioUrl(selected.url());
        info.setSharedIntroPlayable(true);
    }

    /** Returns the complete collection alphabetically for the admin page. */
    public List<RandomIntroRow> listIntros() {
        ensureDirectory();

        try (Stream<Path> files = Files.list(introDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> isMp3(path.getFileName().toString()))
                    .map(this::toRow)
                    .sorted((left, right) ->
                            String.CASE_INSENSITIVE_ORDER.compare(
                                    left.filename(),
                                    right.filename()))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to read the random intro collection.", ex);
        }
    }

    /**
     * Uploads/replaces one MP3 and records its eligibility.
     *
     * @param male eligible for male players
     * @param female eligible for female players
     */
    public String upload(MultipartFile file, boolean male, boolean female) {
        if (!male && !female) {
            throw new IllegalArgumentException(
                    "Select Male, Female, or both for a unisex intro.");
        }
        validateMp3(file);
        ensureDirectory();

        String filename = sanitizeFilename(file.getOriginalFilename());
        Path target = introDirectory.resolve(filename).normalize();
        ensureInsideDirectory(target);

        try (InputStream input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to save the intro MP3.", ex);
        }

        writeMetadata(filename, male, female);
        return filename;
    }

    /** Updates eligibility without requiring the MP3 to be uploaded again. */
    public void updateEligibility(String filename, boolean male, boolean female) {
        if (!male && !female) {
            throw new IllegalArgumentException(
                    "Select Male, Female, or both for a unisex intro.");
        }

        String safeFilename = sanitizeFilename(filename);
        Path audioFile = introDirectory.resolve(safeFilename).normalize();
        ensureInsideDirectory(audioFile);

        if (!Files.isRegularFile(audioFile)) {
            throw new IllegalArgumentException("Random intro file not found.");
        }

        writeMetadata(safeFilename, male, female);
    }

    /** Deletes both the MP3 and its sidecar metadata. */
    public void delete(String filename) {
        String safeFilename = sanitizeFilename(filename);
        Path audioFile = introDirectory.resolve(safeFilename).normalize();
        Path metadataFile = metadataPath(safeFilename);
        ensureInsideDirectory(audioFile);
        ensureInsideDirectory(metadataFile);

        try {
            Files.deleteIfExists(audioFile);
            Files.deleteIfExists(metadataFile);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to delete " + safeFilename + ".", ex);
        }
    }

    private void clearIntro(WalkUpSongInfo info) {
        info.setSharedIntroPlayable(false);
        info.setSharedIntroAudioUrl(null);
    }

    private RandomIntroRow toRow(Path audioFile) {
        String filename = audioFile.getFileName().toString();
        Properties metadata = readMetadata(filename);

        boolean male = Boolean.parseBoolean(
                metadata.getProperty("male", "true"));
        boolean female = Boolean.parseBoolean(
                metadata.getProperty("female", "true"));

        return new RandomIntroRow(
                filename,
                "/uploads/random-intros/" + filename,
                male,
                female);
    }

    private static boolean isEligible(RandomIntroRow row, Gender gender) {
        if (gender == Gender.MALE) {
            return row.male();
        }
        if (gender == Gender.FEMALE) {
            return row.female();
        }

        // OTHER or missing gender receives only intentionally unisex clips.
        return row.male() && row.female();
    }

    private void writeMetadata(String filename, boolean male, boolean female) {
        Properties metadata = new Properties();
        metadata.setProperty("male", Boolean.toString(male));
        metadata.setProperty("female", Boolean.toString(female));

        Path path = metadataPath(filename);
        try (OutputStream output = Files.newOutputStream(path)) {
            metadata.store(output, "Random intro eligibility");
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to save intro eligibility.", ex);
        }
    }

    private Properties readMetadata(String filename) {
        Properties metadata = new Properties();
        Path path = metadataPath(filename);

        if (!Files.isRegularFile(path)) {
            // Existing manually copied MP3s default to unisex.
            metadata.setProperty("male", "true");
            metadata.setProperty("female", "true");
            return metadata;
        }

        try (InputStream input = Files.newInputStream(path)) {
            metadata.load(input);
            return metadata;
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to read eligibility for " + filename + ".", ex);
        }
    }

    private Path metadataPath(String filename) {
        return introDirectory.resolve(filename + META_SUFFIX).normalize();
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(introDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to create the random intro folder.", ex);
        }
    }

    private void validateMp3(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose an MP3 file to upload.");
        }
        if (!isMp3(file.getOriginalFilename())) {
            throw new IllegalArgumentException("Only .mp3 files are supported.");
        }
    }

    private boolean isMp3(String filename) {
        return filename != null
                && filename.toLowerCase(Locale.ROOT).endsWith(".mp3");
    }

    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("A filename is required.");
        }

        String basename = Path.of(original).getFileName().toString().trim();
        String safe = basename.replaceAll("[^A-Za-z0-9._ -]", "_");

        if (!isMp3(safe)) {
            throw new IllegalArgumentException("Only .mp3 files are supported.");
        }
        return safe;
    }

    private void ensureInsideDirectory(Path target) {
        if (!target.startsWith(introDirectory)) {
            throw new IllegalArgumentException("Invalid intro filename.");
        }
    }

    /**
     * Read-only row used directly by Thymeleaf.
     */
    public record RandomIntroRow(
            String filename,
            String url,
            boolean male,
            boolean female) {

        public boolean unisex() {
            return male && female;
        }
    }

    /**
     * One shuffled, global intro order for a game.
     *
     * <p>The old implementation maintained separate male/female/unisex decks.
     * That allowed the same unisex clip to be consumed independently by more
     * than one deck, which made some intros sound much more frequent than they
     * really were. This implementation keeps a single used set across every
     * batter in the game.</p>
     */
    private static final class GameIntroSequence {

        private final List<RandomIntroRow> sourceCollection;

        /** Current shuffled order for this cycle. */
        private List<RandomIntroRow> shuffledOrder;

        /** Filenames already played during the current cycle. */
        private final Set<String> usedThisCycle = new HashSet<>();

        /**
         * Where the next eligibility search begins. Keeping a cursor means the
         * game follows the randomized order rather than choosing independently
         * for every batter.
         */
        private int cursor = 0;

        /** Most recently played clip, used to prevent immediate repetition. */
        private String lastPlayedFilename;

        private GameIntroSequence(List<RandomIntroRow> collection) {
            this.sourceCollection = List.copyOf(collection);
            reshuffleForNewCycle();
        }

        /**
         * Returns the next unused eligible intro in the game's randomized order.
         *
         * <p>This method is synchronized because two manager devices can request
         * batter audio at nearly the same time.</p>
         */
        private synchronized RandomIntroRow nextFor(Gender gender) {
            if (sourceCollection.isEmpty()) {
                return null;
            }

            RandomIntroRow selected = findNextUnusedEligible(gender);

            if (selected == null) {
                /*
                 * Every clip eligible for this batter has already been used in
                 * the current cycle. Start a fresh randomized cycle.
                 */
                reshuffleForNewCycle();
                selected = findNextUnusedEligible(gender);
            }

            if (selected == null) {
                // There are simply no clips eligible for this player's gender.
                return null;
            }

            usedThisCycle.add(selected.filename());
            lastPlayedFilename = selected.filename();
            return selected;
        }

        /**
         * Searches forward from the current cursor through one complete pass of
         * the shuffled collection.
         */
        private RandomIntroRow findNextUnusedEligible(Gender gender) {
            int size = shuffledOrder.size();
            if (size == 0) {
                return null;
            }

            for (int offset = 0; offset < size; offset++) {
                int index = (cursor + offset) % size;
                RandomIntroRow candidate = shuffledOrder.get(index);

                if (usedThisCycle.contains(candidate.filename())) {
                    continue;
                }

                if (!RandomIntroService.isEligible(candidate, gender)) {
                    continue;
                }

                /*
                 * Avoid an immediate duplicate when another unused eligible
                 * choice still exists later in the sequence.
                 */
                if (lastPlayedFilename != null
                        && lastPlayedFilename.equals(candidate.filename())
                        && hasAlternativeEligible(gender, index)) {
                    continue;
                }

                cursor = (index + 1) % size;
                return candidate;
            }

            return null;
        }

        /**
         * Returns true when a different unused eligible clip is available.
         */
        private boolean hasAlternativeEligible(Gender gender, int excludedIndex) {
            for (int index = 0; index < shuffledOrder.size(); index++) {
                if (index == excludedIndex) {
                    continue;
                }

                RandomIntroRow candidate = shuffledOrder.get(index);
                if (usedThisCycle.contains(candidate.filename())) {
                    continue;
                }

                if (lastPlayedFilename != null
                        && lastPlayedFilename.equals(candidate.filename())) {
                    continue;
                }

                if (RandomIntroService.isEligible(candidate, gender)) {
                    return true;
                }
            }

            return false;
        }

        /**
         * Creates the next randomized cycle.
         *
         * <p>If possible, the first eligible clip of the new cycle will not be
         * the same file that ended the previous cycle. The exact batter gender
         * is not known here, so the nextFor(...) eligibility check provides the
         * final protection against a back-to-back repeat.</p>
         */
        private void reshuffleForNewCycle() {
            shuffledOrder = new ArrayList<>(sourceCollection);
            Collections.shuffle(shuffledOrder);
            usedThisCycle.clear();
            cursor = 0;

            if (shuffledOrder.size() > 1
                    && lastPlayedFilename != null
                    && lastPlayedFilename.equals(
                            shuffledOrder.get(0).filename())) {
                Collections.swap(shuffledOrder, 0, 1);
            }
        }
    }

}