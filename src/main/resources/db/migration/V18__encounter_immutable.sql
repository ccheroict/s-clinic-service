-- ============================================================
-- V18: A signed encounter cannot be edited. By the database, not by agreement.
--
-- Requirement 14.6 asks for the lock at both the application and the database
-- level, and the second one is the one that matters. A service check protects
-- callers that go through the service; a medical record has to survive the ones
-- that do not — a migration written in a hurry, a fix-up script, an ORM flushing
-- a stale entity, or a second application written against this database in five
-- years by someone who never read the service.
--
-- Same reasoning as the audit_log trigger, and the same reason it is a trigger
-- rather than REVOKE: the application owns these tables, so permissions it grants
-- itself are permissions it can withdraw. A trigger is a rule the owner also has
-- to perform a deliberate schema change to get past.
-- ============================================================

create or replace function encounter_content_is_immutable() returns trigger as $$
begin
    -- A draft is a draft. Everything below concerns records that have been
    -- signed.
    if old.status = 'DRAFT' then
        return new;
    end if;

    -- ---------- The one status change that is allowed ----------
    --
    -- SIGNED -> AMENDED, which is how a record records that a signed correction
    -- now exists. The original's own content is untouched by it, which is why
    -- status is not part of what gets signed: if it were, properly correcting a
    -- record would break the original's signature and make the record look
    -- tampered with by the act of correcting it.
    if new.status is distinct from old.status
        and not (old.status = 'SIGNED' and new.status = 'AMENDED') then
        raise exception
            'Không đổi được trạng thái bản ghi khám từ % sang %. '
            'Bản ghi đã ký chỉ có thể chuyển SIGNED sang AMENDED khi có bản '
            'đính chính đã ký.', old.status, new.status
            using errcode = '23514';
    end if;

    -- ---------- Clinical content: never ----------
    --
    -- Listed one column at a time rather than compared as a whole row, because a
    -- whole-row comparison would also catch the lifecycle columns below, and
    -- because a column added later should fail loudly here rather than be
    -- silently mutable. If this trigger ever needs editing to add a column, that
    -- is the moment to think about whether the column is content.
    if new.patient_id       is distinct from old.patient_id
        or new.doctor_id      is distinct from old.doctor_id
        or new.appointment_id is distinct from old.appointment_id
        or new.encountered_at is distinct from old.encountered_at
        or new.reason         is distinct from old.reason
        or new.treatment_plan is distinct from old.treatment_plan
        or new.advice         is distinct from old.advice
        or new.follow_up_date is distinct from old.follow_up_date
        or new.referral_note  is distinct from old.referral_note
        or new.clinical_data  is distinct from old.clinical_data
        or new.clinical_data_schema_id is distinct from old.clinical_data_schema_id
        or new.amends_encounter_id     is distinct from old.amends_encounter_id
        or new.amendment_reason        is distinct from old.amendment_reason
    then
        raise exception
            'Bản ghi khám % đã ở trạng thái % nên nội dung không sửa được. '
            'Hãy tạo bản đính chính trỏ về bản ghi này, kèm lý do, rồi ký.',
            old.id, old.status
            using errcode = '23514';
    end if;

    -- Lifecycle columns fall through on purpose: deleted_at, deleted_by,
    -- delete_reason, retention_until, legal_hold and updated_at. Ceasing to use
    -- a record, or working out when it may be destroyed, is archival
    -- administration and says nothing about what the doctor found. Freezing those
    -- too would make a signed record impossible to retire, which is not what
    -- immutability is for.
    return new;
end;
$$ language plpgsql;

create trigger trg_encounter_content_immutable
    before update on encounter
    for each row execute function encounter_content_is_immutable();

-- ---------- Deleting a signed record ----------
--
-- Refused outright. The retention rules decide when a medical record may be
-- destroyed, and that decision is a soft delete plus a retention date, not a
-- DELETE statement. A signed record removed from the table takes its signature's
-- subject with it and leaves the signature row pointing at nothing.
create or replace function encounter_signed_is_undeletable() returns trigger as $$
begin
    if old.status <> 'DRAFT' then
        raise exception
            'Không xoá được bản ghi khám đã ký (%). Hãy dùng xoá mềm '
            '(deleted_at kèm lý do); việc tiêu hủy do chính sách lưu trữ quyết định.',
            old.id
            using errcode = '23514';
    end if;
    return old;
end;
$$ language plpgsql;

create trigger trg_encounter_signed_no_delete
    before delete on encounter
    for each row execute function encounter_signed_is_undeletable();

-- ---------- Diagnoses are part of the signed content ----------
--
-- The diagnosis rows live in their own table, so locking the encounter alone
-- would leave the diagnoses editable — and the diagnosis is the part of a record
-- most worth changing after the fact. The hash covers them, so an edit here
-- would be detected by verification; this makes it impossible instead, which is
-- the difference between finding out and preventing.
create or replace function encounter_diagnosis_follows_encounter() returns trigger as $$
declare
    encounter_status text;
    target_encounter uuid;
begin
    target_encounter := coalesce(new.encounter_id, old.encounter_id);

    select status into encounter_status from encounter where id = target_encounter;

    if encounter_status is not null and encounter_status <> 'DRAFT' then
        raise exception
            'Bản ghi khám % đã ký nên không thêm, sửa hay xoá chẩn đoán được. '
            'Hãy tạo bản đính chính.', target_encounter
            using errcode = '23514';
    end if;

    return coalesce(new, old);
end;
$$ language plpgsql;

create trigger trg_encounter_diagnosis_immutable
    before insert or update or delete on encounter_diagnosis
    for each row execute function encounter_diagnosis_follows_encounter();

comment on function encounter_content_is_immutable() is
    'Requirement 14.6. Blocks content changes to a signed encounter at the '
        'database level, because a service check only protects callers that go '
        'through the service. Lifecycle columns stay writable so a signed record '
        'can still be retired under the retention policy.';
