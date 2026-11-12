-- Several sessions at once (Phase 4): each has an ID, its address for game clients (<id>.<domain>) and in the
-- dashboard's links. Sessions from before get "s-<seed>" (seed names are digits; an ID starts with a letter).
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS public_id varchar(40);
UPDATE sessions
SET public_id = 's-' || left(trim(BOTH '-' FROM regexp_replace(lower(seed_name), '[^a-z0-9]+', '-', 'g')), 38)
WHERE public_id IS NULL;
ALTER TABLE sessions ALTER COLUMN public_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS sessions_public_id ON sessions (public_id);
