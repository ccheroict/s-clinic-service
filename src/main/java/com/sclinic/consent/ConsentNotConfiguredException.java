package com.sclinic.consent;

/**
 * A consent type has no published wording, so consent for it cannot be collected
 * or checked.
 *
 * <p>Deliberately not a {@link ConsentRequiredException}. From the outside the two
 * look identical — the action is refused either way — but they are opposite
 * problems: one is fixed by a patient signing a form, the other by an
 * administrator publishing wording that nobody has written yet. Reporting the
 * second as the first sends the clinic to ask patients to re-sign something that
 * will keep failing.
 *
 * <p>V14 seeds the seven types without wording on purpose. The text a patient
 * reads is a legal instrument the clinic answers for, and shipping generated
 * legal prose would mean a clinic going live having collected consent against
 * words nobody there approved. An unconfigured system should look unconfigured.
 */
public class ConsentNotConfiguredException extends RuntimeException {

    public ConsentNotConfiguredException(ConsentTypeCode type) {
        super("Loại đồng ý " + type.name() + " chưa có nội dung được công bố. "
                + "Quản trị viên cần soạn và công bố nội dung trước khi thu sự đồng ý "
                + "loại này. Đây là lỗi cấu hình, không phải bệnh nhân từ chối.");
    }
}
