-- Hosted slots (Phase 13): games that run as a server of their own, which their players join with their games; no
-- account plays such a slot from Archipelago's side (its Session-Managers set the game's server up with its connection)
CREATE TABLE session_hosted_slots
(
    session_id uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    slot_name  varchar(64) NOT NULL,
    PRIMARY KEY (session_id, slot_name)
);
