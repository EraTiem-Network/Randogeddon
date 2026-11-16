-- Where a person's font shows (Phase 14): `all` (all text) or `headings` (the headings only); null: as the font is
-- made (a display font in the headings, every other in all text). In place of the yes/no of V18, which only a
-- display font had.
ALTER TABLE users ADD COLUMN font_scope varchar(8);
UPDATE users SET font_scope = 'all' WHERE font_everywhere;
ALTER TABLE users DROP COLUMN font_everywhere;
