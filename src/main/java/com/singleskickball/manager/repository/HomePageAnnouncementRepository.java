package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.HomePageAnnouncement;
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
     * Returns every announcement active on the supplied date, newest schedule
     * first. The homepage renders the result in this same order so the
     * announcement with the most recent start date appears at the top.
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
            @Param("today") LocalDate today);
}
