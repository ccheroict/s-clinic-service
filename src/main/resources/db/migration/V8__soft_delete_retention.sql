-- ============================================================
-- V8: Patient records stop being deletable.
--
-- A clinic has a statutory duty to keep medical records for a minimum period,
-- so "delete" has to mean "withdraw from use", never "remove from the database".
-- The row stays, marked with when it was withdrawn, by whom and why.
--
-- On the retention period itself: it is deliberately NOT written into this
-- migration or into the code. The duration comes from Luật KCB 15/2023 and is a
-- legal question, not an engineering one; a number invented here would be worse
-- than no number, because it would look authoritative and would silently govern
-- when records become destructible. It lives in retention_policy as data the
-- clinic fills in once, with the legal basis recorded alongside it.
--
-- Until a policy row exists, retention_until stays null for every record. That is
-- the safe direction: nothing can be judged past its retention period, so nothing
-- becomes eligible for destruction. An empty configuration cannot destroy data.
-- ============================================================

-- ---------- Retention policy, one row per kind of record ----------
create table retention_policy (
    id          uuid primary key default gen_random_uuid(),

    -- Matches the entity_type vocabulary used by audit_log: 'patient',
    -- 'encounter', 'prescription', ...
    record_type text not null unique,

    retention_years             int not null check (retention_years > 0),

    -- Many retention rules run a different clock once the patient has died.
    -- Null means "no separate rule", and the ordinary period applies.
    retention_years_after_death int check (retention_years_after_death > 0),

    -- Which document and article this number comes from. Required: a retention
    -- period nobody can trace back to a source is not auditable, and this is
    -- the field an inspector will ask about.
    legal_basis text not null check (length(trim(legal_basis)) > 0),

    note        text,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

comment on table retention_policy is
    'Minimum retention period per record type. Left empty on purpose: the clinic '
    'must enter the periods from Luat KCB 15/2023 together with their legal basis. '
    'With no row for a record type, retention dates are not computed and nothing '
    'becomes eligible for destruction.';

-- ---------- Soft delete and retention metadata on patient ----------
alter table patient
    add column deleted_at      timestamptz,
    add column deleted_by      uuid references staff(id),
    add column delete_reason   text,

    -- Earliest date the record may be considered for destruction. Null means
    -- "unknown", which is treated as "keep".
    add column retention_until date,

    -- Blocks destruction regardless of the retention period: an ongoing
    -- complaint, inspection or legal case.
    add column legal_hold      boolean not null default false,

    -- Starts the death-case retention clock when a policy defines one.
    add column deceased_on     date;

-- A withdrawal with no stated reason is not auditable, so the database refuses
-- it rather than trusting every future caller to remember.
alter table patient
    add constraint chk_patient_delete_reason check (
        deleted_at is null
        or (delete_reason is not null and length(trim(delete_reason)) > 0)
    );

-- Who withdrew the record has to be known whenever one was withdrawn.
alter table patient
    add constraint chk_patient_deleted_by check (
        deleted_at is null or deleted_by is not null
    );

-- A date of death in the future is a data-entry error, and it would push the
-- retention clock forward instead of starting it. That check lives in the
-- application, not here: a CHECK constraint comparing against current_date is
-- not immutable, so a dump restored years later could fail to validate rows that
-- were perfectly valid when written -- and these records are meant to outlive
-- several migrations of the system they live in.

-- Every list and search excludes withdrawn records, so the index that serves
-- them should too.
create index idx_patient_active on patient (full_name) where deleted_at is null;
create index idx_patient_active_phone on patient (phone) where deleted_at is null;

-- Supports "what is eligible for destruction", the query the retention job in a
-- later phase will run.
create index idx_patient_retention on patient (retention_until)
    where deleted_at is not null and legal_hold = false;
