-- A session's slots open to all of its players (Phase 21): a slot every player of it plays (its password for each),
-- or a hosted slot every player of it gets (its patch files, the bridge's tunnel; never its password). A hosted slot
-- not open goes only to the players named for it (session_worlds). Every slot hosted so far was open to all
CREATE TABLE session_open_slots
(
    session_id uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    slot_name  varchar(64) NOT NULL,
    PRIMARY KEY (session_id, slot_name)
);
INSERT INTO session_open_slots (session_id, slot_name) SELECT session_id, slot_name FROM session_hosted_slots;
