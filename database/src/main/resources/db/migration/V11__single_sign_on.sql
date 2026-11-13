-- Single sign-on (Phase 8): Administrators log in through Authentik (OIDC). An account is linked to its Authentik user
-- by the ID token's `sub`, the first time by its verified e-mail address.
ALTER TABLE users ADD COLUMN oidc_subject varchar(255) UNIQUE;

-- How a login was made: a password (with two-factor login if on), Authentik, or a one-time link from the host's CLI.
-- With Authentik configured, an Administrator acts as one only after `oidc` or `link`.
ALTER TABLE login_sessions ADD COLUMN method varchar(16) NOT NULL DEFAULT 'password'
    CHECK (method IN ('password', 'oidc', 'link'));

-- One-time login links (`randogeddon admins --login`), for when Authentik can't be reached; only the token's SHA-256
CREATE TABLE login_links
(
    token_hash varchar(64) PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamp   NOT NULL,
    expires_at timestamp   NOT NULL,
    used_at    timestamp
);
