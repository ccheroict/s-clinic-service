package com.sclinic.encounter;

/**
 * An attempt to change the content of a record that is no longer a draft.
 *
 * <p>Requirement 14.6. A 409 rather than a 403: the caller may well be the doctor
 * who wrote it and perfectly entitled to correct the record — what they cannot do
 * is overwrite what was signed. The message says what to do instead, because the
 * alternative is a clinician concluding the system has lost their correction.
 */
public class EncounterNotEditableException extends RuntimeException {

    public EncounterNotEditableException(EncounterStatus status) {
        super("Bản ghi khám đang ở trạng thái " + status.name()
                + " nên nội dung không sửa được. Hãy tạo bản đính chính "
                + "(amendment) trỏ về bản ghi này, kèm lý do, rồi ký.");
    }
}
