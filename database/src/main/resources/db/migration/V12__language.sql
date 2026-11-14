-- The language a person reads Randogeddon in (Phase 9): `en` or `de`; null follows their browser. Their mails go out
-- in it too (null: English).
ALTER TABLE users ADD COLUMN language varchar(8);
