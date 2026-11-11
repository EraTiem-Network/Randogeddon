-- Logins (Phase 2). Users are identified by their UUID; the name is the display name.
CREATE TABLE users
(
    id            uuid PRIMARY KEY,
    name          varchar(64)  NOT NULL,
    -- Argon2id, PHC string format
    password_hash varchar(255) NOT NULL,
    created_at    timestamp    NOT NULL,
    deleted_at    timestamp
);
-- Names are unique among active accounts, without regard to case ("Alice" and "alice" can't both exist)
CREATE UNIQUE INDEX users_active_name ON users (lower(name)) WHERE deleted_at IS NULL;

-- A logged-in browser. Only the SHA-256 of the cookie's token is stored.
CREATE TABLE login_sessions
(
    token_hash varchar(64) PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamp   NOT NULL,
    expires_at timestamp   NOT NULL
);
CREATE INDEX login_sessions_user ON login_sessions (user_id);

-- A seed that was served; its slot passwords stay the same across restarts
CREATE TABLE sessions
(
    id         uuid PRIMARY KEY,
    seed_name  varchar(64) NOT NULL UNIQUE,
    created_at timestamp   NOT NULL
);

-- The password a game client needs to connect as this slot (checked by the gateway, shown to its player)
CREATE TABLE slot_passwords
(
    session_id uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    slot_name  varchar(64) NOT NULL,
    password   varchar(64) NOT NULL,
    PRIMARY KEY (session_id, slot_name)
);
