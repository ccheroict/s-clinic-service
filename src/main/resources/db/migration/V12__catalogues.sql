-- ============================================================
-- V12: The reference data everything else will be written against.
--
-- Diagnoses, drugs and allergens exist as codes rather than free text because
-- three later obligations depend on it: a prescription has to carry an ICD-10
-- code and a drug identifiable to the national e-prescription system, an
-- allergy check has to be able to say that this drug contains that substance,
-- and an invoice has to name a priced service.
--
-- None of that works on typed-in names. "Penicillin", "penicilin" and "PNC" are
-- the same substance to a person and three different strings to a database, and
-- the allergy check that matters is the one that fires on all three.
--
-- Shipped empty. These are national catalogues published by the Ministry of
-- Health; inventing entries here would put invented clinical codes into
-- prescriptions. They are loaded from file -- see CatalogueImportService.
-- ============================================================

-- ---------- Diagnoses (ICD-10) ----------
create table catalog_icd10 (
    id         uuid primary key default gen_random_uuid(),

    -- 'L20.9', 'L40'. Kept as text: ICD-10 codes contain letters and dots and
    -- are not ordered numerically.
    code       text not null unique,

    name       text not null,
    name_en    text,

    -- 'XII' for skin diseases. Lets a dermatology clinic narrow the picker to
    -- the chapter it works in.
    chapter    text,

    active     boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- ---------- Allergens ----------
create table catalog_allergen (
    id         uuid primary key default gen_random_uuid(),
    code       text not null unique,
    name       text not null,

    category   text not null
               check (category in ('DRUG', 'FOOD', 'ENVIRONMENT', 'OTHER')),

    -- Grouping, so recording an allergy to one penicillin can warn about the
    -- class. Self-referencing rather than a separate table: the hierarchy is
    -- shallow and a drug class is itself an allergen a patient can react to.
    parent_id  uuid references catalog_allergen(id),

    active     boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index idx_allergen_parent on catalog_allergen (parent_id);

-- ---------- Drugs ----------
create table catalog_drug (
    id            uuid primary key default gen_random_uuid(),

    -- The clinic's own code. The national code is separate because a drug may
    -- not have one yet, and prescriptions need whichever exists.
    code          text not null unique,

    -- Code in the Ministry of Health drug list, for the national
    -- e-prescription system. Nullable, and unique only where present.
    national_code text,

    -- International non-proprietary name: 'Amoxicillin'. This is the field an
    -- allergy check means, because the brand name varies by manufacturer while
    -- the substance does not.
    inn_name      text not null,

    -- 'Augmentin'. What is written on the box and what the patient will say.
    brand_name    text,

    strength      text,                   -- '500mg', '0.1%'
    dosage_form   text,                   -- 'vien nen', 'kem boi'
    route         text,                   -- 'uong', 'boi ngoai da'
    unit          text not null default 'vien',

    -- Special-control classification. Narcotic and psychotropic prescribing
    -- carries extra duties, so the classification has to be on the drug rather
    -- than remembered by the prescriber.
    control_class text
                  check (control_class is null
                         or control_class in ('NARCOTIC', 'PSYCHOTROPIC', 'PRECURSOR')),

    active        boolean not null default true,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create unique index uq_drug_national_code
    on catalog_drug (national_code) where national_code is not null;

-- Searched by name far more often than by code, and always as a prefix or
-- substring of a word the user is typing.
create index idx_drug_inn_name on catalog_drug (lower(inn_name));
create index idx_drug_brand_name on catalog_drug (lower(brand_name));
create index idx_icd10_name on catalog_icd10 (lower(name));

-- ---------- Which drugs contain which allergens ----------
-- Many-to-many: a combination product contains several substances, and a
-- substance appears in many products. This is the join the allergy check at
-- prescribing time walks.
create table catalog_drug_allergen (
    drug_id     uuid not null references catalog_drug(id) on delete cascade,
    allergen_id uuid not null references catalog_allergen(id) on delete cascade,
    primary key (drug_id, allergen_id)
);
create index idx_drug_allergen_allergen on catalog_drug_allergen (allergen_id);

-- ---------- Services: the fields an invoice needs ----------
-- The table existed with a price and nothing else. An invoice under
-- NÐ 123/2020 needs a unit and a VAT rate per line, and both belong to the
-- service rather than being typed in per invoice.
alter table service
    add column unit        text not null default 'lan',
    add column vat_rate    numeric(5,2) not null default 0,
    add column description text,
    add column created_at  timestamptz not null default now(),
    add column updated_at  timestamptz not null default now();

alter table service
    add constraint chk_service_price_not_negative check (price >= 0),
    add constraint chk_service_vat_rate check (vat_rate >= 0 and vat_rate <= 100);
