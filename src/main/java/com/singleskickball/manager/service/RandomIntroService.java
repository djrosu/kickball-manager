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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
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
 * <p>At game start, this service creates shuffled in-memory "decks" of eligible
 * intro clips. A batter receives the next clip from the appropriate deck rather
 * than a completely independent random selection. This provides two important
 * behaviors:</p>
 *
 * <ul>
 *   <li>Every eligible clip is used before that eligibility group repeats.</li>
 *   <li>The same clip is not played back-to-back when another eligible choice
 *       exists, even when consecutive batters have different genders.</li>
 * </ul>
 *
 * <p>When a deck is exhausted, it is reshuffled for another cycle. The sequence
 * is temporary game-session state and intentionally is not stored in the
 * database.</p>
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
     * Shuffled, per-game playback state.
     *
     * <p>Each eligibility group has its own deck so male-only and female-only
     * clips are not consumed by ineligible batters. A single last-played value
     * spans all groups and prevents immediate duplication of a unisex clip when
     * consecutive batters use different decks.</p>
     */
    private static final class GameIntroSequence {

        private final List<RandomIntroRow> sourceCollection;
        private final Map<EligibilityGroup, Deque<RandomIntroRow>> decks =
                new EnumMap<>(EligibilityGroup.class);

        private String lastPlayedFilename;

        private GameIntroSequence(List<RandomIntroRow> collection) {
            this.sourceCollection = List.copyOf(collection);
            for (EligibilityGroup group : EligibilityGroup.values()) {
                decks.put(group, buildDeck(group));
            }
        }

        /**
         * Thread-safe because two manager devices can request batter audio at
         * nearly the same moment.
         */
        private synchronized RandomIntroRow nextFor(Gender gender) {
            EligibilityGroup group = EligibilityGroup.forGender(gender);
            Deque<RandomIntroRow> deck = decks.get(group);

            if (deck == null || deck.isEmpty()) {
                deck = buildDeck(group);
                decks.put(group, deck);
            }

            if (deck.isEmpty()) {
                return null;
            }

            /*
             * When possible, avoid replaying the same clip immediately across
             * gender decks. Move the duplicate to the back and use another clip.
             */
            if (deck.size() > 1
                    && lastPlayedFilename != null
                    && lastPlayedFilename.equals(deck.peekFirst().filename())) {
                deck.addLast(deck.removeFirst());
            }

            RandomIntroRow selected = deck.removeFirst();
            lastPlayedFilename = selected.filename();
            return selected;
        }

        private Deque<RandomIntroRow> buildDeck(EligibilityGroup group) {
            List<RandomIntroRow> eligible = sourceCollection.stream()
                    .filter(row -> group.accepts(row))
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

            Collections.shuffle(eligible);

            /*
             * When beginning a new cycle, avoid putting the previous cycle's
             * final clip first if another choice exists.
             */
            if (eligible.size() > 1
                    && lastPlayedFilename != null
                    && lastPlayedFilename.equals(eligible.get(0).filename())) {
                Collections.swap(eligible, 0, 1);
            }

            return new ArrayDeque<>(eligible);
        }
    }

    private enum EligibilityGroup {
        MALE {
            @Override
            boolean accepts(RandomIntroRow row) {
                return row.male();
            }
        },
        FEMALE {
            @Override
            boolean accepts(RandomIntroRow row) {
                return row.female();
            }
        },
        UNISEX {
            @Override
            boolean accepts(RandomIntroRow row) {
                return row.male() && row.female();
            }
        };

        abstract boolean accepts(RandomIntroRow row);

        static EligibilityGroup forGender(Gender gender) {
            if (gender == Gender.MALE) {
                return MALE;
            }
            if (gender == Gender.FEMALE) {
                return FEMALE;
            }
            return UNISEX;
        }
    }
}
