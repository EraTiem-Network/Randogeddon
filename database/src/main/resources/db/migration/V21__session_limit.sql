-- The limit of running sessions per user (Phase 15): an account's own number (null: the default, the setting
-- `sessions_per_user`), and who created a session, which is what counts towards it now (not who manages it).
ALTER TABLE users ADD COLUMN session_limit integer;
ALTER TABLE sessions ADD COLUMN created_by uuid REFERENCES users (id);
-- So far the creator became the session's first Session-Manager, the only part someone gives themselves
UPDATE sessions s
SET created_by = (SELECT r.user_id
                  FROM session_roles r
                  WHERE r.session_id = s.id AND r.added_by = r.user_name
                  ORDER BY r.added_at
                  LIMIT 1);
