-- Reference schema; Repository initializes this automatically for local SQLite.
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS records (
    kind TEXT NOT NULL,
    id TEXT NOT NULL,
    owner TEXT NOT NULL,
    payload TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (kind, id, owner)
);
CREATE INDEX IF NOT EXISTS owner_records ON records(owner, kind, updated_at);

-- kind: search | journey | session | event | watch | watch_event
-- payload: JSON matching the Pydantic API contracts.
-- owner: SHA256 of the development session capability, not a client userId.
-- All client reads use the composite owner key. Only the server watch worker
-- enumerates watches across owners. Production account/RLS migration is separate.
