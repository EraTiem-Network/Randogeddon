-- What a session's dashboard shows, kept by the tracker (Phase 19): while the session runs, the last it knew (shown
-- while its game server isn't running, e.g. asleep after a deploy); once it finished, its archive (final). Before,
-- both were files in the tracker's volume, which isn't backed up. Also, once, the tracker's history file from
-- before (baseline): the events after it are replayed on top. By the session's public ID; deleted with the session.
CREATE TABLE session_snapshots (
  session varchar(40) PRIMARY KEY,
  at_ms bigint NOT NULL,
  final boolean NOT NULL DEFAULT false,
  snapshot text,
  baseline text
);
