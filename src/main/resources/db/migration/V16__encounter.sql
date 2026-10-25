-- ============================================================
-- V16: The encounter record, and the versioned shape of its clinical data.
--
-- This is the table that replaces the paper chart, so two things about it are
-- not ordinary application design.
--
-- It has to outlive the software. A dermatology record written today may be read
-- in fifteen years by something that is not this application, so the clinical
-- data is stored as JSON against a schema whose version is recorded on the row —
-- not as a hundred nullable columns that only this codebase can interpret, and
-- not as free JSON that nothing can validate.
--
-- And once signed it has to be unchangeable, including by the application that
-- wrote it. The DRAFT state is built here; the lock and the amendment chain come
-- with the signing work, but the columns they need are added now so that a later
-- migration does not have to rewrite this one.
-- ============================================================

-- ---------- The shape of specialty-specific clinical data ----------
create table clinical_data_schema (
    id            uuid primary key default gen_random_uuid(),

    -- Which specialty's form this is. A code rather than a reference to the
    -- specialty table: these schemas are versioned documents that outlive
    -- organisational changes, and a renamed or merged specialty row must not
    -- change what an old record was validated against.
    specialty_code text not null,

    version       integer not null check (version > 0),

    title         text not null,

    -- The JSON Schema itself. Stored rather than loaded from the classpath so
    -- that a record can always be re-validated against the exact schema it was
    -- written under, including after the application has been upgraded past it.
    schema        jsonb not null,

    published_at  timestamptz,
    retired_at    timestamptz,
    created_at    timestamptz not null default now(),

    constraint uq_clinical_schema_version unique (specialty_code, version)
);

create index idx_clinical_schema_current
    on clinical_data_schema (specialty_code, version desc)
    where published_at is not null and retired_at is null;

-- ---------- The encounter ----------
--
-- V1 already created a rudimentary encounter table, so this alters rather than
-- creates. What V1 had was a visit with a free-text diagnosis and an unversioned
-- jsonb blob — the right sketch, and not a medical record.
alter table encounter
    -- The name said date and the type said timestamp. Renamed rather than left
    -- to mislead every future reader: nothing references it yet, because no
    -- entity for this table existed before now.
    rename column encounter_date to encountered_at;

alter table encounter
    -- An encounter with no doctor is not a medical record. Nullable in V1, and
    -- safe to tighten because the system has never been deployed, so there are
    -- no rows anywhere that would fail it.
    alter column doctor_id set not null,

    -- The free-text diagnosis goes. encounter_diagnosis replaces it with coded,
    -- countable diagnoses, and this column is the same trap as the free-text
    -- allergies field: prose that looks like recorded information and is
    -- invisible to anything that needs to read it.
    --
    -- Dropped outright rather than migrated to a legacy note, as V13 did for
    -- allergies, and the difference is only that there is no data to migrate:
    -- the clinic owner has confirmed the system is not deployed anywhere. Had
    -- there been a single row of it, this would have to be carried across and
    -- flagged for review instead, because a diagnosis is not something to parse
    -- automatically.
    drop column diagnosis,

    add column advice          text,
    add column follow_up_date  date,

    -- Referral to another facility. Free text for now: the structured form
    -- depends on the national referral format, and a guessed structure would be
    -- harder to correct later than prose.
    add column referral_note   text,

    add column status text not null default 'DRAFT'
        check (status in ('DRAFT', 'SIGNED', 'AMENDED')),

    -- The exact schema version the clinical data was validated against.
    -- Requirement 13.5: an encounter written under version 1 must still be
    -- readable once version 2 exists, which is only possible if the row says
    -- which one applied. Validation on write uses it, and so does rendering a
    -- historical record.
    add column clinical_data_schema_id uuid references clinical_data_schema(id),

    -- The record this one corrects. An amendment is a new row rather than an
    -- edit, because the original has been signed and must stay exactly as it was
    -- signed. Populated by the signing work; the column is added now so that
    -- migration does not have to rewrite this one.
    add column amends_encounter_id uuid references encounter(id),
    add column amendment_reason    text,

    -- Lifecycle, matching patient.
    add column deleted_at      timestamptz,
    add column deleted_by      uuid references staff(id),
    add column delete_reason   text,
    add column retention_until date,
    add column legal_hold      boolean not null default false,
    add column updated_at      timestamptz not null default now(),

    add constraint chk_encounter_amendment check (
        (amends_encounter_id is null and amendment_reason is null)
        or (amends_encounter_id is not null
            and amendment_reason is not null
            and length(trim(amendment_reason)) > 0)
    ),

    -- Same rule as patient: the database requires a reason, rather than trusting
    -- every future caller to remember.
    add constraint chk_encounter_delete_reason check (
        (deleted_at is null and deleted_by is null and delete_reason is null)
        or (deleted_at is not null
            and deleted_by is not null
            and delete_reason is not null
            and length(trim(delete_reason)) > 0)
    );

-- V1's index was (patient_id, encounter_date desc) and followed the rename.
create index idx_encounter_amends on encounter (amends_encounter_id);

