-- ============================================================
-- V14: Consent, recorded well enough to prove.
--
-- NĐ 13/2023 treats health data as sensitive personal data, which may only be
-- processed with consent that the controller can demonstrate. "The patient
-- agreed" is not demonstrable. What is demonstrable is: this patient, this
-- purpose, this exact wording, at this time, collected this way, by this person.
-- That is what these three tables hold.
--
-- ---------- Why three tables and not one flag per patient ----------
--
-- A boolean "consented = true" cannot answer the only question that matters when
-- consent is challenged: agreed to what? Wording changes — a clinic adds
-- marketing use of photographs, or narrows a purpose — and a record pointing at
-- "the consent form" points at whatever the form says today, not what the
-- patient read. So the wording is versioned and the record points at a version.
-- ============================================================

-- ---------- The kinds of consent ----------
create table consent_type (
    id          uuid primary key default gen_random_uuid(),

    code        text not null unique,
    name        text not null,

    -- What this consent covers, for staff. Not the patient-facing wording: that
    -- is versioned, and this is not.
    description text,

    -- Whether this consent covers sensitive personal data under NĐ 13/2023.
    --
    -- On the type rather than worked out per record, because it decides what the
    -- patient-facing wording must say (see the trigger below) and that is a
    -- property of the category, not of one patient's form.
    is_sensitive boolean not null default false,

    -- Whether the clinic may act without it. Treatment consent gates treatment;
    -- marketing consent gates marketing and nothing else. Refusing marketing
    -- must never block care, so the distinction is data rather than a rule
    -- somewhere in code that could grow an exception.
    blocks_care boolean not null default false,

    active      boolean not null default true,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

-- ---------- The wording, versioned ----------
create table consent_type_version (
    id           uuid primary key default gen_random_uuid(),
    type_id      uuid not null references consent_type(id),

    version      integer not null check (version > 0),

    -- The text the patient actually reads. Stored in full rather than as a
    -- reference to a document: a URL is not evidence, and a file that can be
    -- replaced in place is worse than no evidence at all.
    content      text not null check (length(trim(content)) > 0),

    -- Requirement 11.4: the wording for a sensitive-data consent must tell the
    -- patient, in terms, that what is being processed is sensitive personal
    -- data. Held in its own column rather than trusted to be somewhere in
    -- content, so that it can be required (trigger below) and so a reviewer can
    -- see it without reading the whole form.
    sensitivity_notice text,

    -- The purpose of processing, in the wording the patient sees. NĐ 13/2023
    -- requires consent to be specific as to purpose; a consent to "data
    -- processing" in general is not one.
    purpose      text not null check (length(trim(purpose)) > 0),

    effective_from date not null,

    -- Null until published. An unpublished version is a draft, and collecting
    -- consent against a draft is collecting it against wording that may still
    -- change.
    published_at timestamptz,
    retired_at   timestamptz,

    created_at   timestamptz not null default now(),

    constraint uq_consent_version unique (type_id, version),

    -- Exists so consent_record can carry its type and have the database keep
    -- that copy honest. See the composite foreign key below.
    constraint uq_consent_version_type unique (id, type_id)
);

create index idx_consent_version_published
    on consent_type_version (type_id, version desc)
    where published_at is not null and retired_at is null;

-- Requirement 11.4, enforced rather than documented.
--
-- A cross-table rule, so a CHECK cannot express it: the obligation lives on the
-- type and the text lives on the version. A trigger is the only declarative
-- place for it, and it has to be declarative because version rows arrive from
-- migrations, from the application, and from whatever an administrator runs by
-- hand — and the hand-run case is exactly the one that would skip a service-layer
-- check.
create or replace function enforce_sensitivity_notice() returns trigger as $$
declare
    sensitive boolean;
begin
    select is_sensitive into sensitive from consent_type where id = new.type_id;

    if sensitive and (new.sensitivity_notice is null
                      or length(trim(new.sensitivity_notice)) = 0) then
        -- Raised as a check violation (23514) rather than with the default
        -- P0001. This is an integrity constraint that happens to need a lookup
        -- to express, and every client should see it as one: JDBC drivers and
        -- frameworks route 23xxx to their integrity-violation types, while
        -- P0001 lands in whatever bucket holds unrecognised server errors.
        raise exception
            'Consent wording for a sensitive-data type must state that the data '
            'processed is sensitive personal data (NĐ 13/2023). '
            'consent_type_version.sensitivity_notice is empty for type %',
            new.type_id
            using errcode = '23514';
    end if;

    return new;
end;
$$ language plpgsql;

create trigger trg_consent_sensitivity_notice
    before insert or update on consent_type_version
    for each row execute function enforce_sensitivity_notice();

-- ---------- What a patient agreed to ----------
create table consent_record (
    id              uuid primary key default gen_random_uuid(),

    patient_id      uuid not null references patient(id),

    -- Carried alongside the version although the version implies it. The
    -- question asked before every gated action is "does this patient have a live
    -- consent of this type", and answering it by joining through to the version
    -- is a join on the hottest check in the system.
    --
    -- The copy cannot drift: the composite foreign key below ties the pair
    -- (version, type) to a row that already pairs them, so a record naming the
    -- wrong type for its version does not insert.
    consent_type_id uuid not null,
    version_id      uuid not null,

    -- The purpose as agreed. Copied from the version at the time of granting
    -- rather than read through the join, because a later correction to the
    -- version's wording must not retroactively change what this patient agreed
    -- to — which is the whole reason the wording is versioned.
    purpose         text not null,

    granted_at      timestamptz not null,

    collection_method text not null check (collection_method in (
        -- Signed on paper and filed. Still the common case, and a scan of it is
        -- better evidence than a checkbox.
        'PAPER',
        -- Captured on a screen at the clinic, with a staff member present.
        'IN_PERSON_DIGITAL',
        -- Agreed remotely, e.g. before a first visit.
        'REMOTE_DIGITAL',
        -- Given in speech, with a staff member recording it. Weakest evidence,
        -- and named so it is visible as such rather than hidden among the others.
        'VERBAL'
    )),

    -- Where the proof is: a scan reference, a signature id, a note of who
    -- witnessed a verbal consent. Free text because the clinic's filing system
    -- is not something this schema should dictate.
    evidence        text,

    collected_by    uuid not null references staff(id),

    -- ---------- Ending it ----------

    -- Requirement 11.6. The patient's right to withdraw is unconditional, so no
    -- reason is required — demanding one would make the system an obstacle to a
    -- right it exists to serve. Who recorded it is required, because somebody
    -- took the call.
    withdrawn_at    timestamptz,
    withdrawn_by    uuid references staff(id),
    withdrawal_note text,

    -- Replaced by a newer consent, which is not the same event as a withdrawal
    -- and must not be recorded as one. Wording changes and the patient agrees
    -- again; the old record stays as proof of what applied until then.
    superseded_at   timestamptz,
    superseded_by   uuid references consent_record(id),

    created_at      timestamptz not null default now(),

    -- Keeps consent_type_id honest against version_id, declaratively.
    constraint fk_consent_record_version foreign key (version_id, consent_type_id)
        references consent_type_version (id, type_id),

    constraint chk_consent_withdrawal check (
        (withdrawn_at is null and withdrawn_by is null)
        or (withdrawn_at is not null and withdrawn_by is not null)
    ),

    -- A record is current, withdrawn, or superseded. Both at once has no
    -- meaning, and whichever the "is it live" query happened to test would
    -- decide the answer.
    constraint chk_consent_single_ending check (
        withdrawn_at is null or superseded_at is null
    )
);

-- One live consent per patient per type.
--
-- Without this, re-consenting to new wording leaves two live records and
-- "which version is in force" has no answer — which defeats the point of
-- versioning the wording at all. Partial, so withdrawn and superseded records
-- stay as the history they are.
create unique index uq_consent_record_live
    on consent_record (patient_id, consent_type_id)
    where withdrawn_at is null and superseded_at is null;

-- The query that runs before every gated action.
create index idx_consent_record_live_lookup
    on consent_record (patient_id, consent_type_id)
    where withdrawn_at is null and superseded_at is null;

create index idx_consent_record_patient on consent_record (patient_id);

-- ============================================================
-- The seven types required by requirement 11.1.
--
-- The taxonomy is seeded; the wording is not.
--
-- Seeding types is safe: requirement 11.1 fixes the list, and a missing type
-- means a gated action either fails for everyone or, worse, passes because
-- nothing was there to check. Seeding wording is not safe. The text a patient
-- reads is a legal instrument the clinic is answerable for, and shipping
-- generated Vietnamese legal prose would mean some clinic goes live having
-- collected consent against words nobody there ever approved.
--
-- So consent_type_version starts empty, exactly as retention_policy does, and
-- for the same reason: an unconfigured system must look unconfigured. The
-- service reports a type with no published version as a configuration fault
-- naming the type, not as the patient having refused — two failures that look
-- identical from the outside and need completely different fixing.
-- ============================================================
insert into consent_type (code, name, description, is_sensitive, blocks_care) values
    ('TREATMENT',
     'Đồng ý khám và điều trị',
     'Sự đồng ý thực hiện việc khám, chẩn đoán và điều trị theo Luật Khám bệnh, '
         || 'chữa bệnh 15/2023.',
     true, true),

    ('SENSITIVE_DATA',
     'Đồng ý xử lý dữ liệu cá nhân nhạy cảm',
     'Sự đồng ý xử lý dữ liệu sức khỏe — là dữ liệu cá nhân nhạy cảm theo '
         || 'Nghị định 13/2023/NĐ-CP.',
     true, true),

    ('CLINICAL_PHOTO',
     'Đồng ý chụp và lưu ảnh lâm sàng',
     'Sự đồng ý chụp, lưu và sử dụng ảnh lâm sàng cho mục đích điều trị của '
         || 'chính bệnh nhân.',
     true, false),

    ('PHOTO_TRAINING',
     'Đồng ý dùng ảnh cho đào tạo',
     'Sự đồng ý dùng ảnh lâm sàng cho mục đích đào tạo, hội thảo chuyên môn. '
         || 'Tách riêng khỏi ảnh điều trị: đồng ý cho bác sĩ xem không phải là '
         || 'đồng ý cho người khác xem.',
     true, false),

    ('PHOTO_MARKETING',
     'Đồng ý dùng ảnh cho quảng cáo',
     'Sự đồng ý dùng ảnh lâm sàng cho mục đích quảng cáo, truyền thông. '
         || 'Luôn tách riêng và không bao giờ là điều kiện để được điều trị.',
     true, false),

    ('APPOINTMENT_REMINDER',
     'Đồng ý nhận tin nhắn nhắc hẹn',
     'Sự đồng ý nhận tin nhắn, cuộc gọi nhắc lịch hẹn. Rút đồng ý thì ngừng gửi '
         || 'ngay (yêu cầu 11.7).',
     false, false),

    ('PROCEDURE',
     'Đồng ý thực hiện thủ thuật',
     'Sự đồng ý thực hiện thủ thuật can thiệp cụ thể, thu riêng cho từng thủ '
         || 'thuật chứ không thu một lần cho mọi thủ thuật về sau.',
     true, true);
