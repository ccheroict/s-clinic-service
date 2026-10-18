-- ============================================================
-- V11: Least privilege for patient records, with a way out.
--
-- A doctor sees the patients they treat. That is the minimum-necessary principle
-- applied to health data, and it is also the rule that gets in the way at the
-- worst possible moment: a patient arrives unconscious, the doctor on duty has
-- never treated them, and the record they need is the one they cannot open.
--
-- So the rule is enforced but not absolute. A doctor can grant themselves
-- temporary access by stating a reason, and that act is recorded loudly. The
-- design assumption is that clinicians will do the right thing and that the
-- deterrent is accountability rather than prevention -- a locked door with no
-- key leads to shared logins, which is worse than an opened door with a name
-- against it.
-- ============================================================

create table break_glass_grant (
    id         uuid primary key default gen_random_uuid(),

    staff_id   uuid not null references staff(id),
    patient_id uuid not null references patient(id),

    -- Mandatory, and checked here as well as in the request: a grant with no
    -- stated reason is exactly the one a review would want to ask about, and
    -- that must not depend on every future caller remembering to validate.
    reason     text not null check (length(trim(reason)) > 0),

    granted_at timestamptz not null default now(),

    -- Time-limited by design. An access grant with no end turns into a
    -- permission, which is the thing this exists to avoid.
    expires_at timestamptz not null,

    -- Lets an admin end a grant early without deleting the record of it.
    revoked_at timestamptz,

    constraint chk_break_glass_window check (expires_at > granted_at)
);

-- The access check runs on every patient read by a doctor, so the lookup it
-- performs is the one that needs the index.
create index idx_break_glass_active
    on break_glass_grant (staff_id, patient_id, expires_at desc)
    where revoked_at is null;

-- For the review question "what did this person open that they had no
-- relationship with", which is asked by staff member, not by patient.
create index idx_break_glass_staff on break_glass_grant (staff_id, granted_at desc);
