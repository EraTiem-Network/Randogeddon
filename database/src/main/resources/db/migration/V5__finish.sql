-- Finishing sessions (Phase 3): when a session finished (read-only from then on, a past session once a newer one is
-- created), when it finishes by itself (every slot is done; NULL: not due), whether its auto-deletion is disabled,
-- and since when its retention counts once that was enabled again (NULL: since it finished). And the cheats admins
-- ran from the admin panel.
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS finished_at timestamp;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS finished_by varchar(64);
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS finish_due_at timestamp;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS keep boolean NOT NULL DEFAULT false;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS retained_since timestamp;

CREATE TABLE IF NOT EXISTS cheat_log
(
    id         bigserial PRIMARY KEY,
    session_id uuid         NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    at         timestamp    NOT NULL,
    -- The admin's name at the time; the user may be renamed or deleted later
    admin      varchar(64)  NOT NULL,
    action     varchar(32)  NOT NULL,
    slot_name  varchar(64)  NOT NULL,
    value      varchar(255)
);
CREATE INDEX IF NOT EXISTS cheat_log_session ON cheat_log (session_id, at);
