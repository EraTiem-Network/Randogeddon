-- How long the game server has been running for a seed, without the times it was stopped. The gateway adds to
-- run_time_ms while it runs (every 30 s and when it ends); counted_until is when it last did, NULL while not running.
ALTER TABLE sessions ADD COLUMN run_time_ms bigint NOT NULL DEFAULT 0;
ALTER TABLE sessions ADD COLUMN counted_until timestamp;
