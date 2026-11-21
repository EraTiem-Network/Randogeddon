-- A hosted Minecraft Dig slot whose Minecraft server Randogeddon runs itself (Phase 20), reached through Randogeddon
-- Bridge's tunnel: switched on by an Administrator only (when the session is created or later), off by its
-- Session-Managers too. Off: its Session-Managers run the server themselves, as for every hosted slot
ALTER TABLE session_hosted_slots ADD COLUMN direct boolean NOT NULL DEFAULT false;
