-- Share links (Phase 6): a session followed without a login, read-only (for a stream). One per session; a new link
-- replaces the old one. Only the token's SHA-256 is stored.
CREATE TABLE share_links
(
    session_id uuid PRIMARY KEY REFERENCES sessions (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL UNIQUE,
    created_by varchar(64),
    created_at timestamp   NOT NULL
);
