-- Adds the per-game/team manager assignment needed to separate League
-- Supervisor permissions from normal Team Manager permissions.
--
-- This migration is intentionally additive only. It does not remove, overwrite,
-- truncate, or modify existing player profile/password/availability data.

ALTER TABLE teams
    ADD COLUMN manager_player_id BIGINT;

ALTER TABLE teams
    ADD CONSTRAINT fk_teams_manager_player
        FOREIGN KEY (manager_player_id) REFERENCES players(id);
