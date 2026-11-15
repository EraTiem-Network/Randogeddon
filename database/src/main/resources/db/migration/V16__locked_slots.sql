-- Locked slots (Phase 13): a slot that starts locked can't connect until its key is found. The gateway refuses its
-- Connect while this is set; the session's creation sets it, the tracker clears it once the key has turned up.
ALTER TABLE slot_passwords ADD COLUMN locked boolean NOT NULL DEFAULT false;
