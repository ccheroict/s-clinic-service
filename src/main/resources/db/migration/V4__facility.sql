-- ============================================================
-- V4: Facility (co so kham chua benh)
--
-- Root identity record for the clinic. Required by:
--   * Prescription code (14 chars = facility code + sequence + type marker)
--   * National e-prescription system auth (ma lien thong co so)
--   * E-invoice issuance (mau so, ky hieu, ma don vi VNPT)
--   * Printed documents (giay phep hoat dong, nguoi chiu trach nhiem chuyen mon)
--
-- Modelled as a table rather than application config because ADMIN must be able
-- to change it at runtime, changes must be audited, and a second row is needed
-- if the clinic opens a branch.
-- ============================================================

create table facility (
    id                     uuid primary key default gen_random_uuid(),

    -- Identity
    name                   text not null,
    kcb_code               text not null unique,   -- ma co so KCB (Bo Y te)
    interop_code           text,                   -- ma lien thong co so (he thong don thuoc quoc gia)
    tax_code               text,                   -- ma so thue

    -- Contact / address
    address                text,
    phone                  text,
    email                  text,

    -- Operating licence (Nghi dinh 96/2023)
    license_no             text,                   -- so giay phep hoat dong
    license_issued_at      date,
    technical_director     text,                   -- nguoi chiu trach nhiem chuyen mon

    -- E-invoice configuration (ND 123/2020 + TT 78/2021), provider: VNPT
    einvoice_template_code text,                   -- mau so hoa don, e.g. '1'
    einvoice_serial        text,                   -- ky hieu hoa don, e.g. 'C25TAA'
    einvoice_unit_code     text,                   -- ma don vi tren he thong VNPT

    active                 boolean not null default true,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now()
);

create index idx_facility_active on facility (active);
