-- ============================================================
-- V9: Patient identity numbers stop being readable in the database.
--
-- national_id (CCCD), insurance_no and tax_code identify a person outright. A
-- leaked database dump containing them is a different order of harm from one
-- containing appointment times, so they are encrypted with a key the database
-- never sees -- see EncryptedStringConverter for why this is done in the
-- application rather than with pgcrypto.
--
-- ---------- Why this is two migrations, not one ----------
--
-- This one only ADDS columns. The plaintext columns stay, and a startup job
-- copies the values across in encrypted form. Dropping the old columns is a
-- separate migration that must ship in a LATER release.
--
-- Shipping both at once would destroy data: Flyway runs before the application
-- starts, so the drop would execute before the conversion job had ever run. The
-- gap between the two releases is also the only window in which a conversion
-- that went wrong can be diagnosed with the original values still present.
--
-- The next release adds V10 with:
--     alter table patient
--         drop column national_id,
--         drop column insurance_no,
--         drop column tax_code;
-- and must not be deployed until the verification query at the bottom of this
-- file returns zero rows in that environment.
-- ============================================================

alter table patient
    add column national_id_enc  text,
    add column insurance_no_enc text,
    add column tax_code_enc     text,

    -- Keyed hash of national_id, so an exact lookup by CCCD is still possible.
    -- The encrypted column cannot be searched: it uses a fresh IV per write, so
    -- the same number yields different ciphertext every time. See BlindIndex for
    -- why that is preferred over deterministic encryption.
    add column national_id_hash text;

-- Lookup by identity number has to be fast, and two patients must not end up
-- holding the same CCCD. Unique, but partial: most patients have no CCCD
-- recorded, and in a plain unique index every one of those would collide.
create unique index uq_patient_national_id_hash
    on patient (national_id_hash) where national_id_hash is not null;

-- ---------- Verification before V10 ----------
-- Rows still holding a plaintext identifier that has not been carried across.
-- Must return zero in an environment before V10 may be applied there:
--
--   select count(*) from patient
--    where (national_id  is not null and national_id_enc  is null)
--       or (insurance_no is not null and insurance_no_enc is null)
--       or (tax_code     is not null and tax_code_enc     is null);
