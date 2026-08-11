package com.singleskickball.manager.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * One homepage announcement banner.
 *
 * <p>The start/end dates are inclusive. A null end date means the announcement
 * remains eligible indefinitely. The enabled flag lets an announcement be
 * disabled immediately without changing its scheduling dates.</p>
 */
@Entity
@Table(name = "home_page_announcements")
public class HomePageAnnouncement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Text shown in the single-line homepage banner. */
    @Column(nullable = false, length = 1000)
    private String message;

    /** First calendar date on which the announcement may be displayed. */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Last calendar date on which the announcement may be displayed, inclusive. */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** Optional CSS hex color used for the banner background. */
    @Column(name = "background_color", length = 9)
    private String backgroundColor;

    /** Optional CSS hex color used for the banner text. */
    @Column(name = "text_color", length = 9)
    private String textColor;

    /** Quick administrative on/off switch. */
    @Column(nullable = false)
    private boolean enabled = true;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(String backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public String getTextColor() {
        return textColor;
    }

    public void setTextColor(String textColor) {
        this.textColor = textColor;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
