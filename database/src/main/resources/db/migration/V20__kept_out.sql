-- An Administrator keeps an account out (Phase 15): since when, until when (null with blocked_at set: suspended,
-- until it is taken back), the reason they are told, and who did it. A time-out that ran out counts as none.
ALTER TABLE users ADD COLUMN blocked_at timestamp;
ALTER TABLE users ADD COLUMN blocked_until timestamp;
ALTER TABLE users ADD COLUMN blocked_reason varchar(200);
ALTER TABLE users ADD COLUMN blocked_by varchar(64);
