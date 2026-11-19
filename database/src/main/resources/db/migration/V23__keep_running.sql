-- A session whose game server keeps running without players (Phase 18): an Administrator's choice; else it sleeps
-- after the time of the setting `idle_minutes` and starts again when a player connects
ALTER TABLE sessions ADD COLUMN keep_running boolean NOT NULL DEFAULT false;
