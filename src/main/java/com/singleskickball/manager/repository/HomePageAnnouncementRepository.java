package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.HomePageAnnouncement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Database access for scheduled homepage announcements.
 */
public interface HomePageAnnouncementRepository
        extends JpaRepository<HomePageAnnouncement, Long> {

    /**
     * Returns announcements active on the supplied date, newest schedule first.
     *
     * <p>Pageable is used so callers can efficiently request only the single
     * banner that should currently be displayed.</p>
     */
    @Query("""
            select announcement
              from HomePageAnnouncement announcement
             where announcement.enabled = true
               and announcement.startDate <= :today
               and (announcement.endDate is null or announcement.endDate >= :today)
             order by announcement.startDate desc, announcement.id desc
            """)
    List<HomePageAnnouncement> findActiveAnnouncements(
            @Param("today") LocalDate today,
            Pageable pageable);
}
