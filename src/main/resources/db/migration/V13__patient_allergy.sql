-- ============================================================
-- V13: Allergies become records the system can act on.
--
-- Until now an allergy was a sentence in patient.allergies. A sentence cannot
-- stop a prescription. "Dị ứng penicillin", "DU penicilin" and "penicillin (nhẹ)"
-- all mean the same thing to a person reading the chart and nothing at all to a
-- check that has to fire before a drug is prescribed.
--
-- ---------- What happens to the sentences already there ----------
--
-- They are moved to a note and the patient is flagged for review. They are NOT
-- parsed into coded allergens.
--
-- That is the whole point of doing it this way. A parser would get most rows
-- right, and the rows it got wrong would be invisible: a patient whose
-- "không dị ứng thuốc" became an allergy record, or whose "dị ứng kháng sinh"
-- became penicillin only. Either error is a clinical one, and neither announces
-- itself. A flag that says a human has not yet checked this is honest about what
-- the system knows; a guessed allergen looks like a fact.
-- ============================================================

create table patient_allergy (
    id            uuid primary key default gen_random_uuid(),

    patient_id    uuid not null references patient(id),

    -- The coded substance. Not nullable: an allergy record with no substance is
    -- a note, and notes have their own field.
    allergen_id   uuid not null references catalog_allergen(id),

    -- What actually happened. Free text on purpose: this is a clinical
    -- observation, and forcing it into a code list would lose the detail that
    -- makes it useful to the next clinician.
    reaction      text,

    severity      text not null
                  check (severity in ('MILD', 'MODERATE', 'SEVERE')),

    -- How sure anyone is. A patient's own report and a confirmed test result are
    -- both worth recording and must not look identical.
    certainty     text not null default 'REPORTED'
                  check (certainty in ('SUSPECTED', 'REPORTED', 'CONFIRMED')),

    recorded_on   date not null,
    recorded_by   uuid not null references staff(id),

    -- An allergy that turned out not to be one is retired, not deleted: the fact
    -- that it was believed, and acted on, is part of the record.
    active        boolean not null default true,
    inactivated_at timestamptz,
    inactivated_by uuid references staff(id),
    inactivation_reason text,

    note          text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),

    constraint chk_allergy_inactivation check (
        active = true
        or (inactivated_at is not null
            and inactivated_by is not null
            and inactivation_reason is not null
            and length(trim(inactivation_reason)) > 0)
    )
);

-- Requirement 10.2: one live record per substance per patient.
--
-- Partial, so a retired record does not block re-recording the same substance
-- later. Enforced here rather than only in the service because a duplicate
-- allergy is how a severity gets silently downgraded: two rows for penicillin,
-- one SEVERE and one MILD, and whichever the check reads first decides.
create unique index uq_patient_allergy_active
    on patient_allergy (patient_id, allergen_id) where active = true;

-- The query the prescribing check runs: what does this patient react to.
create index idx_patient_allergy_lookup
    on patient_allergy (patient_id) where active = true;

-- ---------- The review flag ----------
alter table patient
    -- Set for every patient whose free-text allergies were carried across, and
    -- cleared by a clinician once they have looked. Not a boolean with a default
    -- of false: null would be indistinguishable from "reviewed", and the whole
    -- value of this column is telling those two apart.
    add column allergy_review_required boolean not null default false,

    -- Where the original sentence goes. Kept verbatim: it is what the clinic
    -- actually recorded, and a reviewer needs to see the words rather than a
    -- normalised version of them.
    add column allergy_legacy_note text;

create index idx_patient_allergy_review
    on patient (allergy_review_required) where allergy_review_required = true;

-- ---------- Carry the free text across ----------
-- Verbatim into the note, and flag for review. No interpretation.
update patient
   set allergy_legacy_note = allergies,
       allergy_review_required = true
 where allergies is not null
   and length(trim(allergies)) > 0;

-- The original column is deliberately left in place for now, like the identifier
-- columns in V9: dropping it in the same release that moves the data removes the
-- only way to check the move was faithful. A later migration drops it, once this
-- returns zero:
--
--   select count(*) from patient
--    where allergies is not null and length(trim(allergies)) > 0
--      and allergy_legacy_note is distinct from allergies;
