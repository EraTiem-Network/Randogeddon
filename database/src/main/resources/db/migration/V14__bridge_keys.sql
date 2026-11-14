-- Randogeddon Bridge (Phase 12): a key made on the dashboard is a login of its own kind (`bridge`), sent by the app as
-- a bearer token; it reaches only what the app needs. When it was last used, for the list in *Your account*.
ALTER TABLE login_sessions DROP CONSTRAINT login_sessions_method_check;
ALTER TABLE login_sessions ADD CONSTRAINT login_sessions_method_check
    CHECK (method IN ('password', 'oidc', 'link', 'bridge'));
ALTER TABLE login_sessions ADD COLUMN last_used_at timestamp;
