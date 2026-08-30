-- ============================================================
-- V7: Make the audit trail tamper-evident and give it the request context a
--     forensic review actually needs.
--
-- Two separate protections, because they answer different threats:
--
--   1. Append-only triggers stop the application itself from rewriting history,
--      whether through a bug, a careless migration or SQL injection. This is the
--      realistic threat and the trigger blocks it regardless of which role the
--      application connects as.
--
--   2. A hash chain makes tampering *detectable* by anyone who can still write
--      to the table directly, which includes a database superuser who could drop
--      the trigger. Each row carries the hash of the previous row, so removing
--      or altering any row in the middle breaks every link after it. Detection
--      is the most a database can offer against its own administrator; the chain
--      is what turns "we think the log is intact" into something checkable.
--
-- On purging: the triggers are unconditional, with no runtime escape hatch. If a
-- retention rule ever requires deleting old entries, that must arrive as its own
-- reviewed migration that drops and recreates the trigger, not as a flag some
-- request can flip.
-- ============================================================

alter table audit_log
    -- Request context. Answers "from where", which "who" alone does not.
    add column ip         text,
    add column user_agent text,

    -- Which session performed the action, so one compromised login can be
    -- traced across every record it touched.
    --
    -- Deliberately NOT a foreign key to session_token: expired sessions get
    -- cleaned up, and audit rows can never be deleted, so an FK would either
    -- block that cleanup or force a cascade that erases audit history.
    add column session_id uuid,

    -- Hash chain. Null for rows written before this migration; see below.
    add column prev_hash  text,
    add column entry_hash text;

create index idx_audit_session on audit_log (session_id);

-- ---------- Chain head, kept outside audit_log ----------
-- One row, two jobs.
--
--   1. Serialises writers. Each writer takes a row lock here before reading the
--      current head, so two concurrent entries cannot both claim the same
--      position and fork the chain. A row lock rather than an advisory lock
--      because it is plain SQL on the same connection as the insert, with no
--      assumption about which connection a lock statement lands on.
--
--   2. Closes the one gap a hash chain cannot close on its own. Entries removed
--      from the *end* of the chain leave nothing behind to notice, because
--      nothing follows them. Recording the head hash and the entry count outside
--      audit_log means a truncated tail shows up as a head that no longer matches
--      what this row says it should be.
--
-- Someone able to write to audit_log directly can also correct this row, so it
-- raises the bar rather than closing the door. What it removes is the cheap
-- attack: deleting rows and hoping nobody counted.
create table audit_chain_head (
    id          smallint primary key default 1 check (id = 1),
    head_hash   text,
    entry_count bigint      not null default 0,
    updated_at  timestamptz not null default now()
);

insert into audit_chain_head (id, head_hash, entry_count) values (1, null, 0);

-- A repeated entry_hash means two rows claim the same position in the chain,
-- which can only happen if the chain forked. Unique makes that fail loudly
-- instead of silently. Null repeats freely in a Postgres unique index, so the
-- pre-migration rows below are unaffected.
create unique index uq_audit_entry_hash on audit_log (entry_hash);

-- Rows written before this migration keep null hashes rather than being
-- backfilled. Backfilling would mean recomputing hashes in SQL with exactly the
-- same canonical form the application uses, and any drift between the two would
-- produce a chain that verifies today and fails after an unrelated refactor.
-- The verifier reports these as unchained instead of pretending they are proven.

-- ---------- Append-only enforcement ----------
create or replace function audit_log_reject_change() returns trigger as $$
begin
    raise exception 'audit_log is append-only: % is not permitted', tg_op
        using errcode = 'restrict_violation',
              hint = 'Correct a wrong entry by appending a new one.';
end;
$$ language plpgsql;

create trigger trg_audit_log_no_update
    before update on audit_log
    for each row execute function audit_log_reject_change();

create trigger trg_audit_log_no_delete
    before delete on audit_log
    for each row execute function audit_log_reject_change();

-- Truncate bypasses row triggers entirely, so it needs its own statement-level
-- one. Without this, the whole trail could be erased in a single statement.
create trigger trg_audit_log_no_truncate
    before truncate on audit_log
    for each statement execute function audit_log_reject_change();
