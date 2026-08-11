package com.singleskickball.manager.dto;

/**
 * Safe, presentation-ready homepage announcement.
 *
 * <p>The database entity is intentionally not passed directly to Thymeleaf.
 * Colors are normalized by the service first so database content cannot inject
 * arbitrary CSS into the page.</p>
 */
public record HomePageAnnouncementBanner(
        Long id,
        String message,
        String backgroundColor,
        String textColor) {
}
