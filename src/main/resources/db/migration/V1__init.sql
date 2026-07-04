-- ============================================================
-- s-clinic V1 - initial schema
-- Small clinic management. Specialty: dermatology (da lieu),
-- multi-doctor, designed to expand to other specialties later.
--
-- Design principle:
--   * Neutral core: patient / appointment / encounter / invoice
--   * Specialty-specific data kept flexible (encounter.clinical_data jsonb,
--     dermatology clinical photos, multi-session treatment courses)
--   * Access control is enforced at the APPLICATION layer (Spring Security),
--     not Postgres RLS. audit_log tracks access to sensitive records.
-- ============================================================

create extension if not exists "pgcrypto";

-- ---------- Specialty (enables multi-specialty expansion) ----------
create table specialty (
    id          uuid primary key default gen_random_uuid(),
    code        text unique not null,           -- 'DERMATOLOGY', ...
    name        text not null,
    active      boolean not null default true
);

-- ---------- Staff (doctors, receptionists, admins) ----------
create table staff (
    id            uuid primary key default gen_random_uuid(),
    username      text unique not null,
    password_hash text not null,                 -- bcrypt; never store plaintext
    full_name     text not null,
    role          text not null check (role in ('DOCTOR','RECEPTIONIST','ADMIN')),
    specialty_id  uuid references specialty(id), -- relevant for doctors
    phone         text,
    email         text,
    active        boolean not null default true,
    created_at    timestamptz not null default now()
);

-- ---------- Patient ----------
create table patient (
    id           uuid primary key default gen_random_uuid(),
    code         text unique,                    -- human-friendly patient code
    full_name    text not null,
    dob          date,
    sex          text check (sex in ('M','F','U')) default 'U',
    phone        text,
    address      text,
    medical_history text,                         -- tien su benh (sensitive)
    allergies    text,
    note         text,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);
create index idx_patient_name  on patient (lower(full_name));
create index idx_patient_phone on patient (phone);

-- ---------- Service catalog (configurable per specialty) ----------
create table service (
    id           uuid primary key default gen_random_uuid(),
    specialty_id uuid references specialty(id),
    code         text unique,
    name         text not null,
    price        numeric(14,2) not null default 0,
    active       boolean not null default true
);

-- ---------- Appointment ----------
create table appointment (
    id            uuid primary key default gen_random_uuid(),
    patient_id    uuid not null references patient(id),
    doctor_id     uuid references staff(id),
    scheduled_at  timestamptz not null,
    duration_min  int default 30,
    status        text not null check (status in
                  ('BOOKED','CONFIRMED','ARRIVED','IN_PROGRESS','DONE','CANCELLED','NO_SHOW'))
                  default 'BOOKED',
    reason        text,
    note          text,
    created_at    timestamptz not null default now()
);
create index idx_appt_doctor_time on appointment (doctor_id, scheduled_at);
create index idx_appt_patient     on appointment (patient_id);

-- ---------- Encounter (a clinical visit) ----------
create table encounter (
    id             uuid primary key default gen_random_uuid(),
    patient_id     uuid not null references patient(id),
    doctor_id      uuid references staff(id),
    appointment_id uuid references appointment(id),
    encounter_date timestamptz not null default now(),
    reason         text,
    diagnosis      text,
    treatment_plan text,
    -- specialty-specific structured data (e.g. dermatology exam form);
    -- keeps schema stable when adding new specialties
    clinical_data  jsonb,
    created_at     timestamptz not null default now()
);
create index idx_encounter_patient on encounter (patient_id, encounter_date desc);
create index idx_encounter_doctor  on encounter (doctor_id);

-- ---------- Clinical photos (dermatology before/after) ----------
create table clinical_photo (
    id           uuid primary key default gen_random_uuid(),
    encounter_id uuid not null references encounter(id) on delete cascade,
    storage_key  text not null,                  -- key in private object storage, NOT a public URL
    caption      text,
    body_site    text,                           -- vung da
    taken_at     timestamptz not null default now()
);
create index idx_photo_encounter on clinical_photo (encounter_id);

-- ---------- Treatment course (multi-session, e.g. laser/peel) ----------
create table treatment_course (
    id            uuid primary key default gen_random_uuid(),
    patient_id    uuid not null references patient(id),
    service_id    uuid references service(id),
    total_sessions int not null default 1,
    started_at    date,
    status        text not null check (status in ('ACTIVE','COMPLETED','CANCELLED')) default 'ACTIVE',
    note          text
);
create table treatment_session (
    id            uuid primary key default gen_random_uuid(),
    course_id     uuid not null references treatment_course(id) on delete cascade,
    encounter_id  uuid references encounter(id),
    session_no    int not null,
    performed_at  timestamptz,
    note          text,
    unique (course_id, session_no)
);

-- ---------- Prescription ----------
create table prescription (
    id           uuid primary key default gen_random_uuid(),
    encounter_id uuid not null references encounter(id) on delete cascade,
    created_at   timestamptz not null default now()
);
create table prescription_item (
    id              uuid primary key default gen_random_uuid(),
    prescription_id uuid not null references prescription(id) on delete cascade,
    drug_name       text not null,
    dose            text,
    quantity        numeric(10,2),
    instruction     text                          -- cach dung
);

-- ---------- Invoice / billing ----------
create table invoice (
    id           uuid primary key default gen_random_uuid(),
    patient_id   uuid not null references patient(id),
    encounter_id uuid references encounter(id),
    total        numeric(14,2) not null default 0,
    status       text not null check (status in ('DRAFT','UNPAID','PAID','CANCELLED')) default 'DRAFT',
    created_at   timestamptz not null default now()
);
create table invoice_item (
    id          uuid primary key default gen_random_uuid(),
    invoice_id  uuid not null references invoice(id) on delete cascade,
    service_id  uuid references service(id),
    description text not null,                    -- service or drug description snapshot
    quantity    numeric(10,2) not null default 1,
    unit_price  numeric(14,2) not null default 0,
    amount      numeric(14,2) not null default 0
);
create index idx_invoice_patient on invoice (patient_id);

-- ---------- Audit log (who accessed/changed sensitive records) ----------
create table audit_log (
    id          bigserial primary key,
    staff_id    uuid references staff(id),
    action      text not null,                   -- VIEW / CREATE / UPDATE / DELETE
    entity_type text not null,                   -- 'patient', 'encounter', ...
    entity_id   uuid,
    detail      jsonb,
    created_at  timestamptz not null default now()
);
create index idx_audit_entity on audit_log (entity_type, entity_id);
create index idx_audit_staff   on audit_log (staff_id, created_at desc);

-- ---------- Seed: default specialty ----------
insert into specialty (code, name) values ('DERMATOLOGY', 'Da lieu')
on conflict (code) do nothing;
