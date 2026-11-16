-- The font a person reads Randogeddon in (Phase 14): `original`, or a theme's code for that theme's font; null: the
-- font of the theme they chose. And whether a display font (headings only by default) shows in all text.
ALTER TABLE users ADD COLUMN font varchar(16);
ALTER TABLE users ADD COLUMN font_everywhere boolean NOT NULL DEFAULT false;
