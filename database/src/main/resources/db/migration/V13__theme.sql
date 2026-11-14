-- How a person sees Randogeddon (Phase 10): a theme's code and the mode (`light`, `dark`, `auto`); null: the default
-- theme, automatic.
ALTER TABLE users ADD COLUMN theme varchar(16);
ALTER TABLE users ADD COLUMN theme_mode varchar(8);
