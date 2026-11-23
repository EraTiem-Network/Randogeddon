-- A forgotten password (0.23.9): the link of a mail to the account's address sets a new one. Only the SHA-256 of its
-- token is stored; it works once (the row goes when it is used) and for minutes, and an account has at most one (a
-- new mail replaces it). The gateway's role gets nothing here (GatewayRole names what it reads).
CREATE TABLE password_resets
(
    token_hash varchar(64) PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamp   NOT NULL,
    expires_at timestamp   NOT NULL
);
CREATE UNIQUE INDEX password_resets_user ON password_resets (user_id);
