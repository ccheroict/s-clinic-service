-- ============================================================
-- V17: Digital signatures, and what a doctor needs to be allowed to produce one.
--
-- Requirement 14. A signature here is not decoration on a PDF: it is what lets
-- an electronic record stand in for the paper one under Luật KCB 15/2023, which
-- means the stored signature has to be enough, years later, to show both that a
-- particular person signed and that the bytes have not changed since.
-- ============================================================

-- ---------- What the signer is licensed to do ----------
alter table staff
    -- Số giấy phép hành nghề. Required by requirement 14.3, and the thing a
    -- record has to carry to be defensible: "signed by Dr X" is only meaningful
    -- alongside the licence that let Dr X sign it.
    add column practice_licence_no text,

    -- Mã liên thông của bác sĩ in the national system. Separate from the
    -- licence number because they are issued by different bodies and a doctor
    -- may have one before the other.
    add column national_doctor_code text,

    -- Phạm vi hành nghề: what this licence actually permits. Stored as text
    -- rather than a code list because the wording on a Vietnamese licence is
    -- not drawn from a closed vocabulary, and flattening it into categories
    -- would lose the part a reviewer would want to read.
    add column practice_scope text,

    -- Which signing credential belongs to this person at the provider.
    --
    -- Not the certificate itself: the certificate is captured on each signature
    -- (below), because it is the certificate *at the time of signing* that
    -- matters and certificates are reissued. This is the handle used to ask the
    -- provider to sign — a credential id in the remote signing service.
    add column signing_credential_id text,

    -- Which provider that credential lives with, so a clinic can migrate
    -- between providers without every historical signature becoming ambiguous.
    add column signing_provider text;

comment on column staff.signing_credential_id is
    'Handle for this person''s key at the remote signing provider. Never the '
        'private key, which by design never leaves the provider.';

-- ---------- The signature itself ----------
create table signature (
    id              uuid primary key default gen_random_uuid(),

    -- What was signed. Generic rather than a foreign key to encounter, because
    -- prescriptions and invoices will be signed by the same mechanism, and a
    -- separate signature table per signed thing would mean the verification
    -- logic existed once per table.
    entity_type     text not null,
    entity_id       uuid not null,

    signer_staff_id uuid not null references staff(id),

    -- ---------- The cryptography ----------

    -- Hash of exactly the bytes that were signed, and the algorithm that
    -- produced it. Both stored: verifying means recomputing this hash from the
    -- record as it stands now and comparing, and a hash whose algorithm is
    -- unrecorded cannot be recomputed with certainty in ten years.
    signed_hash     text not null,
    hash_algorithm  text not null,

    -- The signature value as returned by the provider, base64.
    signature_value text not null,

    -- The algorithm the provider used. Recorded rather than assumed, for the
    -- same reason as the hash algorithm.
    signature_algorithm text not null,

    -- The signer's certificate chain at the moment of signing, base64 DER,
    -- leaf first.
    --
    -- Captured per signature on purpose. Certificates expire and get reissued;
    -- a signature made under a certificate that has since expired is still a
    -- valid signature made at a time when the certificate was good, and that can
    -- only be shown if the certificate that was used is kept with it. Pointing
    -- at the staff member's current certificate would make every historical
    -- signature unverifiable the first time a certificate was renewed.
    certificate_chain text,

    -- Subject and serial, pulled out of the certificate so that a reviewer can
    -- read who signed without parsing DER.
    certificate_subject text,
    certificate_serial  text,

    -- ---------- Provenance ----------

    -- Which implementation of the signing port produced this. A clinic that
    -- changes provider must still be able to verify what the old one signed, and
    -- that starts with knowing which one it was.
    provider_code   text not null,

    -- The provider's own identifier for the signing transaction, for reconciling
    -- against their records when something is disputed.
    provider_transaction_id text,

    signed_at       timestamptz not null,
    created_at      timestamptz not null default now(),

    -- Signatures are never updated or deleted. Enforced below by trigger, as
    -- audit_log is: a signature that can be replaced is not evidence of anything.
    constraint chk_signature_hash_not_blank check (length(trim(signed_hash)) > 0),
    constraint chk_signature_value_not_blank check (length(trim(signature_value)) > 0)
);

create index idx_signature_entity on signature (entity_type, entity_id, signed_at desc);
create index idx_signature_signer on signature (signer_staff_id, signed_at desc);

-- Append-only, the same way audit_log is.
--
-- The reasoning is the same too: REVOKE does not help while the application owns
-- the table, so the guard has to be a trigger that the owner also cannot talk
-- its way past without a deliberate schema change.
create or replace function signature_is_append_only() returns trigger as $$
begin
    raise exception
        'signature rows are append-only: a signature that can be changed or '
        'removed is not evidence. To record that a signature is no longer '
        'relied upon, sign a correction rather than editing this row.'
        using errcode = '23514';
end;
$$ language plpgsql;

create trigger trg_signature_no_update
    before update on signature
    for each row execute function signature_is_append_only();

create trigger trg_signature_no_delete
    before delete on signature
    for each row execute function signature_is_append_only();

create trigger trg_signature_no_truncate
    before truncate on signature
    for each statement execute function signature_is_append_only();

comment on table signature is
    'One digital signature over one record. Append-only. The certificate used is '
        'stored per row rather than looked up, so a signature stays verifiable '
        'after the signer''s certificate is renewed or expires.';
