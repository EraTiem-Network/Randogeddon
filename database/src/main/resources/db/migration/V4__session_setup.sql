-- Sessions created in the admin panel (Phase 3): the Archipelago version they were generated with, the game server's
-- options, and which users play which world (a world can belong to several users: they play it together)
ALTER TABLE sessions ADD COLUMN ap_version varchar(32);
ALTER TABLE sessions ADD COLUMN options text;

CREATE TABLE session_worlds
(
    session_id uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    slot_name  varchar(64) NOT NULL,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (session_id, slot_name, user_id)
);
