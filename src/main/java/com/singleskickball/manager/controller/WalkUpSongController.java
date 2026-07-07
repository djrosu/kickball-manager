package com.singleskickball.manager.controller;

import com.singleskickball.manager.dto.WalkUpSongInfo;
import com.singleskickball.manager.service.WalkUpSongService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Small JSON endpoint used by the manager screen to fetch walk-up song details
 * for a roster entry/current batter.
 */
@RestController
@RequestMapping("/manager/walk-up")
public class WalkUpSongController {

    private final WalkUpSongService walkUpSongService;

    public WalkUpSongController(WalkUpSongService walkUpSongService) {
        this.walkUpSongService = walkUpSongService;
    }

    @GetMapping("/{rosterEntryId}")
    public WalkUpSongInfo getWalkUpSong(@PathVariable Long rosterEntryId) {
        return walkUpSongService.getWalkUpSongInfo(rosterEntryId);
    }
}
