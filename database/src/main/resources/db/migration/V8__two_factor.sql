-- Two-factor login (Phase 5): a TOTP secret per user, encrypted with the tracker's key (TOTP_KEY or its key file).
-- It counts once it was confirmed with a code (totp_enabled_at); totp_last_step is the newest 30-second step a code
-- was accepted for, so no code works twice.
ALTER TABLE users ADD COLUMN totp_secret varchar(255);
ALTER TABLE users ADD COLUMN totp_enabled_at timestamp;
ALTER TABLE users ADD COLUMN totp_last_step bigint;

-- One-time codes for a lost authenticator; only their SHA-256 is stored (they are long random strings)
CREATE TABLE recovery_codes
(
    user_id   uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash varchar(64) NOT NULL,
    used_at   timestamp,
    PRIMARY KEY (user_id, code_hash)
);
