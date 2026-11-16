-- Logins through the provider (Phase 13): its session ID (the ID token's `sid`), so its back-channel logout ends
-- exactly the logins of that session.
ALTER TABLE login_sessions ADD COLUMN provider_session varchar(128);
CREATE INDEX login_sessions_provider_session ON login_sessions (provider_session);
