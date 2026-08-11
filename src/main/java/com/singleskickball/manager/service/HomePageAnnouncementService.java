package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.HomePageAnnouncementBanner;
import com.singleskickball.manager.model.HomePageAnnouncement;
import com.singleskickball.manager.repository.HomePageAnnouncementRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves the announcement banner that should appear on the player homepage.
 */
@Service
public class HomePageAnnouncementService {

    private static final String DEFAULT_BACKGROUND = "#d71920";
    private static final String DEFAULT_TEXT = "#ffffff";

    /** Supports #RGB, #RGBA, #RRGGBB, and #RRGGBBAA. */
    private static final Pattern SAFE_HEX_COLOR =
            Pattern.compile("^#(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{4}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$");

    private final HomePageAnnouncementRepository repository;
    private final ZoneId leagueZoneId;

    public HomePageAnnouncementService(
            HomePageAnnouncementRepository repository,
            @Value("${app.time-zone:America/New_York}") String leagueTimeZone) {
        this.repository = repository;
        this.leagueZoneId = ZoneId.of(leagueTimeZone);
    }

    /**
     * Returns at most one active banner for today.
     *
     * <p>If schedules overlap, the announcement with the latest start date wins;
     * a higher id breaks a tie. That makes replacing an announcement predictable
     * without requiring another priority column.</p>
     */
    @Transactional(readOnly = true)
    public Optional<HomePageAnnouncementBanner> getActiveBanner() {
        return repository.findActiveAnnouncements(
                        LocalDate.now(leagueZoneId),
                        PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .filter(announcement -> announcement.getMessage() != null
                        && !announcement.getMessage().isBlank())
                .map(this::toBanner);
    }

    private HomePageAnnouncementBanner toBanner(HomePageAnnouncement announcement) {
        return new HomePageAnnouncementBanner(
                announcement.getId(),
                announcement.getMessage().trim(),
                safeColor(announcement.getBackgroundColor(), DEFAULT_BACKGROUND),
                safeColor(announcement.getTextColor(), DEFAULT_TEXT));
    }

    private String safeColor(String value, String fallback) {
        if (value == null || !SAFE_HEX_COLOR.matcher(value.trim()).matches()) {
            return fallback;
        }
        return value.trim();
    }
}
