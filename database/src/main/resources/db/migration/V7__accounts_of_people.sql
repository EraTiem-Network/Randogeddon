-- Accounts belong to people (Phase 5): they are invited by e-mail and choose their name and password themselves.
-- An invited user has neither until they sign up (signed_up_at); the unique index on the name ignores NULL.
ALTER TABLE users ALTER COLUMN name DROP NOT NULL;
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ADD COLUMN email varchar(254);
ALTER TABLE users ADD COLUMN signed_up_at timestamp;
-- When the account deletes itself: 2w, 4w, 3m, 6m, 1y after the end of the user's last session, or never
ALTER TABLE users ADD COLUMN auto_delete varchar(8) NOT NULL DEFAULT 'never';
-- The warning mail before the deletion went out then (reset when the deletion moves later)
ALTER TABLE users ADD COLUMN delete_warned_at timestamp;
-- The accounts from before had their login already
UPDATE users SET signed_up_at = created_at;
-- One account per address, without regard to case, among active accounts
CREATE UNIQUE INDEX users_active_email ON users (lower(email)) WHERE deleted_at IS NULL;

-- The link of an invitation mail; only the SHA-256 of its token is stored. A new one replaces the user's older ones.
CREATE TABLE invitations
(
    token_hash     varchar(64) PRIMARY KEY,
    user_id        uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    email          varchar(254) NOT NULL,
    suggested_name varchar(64),
    created_at     timestamp    NOT NULL,
    expires_at     timestamp    NOT NULL,
    used_at        timestamp
);
CREATE INDEX invitations_user ON invitations (user_id);

-- A new e-mail address waiting for its confirmation (the link in a mail to that address)
CREATE TABLE email_changes
(
    token_hash varchar(64) PRIMARY KEY,
    user_id    uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    email      varchar(254) NOT NULL,
    created_at timestamp    NOT NULL,
    expires_at timestamp    NOT NULL
);
CREATE INDEX email_changes_user ON email_changes (user_id);

-- The name each user had in a session: it follows a rename while the session runs and stays once it is finished, so
-- an old session still shows the names of then (also of a deleted account)
ALTER TABLE session_worlds ADD COLUMN user_name varchar(64);
UPDATE session_worlds w SET user_name = u.name FROM users u WHERE u.id = w.user_id;
