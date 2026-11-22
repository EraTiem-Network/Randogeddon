-- The gateway's role adds only the kinds of events the gateway writes (Phase 23): row-level security on
-- session_events. Its policy names the role, whose name is a setting, so the tracker makes it again at each start with
-- the role's rights (GatewayRole, the same name); a role without a policy adds nothing. Until then this one holds for
-- every role, made before the security is switched on and in the same transaction, so the gateway's inserts are never
-- refused in between (only the gateway's role has INSERT besides the owner). The tracker owns the table and isn't bound
-- by it (nor is a superuser), so it keeps writing its own kinds (cheat) and reading everything. No data changes
CREATE POLICY gateway_event_kinds ON session_events FOR INSERT TO PUBLIC
  WITH CHECK (kind IN ('packet', 'start', 'stop'));
ALTER TABLE session_events ENABLE ROW LEVEL SECURITY;
