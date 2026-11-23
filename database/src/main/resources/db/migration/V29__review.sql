-- The database's review (Phase 23, 0.23.4). Short locks on small tables; whatever a new constraint would refuse is
-- removed first, in this same transaction.

-- A session's events and snapshot belong to it: deleted with it (one statement), never written for a session that
-- isn't there (a game server's late stop, a reused ID), and carried along if its ID changes. What was left over
-- from sessions deleted before goes first.
DELETE FROM session_events e WHERE NOT EXISTS (SELECT 1 FROM sessions s WHERE s.public_id = e.session);
DELETE FROM session_snapshots n WHERE NOT EXISTS (SELECT 1 FROM sessions s WHERE s.public_id = n.session);
ALTER TABLE session_events ADD CONSTRAINT session_events_session
  FOREIGN KEY (session) REFERENCES sessions (public_id) ON UPDATE CASCADE ON DELETE CASCADE;
ALTER TABLE session_snapshots ADD CONSTRAINT session_snapshots_session
  FOREIGN KEY (session) REFERENCES sessions (public_id) ON UPDATE CASCADE ON DELETE CASCADE;

-- An event is at most 2 MiB (the gateway keeps far less of a packet; the tracker reads them into memory), and its ID
-- is always the table's own: one named by an inserter could put itself past every later event. Every role that adds
-- events (the gateway's) may name only the four columns; the tracker gives the same rights at its start (GatewayRole)
DELETE FROM session_events WHERE octet_length(packet) > 2097152;
ALTER TABLE session_events ADD CONSTRAINT session_events_packet_size CHECK (octet_length(packet) <= 2097152);
ALTER TABLE session_events ALTER COLUMN id SET GENERATED ALWAYS;
DO $$
DECLARE
  inserter name;
BEGIN
  FOR inserter IN
    SELECT DISTINCT pg_get_userbyid(a.grantee) FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a
    WHERE c.oid = 'session_events'::regclass AND a.privilege_type = 'INSERT' AND a.grantee <> c.relowner AND a.grantee <> 0
  LOOP
    EXECUTE format('REVOKE INSERT ON session_events FROM %I', inserter);
    EXECUTE format('GRANT INSERT (session, at_ms, kind, packet) ON session_events TO %I', inserter);
  END LOOP;
END $$;

-- How far the events were read when the tracker last kept a running session's state (kept in baseline, the state
-- the events after it are replayed onto): a restart replays only what came after
ALTER TABLE session_snapshots ADD COLUMN events_until bigint;

-- At most one invitation and one address waiting for its confirmation per account (a second row hid both): the
-- newest of each stays
DELETE FROM invitations i WHERE EXISTS (
  SELECT 1 FROM invitations n WHERE n.user_id = i.user_id AND (n.created_at, n.token_hash) > (i.created_at, i.token_hash)
);
DROP INDEX IF EXISTS invitations_user;
CREATE UNIQUE INDEX invitations_user ON invitations (user_id);
DELETE FROM email_changes e WHERE EXISTS (
  SELECT 1 FROM email_changes n WHERE n.user_id = e.user_id AND (n.created_at, n.token_hash) > (e.created_at, e.token_hash)
);
DROP INDEX IF EXISTS email_changes_user;
CREATE UNIQUE INDEX email_changes_user ON email_changes (user_id);

-- Recovery codes are kept as an HMAC under a key that isn't in the database since 0.13.17 (2026-11-16), not as the
-- plain SHA-256 V8 says: those of before (a hash of 41 bits, guessed from a dump in minutes) are no longer taken. The
-- table doesn't say which is which: the codes of every account that turned two-factor login on before that release
-- go (its owner keeps the authenticator, and makes new codes on the settings page)
DELETE FROM recovery_codes r USING users u
WHERE u.id = r.user_id AND (u.totp_enabled_at IS NULL OR u.totp_enabled_at < '2026-11-17 00:00:00');

-- An account's time zone (an IANA name, from its browser): the mails give their times in it; NULL: not known, UTC
ALTER TABLE users ADD COLUMN time_zone varchar(64);

-- The sessions a person plays in are looked up by them (their activity, a rename)
CREATE INDEX IF NOT EXISTS session_worlds_user ON session_worlds (user_id);
