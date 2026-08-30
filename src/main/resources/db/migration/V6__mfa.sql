-- ============================================================
-- V6: Two-factor authentication (TOTP, RFC 6238)
--
-- Required for ADMIN and DOCTOR: a leaked password alone must not be enough to
-- open a patient record.
--
-- NOTE on totp_secret: stored as base32 text for now. It is a credential and
-- belongs in the column-encryption work (Task 6) together with national_id and
-- insurance_no; until then a database leak would let an attacker mint codes.
-- ============================================================

alter table staff
    add column totp_secret       text,
    add column totp_enabled      boolean not null default false,
    add column totp_confirmed_at timestamptz;

-- ---------- Single-use backup codes ----------
-- For the case where the authenticator device is lost. Only bcrypt hashes are
-- kept, and each code is burned on first use.
create table staff_backup_code (
    id        uuid primary key default gen_random_uuid(),
    staff_id  uuid not null references staff(id) on delete cascade,
    code_hash text not null,
    used_at   timestamptz,
    created_at timestamptz not null default now()
);
create index idx_backup_code_staff on staff_backup_code (staff_id) where used_at is null;
