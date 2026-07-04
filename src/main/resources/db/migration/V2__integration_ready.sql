-- ============================================================
-- s-clinic V2 - integration readiness
-- Prepare for FUTURE integration with external systems:
--   * Hoa don dien tu (e-invoice, ND 123/2020 + TT 78)
--   * Don thuoc dien tu (national e-prescription)
--   * Benh an dien tu / EMR (TT 46/2018, HL7 FHIR exchange)
--
-- Strategy:
--   1. Add standard identifiers / codes so records can be matched to
--      external systems (CCCD, BHYT, ICD-10, tax code, national codes).
--   2. Add per-record sync state columns (status + external code).
--   3. Add a generic outbox table to publish domain events asynchronously
--      to whichever provider adapter is plugged in later (no vendor lock-in).
-- ============================================================

-- ---------- Standard identifiers for EMR / insurance / e-invoice ----------
alter table patient
    add column national_id  text,   -- CCCD/CMND
    add column insurance_no text,    -- so the BHYT
    add column tax_code     text;    -- buyer tax code (for e-invoice), optional
create index idx_patient_national_id on patient (national_id);
create index idx_patient_insurance   on patient (insurance_no);

-- ICD-10 coded diagnosis for EMR/FHIR interoperability
alter table encounter
    add column diagnosis_code text;  -- ICD-10, e.g. 'L20.9'

-- ---------- E-invoice sync state on invoice ----------
alter table invoice
    add column einvoice_status   text not null default 'NONE'
        check (einvoice_status in ('NONE','PENDING','ISSUED','FAILED','CANCELLED')),
    add column einvoice_provider text,            -- e.g. 'VNPT','VIETTEL','MISA'
    add column einvoice_code     text,            -- external invoice number / lookup code
    add column einvoice_issued_at timestamptz;

-- ---------- E-prescription sync state ----------
alter table prescription
    add column national_rx_code text,             -- ma don thuoc quoc gia
    add column sync_status      text not null default 'NONE'
        check (sync_status in ('NONE','PENDING','SYNCED','FAILED')),
    add column synced_at        timestamptz;

-- ---------- Generic integration outbox (async publish to external systems) ----------
-- A domain event is written here in the same transaction as the business change.
-- A separate worker reads PENDING rows and pushes to the right provider adapter,
-- then marks SENT/FAILED. Decouples core domain from external system availability.
create table integration_outbox (
    id            bigserial primary key,
    event_type    text not null,                 -- 'EINVOICE_ISSUE','EPRESCRIPTION_PUSH','EMR_SYNC', ...
    target_system text not null,                  -- 'E_INVOICE','E_PRESCRIPTION','EMR', ...
    entity_type   text not null,                  -- 'invoice','prescription','encounter'
    entity_id     uuid not null,
    payload       jsonb not null,                 -- snapshot to send (provider-agnostic)
    status        text not null default 'PENDING'
        check (status in ('PENDING','SENT','FAILED','SKIPPED')),
    attempts      int not null default 0,
    last_error    text,
    created_at    timestamptz not null default now(),
    sent_at       timestamptz
);
create index idx_outbox_status on integration_outbox (status, created_at);
create index idx_outbox_entity on integration_outbox (entity_type, entity_id);

-- ---------- External reference mapping (our id <-> external system id) ----------
create table external_reference (
    id            uuid primary key default gen_random_uuid(),
    target_system text not null,                  -- 'E_INVOICE','EMR', ...
    entity_type   text not null,
    entity_id     uuid not null,
    external_id   text not null,
    metadata      jsonb,
    created_at    timestamptz not null default now(),
    unique (target_system, entity_type, entity_id)
);