-- One encounter per appointment.
--
-- Partial twice over: appointment_id is null for walk-ins, and an amendment is a
-- separate row that must not collide with the record it corrects. Without the
-- second condition, amending an encounter created from an appointment would be
-- impossible — which is the kind of thing that is discovered late and fixed by
-- dropping the constraint rather than by thinking about it.
create unique index uq_encounter_appointment
    on encounter (appointment_id)
    where appointment_id is not null and amends_encounter_id is null;

-- ---------- Diagnoses ----------
create table encounter_diagnosis (
    id           uuid primary key default gen_random_uuid(),
    encounter_id uuid not null references encounter(id) on delete cascade,

    icd10_id     uuid not null references catalog_icd10(id),

    -- The code and name as they stood when the diagnosis was recorded.
    --
    -- Copied, not read through the reference. A signed record has to keep saying
    -- what the doctor signed: if the catalogue is later corrected — a code
    -- retired, a Vietnamese name reworded — the historical record must not
    -- silently change to match. The reference is kept as well, so current
    -- catalogue data can still be reached when it is wanted.
    icd10_code   text not null,
    icd10_name   text not null,

    -- Exactly one primary diagnosis per encounter (requirement 13.2).
    --
    -- The index below enforces *at most* one. Exactly one is checked when the
    -- record is signed rather than on every insert, because a draft being typed
    -- legitimately passes through having none, and a constraint that forbids
    -- that would force the first diagnosis entered to be the primary one and
    -- make correcting it a two-step dance.
    is_primary   boolean not null default false,

    note         text,
    created_at   timestamptz not null default now(),

    constraint uq_encounter_icd10 unique (encounter_id, icd10_id)
);

create unique index uq_encounter_primary_diagnosis
    on encounter_diagnosis (encounter_id)
    where is_primary;

create index idx_encounter_diagnosis_encounter on encounter_diagnosis (encounter_id);

-- ============================================================
-- The first dermatology schema.
--
-- Seeded, unlike the consent wording and the national catalogues, and the
-- difference is worth stating. Consent text is a legal instrument the clinic is
-- answerable for; the ICD-10 and drug catalogues are national registers issued
-- by the Ministry. This is neither: it is a description of which fields a
-- dermatology examination records, it is the shape asked for in requirement
-- 13.3, and a clinic cannot write an encounter at all until one exists.
--
-- It is still the clinic's to change. Publishing version 2 through the API
-- leaves every record written under version 1 readable and valid, which is the
-- whole point of versioning it — so starting from something usable costs
-- nothing that cannot be corrected.
-- ============================================================
insert into clinical_data_schema (specialty_code, version, title, schema, published_at)
values (
    'DERMATOLOGY',
    1,
    'Khám da liễu - mô tả tổn thương',
    $${
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "title": "Khám da liễu",
      "type": "object",
      "properties": {
        "lesionDescription": {
          "type": "string",
          "title": "Mô tả tổn thương",
          "maxLength": 4000
        },
        "lesionTypes": {
          "type": "array",
          "title": "Dạng tổn thương",
          "items": {
            "type": "string",
            "enum": ["MACULE", "PAPULE", "PLAQUE", "NODULE", "VESICLE",
                     "PUSTULE", "BULLA", "WHEAL", "SCALE", "CRUST",
                     "EROSION", "ULCER", "ATROPHY", "SCAR", "OTHER"]
          },
          "uniqueItems": true
        },
        "locations": {
          "type": "array",
          "title": "Vị trí",
          "items": {
            "type": "string",
            "enum": ["SCALP", "FACE", "NECK", "TRUNK_FRONT", "TRUNK_BACK",
                     "ARM_LEFT", "ARM_RIGHT", "HAND_LEFT", "HAND_RIGHT",
                     "LEG_LEFT", "LEG_RIGHT", "FOOT_LEFT", "FOOT_RIGHT",
                     "GENITAL", "NAIL", "MUCOSA", "GENERALISED"]
          },
          "uniqueItems": true
        },
        "bodySurfaceAreaPercent": {
          "type": "number",
          "title": "Diện tích (% BSA)",
          "minimum": 0,
          "maximum": 100
        },
        "severity": {
          "type": "string",
          "title": "Phân độ",
          "enum": ["MILD", "MODERATE", "SEVERE"]
        },
        "severityScale": {
          "type": "string",
          "title": "Thang điểm sử dụng",
          "description": "Ví dụ: PASI, SCORAD, EASI, IGA",
          "maxLength": 100
        },
        "severityScore": {
          "type": "number",
          "title": "Điểm",
          "minimum": 0
        },
        "itchScore": {
          "type": "integer",
          "title": "Mức ngứa (0-10)",
          "minimum": 0,
          "maximum": 10
        },
        "notes": {
          "type": "string",
          "title": "Ghi chú thêm",
          "maxLength": 4000
        }
      },
      "additionalProperties": false
    }$$::jsonb,
    now()
);

comment on table clinical_data_schema is
    'Versioned JSON Schema per specialty. An encounter records which version its '
        'clinical data was validated against, so a record written under an older '
        'version stays readable and re-validatable after a newer one is published.';

comment on column encounter.status is
    'DRAFT is editable. SIGNED is locked at both the application and database '
        'level. AMENDED marks a record that a later signed amendment corrects; '
        'the original is never modified.';
