package com.sclinic.signing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The provider in place when the clinic has not configured one.
 *
 * <p>It refuses to sign, and says why. That is the whole of its behaviour, and
 * it is the right behaviour: a clinic setting the system up needs to register
 * patients, write drafts and collect consent for weeks before its certificates
 * arrive, so signing being unavailable must not stop the rest of the application
 * from starting.
 *
 * <p>What it deliberately does <b>not</b> do is sign with a locally held key.
 * That would be easy, would satisfy every test, and would be the most dangerous
 * thing in this codebase: records would be marked SIGNED, carry a technically
 * valid signature, and have no legal standing whatsoever — and nobody would find
 * out until a record was challenged. A clinic that cannot sign should see that it
 * cannot sign.
 *
 * <p>Verification of existing signatures is likewise refused rather than
 * answered. Returning "valid" would be a lie and returning "invalid" would
 * suggest the record had been tampered with; neither is true, and the honest
 * answer is that this installation cannot check.
 */
@Component
@ConditionalOnMissingBean(DigitalSignatureProvider.class)
public class UnconfiguredSignatureProvider implements DigitalSignatureProvider {

    static final String PROVIDER_CODE = "NONE";

    private static final String EXPLANATION =
            "Chưa cấu hình nhà cung cấp ký số. Hãy đặt sclinic.signing.provider "
                    + "cùng thông tin kết nối trước khi ký bản ghi khám. "
                    + "Hệ thống cố tình KHÔNG ký bằng khoá lưu trên máy chủ: "
                    + "bản ghi như vậy trông đã ký mà không có giá trị pháp lý.";

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public SignatureResult sign(SignatureRequest request) {
        throw new SignatureFailedException(EXPLANATION);
    }

    @Override
    public SignatureVerification verify(SignatureVerificationRequest request) {
        // Not "invalid": that would say the record looks tampered with. The
        // truthful answer is that this installation has no provider to ask.
        return new SignatureVerification(false, false, false,
                "Không kiểm tra được chữ ký: " + EXPLANATION);
    }
}
