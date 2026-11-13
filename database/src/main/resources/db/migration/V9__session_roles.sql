-- Roles (Phase 6): Administrators are account-wide (users.is_admin, as admins were before). Per session besides the
-- players (session_worlds): its Session-Managers (the creator, and the co-managers they add), support and watchers.
-- One of these per person and session; a Session-Manager can also play slots, support and watchers can't.
CREATE TABLE session_roles
(
    session_id uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       varchar(16) NOT NULL CHECK (role IN ('manager', 'support', 'watcher')),
    -- The name of then, like session_worlds.user_name
    user_name  varchar(64),
    added_by   varchar(64),
    added_at   timestamp   NOT NULL,
    PRIMARY KEY (session_id, user_id)
);
CREATE INDEX session_roles_user ON session_roles (user_id);

-- What Administrators set for everyone: how many running sessions one user may manage at once
CREATE TABLE app_settings
(
    key   varchar(64) PRIMARY KEY,
    value text        NOT NULL
);
INSERT INTO app_settings (key, value) VALUES ('sessions_per_user', '1');

-- Who ran a cheat as what: an Administrator, a Session-Manager or support (the feed says so)
ALTER TABLE cheat_log ADD COLUMN by_role varchar(16) NOT NULL DEFAULT 'administrator';
